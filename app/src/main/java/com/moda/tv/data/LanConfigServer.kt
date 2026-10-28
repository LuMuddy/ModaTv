package com.moda.tv.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.moda.tv.R
import com.moda.tv.model.AppError
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 局域网授权页服务。
 *
 * 存在的唯一目的：让用户在手机浏览器里填 8 位一次性授权码，避免在电视遥控器上逐位敲。
 *
 * 授权码只是一次性兑换券，不是凭据本身：电视端拿它去兑换 token，token 落进
 * AuthSessionStore 并回写 [Api.TOKEN]，此后的一切请求只认 token。
 * 也就是说本类**不保管、也不转发任何凭据**——它只把手机的这一次提交换成「成功 / 失败」，
 * 再把电视端当前的状态渲染回页面（见 [pageHtml]）。
 *
 * 生命周期是本类最重要的约束，因此**不做任何常驻持有**：
 *   - [start] 由 Dialog 进入组合时调用（绑定 0.0.0.0，端口从 9999 起逐个试）；
 *   - [stop]  由 Dialog 离开组合 / 设置页退出 / 应用进入后台时调用，
 *             close() 掉 ServerSocket 并把协程作用域 cancel 掉，端口立即归还系统。
 * 没有前台服务、没有静态单例、没有延时回收——对象被丢弃即彻底消失。
 *
 * 安全边界：只有 Dialog 打开期间端口才在监听，关掉即完全无法访问。
 * 不做额外的访问口令——能连上（局域网内）就能提交授权码，而码本身由后端一次性作废，
 * 端口不监听时外部根本无从下手。
 */
