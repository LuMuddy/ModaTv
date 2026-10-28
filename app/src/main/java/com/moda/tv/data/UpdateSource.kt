package com.moda.tv.data

import android.util.Log
import android.util.Xml
import com.moda.tv.model.AppError
import com.moda.tv.model.UpdateInfo
import java.io.IOException
import java.io.StringReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/**
 * 版本检查的取数层：读 GitHub Releases 的 atom feed。
 *
 * 为什么是 atom 而不是 GitHub REST API：feed 免鉴权、没有 60 次/小时 的匿名配额，
 * 而我们在意的只有「最新一版的版本号 + 更新日志」这两件事，feed 里都有。
 *
 * 为什么 APK 地址靠版本号拼、不查 API：CI（.github/workflows/android-release.yml）
 * 在推 `v*` tag 时把 asset 固定命名成 `ModaTv-<version>.apk`，
 * 于是「版本号 → 直链」是一条确定的函数关系，少一次网络往返。
 */
object UpdateSource {

    private const val TAG = "UpdateSource"

    private const val OWNER = "LuMuddy"
    private const val REPO = "ModaTv"

    /**
     * 检查这条路**只有直连**，没有镜像回落。
     * 实测镜像对 `../releases.atom` 返回 403 "Invalid input."，
     * 镜像只代理 release 的 asset 下载路径；写一条注定 403 的回落等于死代码。
     *
     * **owner/repo 必须与 [apkUrl] 用同一份常量**：这里曾经指到别的仓库的 feed，
     * 于是取到的是对方的版本号（5.0.1），拿去拼本仓库的 APK 直链必然 404，
     * 用户那边每一句都是「下载失败，可重试」，而真正的病因是 feed 指错了仓库。
     */
    private const val ATOM_URL = "https://github.com/$OWNER/$REPO/releases.atom"

    /**
     * 下载镜像：只代理 release 的 asset 路径。
     * 早先的 ghfast.top 已经停服（TLS 直接断开，实测连不上），换 gh-proxy.com（实测 Range 206）。
     * 镜像随时可能没，所以它只是**候选之一**，直连永远排在后面兜底（见 [apkUrls]）。
     */
    private const val MIRROR_PREFIX = "https://gh-proxy.com/"

    /**
     * 更新日志的上屏上限。
     * generate_release_notes 生成的是整轮提交列表，长版本动辄数万字，
     * 一次塞进 Compose Text 会让整屏测量的代价随正文长度线性增长，而 TV 上没人会读完。
     */
    private const val NOTES_MAX = 6000

    /**
     * 最新一版的版本号 / 更新日志 / APK 直链。
     *
     * 失败只抛 [ApiException]（[AppError.Network] 或 [AppError.EmptyPayload]），
     * 绝不抛裸异常：数据层不承担文案，裸 IOException 落到 ViewModel 只能变成「未知错误」。
     * **仓库还没有 Release 时就是 [AppError.EmptyPayload]** —— feed 只有头、没有 entry，
     * 这是「没有新版」而不是故障，调用方按「已是最新」处理即可。
     */
    suspend fun latest(): UpdateInfo = withContext(Dispatchers.IO) {
        parseLatest(fetchAtom())
    }

