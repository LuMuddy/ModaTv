package com.moda.tv.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.moda.tv.model.Danmaku
import kotlin.math.abs
import kotlin.math.max

private const val ScrollDurationMs = 8000L
private const val FixedDurationMs = 4200L
private const val FadeInMs = 300f
private const val FadeOutMs = 900f
private const val MaxTopLanes = 3
private const val MaxBottomLanes = 3
private const val LaneGapPx = 18f
private const val MaxTextLength = 90
private const val DriftResyncMs = 1200L

/** 一条弹幕的排版与排布结果；排布只在尺寸/数据变化时做，绘制期只做位移 */
private class Target(val dm: Danmaku) {
    var placement = -1
    var topPx = 0f
    var layout: TextLayoutResult? = null
}

/** 文字测量带缓存：弹幕文本重复率高，逐帧重排会直接掉帧 */
private class Measurer(private val textMeasurer: TextMeasurer, private val style: TextStyle) {
    private val cache = HashMap<String, TextLayoutResult>()
    fun layout(text: String): TextLayoutResult =
        cache.getOrPut(text) { textMeasurer.measure(AnnotatedString(text), style) }
}

/**
 * 弹幕浮层：自绘 Canvas。
 * 时间轴用播放位置派生（播放中按帧推进、暂停即冻结），因此拖动进度条后弹幕会整体跟随，
 * 不需要重新发射。轨道分配见 [arrange]。
 */
@Composable
fun DanmakuOverlay(
    danmaku: List<Danmaku>,
    positionMs: Long,
    playing: Boolean,
    modifier: Modifier = Modifier,
    bottomInset: Dp = OsdBottomInset,
    /** 是否渲染；false 时直接返回，不注册帧循环、不测量、不绘制 */
    enabled: Boolean = true,
    /** 字号档位（sp） */
    fontSizeSp: Float = 22f,
    /** 可绘制区域从屏幕顶部让出的比例，0f..1f */
    topRatio: Float = 0f,
    /** 可绘制区域从屏幕底部让出的比例，0f..1f */
    bottomRatio: Float = 0f,
) {
    // 关闭时不留下任何 composable 与 frame 回调，绘制开销为零
    if (!enabled || danmaku.isEmpty()) return
    val textMeasurer = rememberTextMeasurer(cacheSize = 128)
    val measurer = remember(textMeasurer, fontSizeSp) {
        Measurer(textMeasurer, TextStyle(fontSize = fontSizeSp.sp, fontWeight = FontWeight.Medium))
    }
    val bottomInsetPx = with(LocalDensity.current) { bottomInset.toPx() }
    val topRatioClamped = topRatio.coerceIn(0f, 0.85f)
    val bottomRatioClamped = bottomRatio.coerceIn(0f, 0.85f)
    var widthPx by remember { mutableIntStateOf(0) }
    var heightPx by remember { mutableIntStateOf(0) }
    var targets by remember(danmaku) { mutableStateOf(danmaku.map { Target(it) }) }
    var clock by remember(danmaku) { mutableLongStateOf(positionMs) }

    // 播放中自走一帧一份的时钟；暂停时循环不再推进，画面冻结在当前位置
    LaunchedEffect(playing, danmaku) {
        if (!playing) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) clock += (now - last) / 1_000_000L
                last = now
            }
        }
    }

    // 外部位置跳变（seek / 起播 / 长时间漂移）时归位；小抖动不打断，避免弹幕来回跳
    LaunchedEffect(positionMs, playing) {
        if (abs(positionMs - clock) > if (playing) DriftResyncMs else 1L) clock = positionMs
    }

    // 位置裁剪：上下边距先按屏幕比例让出去，再让出 OSD 底部控制区，
    // 剩下的矩形就是弹幕唯一可用的绘制区域
    val regionTopPx = heightPx * topRatioClamped
    val usableHeightPx = (heightPx - regionTopPx - heightPx * bottomRatioClamped - bottomInsetPx)
        .coerceAtLeast(0f)

    LaunchedEffect(targets, widthPx, heightPx, usableHeightPx, measurer) {
        if (widthPx <= 0 || usableHeightPx <= 0f) return@LaunchedEffect
        // 排布结果写在 Target 的可变字段里，换一个列表实例才能让绘制阶段读到新值
        if (arrange(targets, measurer, widthPx, usableHeightPx)) targets = targets.toList()
    }

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged {
                widthPx = it.width
                heightPx = it.height
            }
    ) {
        if (usableHeightPx <= 0f) return@Canvas
        // 画布只开放裁剪出来的那一条，弹幕不会溢出到边距区
        clipRect(
            left = 0f,
            top = regionTopPx,
            right = size.width,
            bottom = regionTopPx + usableHeightPx,
        ) {
            val now = clock
            targets.forEach { target ->
                val layout = target.layout ?: return@forEach
                if (target.placement < 0) return@forEach
                val elapsed = now - target.dm.timeMs
                val alive = when (target.dm.mode) {
                    1, 2 -> elapsed in 0..FixedDurationMs
                    else -> elapsed in 0..ScrollDurationMs
                }
                if (!alive) return@forEach
                val textWidth = layout.size.width.toFloat()
                val left: Float
                var alpha = 1f
                when (target.dm.mode) {
                    1, 2 -> {
                        left = (size.width - textWidth) / 2f
                        alpha = fixedAlpha(elapsed)
                    }
                    else -> left = size.width - (elapsed.toFloat() / ScrollDurationMs) * (size.width + textWidth)
                }
                if (alpha <= 0f) return@forEach
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(left, regionTopPx + target.topPx),
                    color = target.dm.color.toComposeColor(alpha),
                    shadow = DanmakuShadow,
                )
            }
        }
    }
}