class LanConfigServer(
    /**
     * 手机端页面模板，来自 assets/index.html（见 [loadPageTemplate]）。
     * 页面文本不在本类里，改样式/文案只改那个文件，Kotlin 侧不动。
     */
    private val pageTemplate: String,
    /**
     * 当前授权状态的读取入口。
     *
     * 默认走 [Auth.refresh]：token 的唯一真值是 Auth 手里那一份，本类**不自己 create 一个
     * AuthSessionStore**——同一个 DataStore 文件在一个进程里开出第二个实例会直接抛异常，
     * 而「页面自己要读状态」根本不是另立一个 store 的理由。
     */
    private val status: suspend () -> AuthStatus? = {
        Auth.refresh()?.let { AuthStatus(authorized = true, expiresAt = it.expiresAt) }
    },
) {

    @Volatile
    private var socket: ServerSocket? = null

    @Volatile
    private var acceptScope: CoroutineScope? = null

    /** 正在监听的端口；-1 表示没起来 */
    @Volatile
    var port: Int = -1
        private set

    /** 起服务，返回端口；端口全被占用时返回 -1 */
    fun start(): Int {
        if (socket != null) return port
        val ss = bindAnyPort() ?: run {
            port = -1
            return -1
        }
        socket = ss
        port = ss.localPort
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        acceptScope = scope
        scope.launch { acceptLoop(ss) }
        return port
    }

    /** 关端口 + 取消协程；可重复调用 */
    fun stop() {
        val ss = socket ?: return
        socket = null
        port = -1
        // 先关 socket：阻塞在 accept() 上的协程会立刻抛，循环随之退出
        runCatching { ss.close() }
        acceptScope?.cancel()
        acceptScope = null
    }

    /** 9999 起找 20 个端口；全部占用就返回 null（Dialog 会提示无法开启服务） */
    private fun bindAnyPort(): ServerSocket? {
        for (offset in 0 until PORT_TRY_COUNT) {
            val candidate = PREFERRED_PORT + offset
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    // 绑 0.0.0.0：手机要能从局域网进来，不能只绑 loopback
                    bind(InetSocketAddress("0.0.0.0", candidate), BACKLOG)
                }
            } catch (e: Exception) {
                // 该端口被占用，换下一个
            }
        }
        return null
    }

    private suspend fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val client = try {
                ss.accept()
            } catch (e: Exception) {
                // stop() 关掉 socket 的正常路径，直接退出循环
                return
            }
            acceptScope?.launch { handle(client) }
        }
    }

    private suspend fun handle(client: Socket) {
        try {
            client.use { c ->
                c.soTimeout = SOCKET_TIMEOUT_MS
                val request = parseRequest(BufferedInputStream(c.getInputStream()))
                val output = c.getOutputStream()
                if (request == null) {
                    respond(output, 400, TEXT_TYPE, "Bad Request")
                    return
                }
                route(request, output)
            }
        } catch (e: Exception) {
            // 单个连接出错（手机中途断开等）不能影响服务本身
        }
    }

    private suspend fun route(request: HttpRequest, output: OutputStream) {
        when {
            request.method == "GET" && (request.path == "/" || request.path == "/index.html") ->
                render(output, RESULT_NONE)
            request.method == "POST" && request.path == "/save" ->
                save(output, request.form["invite"])
            else -> respond(output, 404, TEXT_TYPE, "404 Not Found")
        }
    }

    /**
     * POST /save：8 位授权码 → 兑换 token。
     *
     * 落盘只发生在 [Auth.pair] 内部一次（写 store + 回写 [Api.TOKEN]）。
     * 这里再写一遍就出现两个真值来源：手机上显示「已授权」而实际没落盘、
     * 或者反过来，只能靠人眼比对两处实现才发现——所以本类只负责判定成功与失败。
     */
    private suspend fun save(output: OutputStream, rawInvite: String?) {
        // 手机键盘与复制粘贴常带空格 / 换行，先去掉再判空：
        // 否则码前多一个空格会被当成「没填」，用户看着自己填了却被告知未填写
        val invite = rawInvite.orEmpty().filterNot { it.isWhitespace() }
        if (invite.isEmpty()) {
            render(output, RESULT_EMPTY)
            return
        }
        val paired = try {
            Auth.pair(invite)
        } catch (e: ApiException) {
            // 失败必须分得开：码坏了要用户去换新码，存储坏了要用户重试，
            // 混成一句「授权失败」用户只能瞎试一轮
            render(output, resultOf(e.error))
            return
        } catch (e: Exception) {
            // 不是 ApiException 就只剩 Auth.pair 内部的本地写入（DataStore）会抛：
            // 网络失败与业务失败都已被 Api.call 归一成 ApiException
            render(output, RESULT_STORAGE)
            return
        }
        // 成功即已落盘：直接用刚拿到的这个会话渲染，不再 refresh 一次——
        // 多一次往返，还可能在这一瞬间读到旧状态，与本页的「授权成功」自相矛盾
        respond(output, 200, HTML_TYPE, pageHtml(AuthStatus(true, paired.expiresAt), RESULT_SAVED))
    }

    /** 把 [AppError] 折成页面能显示的一条结果码；分类口径与用户的下一步动作一一对应 */
    private fun resultOf(error: AppError): String = when (error) {
        AppError.AuthFailed -> RESULT_AUTH_FAILED
        AppError.Unauthorized -> RESULT_AUTH_REQUIRED
        AppError.Storage -> RESULT_STORAGE
        else -> RESULT_FAILED
    }

    private suspend fun render(output: OutputStream, result: String) {
        // 状态读失败不能让整页打不开：这一页的正事是让用户提交授权码，状态行只是参考，
        // 所以降级成「未授权」继续渲染——本机确实也只是「还没换到有效 token」
        val current = try {
            status() ?: AuthStatus(authorized = false)
        } catch (e: Exception) {
            AuthStatus(authorized = false)
        }
        respond(output, 200, HTML_TYPE, pageHtml(current, result))
    }

    // ---------------- 极简 HTTP 解析 ----------------

    private data class HttpRequest(
        val method: String,
        val path: String,
        val form: Map<String, String>,
    )

    private fun parseRequest(input: InputStream): HttpRequest? {
        val head = readHead(input) ?: return null
        val lines = head.split("\r\n")
        if (lines.isEmpty()) return null
        val parts = lines[0].split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val target = parts[1]
        // 路由只看路径：授权码在 body 里，query 一律忽略
        val rawPath = target.substringBefore('?')
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) {
                headers[lines[i].substring(0, idx).trim().lowercase()] = lines[i].substring(idx + 1).trim()
            }
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) {
            val buf = ByteArray(length.coerceAtMost(MAX_BODY))
            var read = 0
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n <= 0) break
                read += n
            }
            String(buf, 0, read, Charsets.UTF_8)
        } else {
            ""
        }
        return HttpRequest(method, rawPath, parsePairs(body))
    }

    /** 逐字节读到空行为止；返回请求行 + 头部原文 */
    private fun readHead(input: InputStream): String? {
        val sb = StringBuilder(256)
        var tail = 0
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            sb.append(b.toChar())
            total++
            if (total > MAX_HEAD) return null
            tail = (tail shl 8) or b
            if (tail == 0x0D0A0D0A) break
        }
        return sb.toString().trimEnd('\r', '\n')
    }

    private fun parsePairs(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isBlank()) continue
            out[decode(pair.substringBefore('='))] = decode(pair.substringAfter('=', ""))
        }
        return out
    }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun respond(output: OutputStream, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(statusText(code)).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            // 手机浏览器不缓存这张页面：每次打开都该看到电视端此刻的授权状态
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        else -> "OK"
    }

    // ---------------- 手机端页面（模板来自 assets/index.html）----------------

    /**
     * 把模板里的三个槽位换成「状态码 + 一个数字」，然后整页发出去。
     *
     * 为什么只发状态码不发句子：会显示在电视屏幕上的中文必须走 string 资源（代码里不留中文字面量），
     * 而这张页面是电视渲染给手机浏览器看的，句子归 index.html 一处维护——数据层只表态
     * 「哪种结果」，页面负责说人话，两端就不会各写一份迟早对不上的文案。
     *
     * 为什么这里没有 HTML 转义：三个值全部取自闭集常量与一个 Long，
     * 不含用户输入、不含 invite、**更不含 token**——页面从头到尾拿不到任何凭据，
     * 也就没有「哪一处忘了转义就把凭据漏进 HTML」这条失败路径。
     */
    private fun pageHtml(current: AuthStatus, result: String): String =
        pageTemplate
            .replace(PLACEHOLDER_STATE, if (current.authorized) STATE_AUTHORIZED else STATE_UNAUTHORIZED)
            .replace(PLACEHOLDER_EXPIRES, epochMillis(current.expiresAt).toString())
            .replace(PLACEHOLDER_RESULT, result)

    companion object {
        /** assets 下的页面模板文件名 */
        private const val PAGE_ASSET = "index.html"

        /**
         * 读取 assets/index.html 作为手机端页面模板。
         *
         * 读失败（资源被裁掉、包体异常）时就地拼一张最简页面：
         * 授权页是唯一的填码入口，宁可少样式也不能给用户一张白页。
         */
        fun loadPageTemplate(context: Context): String = try {
            context.assets.open(PAGE_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">" +
                "<title>ModaTV</title></head><body>" +
                context.getString(R.string.lan_page_template_missing) +
                "</body></html>"
        }

        private const val PREFERRED_PORT = 9999
        private const val PORT_TRY_COUNT = 20
        private const val BACKLOG = 8
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val MAX_HEAD = 8 * 1024
        private const val MAX_BODY = 8 * 1024
        private const val HTML_TYPE = "text/html; charset=utf-8"
        private const val TEXT_TYPE = "text/plain; charset=utf-8"

        /**
         * assets/index.html 里的三个占位符：模板与 [pageHtml] 的两侧靠这三对常量对齐。
         * 值写成 body 的 data 属性值而不是 HTML 注释，是因为页面只需要「读一个状态码」，
         * 不需要在服务端拼 HTML 片段。
         */
        private const val PLACEHOLDER_STATE = "__AUTH_STATE__"
        private const val PLACEHOLDER_EXPIRES = "__EXPIRES_AT__"
        private const val PLACEHOLDER_RESULT = "__RESULT__"

        /** 当前状态（data-auth-state）：这一台电视此刻有没有有效 token */
        private const val STATE_AUTHORIZED = "authorized"
        private const val STATE_UNAUTHORIZED = "unauthorized"

        /** 本次提交结果（data-result）：没提交过就是空串，页面据此不显示提示条 */
        private const val RESULT_NONE = ""
        private const val RESULT_EMPTY = "empty"
        private const val RESULT_SAVED = "saved"
        private const val RESULT_AUTH_FAILED = "auth_failed"
        private const val RESULT_AUTH_REQUIRED = "auth_required"
        private const val RESULT_STORAGE = "storage"
        private const val RESULT_FAILED = "failed"
    }
}

