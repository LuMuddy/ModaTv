package com.moda.tv.data

import android.util.Log
import com.moda.tv.model.AppError
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import okhttp3.Request

/**
 * APK 下载：流式写盘 + 进度回调。
 *
 * 走 [UpdateSource.apkUrls] 给出的候选源（镜像在前、github 直连在后）依次尝试：
 * 镜像对国内链路快，但第三方服务会停（ghfast.top 已经停了），只留一个源就是把
 * App 的自我更新绑在别人的可用性上。
 */
object UpdateDownloader {

    private const val TAG = "UpdateDownloader"

    private const val BUFFER = 64 * 1024

    /**
     * 按顺序尝试 [urls]，第一个成功的写进 [dest] 并返回它。
     *
     * 换源必须整体重来（不用 Range 续传）：不同镜像返回的字节不保证同源，
     * 拼起来可能是一个装不上的包。代价是多花一次流量，换来「装上去一定能用」。
     *
     * 先写 `<dest>.tmp` 再 rename：APK 安装器只看最终文件名，一旦半截文件顶着
     * 正式名字落盘，用户点「安装」看到的是解析失败而不是「下载没完成」，且下次检查
     * 也不会重新下（文件已存在）。rename 在同一文件系统内是原子的，失败也不会留下半个正式文件。
     *
     * [onProgress] 的 total 在 Content-Length 缺失（-1）时传 0，调用方据此显示不确定进度。
     * 回调在 IO 线程上调用，不做切线程——每 64KB 切一次主线程才是真正的开销。
     */
    suspend fun download(urls: List<String>, dest: File, onProgress: (downloaded: Long, total: Long) -> Unit): File {
        val target = dest.absoluteFile
        val tmp = File(target.parentFile, "${target.name}.tmp")
        if (urls.isEmpty()) throw ApiException(AppError.Network)
        var last: ApiException? = null
        for ((i, url) in urls.withIndex()) {
            // 换源等于从头再来：不清零的话进度条会停在上一源已经下到的位置，看起来像卡住了
            onProgress(0L, 0L)
            try {
                fetch(url, tmp, onProgress)
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    Log.w(TAG, "APK 落盘失败：rename 到 ${target.name} 被拒")
                    // 落盘失败与源无关，换源重下一次只会再写同一块盘、再失败一次
                    throw ApiException(AppError.Storage)
                }
                return target
            } catch (e: CancellationException) {
                tmp.delete()
                throw e
            } catch (e: ApiException) {
                tmp.delete()
                // 落盘失败与源无关：换源重下一次只会再写同一块盘、再失败一次
                if (e.error == AppError.Storage) throw e
                last = e
                Log.w(TAG, "候选源 ${i + 1}/${urls.size} 失败：${e.error}（$url），换下一个")
            }
        }
        throw last ?: ApiException(AppError.Network)
    }

    /**
     * 单个源的一次完整尝试：流式写 [tmp]，进度回调走 [onProgress]。
     * 失败一律转成 [ApiException]（取消除外），换源的决定留给上层。
     */
    private suspend fun fetch(url: String, tmp: File, onProgress: (downloaded: Long, total: Long) -> Unit) {
        val req = Request.Builder().url(url).get().build()
        val call = Api.http.newCall(req)

        // 复用 Api.http：连接池与超时口径只有一处真值（与 UpdateSource 同理）
        try {
            // runInterruptible 而不是裸 withContext：协程取消时要能打断阻塞中的 socket 读，
            // 否则用户退出页面后这个线程仍会把整个 APK 读完、把流量和电量白白烧掉
            runInterruptible(Dispatchers.IO) {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "APK 下载失败：HTTP ${resp.code}")
                        throw ApiException(AppError.Http(resp.code))
                    }
                    val body = resp.body ?: throw ApiException(AppError.EmptyPayload)
                    tmp.parentFile?.mkdirs()
                    body.byteStream().use { input ->
                        FileOutputStream(tmp).use { output ->
                            val buffer = ByteArray(BUFFER)
                            var done = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                onProgress(done, body.contentLength().coerceAtLeast(0L))
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            tmp.delete()
            if (e is CancellationException) throw e
            // 中断式取消会让 socket 读抛 InterruptedIOException，此时协程已经被取消：
            // 先认领取消，别把用户主动退出伪装成「网络错误」（静默降级的范围不含取消：取消是用户意图，不是故障）
            currentCoroutineContext().ensureActive()
            throw when (e) {
                is ApiException -> e
                is IOException -> ApiException(AppError.Network, e)
                else -> ApiException(AppError.Unknown, e)
            }
        }
    }
}
