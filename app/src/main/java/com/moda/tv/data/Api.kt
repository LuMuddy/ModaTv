package com.moda.tv.data

import android.util.Log
import com.moda.tv.BuildConfig
import com.moda.tv.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 后端调用层抛出的唯一失败类型：只带结构化错误码 [AppError]，不带文案。
 *
 * 请求路径、动作名、请求头与响应包装属于**运行期契约**，不随仓库发布——
 * 客户端只需要知道：所有请求都发往 [BASE]，失败时抛 [ApiException]。
 *
 * 为什么不是 message 字符串：文案在这里生成，数据层就被某一种语言绑死了——
 * UI 拿到它只能原样上屏，系统语言是英文时仍会显示中文。
 * 人话由 ui/components/ErrorText.kt 按当前语言取资源。
 */
class ApiException(val error: AppError, cause: Throwable? = null) : Exception(cause)

object Api {
    /**
     * 后端地址是一个代码常量，不再是用户可编辑的设置项，也不再从 BuildConfig 读。
     *
     * 为什么：地址由我们自己的代理决定，做成可编辑只换来两种故障——用户改错之后
     * 整机静默失灵、且没有任何自愈路径；以及仓库里必须同时维护"当前值"和"出厂值"
     * 两个真值来源。写死之后两者合一，也就没有注入时序的问题可讲。
     */
    const val BASE: String = "http://113.45.77.172/vqq/api.php"

    /**
     * 授权会话令牌：空串 == 未授权。
     *
     * 为什么存 token 而不是授权码：授权码是一次性兑换券，服务端用过即废，
     * 把它当长期凭据只会在重启后换回一串 401。换回来的 token 才是可复用的身份。
     *
     * 由 ModaApp.onCreate 同步注入（异步注入会在首帧请求上留一个空 token 的窗口），
     * 授权码兑换成功后立刻回写。volatile：UI 线程写，OkHttp 线程读。
     */
    @Volatile
    var TOKEN: String = ""

    /**
     * 本机指纹：运行时算出，绝不写进仓库。
     *
     * 为什么算而不是内置：它是服务端把 token 绑定到具体设备的依据，写进仓库就等于
     * 全部设备共用一个指纹，绑定语义当场失效；而且仓库是公开的，任何掺在仓库里的
     * "设备标识"都等于泄露。由 ModaApp.onCreate 在任何请求之前注入。
     */
    @Volatile
    var FP: String = ""

    /** vkey 接口固定域名（app.js 中 DEFAULT_DOMAIN，不再有切换面板） */
    private const val DOMAIN = "https://omex.tc.qq.com/"

