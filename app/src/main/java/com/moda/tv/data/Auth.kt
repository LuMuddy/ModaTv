package com.moda.tv.data

import android.content.Context
import android.util.Log
import com.moda.tv.data.local.AuthSession
import com.moda.tv.data.local.AuthSessionStore
import com.moda.tv.data.local.AuthSessionStoreImpl
import com.moda.tv.model.AppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 一张微信登录二维码。
 *
 * [sid] 是轮询的凭据，[uuid] 是二维码里的原文之一部分（见 AuthGateScreen 的模板常量），
 * [expiresSec] 只是服务端给的参考时长——真正的"过期"以 wx_poll 回的 status 为准，
 * 客户端不自己按秒数计时：本地时钟和服务器差几分钟是常事，本地判过期只会误杀还能用的码。
 */
data class WxQr(val sid: String, val uuid: String, val expiresSec: Long)

/**
 * 一次 wx_poll 的结果。
 *
 * 只有 [Authorized] 会把会话落盘；[NeedCode] 里的 token 是"这次扫码会话"的凭据，
 * 账号还没激活，拿它去 login_me 只会得到 login:false，所以故意不落盘——
 * 激活成功那一刻才写盘（见 [Auth.activate]）。
 */
sealed interface WxPoll {
    /** 二维码已给出，还没人扫 */
    data object Pending : WxPoll

    /** 手机已扫，等机主点「确认登录」 */
    data object Scanned : WxPoll

    /** 这张码失效了，界面要换一张 */
    data object Expired : WxPoll

    /** 登录完成：会话已经落盘并生效，调用方只需把它交给界面 */
    data class Authorized(val session: AuthSession) : WxPoll

    /** 账号未激活：带上这次扫码会话的 token，激活时两处都要用它 */
    data class NeedCode(val token: String) : WxPoll
}

/**
 * 授权：微信扫码登录（主路径）+ 一次性授权码激活（扫码之后/失败之后的补位）。
 *
 * 为什么改成这个顺序：服务端已经把设备授权那条路关掉了——实测旧的兑换/补信息动作一律回
 * 「服务端已关闭设备授权」或 authorized:false，旧的设备身份头也不再被认，
 * 也就是说**一张 8 位码单独兑换不出任何身份**。现在能换到 token 的只有一条路：
 * wx_qr 取码 → 微信扫一扫确认 → wx_poll 回 ok（或 need_code 再用授权码激活一次）。
 * 所以本层只做四件事：取码、轮询、激活、核验，其余数据层代码一律只认 [Api.TOKEN]。
 */
object Auth {

    private const val TAG = "Auth"

    /**
     * store 由 ModaApp.onCreate 注入：取码 / 轮询 / 激活的公开签名不带 Context（调用方是
     * ViewModel，它已经拿着 Application 了），但落盘需要 Context 才能开 DataStore。
     * 注入点收在一处，就不必在每个调用点重复传 Context。
     */
    @Volatile
    private var store: AuthSessionStore? = null

    internal fun install(context: Context) {
        if (store == null) store = AuthSessionStoreImpl.create(context.applicationContext)
    }

    private fun requireStore(): AuthSessionStore {
        val s = store
        // 注入没发生就落不了盘，等同于本地存储不可用——不要在没地方存的情况下去消耗授权码
        return s ?: throw ApiException(AppError.Storage)
    }

    /**
     * 取一张登录二维码。sid / uuid 缺任何一个都等于这张码不可用（轮不动、画不出），
     * 直接当上游故障上抛：界面会显示「获取失败 + 刷新」，而不是画一张永远刷不动的图。
     */
    suspend fun requestQr(): WxQr = withContext(Dispatchers.IO) {
        val j = Api.wxQr()
        val sid = j.optString("sid")
        val uuid = j.optString("uuid")
        if (sid.isBlank() || uuid.isBlank()) throw ApiException(AppError.Upstream(null))
        WxQr(sid = sid, uuid = uuid, expiresSec = j.optLong("expires", 0L))
    }