    /**
     * 复用 [Api.http] 而不是自建客户端：连接池、DNS 缓存、超时口径只有一处真值。
     * 再 new 一个 OkHttpClient 会多一套线程池与连接池，且超时值改一处漏一处。
     */
    private fun fetchAtom(): String {
        val req = Request.Builder().url(ATOM_URL).get().build()
        val xml = try {
            Api.http.newCall(req).execute().use { resp ->
                // 非 2xx 在这里归成 Network：对本层而言「GitHub 或中间链路没给我们内容」
                // 与断网对用户是同一件事（稍后重试），错误码只进日志
                if (!resp.isSuccessful) {
                    Log.w(TAG, "版本检查失败：HTTP ${resp.code}")
                    throw ApiException(AppError.Network)
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            Log.w(TAG, "版本检查网络失败：${e.message}")
            throw ApiException(AppError.Network, e)
        }
        if (xml.isBlank()) {
            Log.w(TAG, "版本检查拿到空响应体")
            throw ApiException(AppError.EmptyPayload)
        }
        return xml
    }

    /**
     * 只取第一条 entry（feed 里最新的一条排在最前），拿到就停：
     * 完整的 feed 会带上全部历史版本的正文，解析后来那些纯属浪费。
     *
     * **版本号必须从 `<link rel="alternate">` 的 href 取，不能从 `<title>` 取**：
     * atom 里的 `<title>` 是「发布名称」（release name），是维护者在发布页手填的自由文本；
     * 只有 href 里的 `/releases/tag/<tag>` 才是真正的 git tag。三种实测样本：
     * - scrcpy：href 是 `/tag/v5.0.1`，title 却是 `scrcpy 5.0.1`。
     *   按 title 取会把版本号解析成 "scrcpy 5.0.1"，拿去和本地版本比时数字前缀取到 0，
     *   于是永远判定「不比当前版本新」，更新弹窗再也不出现——这是真实故障，不是理论风险。
     * - okhttp：title 是 `parent-5.5.1`，与 href 的 tag 恰好一致，按 title 也能跑。
     * - 本仓库：CI 用 generate_release_notes 自动生成发布名称，恰好等于 tag，所以旧实现一直没暴露。
     * 前两种样本合起来说明 title 只是「碰巧常常等于 tag」，只要维护者手填一次名称功能就废，
     * 所以不要把它「优化」回 title——title 只保留为 href 缺失时的兜底。
     * 兜底路径任一来源的 tag 都必须能解析出数字，否则宁可当「没有新版」（[AppError.EmptyPayload]），
     * 也不拿一个假版本号去和本地版本比较。
     */
    private fun parseLatest(xml: String): UpdateInfo {
        val parser = Xml.newPullParser()
        // atom 的元素都不带前缀；关掉命名空间处理后 getName() 直接就是 "entry" / "title"
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)

        var title = ""
        var href = ""
        var content = ""
        var inEntry = false

        try {
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.END_TAG && parser.name == "entry") break
                if (event == XmlPullParser.START_TAG && !inEntry) {
                    inEntry = parser.name == "entry"
                } else if (event == XmlPullParser.START_TAG) {
                    // 必须限定在 entry 内取字段：feed 自己也有 <title>（"Release notes from ModaTv"），
                    // 少了这一层，空 feed 会把 feed 标题当成版本号，凭空造出一个"新版"
                    when (parser.name) {
                        "title" -> title = parser.nextText().orEmpty()
                        "content" -> content = parser.nextText().orEmpty()
                        "link" -> {
                            // rel 缺省即 alternate；href 里的 /releases/tag/<tag> 是版本号的唯一可信来源
                            val rel = parser.getAttributeValue(null, "rel")
                            if (rel == null || rel == "alternate") {
                                href = parser.getAttributeValue(null, "href").orEmpty()
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: XmlPullParserException) {
            // feed 被运营商/代理插了页或截断：内容不可信，按空载荷处理，不猜版本号
            Log.w(TAG, "atom 解析失败：${e.message}")
            throw ApiException(AppError.EmptyPayload, e)
        } catch (e: IOException) {
            Log.w(TAG, "atom 读取失败：${e.message}")
            throw ApiException(AppError.Network, e)
        }

        // href 里的 tag 优先；href 缺失（或不是 releases/tag 形态）时才退回 title
        val tag = href.substringAfterLast("/releases/tag/", "").trim()
        val version = (tag.ifBlank { title.trim() })
            .let { if (it.startsWith("v") || it.startsWith("V")) it.substring(1) else it }
        // 没有 entry、entry 没标题、或者 tag 是纯文本（如 "nightly"）：只报空，绝不编一个版本号出来。
        // 少了数字这一关，一个非数字 tag 会以「版本号」的身份进入比较逻辑，比的是垃圾值。
        if (version.isBlank() || version.none { it.isDigit() }) {
            Log.w(TAG, "atom 里没有可用的 release（tag=${tag}, title=${title.trim()}）")
            throw ApiException(AppError.EmptyPayload)
        }
        return UpdateInfo(
            versionName = version,
            notes = stripHtml(content),
            apkUrl = "https://github.com/$OWNER/$REPO/releases/download/v$version/ModaTv-$version.apk",
        )
    }

    /**
     * 一次下载的候选源，按尝试顺序排：镜像在前、直连在后。
     *
     * 镜像对国内链路快得多，但它是个第三方服务，随时会没（ghfast.top 就是这样没的）；
     * 直连 github 返回 302 之后同样能下完，只是慢。两个都留才有「总有一个能通」，
     * 只剩一个就等于把 App 的自我更新绑在别人的可用性上——而这正是用户看到「下载失败」的那次故障。
     */
    fun apkUrls(info: UpdateInfo): List<String> = listOf(MIRROR_PREFIX + info.apkUrl, info.apkUrl)

    // 块级收尾先换成换行，否则 <li>a</li><li>b</li> 会连成一行 "a b"
    private val BLOCK_END = Regex("(?i)<\\s*br\\s*/?\\s*>|</\\s*(p|li|div|h[1-6]|tr)\\s*>")
    private val ANY_TAG = Regex("<[^>]*>")
    private val INLINE_BLANK = Regex("[ \\t\\u00a0]+")
    private val MANY_BREAKS = Regex("\\n{3,}")

    /**
     * atom 的 content 是 GitHub 的 HTML 正文（XML 解析已脱掉一层转义）。
     * 上屏只需要纯文本：标签一律去掉，实体还原，空白折叠，最后按 [NOTES_MAX] 截断。
     */
    private fun stripHtml(html: String): String {
        if (html.isBlank()) return ""
        val text = html.replace(BLOCK_END, "\n")
            .replace(ANY_TAG, "")
            .let { unescape(it) }
            .replace(INLINE_BLANK, " ")
            .replace(MANY_BREAKS, "\n\n")
            .trim()
        return if (text.length <= NOTES_MAX) text else text.take(NOTES_MAX) + "…"
    }

    // &amp; 最后还原，否则 "&amp;lt;" 会被先解成 "<" 再当成标签吃掉
    private fun unescape(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