    internal val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // 请求日志只给 Android Studio 直跑的 debug 包用：判断在这里而不是运行时再做，
            // release 包的调用链上就没有这个拦截器，「发版忘了关日志」不可能发生
            .apply { if (BuildConfig.DEBUG) addInterceptor(apiLogInterceptor) }
            .build()
    }

    private const val TAG = "Api"

    private val JSON = "application/json".toMediaType()

    /**
     * 全应用**唯一**的出网入口。
     *
     * 新增接口只加 action 与 body，鉴权参数由这里统一注入，不要在调用点拼头拼 URL：
     * 身份有两个来源（指纹恒带、token 视授权状态而定），散在调用点就会出现
     * "某个新接口漏了 X-Auth-Fp"这种只在真机上报 401 的故障。
     *
     * [requireAuth] = true 是业务请求的门：TOKEN 空白时一个字节都不发，
     * 空令牌必然被后端拒绝，省掉一次注定失败的往返，也让用户看到的是"去授权"
     * 而不是"网络错误"。授权链路（wx_qr / wx_poll / login_activate / login_me）恰好跑在
     * "本地还没有 token"和"token 还没落盘"这些中间态上，所以它们一律走 requireAuth = false。
     */
    internal suspend fun send(
        action: String,
        body: JSONObject,
        requireAuth: Boolean = true,
    ): JSONObject = withContext(Dispatchers.IO) {
        val token = TOKEN
        if (requireAuth && token.isBlank()) throw ApiException(AppError.Unauthorized)
        val req = Request.Builder()
            .url("$BASE?a=$action")
            .header("Content-Type", "application/json")
            // 身份头是 X-Auth-Session（扫码登录体系）。设备授权时代的那个设备头已经被后端
            // 彻底移除——实测带着它打业务接口只回「缺少身份」，也就是说它现在等于没带。
            .apply { if (token.isNotBlank()) header("X-Auth-Session", token) }
            // 指纹始终带：未授权时它是唯一的身份，服务端据此决定放不放行、把 token 绑给谁
            .header("X-Auth-Fp", FP)
            .post(body.toString().toRequestBody(JSON))
            .build()
        // 只 cancel 协程打不断阻塞的 execute()：旧请求会照跑完、照打后端，于是「先 cancel 再 launch」
        // 的调用点（扫码轮询、后台核验）会并发发出重复请求。把取消绑到 OkHttp 的 call 上，
        // 取消才等于真的不发了。
        val call = http.newCall(req)
        val detach = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        val text = try {
            call.execute().use { resp ->
                val t = resp.body?.string().orEmpty()
                // 401 单列成 Unauthorized：它要说的是"这份凭据无效，去重新授权"，
                // 而不是"服务端这次出错，稍后重试"——和别的非 2xx 混成 Http(code) 就说不出这句话
                // 带着凭据被拒 = 这份 token 已被后台判死：广播出去，由 ViewModel 统一作废会话、
                // 回到首页并停在授权门。兑换/补信息（requireAuth = false）不算——那时本来就没有有效凭据，
                // 它们的 401 只是"这张码不能用"
                if (resp.code == 401) {
                    if (requireAuth) AuthSignal.reportInvalid()
                    throw ApiException(AppError.Unauthorized)
                }
                // 非 2xx 与「2xx 但空体」是两回事：前者是服务端故障码，后者是后端/代理返回了空
                if (!resp.isSuccessful) throw ApiException(AppError.Http(resp.code))
                if (t.isBlank()) throw ApiException(AppError.EmptyPayload)
                t
            }
        } catch (e: IOException) {
            // 取消也会以 IOException("Canceled") 冒上来，那不是网络故障：先把取消原样抛出，
            // 否则调用方会把「用户离开了这一屏」记成一次网络错误
            currentCoroutineContext().ensureActive()
            // 裸 IOException 到 ViewModel 只能落成「未知错误」，就地归一为网络失败，
            // 用户才看得到「检查网络」这句可行动的话
            throw ApiException(AppError.Network, e)
        } finally {
            detach?.dispose()
        }
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            // 响应体可能是代理的错误页；正文里可能带地址和签名，只进日志不上屏
            Log.w(TAG, "接口未返回 JSON（${text.length} 字节）：${text.take(120)}")
            throw ApiException(AppError.Upstream(null), e)
        }
        if (!json.optBoolean("ok", false)) {
            // 上游的 error/msg 是它自己的语言，只能进日志；AppError 里不许留中文
            val upstream = json.optString("error").ifBlank { json.optString("msg") }
            Log.w(TAG, "上游返回 ok=false：${upstream.take(120)}")
            throw ApiException(AppError.Upstream(null))
        }
        json
    }

    /** 业务请求的薄门：只表达"要授权"，鉴权与错误映射仍全在 [send] 里 */
    suspend fun call(action: String, body: JSONObject): JSONObject =
        send(action, body, requireAuth = true)

    // ---------- 授权：扫码登录 + 一次性激活 + 会话核验 ----------
    //
    // 这四个动作都走 requireAuth = false：它们要么发生在"还没有凭据"的时刻，
    // 要么凭据本身就是被检验的对象。让它们过一遍 token 闸门，未授权设备就永远出不了授权门。

    /** 取一张微信登录二维码：{ok, sid, uuid, qr, expires}。sid 轮询、uuid 拼二维码原文 */
    suspend fun wxQr(): JSONObject = send("wx_qr", JSONObject(), requireAuth = false)

    /** 轮询扫码状态：{ok, status}；status 为 ok / need_code 时另带 token 与 acc */
    suspend fun wxPoll(sid: String): JSONObject =
        send("wx_poll", JSONObject().put("sid", sid), requireAuth = false)

    /**
     * 用一次性授权码激活刚扫出来的账号。
     *
     * 身份读两处：请求头 X-Auth-Session（[TOKEN]）与 body.token——这不是冗余，
     * 后端两处都看，少一处就是「登录状态已失效」。
     */
    suspend fun loginActivate(token: String, code: String): JSONObject =
        send(
            "login_activate",
            JSONObject().apply {
                put("token", token)
                put("code", code)
            },
            requireAuth = false,
        )

    /**
     * 核验当前会话。
     *
     * 会话无效时后端回 HTTP 200 + ok:true + login:false（不是 401），所以"无效"这件事
     * 只能由调用方读 login 字段判定；走 requireAuth = true 的那套 401 广播在这里永远不会触发。
     */
    suspend fun loginMe(): JSONObject = send("login_me", JSONObject(), requireAuth = false)

    // ---------- 6 个 action ----------

    suspend fun search(query: String, pageNum: Int = 1, pageSize: Int = 30): List<VideoItem> {
        val j = call("search", JSONObject().apply {
            put("query", query)
            put("pagenum", pageNum)
            put("pagesize", pageSize)
        })
        return parseItems(j.optJSONArray("items") ?: JSONArray())
    }

    suspend fun eps(cid: String): JSONObject = call("eps", JSONObject().put("cid", cid))

    suspend fun info(vid: String): JSONObject = call("info", JSONObject().put("vid", vid))

    suspend fun meta(vids: List<String>): JSONObject = call("meta", JSONObject().put("ids", JSONArray(vids)))

    suspend fun barrage(vid: String, from: Long, to: Long): JSONObject =
        call("barrage", JSONObject().apply {
            put("vid", vid); put("from", from); put("to", to)
        })

    suspend fun vkey(fn: String): JSONObject =
        call("vkey", JSONObject().apply { put("fn", fn); put("domain", DOMAIN) })

    /** search 结果映射，字段口径照搬 app.js 的 searchItemToFeed() */
    internal fun parseItems(arr: JSONArray): List<VideoItem> {
        val out = ArrayList<VideoItem>(arr.length())
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val doc = it.optJSONObject("doc") ?: JSONObject()
            val v = it.optJSONObject("videoInfo") ?: JSONObject()
            val cid = doc.optString("id")
            val title = strip(v.optString("title"))
            if (cid.isBlank() || title.isBlank()) continue
            val tags = ArrayList<String>()
            v.optString("typeName").takeIf { it.isNotBlank() }?.let { tags += it }
            v.optString("year").takeIf { it.isNotBlank() }?.let { tags += it }
            out += VideoItem(
                cid = cid,
                vid = v.optString("vid"),
                title = title,
                cover = v.optString("imgUrl"),
                subtitle = strip(v.optString("subTitle"))
                    .ifBlank { strip(v.optString("highlightSubTitle")) },
                desc = strip(v.optString("desc")),
                badge = tags.joinToString(" · "),
            )
        }
        return out
    }

    internal fun strip(s: String): String =
        s.replace("<em>", "").replace("</em>", "").trim()
}
