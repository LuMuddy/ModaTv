package com.moda.tv.ui.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把「下载好的 APK」交给系统安装器。只做 Intent 拼装与文件落地，不碰版本比较、网络与 UI 状态。
 *
 * API 26 是这里唯一的分界线，原因是权限模型换过一次：
 * - API < 26：未知来源安装是**系统级**的一个开关（Settings.ACTION_SECURITY_SETTINGS 里勾一次，
 *   对所有应用生效），应用自己无从查询也无法请求，只能把用户送到那个页面，因此这里恒返回可装。
 * - API >= 26：改成**逐应用授权**（"Install unknown apps"），每个应用单独一个开关，
 *   且只有本应用声明了 REQUEST_INSTALL_PACKAGES 才能在设置页出现这一项；
 *   没授权时打开安装器会被系统静默拒绝，所以调用方必须先问 [canInstall]，
 *   未授权就跳 [settingsIntent]。
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"
    private const val MIME_APK = "application/vnd.android.package-archive"

    private fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /**
     * 安装包的落地目录（cache 下，系统清理缓存时会一并回收，不会污染用户数据）。
     * 这个目录同时是 file_paths.xml 里 cache-path（name="update", path="update/"）唯一允许共享的路径，
     * 放到别处的 APK 会因为 FileProvider 不含对应路径而抛 IllegalArgumentException。
     */
    fun updateDir(context: Context): File {
        val dir = File(context.cacheDir, "update")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * API >= 26 查本应用是否已被授权安装未知来源应用；API < 26 是系统级开关，
     * 应用侧读不到也不该猜，恒为 true（真正被系统开关拦下时，安装器会自己给出提示）。
     */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /**
     * 跳到授权页。API >= 26 直接落在**本应用**那一项（ACTION_MANAGE_UNKNOWN_APP_SOURCES + package:），
     * 用户在列表里翻不到自己；API < 26 只有系统级的应用安全页可去。
     */
    fun settingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }

    /**
     * 拉起系统安装器。返回 false 只表示「没有安装器能接这个 Intent」（部分裁剪过的盒子
     * 或企业管控设备），由调用方降级成提示用户手动安装，不往上抛异常。
     *
     * 两个 flag 都是必需的：GRANT_READ_URI_PERMISSION 把 content:// 的读权限临时授给安装器
     * （安装器是另一个进程，拿不到本应用 cacheDir 的路径）；NEW_TASK 让这段代码
     * 从非 Activity 上下文（例如设置页的 ViewModel）调用也能起得来。
     */
    fun install(context: Context, apk: File): Boolean {
        val uri: Uri = FileProvider.getUriForFile(context, authority(context), apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_APK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "没有能处理 APK 安装的应用：" + apk.absolutePath, e)
            false
        }
    }
}
