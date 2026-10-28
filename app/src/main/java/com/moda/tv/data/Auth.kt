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
 * 授权：把一次性授权码兑换成 token，之后所有请求都用 token。
 *
 * 为什么码只当兑换券：服务端的码用过即废，把它当长期凭据只会得到一个"重启就失效"的
 * 客户端；token 才是可以持久化并反复出示的身份。所以本层只做两件事——兑换、续期，
 * 其余数据层代码一律只认 [Api.TOKEN]。
 */
object Auth {

    private const val TAG = "Auth"

    /**
     * store 由 ModaApp.onCreate 注入：pair / refresh 的公开签名不带 Context（调用方是
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
     * 授权码 → 授权会话；服务端没给 token 就抛 [AppError.AuthFailed]。
     *
     * 成功路径只有一条：拿到 token → 补到期/绑定信息 → 落盘 → 内存生效。
     * 落盘与回写是同一次成功里的两步，任何一步失败都不留半截状态（见下方注释）。
     */
    suspend fun pair(invite: String): AuthSession = withContext(Dispatchers.IO) {
        // 码是一次性的：先确认落盘通道可用，再发兑换请求。反过来最坏的结局是码被服务端
        // 用掉了、token 却没存下来——消耗掉了却一无所有，且无法重试同一个码
        val sessionStore = requireStore()
        // 归一化只在这一处做，且要和站点页面那张输入框的口径一致：
        // 站点是 toUpperCase().replace(/[^A-Z0-9]/g,'')，所以粘贴 "k7m2-qx9p" / "K7M2 QX9P"
        // 在那边都能过。我们如果只 trim + uppercase，中间那个 '-' 会原样发给后端并被判无效，
        // 用户会以为「码错了」而不是「我的手误没被清掉」。长度不在这里截：
        // 后端才是码的权威，前端截断会把一个合法的更长的码悄悄改坏。
        val code = invite.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
        // 空码不必打扰后端：兑换失败的错误码本来就在说"这个码不能用"
        if (code.isEmpty()) throw ApiException(AppError.AuthFailed)

        // requireAuth = false：这一刻本地还没有 token，正是要把 token 换回来的请求
        val paired = try {
            Api.send("auth_pair", JSONObject().apply {
                put("fp", Api.FP)
                put("invite", code)
            }, requireAuth = false)
        } catch (e: ApiException) {
            // auth_pair 的失败几乎都是"这个码不能用"（无效 / 已用过 / 已过期），
            // 服务端用 ok=false + 它自己的中文原因表达，到这一层只能落成 AuthFailed，
            // 好让设置页说"重新填码"；网络类失败原样上抛，因为那时该说的是"检查网络"
            throw if (e.error is AppError.Upstream || e.error == AppError.Unauthorized) {
                ApiException(AppError.AuthFailed, e)
            } else {
                e
            }
        }

        val token = paired.optString("token")
        // ok=true 却没 token：兑换没成立（例如码被并发用掉）。绝不能存一个空 token 的会话，
        // 否则本地"已授权"但每个请求都被后端判无效，用户永远看不到该重新填码的那句话
        if (token.isBlank()) throw ApiException(AppError.AuthFailed)

        val base = AuthSession(
            token = token,
            admin = paired.optBoolean("admin", false),
            via = paired.optString("via"),
            rid = paired.optString("rid"),
            boundIp = paired.optString("bound_ip"),
            boundIpKind = paired.optString("bound_ip_kind"),
            pairedAt = paired.optLong("paired_at", 0L),
        )

        // auth_device 要带 X-Auth-Device，而这个头读的就是 Api.TOKEN：缓存必须先切到新 token，
        // 补信息的请求才认得它自己。切之前记下旧值——下文任何一步失败都靠它回滚
        val previous = Api.TOKEN
        Api.TOKEN = token
        try {
            // requireAuth = false：Api.TOKEN 刚换成新值，这里要的是 send 照现状带上去，
            // 而不是再过一次"有没有 token"的门——那只是同一个事实的第二次判断
            val full = try {
                merge(base, Api.send("auth_device", JSONObject(), requireAuth = false))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 补信息失败不能连 token 一起丢：token 已经到手且大概率可用，缺的只是到期 / 绑定
                // 这些展示字段，下次 refresh 还能补回来；为它们丢掉一次成功的兑换是最坏的取舍
                Log.w(TAG, "auth_device 补信息失败，先按已知字段落盘：${e.message}")
                base
            }
            try {
                sessionStore.save(full)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 落盘失败等同于这次兑换没成，别再往下走
                throw if (e is ApiException) e else ApiException(AppError.Storage, e)
            }
            full
        } catch (e: Throwable) {
            // 「内存回写 token」与「落盘」是同一次成功里的两步，必须一起成立：
            // 落盘没成、或者协程正好在这里被取消，都要把内存退回旧值。否则会剩下
            // "本进程自称已授权、重启后未授权"的半截状态，而它没有任何地方能自愈
            Api.TOKEN = previous
            throw e
        }
    }

