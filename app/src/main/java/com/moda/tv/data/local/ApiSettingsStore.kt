package com.moda.tv.data.local

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.moda.tv.R
import com.moda.tv.data.Api
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 代理网址（用户自建后端的地址）。
 *
 * 与 HistoryStore / FavoritesStore 一样是 DataStore Preferences：
 * 键缺失 == 用户没改过，即 [Api.DEFAULT_BASE]。刻意不预写默认值，
 * 这样"恢复默认"就是删键，而不是把一个和常量重复的字面量存两遍。
 */
interface ApiSettingsStore {
    /** 当前生效地址；没存过时直接给出默认值，订阅方永远不用判空 */
    val baseUrl: Flow<String>

    suspend fun get(): String

    suspend fun save(base: String)

    /** 删键回到默认值，返回默认地址供调用方立刻注入 [Api.BASE] */
    suspend fun reset(): String
}

// 与 moda_history / moda_favorites / moda_search_history 同款：按 applicationContext 缓存，
// 重复 create 不会触发 multiple DataStores active 崩溃
private val Context.apiSettingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "moda_api_settings")

/** 设置页"代理网址"唯一的键 */
private val BASE_KEY = stringPreferencesKey("api_base")

object ApiSettingsStoreImpl {
    @Volatile
    private var instance: ApiSettingsStore? = null

    fun create(context: Context): ApiSettingsStore =
        instance ?: synchronized(this) {
            instance ?: ApiSettingsPreferencesStore(context.applicationContext.apiSettingsDataStore)
                .also { instance = it }
        }
}

private class ApiSettingsPreferencesStore(
    private val dataStore: DataStore<Preferences>,
) : ApiSettingsStore {

    // 单键写入，没有读-改-写跨步骤，不需要与 HistoryStore 同款的 Mutex
    override val baseUrl: Flow<String> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[BASE_KEY]?.takeIf { it.isNotBlank() } ?: Api.DEFAULT_BASE }
        .distinctUntilChanged()

    override suspend fun get(): String = baseUrl.first()

    override suspend fun save(base: String) {
        dataStore.edit { prefs -> prefs[BASE_KEY] = base }
    }

    override suspend fun reset(): String {
        dataStore.edit { prefs -> prefs.remove(BASE_KEY) }
        return Api.DEFAULT_BASE
    }
}

/**
 * probe 的失败原因：一条 string 资源 + 可选的技术细节占位参数。
 *
 * 刻意不携带成品句子：文案要在渲染那一刻取（见 TvViewModel.commitApiBase），
 * 否则数据层就被某一份文案绑死了。[detail] 只放 HTTP 状态码、okhttp 的原始 message
 * 这类技术信息，不是文案。
 */
data class ProbeFailure(
    @param:StringRes val messageRes: Int,
    val detail: String? = null,
)

/**
 * 代理网址的"合法性"与"可用性"是两件事，分别由 [normalize] 与 [probe] 负责：
 * 前者纯本地判断，后者真发一次 a=probe 请求。设置页只有两者都过才落盘。
 */
internal object ApiEndpoint {

    /** 探针用的 fn：探的是"这个地址能不能应答"，不是"某个资源存不存在"，所以随便给一个值即可 */
    private const val PROBE_FN = "moda-probe.m3u8"

    /** 探一个坏地址不该让用户等 Api.http 的 15s 连接超时 */
    private const val PROBE_TIMEOUT_SEC = 8L

    private val JSON = "application/json".toMediaType()

    /**
     * 校验并规整用户输入：返回 null 表示不合法（设置页据此拒绝，不发任何请求）。
     * 输入法/粘贴常夹带空格与换行，先剔除再判断。
     */
    fun normalize(raw: String): String? {
        val cleaned = raw.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) return null
        val lower = cleaned.lowercase()
        // 只认 http/https，别的协议（或漏写协议头）一律拒绝
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        // toHttpUrlOrNull 顺带拒掉 "http://"、"http:///x" 这类只有协议头、没有 host 的地址
        return cleaned.takeIf { it.toHttpUrlOrNull() != null }
    }

    /**
     * 用 a=probe 实测新地址。返回 null 表示可用，否则是给用户看的失败原因。
     * 复用 Api.http 的连接池，只是把总时长压到 [PROBE_TIMEOUT_SEC]。
     *
     * 失败原因只以 [ProbeFailure]（资源 id + 占位参数）返回，**不带成品句子**：
     * 数据层一旦握着文案，它就只能原样上屏，改词也无从下手。
     */
    suspend fun probe(base: String): ProbeFailure? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$base?a=probe")
            .header("Content-Type", "application/json")
            .header("X-Auth-Device", Api.DEVICE_TOKEN)
            .header("X-Auth-Fp", Api.DEVICE_FP)
            .post(JSONObject().put("fn", PROBE_FN).toString().toRequestBody(JSON))
            .build()
        try {
            Api.http.newBuilder()
                .callTimeout(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build()
                .newCall(req)
                .execute()
                .use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext ProbeFailure(R.string.probe_http_status, resp.code.toString())
                    }
                    val text = resp.body?.string().orEmpty()
                    if (text.isBlank()) return@withContext ProbeFailure(R.string.probe_empty_body)
                    val json = try {
                        JSONObject(text)
                    } catch (e: Exception) {
                        null
                    } ?: return@withContext ProbeFailure(R.string.probe_not_json)
                    // 后端 ok=true 才算真的通：能连上但答不了 probe 的地址对我们没有价值
                    if (json.optBoolean("ok", false)) {
                        null
                    } else {
                        // 后端给的 error 原文只进日志：它是上游的任意文本，摆到用户眼前既可能
                        // 是一串英文，也可能是别的语言
                        val backendError = json.optString("error")
                        Log.w("ApiSettings", "probe 被拒绝：$backendError")
                        ProbeFailure(R.string.probe_rejected)
                    }
                }
        } catch (e: Exception) {
            // 连不上/超时/DNS：okhttp 的 message 是技术信息，作为占位参数交给 UI，
            // 句子本身仍从字符串资源来，文案只有一处出处
            ProbeFailure(R.string.probe_connect_failed, e.message ?: e::class.java.simpleName)
        }
    }
}
