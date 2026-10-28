package com.moda.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/**
 * 一次授权会话：微信扫码登录换回来的 token，加上服务端给的账号信息。
 *
 * 为什么 token 是唯一必需字段：其余字段只用于展示（谁登录了、还剩多久），
 * 缺了不影响发请求，所以全部带默认值，[authorized] 只看 token。
 *
 * 设备绑定时代的绑定 IP / 设备号 / 券号 / 配对时刻 已经全部删掉：
 * 那套后端（设备授权）已关闭，字段永远不会有新值；留着它们只会让"会话"看起来
 * 还有第二层含义，而任何按它们做的判断都只是拿一份死数据当依据。
 */
data class AuthSession(
    val token: String,
    val admin: Boolean = false,
    /** 账号 id（服务端 acc.id） */
    val accId: String = "",
    /** 账号名（服务端 acc.name）：设置页那一行显示的就是它 */
    val accName: String = "",
    /** 服务端最后一次告诉我们"还剩多少秒"（acc.left）。0 = 服务端没说 */
    val leftSec: Long = 0L,
    /** 由 [leftSec] 在客户端折算出的到期时刻（毫秒）。只用于展示，不做本地门禁 */
    val expiresAt: Long = 0L,
) {
    val authorized: Boolean get() = token.isNotBlank()
}

interface AuthSessionStore {
    /** null = 未授权 */
    val session: Flow<AuthSession?>

    suspend fun get(): AuthSession?

    suspend fun save(s: AuthSession)

    suspend fun clear()
}

// 与 moda_history / moda_player_settings 同款：按 applicationContext 缓存，
// 重复 create 不会触发 multiple DataStores active 崩溃
private val Context.authSessionDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "moda_auth_session")

/**
 * 整个会话存一个键：读时要么是一份完整会话，要么什么都没有。
 * 拆成多个键会让"token 写进去了、到期时间没写"这种半截状态可被读到，
 * 而这里正是重启后判断"还授权吗"的地方。
 */
private val SESSION_KEY = stringPreferencesKey("auth_session")

object AuthSessionStoreImpl {
    @Volatile
    private var instance: AuthSessionStore? = null

    fun create(context: Context): AuthSessionStore =
        instance ?: synchronized(this) {
            instance ?: AuthSessionPreferencesStore(context.applicationContext.authSessionDataStore)
                .also { instance = it }
        }
}

private class AuthSessionPreferencesStore(
    private val dataStore: DataStore<Preferences>,
) : AuthSessionStore {

    // 单键写入，没有读-改-写跨步骤，不需要与 SearchHistoryStore 同款的 Mutex
    override val session: Flow<AuthSession?> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> AuthSessionJson.decode(prefs[SESSION_KEY]) }
        .distinctUntilChanged()

    override suspend fun get(): AuthSession? = session.first()

    override suspend fun save(s: AuthSession) {
        // 空 token 不是会话：存进去只会得到"已授权但凭据为空"的脏状态，
        // 与"没有 token 就发不出请求"这条运行时前提直接矛盾，所以一律当清空处理
        if (!s.authorized) {
            clear()
            return
        }
        dataStore.edit { prefs -> prefs[SESSION_KEY] = AuthSessionJson.encode(s) }
    }

    override suspend fun clear() {
        // 删键而不是写一个空对象：键缺失本身就是"未授权"，这才是恢复默认的唯一表达
        dataStore.edit { prefs -> prefs.remove(SESSION_KEY) }
    }
}

/**
 * 手写极简编解码：会话字段全是标量，androidx 自带的 org.json 够用，
 * 引入 kotlinx.serialization 属于新增依赖（与 StoreJson.kt / SearchWordsJson 同款取舍）。
 */
internal object AuthSessionJson {

    fun encode(s: AuthSession): String = JSONObject().apply {
        put("token", s.token)
        put("admin", s.admin)
        put("accId", s.accId)
        put("accName", s.accName)
        put("leftSec", s.leftSec)
        put("expiresAt", s.expiresAt)
    }.toString()

    /**
     * 解析失败、键缺失、token 为空 一律退化成 null（= 未授权）：
     * 本地数据损坏不该让应用起不来，而"读不出来"与"没授权过"在行为上本来就一样。
     *
     * 旧版本存下来的那份会话（设备令牌 + 一堆绑定字段）照样能读出来：
     * token 在就还是 token，多出来的键直接忽略。它能不能用不取决于这里——
     * 冷启动 refresh 走 login_me，回来的 login:false 会把它 signOut 清掉，
     * 这就是"旧设备令牌自然失效"，不需要写任何迁移逻辑。
     */
    fun decode(raw: String?): AuthSession? {
        if (raw.isNullOrBlank()) return null
        return try {
            val o = JSONObject(raw)
            val token = o.optString("token")
            if (token.isBlank()) {
                null
            } else {
                AuthSession(
                    token = token,
                    admin = o.optBoolean("admin", false),
                    accId = o.optString("accId"),
                    accName = o.optString("accName"),
                    leftSec = o.optLong("leftSec", 0L),
                    expiresAt = o.optLong("expiresAt", 0L),
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