    /**
     * 已有 token 时刷新到期 / 绑定信息；未授权返回 null；
     * token 失效则清空本地并返回 null。其余失败原样上抛，由 ViewModel 决定是否提示。
     */
    suspend fun refresh(): AuthSession? = withContext(Dispatchers.IO) {
        // 没有 token 就没有"刷新"这回事：这是常态（未授权），不是错误
        if (Api.TOKEN.isBlank()) return@withContext null
        val sessionStore = requireStore()
        val existing = sessionStore.get()
        val device = try {
            Api.send("auth_device", JSONObject(), requireAuth = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // 401 与 authorized=false 是同一件事的两种说法：这份凭据不作数了
            if (e.error == AppError.Unauthorized) {
                signOut()
                return@withContext null
            }
            throw e
        }
        // 服务端用 ok=true + authorized=false 表达"令牌无效或密钥已更换"。本地若继续认它，
        // 后续每个请求都要白跑一趟才拿到同样的结论
        if (!device.optBoolean("authorized", false)) {
            Log.w(TAG, "auth_device 判定未授权，清空本地会话")
            signOut()
            return@withContext null
        }
        val updated = merge(existing ?: AuthSession(Api.TOKEN), device)
        sessionStore.save(updated)
        Api.TOKEN = updated.token
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
     * 服务端字段优先，但空值不能把兑换时拿到的信息覆盖掉：
     * auth_device 在某些状态下会少给几项（甚至只回 ok/authorized），
     * 用空白把它盖掉，等于用一次成功的刷新擦掉上次的绑定信息。
     */
    private fun merge(base: AuthSession, device: JSONObject): AuthSession = base.copy(
        admin = device.optBoolean("admin", base.admin),
        boundIp = device.optString("bound_ip").ifBlank { base.boundIp },
        expiresAt = device.optLong("expires_at", base.expiresAt),
        grantSec = device.optLong("grant_sec", base.grantSec),
        inviteCode = device.optString("invite_code").ifBlank { base.inviteCode },
        pairedAt = device.optLong("paired_at", 0L).takeIf { it > 0L } ?: base.pairedAt,
    )
}

/**
 * 「凭据已被后台判死」的一次性广播。
 *
 * 为什么要一个信号而不是在各调用点判 401：401 可能从 19 路并发里的任何一路回来
 * （首页整页刷新就是 19 个 tab 并发），逐处判断等于漏掉一处就多一条「停在半死状态」的路。
 * 这里只广播事实，落成界面（作废会话 + 回首页 + 停在授权门）由 ViewModel 一处做。
 *
 * 只由**带着凭据**的请求发（见 Api.send 的 requireAuth）：兑换与补信息跑在「本地还没有有效凭据」
 * 的时刻，它们的 401 不是过期；让它触发全局作废，会把一次填错码变成一次清会话。
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