private val DanmakuShadow = Shadow(Color(0xCC000000), blurRadius = 6f)

private fun fixedAlpha(elapsed: Long): Float {
    val e = elapsed.toFloat()
    return when {
        e < FadeInMs -> e / FadeInMs
        e > FixedDurationMs - FadeOutMs ->
            ((FixedDurationMs - elapsed).toFloat() / FadeOutMs).coerceIn(0f, 1f)
        else -> 1f
    }
}

private fun Int.toComposeColor(alpha: Float): Color =
    Color(0xFF000000.toInt() or (this and 0xFFFFFF)).copy(alpha = alpha)

/**
 * 轨道分配：
 * 1. 滚动弹幕按时间顺序找回扫后「尾部已让出空隙」的轨道，全满就丢弃该条（防刷屏）；
 * 2. 顶部 / 底部固定弹幕各用独立轨道，不与滚动弹幕争位；
 * 3. 每条弹幕的速度按自身宽度反推，宽度不同也保持同一滚完时长，观感一致。
 */
private fun arrange(targets: List<Target>, measurer: Measurer, widthPx: Int, usableHeight: Float): Boolean {
    var changed = false
    targets.forEach { target ->
        val measured = measurer.layout(target.dm.text.take(MaxTextLength))
        if (target.layout !== measured) {
            target.layout = measured
            changed = true
        }
    }

    val scroll = targets.filter { it.dm.mode != 1 && it.dm.mode != 2 }.sortedBy { it.dm.timeMs }
    val laneHeight = (scroll.maxOfOrNull { (it.layout?.size?.height ?: 0).toFloat() } ?: 48f) + 14f
    val laneCount = max(1, (usableHeight / laneHeight).toInt())
    val laneWidth = FloatArray(laneCount) { 0f }
    val laneSpeed = FloatArray(laneCount) { 0f }
    val laneStart = LongArray(laneCount) { Long.MIN_VALUE }

    scroll.forEach { target ->
        val textWidth = (target.layout?.size?.width ?: 0).toFloat()
        val speed = (widthPx + textWidth) / ScrollDurationMs.toFloat()
        var lane = -1
        for (i in 0 until laneCount) {
            val prevStart = laneStart[i]
            val cleared = prevStart == Long.MIN_VALUE ||
                (target.dm.timeMs - prevStart) * laneSpeed[i] >= laneWidth[i] + LaneGapPx
            if (cleared) {
                lane = i
                break
            }
        }
        if (lane < 0) {
            if (target.placement != -1) {
                target.placement = -1
                changed = true
            }
            return@forEach
        }
        laneWidth[lane] = textWidth
        laneSpeed[lane] = speed
        laneStart[lane] = target.dm.timeMs
        val top = lane * laneHeight
        if (target.placement != lane) {
            target.placement = lane
            changed = true
        }
        if (target.topPx != top) {
            target.topPx = top
            changed = true
        }
    }

    val topFixed = targets.filter { it.dm.mode == 1 }.sortedBy { it.dm.timeMs }
    val bottomFixed = targets.filter { it.dm.mode == 2 }.sortedBy { it.dm.timeMs }
    val fixedHeight = (targets.filter { it.dm.mode == 1 || it.dm.mode == 2 }
        .maxOfOrNull { (it.layout?.size?.height ?: 0).toFloat() } ?: 48f) + 14f

    topFixed.forEachIndexed { index, target ->
        val top = (index % MaxTopLanes) * fixedHeight
        if (target.placement != 1000 + index || target.topPx != top) {
            target.placement = 1000 + index
            target.topPx = top
            changed = true
        }
    }
    bottomFixed.forEachIndexed { index, target ->
        val top = usableHeight - (index % MaxBottomLanes + 1) * fixedHeight
        if (target.placement != 2000 + index || target.topPx != top) {
            target.placement = 2000 + index
            target.topPx = top
            changed = true
        }
    }
    return changed
}
