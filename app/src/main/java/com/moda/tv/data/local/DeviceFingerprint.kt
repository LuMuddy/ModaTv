package com.moda.tv.data.local

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

/**
 * 本机指纹：把若干"这台机器装什么都不是、装成什么样"的公开字段拼起来做 sha256。
 *
 * 为什么运行时算、绝不落仓库：服务端拿它把 token 绑到具体设备，写进仓库等于所有设备
 * 共用一个指纹，绑定当场失效；而且仓库是公开的，掺进去的任何设备标识都等于泄露。
 *
 * 为什么只用 Build.* 与 ANDROID_ID：这两样都不需要运行时权限，也不会像 IMEI / MAC 那样
 * 在 Android 10+ 上读不到或被判成过度收集。稳定性上也够用——它们随"换机 / 恢复出厂"变化，
 * 正好是我们希望判成"新设备"的时机。
 */
internal object DeviceFingerprint {

    // 进程内缓存：拼串 + sha256 每次都要花掉毫秒级时间，而结果在进程生命周期里不会变
    @Volatile
    private var cached: String? = null

    fun of(context: Context): String {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: compute(context.applicationContext).also { cached = it }
        }
    }

    private fun compute(context: Context): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        } catch (_: Exception) {
            // 个别定制 ROM 读 ANDROID_ID 会抛 SecurityException；拿不到就空着，
            // 剩下的 Build 字段仍足以把同一台机器认出来，不该让启动流程因此崩掉
            ""
        }
        // 刻意不掺任何"系统版本类"字段。系统版本号一年变一两次；而系统构建指纹那几个字段
        // （build fingerprint 由 brand/product/device:buildId/incremental:type/tags 组成）更糟：
        // 里面的 buildId 与 incremental 一次 OTA 或月度补丁就变，比版本号敏感得多。
        // 掺进来都会把升级完系统的用户判成新设备：本地"已授权"却每个请求都失效，
        // 而且用户完全看不出原因是系统更新。
        // 稳定性优先于"更唯一"：识别力弱一点可以接受，OTA 悄悄废掉 token 不可以。
        //
        // 将来若要改这个字段列表，先给指纹串加算法版本前缀（如 "v2|"）留一条迁移路径，
        // 否则一次改动等于把所有设备同时判成新设备。
        val raw = listOf(
            "brand=${Build.BRAND}",
            "manufacturer=${Build.MANUFACTURER}",
            "device=${Build.DEVICE}",
            "model=${Build.MODEL}",
            "product=${Build.PRODUCT}",
            "board=${Build.BOARD}",
            "androidId=$androidId",
        ).joinToString("|")
        return sha256Hex(raw)
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(64)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
}