    /**
     * 轮询一次扫码状态。
     *
     * 状态判定只看 status 字段：ok 的那一次 token 就在同一个响应里，不需要再换一次票
     * （再换一次只会让"码已被用掉"和"会话刚建立"这两个时刻错开）。
     */
    suspend fun poll(sid: String): WxPoll = withContext(Dispatchers.IO) {
        if (sid.isBlank()) throw ApiException(AppError.Upstream(null))
        val j = Api.wxPoll(sid)
        val status = j.optString("status")
        val token = j.optString("token")
        when (status) {
            "pending" -> WxPoll.Pending
            "scanned" -> WxPoll.Scanned
            "expired" -> WxPoll.Expired
            "ok" -> WxPoll.Authorized(adoptSession(token, j.optJSONObject("acc"), requireStore()))
            "need_code" -> {
                // token 空着激活不了任何东西：这不是"账号未激活"，是这次扫码没成立
                if (token.isBlank()) throw ApiException(AppError.Upstream(null))
                WxPoll.NeedCode(token)
            }
            // 后端以后加状态时不要静默当成 pending：那会让门永远停在「请用微信扫一扫」，
            // 用户看得见二维码却永远等不到下一步，而且没有任何日志能指认它
            else -> {
                Log.w(TAG, "wx_poll 返回未知 status：${status.take(24)}")
                throw ApiException(AppError.Upstream(null))
            }
        }
    }

    /**
     * 一次性授权码激活（扫码之后才有的第二步）。
     *
     * 为什么必须先扫码：login_activate 的身份来自那段扫码会话，没有有效会话时后端一定回
     * 「登录状态已失效，请重新扫码」（实测）。所以本函数只该在 [WxPoll.NeedCode] 之后被调用；
     * 没有 token 时调用方要在本地就把用户挡住，别发这一趟注定失败的请求。
     *
     * 码是一次性的：先确认落盘通道可用再发请求。反过来最坏的结局是码被服务端用掉了、
     * token 却没存下来——既消耗掉了又无法重试同一个码。
     */
    suspend fun activate(token: String, code: String): AuthSession = withContext(Dispatchers.IO) {
        val sessionStore = requireStore()
        // 归一化口径只在这一处：站点那张输入框也是 toUpperCase + 丢非字母数字，
        // 粘贴 "k7m2-qx9p" 在两边都能过。长度不在这里截——后端才是码的权威。
        val normalized = code.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
        if (normalized.isEmpty() || token.isBlank()) throw ApiException(AppError.AuthFailed)

        // login_activate 读两处身份：请求头 X-Auth-Session（由 Api 从 TOKEN 注入）与 body.token。
        // 所以先把这次扫码的 token 放进内存；任何一步失败都回滚，否则会留下
        // 「内存自称有身份、盘上没有会话」的半截状态，而它没有任何地方能自愈
        val previous = Api.TOKEN
        Api.TOKEN = token
        try {
            val j = try {
                Api.loginActivate(token, normalized)
            } catch (e: ApiException) {
                // 激活失败（码无效 / 已用过 / 会话失效）对用户是同一句话：这张码不能用。
                // 网络类失败原样上抛——那时该说的是「检查网络」，不是「码错了」
                throw if (e.error is AppError.Upstream || e.error == AppError.Unauthorized) {
                    ApiException(AppError.AuthFailed, e)
                } else {
                    e
                }
            }
            adoptSession(token, j.optJSONObject("acc"), sessionStore)
        } catch (e: Throwable) {
            Api.TOKEN = previous
            throw e
        }
    }

