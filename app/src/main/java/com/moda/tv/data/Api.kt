package com.moda.tv.data

import com.moda.tv.BuildConfig
import com.moda.tv.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 后端调用层。
 *
 * 请求路径、动作名、请求头与响应包装属于**运行期契约**，不随仓库发布——
 * 客户端只需要知道：所有请求都发往 [BASE]，失败时抛 [ApiException]。
 *
 * 后端地址、设备令牌与指纹一律来自 BuildConfig（构建期从 local.properties 注入），
 * 仓库内不保存任何真实值；缺省时为空串，见 local.properties.example。
 */
class ApiException(message: String) : Exception(message)

object Api {
    /** 出厂默认地址；用户没在设置页改过时就是它（ApiSettingsStore 以键缺失表示"没改过"） */
    val DEFAULT_BASE: String = BuildConfig.API_BASE

    /**
     * 当前生效的后端地址。由 ApiSettingsStore 在应用启动时注入、设置页修改后立即回写，
     * 所以不能是 const val——所有请求都在调用时读它，改完不需要重启应用。
     * volatile：设置页在 IO 线程写，请求在 OkHttp 线程读。
     */
    @Volatile
    var BASE: String = DEFAULT_BASE

    val DEVICE_TOKEN: String = BuildConfig.DEVICE_TOKEN
    val DEVICE_FP: String = BuildConfig.DEVICE_FP
    /** vkey 接口固定域名（app.js 中 DEFAULT_DOMAIN，不再有切换面板） */
    private const val DOMAIN = "https://omex.tc.qq.com/"

    internal val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val JSON = "application/json".toMediaType()

    /** 底层 POST，返回已校验 ok=true 的 JSONObject */
    suspend fun call(action: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE?a=$action")
            .header("Content-Type", "application/json")
            .header("X-Auth-Device", DEVICE_TOKEN)
            .header("X-Auth-Fp", DEVICE_FP)
            .post(body.toString().toRequestBody(JSON))
            .build()
        val text = http.newCall(req).execute().use { resp ->
            val t = resp.body?.string().orEmpty()
            if (t.isBlank()) throw ApiException("接口为空（HTTP ${resp.code}）")
            t
        }
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw ApiException("接口未返回 JSON（${text.take(120)}）")
        }
        if (!json.optBoolean("ok", false)) {
            throw ApiException(
                json.optString("error").ifBlank {
                    json.optString("msg").ifBlank { "接口失败" }
                }
            )
        }
        json
    }

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
