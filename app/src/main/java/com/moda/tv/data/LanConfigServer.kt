package com.moda.tv.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
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
 * 局域网扫码配置服务。
 *
 * 存在的唯一目的：让用户在手机浏览器里改「代理网址」，避免在电视遥控器上敲一条长 URL。
 *
 * 生命周期是本类最重要的约束，因此**不做任何常驻持有**：
 *   - [start] 由 Dialog 进入组合时调用（绑定 0.0.0.0，端口从 9999 起逐个试）；
 *   - [stop]  由 Dialog 离开组合 / 设置页退出 / 应用进入后台时调用，
 *             close() 掉 ServerSocket 并把协程作用域 cancel 掉，端口立即归还系统。
 * 没有前台服务、没有静态单例、没有延时回收——对象被丢弃即彻底消失。
 *
 * 安全边界：只有 Dialog 打开期间端口才在监听，关掉即完全无法访问。
 * 不做 token 校验——能连上（局域网内）就允许改，端口不监听时外部根本无从下手。
 */
class LanConfigServer(
    /** 读取当前生效的代理网址，用于预填网页输入框 */
    private val currentBase: () -> String,
    /**
     * 手机端提交后的落盘动作。校验 / 探活 / 写入都由调用方负责，本类只管协议。
     * 返回值带回"规范化后的实际地址"，因此本类不依赖任何全局状态：
     * 手机上看到的就是真正落盘的那一条，不会出现回显值与生效值不一致。
     */
    private val save: suspend (String) -> SaveOutcome,
    /** 保存成功回调（IO 线程），供电视端 Dialog 实时刷新 */
    private val onSaved: (String) -> Unit,
    /**
     * 手机端页面模板，来自 assets/config.html（见 [loadPageTemplate]）。
     * 页面文本不在本类里，改样式/文案只改那个文件，Kotlin 侧不动。
     */
    private val pageTemplate: String,
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
            request.method == "GET" && (request.path == "/" || request.path == "/index.html") -> {
                respond(output, 200, HTML_TYPE, pageHtml(current = currentBase(), error = null, saved = null))
            }
            request.method == "POST" && request.path == "/save" -> {
                val raw = request.form["url"].orEmpty()
                val outcome = when {
                    raw.isBlank() -> SaveOutcome.Fail("请输入代理网址")
                    else -> save(raw)
                }
                when (outcome) {
                    is SaveOutcome.Ok -> {
                        // 落盘成功：通知电视端 Dialog 换值，手机端显示成功页
                        onSaved(outcome.base)
                        respond(output, 200, HTML_TYPE, pageHtml(current = outcome.base, error = null, saved = outcome.base))
                    }
                    is SaveOutcome.Fail -> respond(
                        output, 200, HTML_TYPE,
                        pageHtml(current = currentBase(), error = outcome.reason, saved = null),
                    )
                }
            }
            else -> respond(output, 404, TEXT_TYPE, "404 Not Found")
        }
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
        // 路由只看路径：没有 token，query 一律忽略
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
            // 手机浏览器不缓存这张页面：每次打开都该看到电视上当前生效的地址
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

    // ---------------- 手机端页面（模板来自 assets/config.html）----------------

    private fun pageHtml(current: String, error: String?, saved: String?): String {
        val value = htmlEscape(saved ?: current)
        val banner = when {
            saved != null -> BANNER_OK
            error != null -> "<div class=\"err\">" + htmlEscape(error) + "</div>"
            else -> ""
        }
        // 占位符替换是字面量替换：value 已做过 HTML 转义，不会被当模板解释
        return pageTemplate
            .replace(PLACEHOLDER_BANNER, banner)
            .replace(PLACEHOLDER_VALUE, value)
    }

    private fun htmlEscape(s: String): String = buildString(s.length + 16) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    companion object {
        /** assets 下的页面模板文件名 */
        private const val PAGE_ASSET = "config.html"

        /**
         * 读取 assets/config.html 作为手机端页面模板。
         *
         * 读失败（资源被裁掉、包体异常）时退回 [FALLBACK_PAGE]：
         * 配置页是唯一的改地址入口，宁可少样式也不能给一张白页。
         */
        fun loadPageTemplate(context: Context): String = try {
            context.assets.open(PAGE_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            FALLBACK_PAGE
        }

        private const val PREFERRED_PORT = 9999
        private const val PORT_TRY_COUNT = 20
        private const val BACKLOG = 8
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val MAX_HEAD = 8 * 1024
        private const val MAX_BODY = 8 * 1024
        private const val HTML_TYPE = "text/html; charset=utf-8"
        private const val TEXT_TYPE = "text/plain; charset=utf-8"
        private const val BANNER_OK = "<div class=\"ok\">已生效，可以关闭此页面</div>"

        /**
         * assets/config.html 里的占位符；模板与替换两侧靠它对齐。
         * 提示条写成 HTML 注释，这样单独用浏览器打开模板时不会看到它。
         */
        private const val PLACEHOLDER_BANNER = "<!--BANNER_SLOT-->"
        private const val PLACEHOLDER_VALUE = "__VALUE__"

        /** 资源缺失时的兜底：宁可让用户看到一句人话，也不要返回空白页 */
        private const val FALLBACK_PAGE =
            "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">" +
                "<title>ModaTV</title></head><body>页面模板缺失，请在电视端手动修改代理网址。</body></html>"
    }
}

/** 保存结果：成功带回规范化后的地址，失败带回给用户看的原因 */
sealed interface SaveOutcome {
    data class Ok(val base: String) : SaveOutcome
    data class Fail(val reason: String) : SaveOutcome
}

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