    /**
     * 冷启动 / 后台核验：login_me 说"还有效"就照用，说"无效"就清盘。
     *
     * 会话无效时后端回的是 HTTP 200 + login:false（不是 401），所以判定只能看字段。
     * 旧版本存下来的设备令牌在这里第一次就会得到 login:false 并被 [signOut] 清掉——
     * 不需要为它写任何迁移逻辑，这条路径本身就是迁移。
     *
     * 返回 null = 已无会话（未授权）；抛异常 = 这一次没问成（断网、后端抖动），
     * 由 ViewModel 决定是否提示，旧会话保留。
     */
    suspend fun refresh(): AuthSession? = withContext(Dispatchers.IO) {
        // 没有 token 就没有"刷新"这回事：这是常态（未授权），不是错误
        if (Api.TOKEN.isBlank()) return@withContext null
        val sessionStore = requireStore()
        val existing = sessionStore.get()
        val j = try {
            Api.loginMe()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // 真 401 与 login:false 是同一件事的两种说法：这份凭据不作数了
            if (e.error == AppError.Unauthorized) {
                signOut()
                return@withContext null
            }
            throw e
        }
        // 服务端用 ok=true + login=false 表达"这个 token 换不到身份了"。本地若继续认它，
        // 后续每个请求都要白跑一趟才拿到同样的结论
        if (!j.optBoolean("login", false)) {
            Log.w(TAG, "login_me 判定会话失效，清空本地会话")
            signOut()
            return@withContext null
        }
        // 会话有效但 acc 可能只回一半：以盘上那份为底，别用空值把上次的账号名擦掉
        val updated = sessionOf(Api.TOKEN, j.optJSONObject("acc"), existing)
        try {
            sessionStore.save(updated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw if (e is ApiException) e else ApiException(AppError.Storage, e)
        }
        updated
    }

    /**
     * 作废本地凭据：清内存 token + 删盘。用户点不到这个动作，它只由两条自动路径触发——
     * [refresh] 判定失效、以及业务请求被后台判 401（见 [AuthSignal]）。
     *
     * 先清内存再删盘：留着一个已失效的 token 只会让每个请求都被后端拒绝一次。
     * 反过来（删盘失败就把 token 留着）会让"已授权"继续挂在屏幕上，而它已经换不到任何数据。
     * 盘没删掉也不会被读到：内存 token 为空时 [refresh] 直接返回 null，
     * 下次冷启动再从盘里读出来时会撞同一次 401，自己纠回来。
     */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        Api.TOKEN = ""
        val s = store ?: return@withContext
        try {
            s.clear()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "清空本地会话失败：${e.message}")
        }
    }

    /**
     * 会话落盘 + 内存生效。两步必须一起成立：
     *
     * 先把 [Api.TOKEN] 切到新值再写盘——写盘是 IO，这段时间里界面可能已经开始发请求，
     * 它们必须已经是带着新身份出去的。写盘失败（或协程在这里被取消）就把内存退回旧值，
     * 否则会剩下「本进程自称已授权、重启后未授权」的半截状态，而它没有任何地方能自愈。
     */
    private suspend fun adoptSession(
        token: String,
        acc: JSONObject?,
        sessionStore: AuthSessionStore,
        base: AuthSession? = null,
    ): AuthSession {
        // ok=true 却没 token：登录没成立（例如码被并发用掉）。绝不能存一个空 token 的会话，
        // 否则本地"已授权"但每个请求都被后端判无效，用户永远看不到该重新扫码的那句话
        if (token.isBlank()) throw ApiException(AppError.AuthFailed)
        val session = sessionOf(token, acc, base)
        val previous = Api.TOKEN
        Api.TOKEN = token
        try {
            sessionStore.save(session)
        } catch (e: CancellationException) {
            Api.TOKEN = previous
            throw e
        } catch (e: Exception) {
            Api.TOKEN = previous
            throw if (e is ApiException) e else ApiException(AppError.Storage, e)
        }
        return session
    }

    /**
     * 服务端字段 → 本地会话。acc 缺失时退回 [base]：refresh 时后端可能只回一半字段，
     * 用空串把它盖掉等于用一次成功的核验擦掉上次登录的账号名。
     */
    private fun sessionOf(token: String, acc: JSONObject?, base: AuthSession? = null): AuthSession {
        val leftSec = acc?.optLong("left", 0L)?.takeIf { it > 0L } ?: base?.leftSec ?: 0L
        return AuthSession(
            token = token,
            admin = acc?.optBoolean("admin", false) ?: base?.admin ?: false,
            accId = acc?.optString("id").orEmpty().ifBlank { base?.accId.orEmpty() },
            accName = acc?.optString("name").orEmpty().ifBlank { base?.accName.orEmpty() },
            leftSec = leftSec,
            // left 是"剩余秒数"，每次读到它都要重新折算成绝对时刻；不重算的话
            // expiresAt 会永远停在第一次登录那一刻，展示出来就是个假时间
            expiresAt = if (leftSec > 0L) {
                System.currentTimeMillis() + leftSec * 1000L
            } else {
                base?.expiresAt ?: 0L
            },
        )
    }
}

/**
 * 「凭据已被后台判死」的一次性广播。
 *
 * 为什么要一个信号而不是在各调用点判 401：401 可能从 19 路并发里的任何一路回来
 * （首页整页刷新就是 19 个 tab 并发），逐处判断等于漏掉一处就多一条「停在半死状态」的路。
 * 这里只广播事实，落成界面（作废会话 + 回首页 + 停在授权门）由 ViewModel 一处做。
 *
 * 只由**带着凭据**的请求发（见 Api.send 的 requireAuth）：取码 / 轮询 / 激活 / 核验跑在
 * 「本地还没有有效凭据」或「凭据正在被检验」的时刻，它们的 401 不是过期；
 * 让它触发全局作废，会把一次填错码变成一次清会话。
 */
object AuthSignal {
    private val _invalidated = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val invalidated: SharedFlow<Unit> = _invalidated.asSharedFlow()

    internal fun reportInvalid() {
        _invalidated.tryEmit(Unit)
    }
}
