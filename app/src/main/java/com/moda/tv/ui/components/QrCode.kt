package com.moda.tv.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * 静区（二维码四周的纯白边）模块数。标准是 4，这里取 2——理由与实测见 [qrImageBitmap]。
 */
private const val QUIET_MODULES = 2

/**
 * 文本 → 二维码位图。纯函数：不读 Context、不碰资源、不做任何 IO。
 *
 * 为什么自己画而不用服务端给的 qr 图片 URL：二维码是**给摄像头看**的像素，
 * 电视上要保证的是"屏幕上这一块必须是干净的黑白模块 + 足够的留白"。
 * 下载来的图会被缩放、被染色、被暗色主题影响，任何一步走偏都只有一个现象：
 * 微信扫不出来，而我们没有任何办法在电视上排查。
 *
 * 参数取值都对应真实约束：
 * - 纠错级别 M：电视画面被压缩/反光/镜头脏都会吃掉模块，M 是官方推荐的屏幕显示档；
 *   再高一级会明显增加模块密度（码变大反而不利于远距离扫）。
 * - 静区 [QUIET_MODULES] = 2 模块：标准是 4，这里故意降到 2。理由不是"用户嫌白边大"，
 *   而是这块码整体比摄像头里常见的码大得多，外面还有一整块纯白底框接着充当静区
 *   （见 AuthGateScreen 的 QR_PADDING），再叠 4 模块就是屏上一大圈白边、码被挤小。
 *   测试条件与结论：256px 位图 / 2 模块静区 / zxing 解码回原文通过，每边白边 5%；
 *   同一条 URL 走 QRCodeWriter 的 MARGIN=4 时是 16%。
 * - 不缩放、不开抗锯齿：每个模块就是整数个像素（见 [QrCodeImage] 的 filterQuality = None），
 *   插值会把黑白边缘抹成灰，灰度阈值一变就认不出模块。
 *
 * ## 为什么绕开 QRCodeWriter、自己按模块铺
 *
 * QRCodeWriter 内部固定带 4 模块静区，EncodeHintType.MARGIN 只是"再要一层"：
 * 实测 MARGIN = 4 出 16% 白边，而 2 / 1 / 0 画出来完全一样（都是 9%），
 * 也就是说 hint 根本压不到 4 模块以下。要真的只留 2 模块，只能拿 Encoder 的纯模块矩阵
 * （无静区）自己铺：每个模块画成 module×module 的纯色块，静区也按模块数铺满。
 */
internal fun qrImageBitmap(text: String, sizePx: Int): ImageBitmap {
    // 只给字符集：MARGIN 对 Encoder 不起作用（静区是我们自己按模块铺的，见下面的 KDoc）
    val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")
    val matrix = Encoder.encode(text, ErrorCorrectionLevel.M, hints).matrix
    val n = matrix.width
    // 一格模块必须占整数个像素：向下取整，宁可整张码小几像素，也不要半像素模块
    // （半像素会被当成灰边，扫码器直接读不出边界）。coerceAtLeast(1)：极小尺寸下也别除出 0
    val module = (sizePx / (n + 2 * QUIET_MODULES)).coerceAtLeast(1)
    val side = (n + 2 * QUIET_MODULES) * module
    val pixels = IntArray(side * side)
    for (y in 0 until side) {
        // 平移到"模块坐标"：减去静区宽度后，落在 [0, n) 之外的都画白
        val my = y / module - QUIET_MODULES
        for (x in 0 until side) {
            val mx = x / module - QUIET_MODULES
            val dark = mx in 0 until n && my in 0 until n && matrix.get(mx, my).toInt() == 1
            // 纯黑白，不要"接近白"的灰：电视的对比度设置会把灰压成黑，模块边界就糊了
            pixels[y * side + x] = if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * 屏幕上的二维码。
 *
 * 位图只在 [text] 或尺寸变化时重算（remember 的键就是这两个）：
 * 轮询状态每 1.5s 变一次，如果每帧重编码，主线程会每隔一会儿抖一下，
 * 而二维码本身在整个会话里一个像素都不会变。
 */
@Composable
fun QrCodeImage(text: String, size: Dp, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val px = with(density) { size.roundToPx() }.coerceAtLeast(1)
    val bitmap = remember(text, px) { runCatching { qrImageBitmap(text, px) }.getOrNull() }
    // 编码失败（文本异常）时什么都不画，由调用方那块占位底框兜住版面，绝不白屏
    if (bitmap == null) return
    // 位图边长是"模块数 × 整数像素"，通常比请求的 px 小几像素（见 qrImageBitmap）。
    // 这里按位图自己的边长摆，绝不再缩放一次——再缩放就会重新引入半像素模块与灰边
    val side = with(density) { bitmap.width.toDp() }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = modifier.size(side),
        contentScale = ContentScale.Fit,
        // None：宁可让 1 个模块对应整数像素、边缘发硬，也不要插值出来的灰边
        filterQuality = FilterQuality.None,
    )
}