/**
 * 页面要显示的授权状态。
 *
 * 刻意只有这两个字段：token、邀请码、绑定 IP 都不进来——页面拿不到的东西就不可能漏出去，
 * 这条约束写成类型，比在渲染处「小心别打印 token」可靠。
 */
data class AuthStatus(
    val authorized: Boolean,
    /** 到期时间；0 = 不限期（后端用 0 表示不过期） */
    val expiresAt: Long = 0L,
)

/**
 * 后端 expires_at 是秒（PHP 的 time()），本地落盘习惯写毫秒，两种口径都认：
 * 1e11 是 1973 年（按毫秒算）与公元 5138 年（按秒算）的分界，小于它只能是秒。
 * 认错单位页面会显示 1970 年——那比不显示更让人以为授权坏了，所以宁可按量级判一次。
 */
private const val EPOCH_MS_THRESHOLD = 100_000_000_000L

private fun epochMillis(raw: Long): Long =
    if (raw > 0 && raw < EPOCH_MS_THRESHOLD) raw * 1000 else raw

/** 局域网地址 / 二维码两项与 HTTP 协议无关的杂活 */
object LanNet {

    /**
     * 本机（电视/模拟器）在局域网里的 IPv4。
     * 只认 isUp 且非回环的接口，并且优先取站点内网地址（192.168/10./172.16-31），
     * 因为 VPN/虚拟网卡（tun0、docker0）常常排在真实的 wlan0 前面。
     * 一个都取不到就返回 null——设置页据此显示"未连接到局域网"，只留手动编辑。
     */
    fun lanIpv4(): String? {
        val candidates = ArrayList<InetAddress>()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        for (nif in interfaces) {
            val usable = try {
                nif.isUp && !nif.isLoopback
            } catch (e: Exception) {
                false
            }
            if (!usable) continue
            val addrs = try {
                nif.inetAddresses.toList()
            } catch (e: Exception) {
                emptyList()
            }
            for (addr in addrs) {
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    candidates += addr
                }
            }
        }
        // 站点内网优先；同一档内保持枚举顺序（wlan0 通常先出现）
        val best = candidates.firstOrNull { it.isSiteLocalAddress } ?: candidates.firstOrNull()
        return best?.hostAddress
    }

    /** 二维码位图：zxing core 只给 BitMatrix，转 Bitmap 是这里唯一的 Android 依赖 */
    fun qrBitmap(text: String, sizePx: Int): Bitmap? = try {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    } catch (e: Exception) {
        null
    }
}
