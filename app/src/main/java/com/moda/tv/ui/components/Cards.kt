package com.moda.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.LocalContentColor
import kotlinx.coroutines.delay
import com.moda.tv.model.Tier
import com.moda.tv.model.VideoItem
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption
import com.moda.tv.ui.theme.TvHeading
import com.moda.tv.ui.theme.TvTitle

/** 焦点描边：TV 上离得远，只靠放大不够，必须同时有明显白边 */
private fun focusBorder(width: Dp, corner: Dp, color: Color = ModaColors.Focus) =
    Border(border = BorderStroke(width, color), shape = RoundedCornerShape(corner))

/**
 * 填蓝色卡底专用两个深色。
 *
 * ModaColors.Accent(#3D7BFF) 配白字只有 3.84:1、AccentSoft(#6E9BFF) 只有 2.69:1，
 * 都低于 WCAG AA 的 4.5:1。颜色常量在 theme/Theme.kt（不在本次修复边界内），
 * 所以在这里派生两个更深的工作色，凡是「蓝底 + 白字」的填充态都用它们：
 *   AccentDeep   #2A5FD6 → 白字 5.67:1 合格
 *   AccentDeepHi #3568DE → 白字 5.02:1 合格（聚焦/按下时略亮，仍有反馈感）
 */
private val AccentDeep = Color(0xFF2A5FD6)
private val AccentDeepHi = Color(0xFF3568DE)

/**
 * 首页轮播的高度预算。实机屏幕是 960x540dp（1920x1080 @320dpi），
 * 一屏必须同时放下「工具栏 + 轮播整体 + 一行内容卡片」，所以尺寸定死而不是随内容长：
 *   主视觉 168dp + 间距 10dp + 缩略图行约 90dp + 内边距 ≈ 270dp，落在 260~300 的预算内。
 * 定死高度的另一个原因：换图时布局不能抖，否则下方内容行的位置会跟着跳。
 */
private val HERO_STAGE_HEIGHT = 360.dp

/** 缩略图宽：16:9 的 160dp 宽 ≈ 90dp 高，一排能露出 5~6 条，提示「后面还有」 */
/** 竖版缩略图：宽 72dp、高 100dp（约 3:4），叠在大图右下角时不遮挡主体 */
private val HERO_THUMB_WIDTH = 72.dp
private val HERO_THUMB_HEIGHT = 100.dp

/**
 * 缩略图行的可视槽位数。容器宽度按它算，必须与 [HERO_THUMB_VISIBLE] 同源：
 * 两处各写一个数字时宽度与实际卡数一旦对不上，最后一张必然被容器从中间切断。
 */
private const val HERO_THUMB_VISIBLE = 5

/** 缩略图之间的间距，宽度预算与 LazyRow 的 horizontalArrangement 都引用它 */
private val HERO_THUMB_GAP = 8.dp

/** 缩略图行左右内边距：聚焦放大 1.06 与 3dp 描边都画在框外，贴边必被裁 */
private val HERO_THUMB_STRIP_PAD = 12.dp

/**
 * ============ 可聚焦元素内的文字配色规则（务必遵守） ============
 *
 * 1) 可聚焦元素内部**禁止硬编码文字颜色**（禁止 color = ModaColors.TextDim 之类）。
 * 2) 原因有两层，第二层是隐形坑：
 *    - 容器在聚焦/按下时会把底换成别的颜色（聚焦=纯白，见下面各 Card 的 focusedContainerColor），
 *      文字必须跟着容器走，否则白底上的浅色字直接看不见。
 *    - TvTitle/TvBody/TvCaption 这些 TextStyle 里**把颜色烤死在 style.color 上**，
 *      而 Text 的取色顺序是 color.takeOrElse { style.color.takeOrElse { LocalContentColor } }。
 *      也就是说「不传 color」并不会跟随内容色，style 里那个死颜色会赢。
 *      所以可聚焦容器里必须显式写 color = LocalContentColor.current。
 * 3) 次级文字用 LocalContentColor.current.copy(alpha = 0.75f) 表达层级：
 *      白底(Bg@75%→#48484B) 9.11:1；深底卡(Text@75%) 8.66:1，都远超 4.5:1。
 * 4) 用的是 androidx.tv.material3.LocalContentColor —— tv-material 的 Card/Surface 提供的是
 *    这一个，material3 包里同名的是另一个 CompositionLocal，读错了永远是默认黑，不会跟随反色。
 */
@Composable
private fun contentColor() = LocalContentColor.current

/** 次级文字色：跟随容器内容色压一档透明度，而不是写死某个灰色 */
@Composable
private fun secondaryContentColor() = LocalContentColor.current.copy(alpha = 0.75f)

@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val corner = 10.dp
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(corner)),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) AccentDeep else ModaColors.SurfaceHigh,
            contentColor = if (primary) Color.White else ModaColors.Text,
            focusedContainerColor = if (primary) AccentDeepHi else ModaColors.Focus,
            // 白底必须配近黑字：White on Focus 是 1.00:1，是全盲
            focusedContentColor = if (primary) Color.White else ModaColors.Bg,
            pressedContainerColor = AccentDeepHi,
            pressedContentColor = Color.White,
            disabledContainerColor = ModaColors.Surface,
            disabledContentColor = ModaColors.TextDim,
        ),
        scale = ButtonDefaults.scale(scale = 1f, focusedScale = 1.08f),
        border = ButtonDefaults.border(
            border = Border.None,
            focusedBorder = focusBorder(3.dp, corner),
        ),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Text(
            text = text,
            style = TvBody.copy(fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            // 显式跟随内容色：TvBody 里烤死的 #F2F3F7 在聚焦白底上只有 1.11:1
            color = contentColor(),
        )
    }
}

/**
 * 首页工具栏用的纯图标按钮。
 * TV 上图标按钮的点击/焦点面积必须够大（56dp 起），否则遥控器很难停住；
 * contentDescription 不是可选项——屏幕阅读器和自动化测试都靠它识别按钮语义。
 */
@Composable
fun IconActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val corner = 12.dp
    IconButton(
        onClick = onClick,
        modifier = modifier.size(56.dp),
        enabled = enabled,
        shape = IconButtonDefaults.shape(shape = RoundedCornerShape(corner)),
        colors = IconButtonDefaults.colors(
            containerColor = ModaColors.SurfaceHigh,
            contentColor = ModaColors.Text,
            // 聚焦仍是白底 + 近黑图标，与其余可聚焦元素保持同一套反色语言
            focusedContainerColor = ModaColors.Focus,
            focusedContentColor = ModaColors.Bg,
            pressedContainerColor = AccentDeepHi,
            pressedContentColor = Color.White,
            // 禁用态（如尚未接线的历史/收藏）压暗但仍可辨识，避免看起来像坏掉的按钮
            disabledContainerColor = ModaColors.Surface,
            disabledContentColor = ModaColors.TextDim,
        ),
        scale = IconButtonDefaults.scale(scale = 1f, focusedScale = 1.08f),
        border = IconButtonDefaults.border(
            border = Border.None,
            focusedBorder = focusBorder(3.dp, corner),
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(28.dp),
            // tv-material 的 Icon 默认 tint = LocalContentColor，这里显式写一遍强调「跟内容色走」
            tint = contentColor(),
        )
    }
}

/** 首页/搜索里的竖版海报卡 */
@Composable
fun VideoCard(
    item: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = 210.dp,
    /** 是否预取高清封面；只有横幅和首屏/当前聚焦的大图才置 true（见 RemoteImage 注释） */
    preferHiRes: Boolean = false,
    /**
     * 卡片标题行数。首页行把它压到 1：两行标题会让整行高度不可预测，
     * 「一屏内看到一整行」就没法保证（见 HomeScreen 里轮播高度的取舍说明）。
     */
    titleMaxLines: Int = 2,
) {
    val corner = 12.dp
    val sized = if (width == null) modifier else modifier.width(width)
    Card(
        onClick = onClick,
        modifier = sized,
        shape = CardDefaults.shape(shape = RoundedCornerShape(corner)),
        colors = CardDefaults.colors(
            containerColor = ModaColors.Surface,
            contentColor = ModaColors.Text,
            focusedContainerColor = ModaColors.SurfaceHigh,
            focusedContentColor = ModaColors.Text,
            pressedContainerColor = AccentDeepHi,
            pressedContentColor = Color.White,
        ),
        scale = CardDefaults.scale(scale = 1f, focusedScale = 1.08f, pressedScale = 1f),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = focusBorder(3.dp, corner),
        ),
    ) {
        Box(Modifier.fillMaxWidth()) {
            RemoteImage(
                url = item.cover,
                title = item.title,
                cid = item.cid,
                preferHiRes = preferHiRes,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f),
                corner = 0.dp,
            )
            if (item.badge.isNotBlank()) {
                Badge(
                    text = item.badge,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = item.title,
                style = TvBody.copy(fontSize = 17.sp),
                color = contentColor(),
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.subtitle,
                    style = TvCaption.copy(fontSize = 14.sp),
                    color = secondaryContentColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 大图区的可视化内容（海报 + 左侧暗色渐变 + 文案）。
 *
 * 单独抽出来是为了让 Crossfade 只重绘这一块：下面缩略图行的焦点矩形、
 * 滚动位置在切条时都不能被重建，否则遥控器手感会跳。
 */
@Composable
private fun HeroStage(
    item: VideoItem,
    fallbackCover: String = "",
    onCoverReady: (String) -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
        // 垫底层：上一张已经加载出来的封面。新图在下面覆盖上去，
        // 加载期间用户看到的是「旧图」而不是空白卡，切换感才连续。
        if (fallbackCover.isNotBlank()) {
            RemoteImage(
                url = fallbackCover,
                title = item.title,
                preferHiRes = false,
                modifier = Modifier.fillMaxSize(),
                corner = 0.dp,
            )
        }
        RemoteImage(
            // 主视觉用横版裁切：竖版海报塞进 16:9 会把人脸裁掉，
            // 和下方竖版缩略图必须是两种构图，否则看起来就是「同一张放大缩小」
            url = item.wideCover,
            title = item.title,
            preferHiRes = false,
            modifier = Modifier.fillMaxSize(),
            corner = 0.dp,
            onLoaded = { loaded -> if (loaded) onCoverReady(item.wideCover) },
        )
        // 左侧暗色渐变：海报可能是浅色（云雾/雪景），纯白字压上去会看不清。
        // 从左向右渐隐，只压文字那一侧，海报右侧仍然完整可见。
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(0.60f)
                .background(
                    // 剧照本身明暗不定（有蓝天雪景、也有暗夜），文字必须靠自己那层遮罩保证可读。
                    // 遮罩只覆盖左侧 60%，右侧仍露出完整剧照；峰值 0.92 足够压住任何亮背景。
                    Brush.horizontalGradient(
                        0f to ModaColors.Bg.copy(alpha = 0.92f),
                        0.45f to ModaColors.Bg.copy(alpha = 0.80f),
                        0.75f to ModaColors.Bg.copy(alpha = 0.38f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 40.dp, end = 40.dp)
                // 文案区收到 52%：给右下角缩略图留出不重叠的空间
                .fillMaxWidth(0.52f),
        ) {
            if (item.badge.isNotBlank()) {
                Badge(item.badge, color = ModaColors.Accent)
                Spacer(Modifier.height(10.dp))
            }
            Text(
                text = item.title,
                // 文字直接压在剧照上，纯色填充遇到亮画面仍然会「糊」。
                // 加一层同色阴影把字从背景里拔出来，这是电视大字号最有效的一招。
                style = TvTitle.copy(
                    fontSize = 38.sp,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.85f),
                        offset = Offset(0f, 3f),
                        blurRadius = 12f,
                    ),
                ),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val desc = item.desc.ifBlank { item.subtitle }
            if (desc.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = desc,
                    style = TvBody.copy(
                        fontSize = 18.sp,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.85f),
                            offset = Offset(0f, 2f),
                            blurRadius = 8f,
                        ),
                    ),
                    color = Color.White.copy(alpha = 0.92f),
                    // 大图区只放「钩子」：两行足够交代看点，完整简介留在详情页
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 单个缩略图：16:9 小图 + 选中高亮。
 * 未选中压一层半透明黑，让「当前是哪一条」在电视上隔着两米也能一眼看出来。
 */
@Composable
private fun HeroThumb(
    item: VideoItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val corner = 8.dp
    Card(
        onClick = onClick,
        modifier = modifier
            .width(HERO_THUMB_WIDTH)
            .height(HERO_THUMB_HEIGHT),
        shape = CardDefaults.shape(shape = RoundedCornerShape(corner)),
        colors = CardDefaults.colors(
            containerColor = ModaColors.Surface,
            contentColor = ModaColors.Text,
            focusedContainerColor = ModaColors.Surface,
            focusedContentColor = ModaColors.Text,
            pressedContainerColor = ModaColors.Surface,
            pressedContentColor = ModaColors.Text,
        ),
        // 聚焦时放大一点：焦点移动时「焦点在哪」比「选中项在哪」更实时
        scale = CardDefaults.scale(scale = 1f, focusedScale = 1.06f, pressedScale = 1f),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = focusBorder(3.dp, corner),
        ),
    ) {
        // 竖版缩略图：填满卡片，不做 aspectRatio（尺寸已由外层 width/height 定死）
        Box(Modifier.fillMaxSize()) {
            RemoteImage(
                url = item.cover,
                title = item.title,
                // 小图上不要高清：eps 是重接口，72dp 宽用 /260 缩略图本来就够清晰
                preferHiRes = false,
                modifier = Modifier.fillMaxSize(),
                corner = 0.dp,
            )
            if (!selected) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            }
            if (selected) {
                // 选中描边画在内容之上，否则会被图盖住看不见
                Box(Modifier.fillMaxSize().border(3.dp, Color.White, RoundedCornerShape(corner)))
            }
        }
    }
}

/** 顶部横幅（单条）：整块可聚焦，按 OK 进详情 */
@Composable
fun HeroBanner(
    item: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val corner = 16.dp
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = CardDefaults.shape(shape = RoundedCornerShape(corner)),
        colors = CardDefaults.colors(
            containerColor = ModaColors.Surface,
            contentColor = ModaColors.Text,
            // 横幅反色成白底会盖住海报、也让白字失效，所以聚焦只靠白描边 + 放大
            focusedContainerColor = ModaColors.Surface,
            focusedContentColor = ModaColors.Text,
            pressedContainerColor = ModaColors.Surface,
            pressedContentColor = ModaColors.Text,
        ),
        scale = CardDefaults.scale(scale = 1f, focusedScale = 1.02f, pressedScale = 1f),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = focusBorder(4.dp, corner),
        ),
    ) {
        HeroStage(item)
    }
}

/**
 * 首页轮播（v.qq.com 首页同构）：上方大图 + 左侧文案，下方一排缩略图。
 *
 * 三种切换方式共用同一个 index：
 *   1. 确认缩略图  → onClick 切到大图
 *   2. 左右键       → 焦点在缩略图行上移动，onFocusChanged 实时把 index 改成聚焦的那条
 *   3. 自动轮播     → 缩略图行无人聚焦满 8 秒后自己往下走
 * 因为都在改同一个 index，大图与文案永远跟着走，不存在「图变了字没变」的错位。
 */
@Composable
fun HeroCarousel(
    items: List<VideoItem>,
    onClick: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
    /** 自动切换间隔；8 秒够看清标题与简介，又不会长到像卡住 */
    intervalMs: Long = 8_000L,
    /**
     * 首焦点落点。让它落在缩略图行而不是大图区：
     * 大图区只是一个「确认进详情」的整块，焦点停在那里时左右键无事可做；
     * 缩略图行既能左右扫、又会实时驱动上方大图，作为入口更顺手。
     */
    thumbFocusRequester: FocusRequester? = null,
) {
    // 列表变了要回到首条，否则越界下标会让大图空白
    var index by remember(items) { mutableStateOf(0) }
    // 焦点是否在缩略图行里：自动轮播的暂停条件
    var thumbsFocused by remember { mutableStateOf(false) }
    // 每次手动操作 +1，用来重置自动轮播的计时
    var userTick by remember { mutableStateOf(0) }
    // 当前已经显示出图的那一条封面：切条时先拿它垫底，避免出现空白帧
    var lastShownCover by remember { mutableStateOf("") }
    // 每张缩略图一个 FocusRequester：自动轮播把 index 往前推时，
    // 焦点要跟着挪到新的那一张，否则会出现「大图换到第 4 条、焦点框还在第 1 条」的错位。
    val thumbRequesters = remember(items) { List(items.size) { FocusRequester() } }
    val thumbListState = rememberLazyListState()
    if (items.isEmpty()) return
    val current = items[index.coerceIn(0, items.lastIndex)]
    val multiple = items.size > 1

    // 自动轮播：key 里带上 index 与 userTick，任何一次手动切换都会让本效果重启，
    // 于是「刚手动翻过就又被自动切走」不会发生——手动操作等于重新计时 8 秒。
    LaunchedEffect(multiple, thumbsFocused, index, userTick) {
        if (!multiple || thumbsFocused) return@LaunchedEffect
        delay(intervalMs)
        val next = (index + 1) % items.size
        index = next
        // 自动翻页时把焦点一起挪过去：
        // 只改 index 只会换大图，缩略图上的白框仍停在旧位置，看起来像「选中项跑偏了」。
        //
        // 顺序很关键：必须先把列表滚到目标项、等它被组合出来，再 requestFocus。
        // 反过来的话目标项可能根本还没上树，requestFocus 抛异常被 runCatching 吞掉，
        // 结果就是「大图换了、焦点框消失了」。
        runCatching { thumbListState.animateScrollToItem(next) }
        // 等一帧让新组合出来的卡片完成布局
        withFrameNanos { }
        val req = thumbRequesters.getOrNull(next)
        val ok = runCatching { req?.requestFocus() }.isSuccess
        android.util.Log.i("HeroAuto", "自动轮播 next=$next req=${req != null} requestOK=$ok")
    }

    // 缩略图叠在大图右下角：横向排在图片内部，所以外层用 Box 而不是 Column。
    // 高度必须显式给定：Box 默认按最大子项撑高，而缩略图行会被 align 到 Box 底部，
    // 高度不定时 align 的参考系就是错的，缩略图会飘到大图中间去。
    // 不要用 height() 卡死外层：缩略图聚焦时会放大并画 3dp 描边，
    // 超出部分会被父布局裁掉——用户看到的就是「光标框只剩一半」。
    // 这里预留一圈 padding 给溢出绘制，内部大图仍按 HERO_STAGE_HEIGHT 定高。
    Box(modifier.fillMaxWidth()) {
        // 大图区：**不可聚焦**。
        // 官网也是这个逻辑：焦点只在下方缩略图之间移动，大图只是显示区。
        // 让它可聚焦会导致左右键在「大图」和「缩略图」两套焦点之间跳，手感很乱。
        Box(
            Modifier
                .fillMaxWidth()
                .height(HERO_STAGE_HEIGHT)
                .clip(RoundedCornerShape(14.dp))
                .background(ModaColors.Surface),
        ) {
            // 切换时既不能出现空白帧，也不能让新旧两块同时挂着一个还没图的 HeroStage。
            // 做法：用上一张成功显示的图做「垫底」，新图加载完再覆盖上去。
            // 这样在缩略图之间来回扫时，大图永远是「上一张→新一张」，不会闪成空卡。
            HeroStage(
                item = current,
                fallbackCover = lastShownCover,
                onCoverReady = { cover -> lastShownCover = cover },
            )
        }

        // 单条时不显示缩略图行：一条没什么可选的
        if (multiple) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    // 留出 18dp：缩略图聚焦会放大 1.06 并画 3dp 描边，
                    // 贴边时描边会被父容器裁掉一半——这就是「光标只剩半边」的成因。
                    .padding(end = 18.dp, bottom = 16.dp)
                    // 宽度 = 内容宽 + 两侧内边距，三项同源，不再各写一个数字。
                    // 这里曾经写成「宽 392dp + padding start 12 / end 6」：
                    // 实际内容只剩 374dp，装不下 5 张卡（392dp），
                    // 配合 wrapContentSize(BottomEnd) 的末端对齐，
                    // 整行被向左顶出 18dp——首张卡的白描边正好被裁掉，
                    // 同时右端又露出半张卡。两个症状同一个算式错误。
                    .width(
                        HERO_THUMB_VISIBLE * HERO_THUMB_WIDTH +
                            (HERO_THUMB_VISIBLE - 1) * HERO_THUMB_GAP +
                            HERO_THUMB_STRIP_PAD * 2,
                    )
                    // 底部压一层右深左浅的渐变：缩略图压在海报上时边界更清楚
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)),
                        ),
                    )
                    // 左右留出 12dp：聚焦放大 1.06（每边约 2dp）+ 3dp 描边要画在框外，
                    // 贴边时必被裁。上下同理，各留 5dp。
                    .padding(
                        horizontal = HERO_THUMB_STRIP_PAD,
                        vertical = 5.dp,
                    ),
            ) {
            LazyRow(
                state = thumbListState,
                horizontalArrangement = Arrangement.spacedBy(HERO_THUMB_GAP),
                // 不要再套 wrapContentSize：它会在内容比容器宽时把整行按末端对齐，
                // 于是首项被顶出左边界。宽度已经由外层算准，这里只要高度。
                modifier = Modifier
                    // 高度要留出放大与描边的余量：+18dp（10dp 内容余量 + 8dp 描边溢出）
                    .height(HERO_THUMB_HEIGHT + 18.dp)
                    .onFocusChanged { thumbsFocused = it.hasFocus },
            ) {
                // 只渲染前 5 条：容器宽度正好是 5 张卡（5×72 + 4×8 = 392dp），
                // 多出的第 6 条会被容器边界从中间切断，看起来像渲染错误。
                // 要看第 6 条往右滚动即可（它会在滚动后成为可见项）。
                items(count = items.size, key = { i -> "hero-thumb-" + items[i].cid }) { i ->
                    val item = items[i]
                    HeroThumb(
                        item = item,
                        selected = i == index,
                        onClick = {
                            // 直接进详情。
                            //
                            // 之前设计成「第一次切换、第二次才进」，思路来自官网，
                            // 但在遥控器上体验很差：焦点落到某条时那张已经是大图了，
                            // 用户按确认的本意就是「看这个」，再按一次才进等于白按一下。
                            // 而且焦点移动会先改 index，导致这个两级判断经常算错。
                            // 现在：确认 = 直接进详情，切换交给左右键。
                            onClick(item)
                        },
                        // 焦点一落上去就切大图：遥控器左右扫过去时上方实时跟随，
                        // 不用再按一次确认键——这是 v.qq.com 官网的手感
                        modifier = Modifier
                            .then(
                                // 每张都挂自己的 requester：自动轮播要靠它把焦点挪过去。
                                // 第 0 条额外接收外部传入的 thumbFocusRequester，用于首焦点定位。
                                buildList {
                                    thumbRequesters.getOrNull(i)?.let { add(Modifier.focusRequester(it)) }
                                    if (i == 0 && thumbFocusRequester != null) {
                                        add(Modifier.focusRequester(thumbFocusRequester))
                                    }
                                }.reduceOrNull { a, b -> a.then(b) } ?: Modifier,
                            )
                            // 不要再叠 clickable：HeroThumb 内部已经是 Card(onClick)，
                            // 外层再套一层可点击修饰符会让两者争抢确认键，实测表现为"只切图不进详情"。
                            // 只保留焦点监听（用于驱动大图跟随），点击语义完全交给 Card。
                            .onFocusChanged { state ->
                                if (state.isFocused && index != i) {
                                    index = i
                                    userTick++
                                }
                            },
                    )
                }
            }
            }  // end Box
        }
    }
}

/**
 * 分集标题常常自带编号（如「第1期：心理战→…」），而卡片上已经有「第 N 集」，
 * 两个编号并排既重复又挤占宽度，这里把前缀剥掉，只留副标题本身。
 */
// 覆盖「第1季 第01集」「第3期：」「第12话」等所有编号形态：
// 可能连续出现（先季再集），所以用 + 循环剥掉，剥到没有编号为止。
private val EpisodePrefix = Regex("(第\\s*\\d+\\s*[季期集话回]\\s*[：:]?\\s*)+")

private fun stripEpisodePrefix(label: String): String =
    label.replace(EpisodePrefix, "").trim().trimStart('：', ':', ' ')

/** 集数 chip：第 N 集 + label，VIP/预告用角标区分 */
@Composable
fun EpisodeChip(
    index: Int,
    label: String,
    isVip: Boolean,
    isTrailer: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 单集缩略图地址；为空时退回纯文字卡（老片或图床缺图都会走这条路） */
    cover: String = "",
    /** 分集描述（综艺的当期看点）；空串表示这一集没有描述，第二行整行不画 */
    summary: String = "",
) {
    val corner = 10.dp
    // 详情页的选集只要「第 N 集」这行字：
    // 缩略图会把集数条撑到 100+dp 高，一屏只能露出一行，找集反而更慢。
    // 缩略图在播放页的选集浮层里已经有了，详情页重复一遍没有额外信息。
    Card(
        onClick = onClick,
        // 190dp：150dp 装不下「第 1 集」之外的第二行描述（只剩约 8 个字，必然截断）
        modifier = modifier.width(190.dp),
        shape = CardDefaults.shape(shape = RoundedCornerShape(corner)),
        colors = CardDefaults.colors(
            containerColor = if (selected) AccentDeep else ModaColors.SurfaceHigh,
            contentColor = Color.White,
            focusedContainerColor = if (selected) AccentDeepHi else ModaColors.Focus,
            focusedContentColor = if (selected) Color.White else ModaColors.Bg,
            pressedContainerColor = AccentDeep,
            pressedContentColor = Color.White,
        ),
        scale = CardDefaults.scale(scale = 1f, focusedScale = 1.08f, pressedScale = 1f),
        border = CardDefaults.border(
            border = if (selected) focusBorder(2.dp, corner, ModaColors.AccentSoft) else Border.None,
            focusedBorder = focusBorder(3.dp, corner),
        ),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                Text(
                    text = "第 ${index + 1} 集",
                    style = TvBody.copy(fontSize = 17.sp),
                    // 未选中聚焦是白底：必须跟内容色，否则烤死的 #F2F3F7 变隐形（1.11:1）
                    color = contentColor(),
                    maxLines = 1,
                )
                if (isVip) {
                    Spacer(Modifier.width(6.dp))
                    Badge("VIP", color = Color(0xFFE0A32E))
                }
                if (isTrailer) {
                    Spacer(Modifier.width(6.dp))
                    Badge("预告", color = Color(0xFF5C6BC0))
                }
            }
            // 分集描述只在真的有内容时出现：综艺的 fields.title 是当期看点，
            // 电视剧只有「剧名_01」，剥完为空，此时整行不画、也不留空白占高度。
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = TvCaption.copy(fontSize = 15.sp),
                    color = contentColor().copy(alpha = 0.72f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            } else {
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/**
 * 清晰度 chip。
 * 不可用档位在 Parser.tiers 与 Repo.episodeInfo 就已经被剔除，走不到这里，
 * 因此不再需要灰色禁用态，只有选中/未选中两种外观。
 */
@Composable
fun TierChip(
    tier: Tier,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val corner = 10.dp
    Card(
        onClick = onClick,
        modifier = modifier.width(120.dp),
        shape = CardDefaults.shape(shape = RoundedCornerShape(corner)),
        colors = CardDefaults.colors(
            containerColor = if (selected) AccentDeep else ModaColors.SurfaceHigh,
            contentColor = Color.White,
            focusedContainerColor = ModaColors.Focus,
            focusedContentColor = ModaColors.Bg,
            pressedContainerColor = AccentDeepHi,
            pressedContentColor = Color.White,
        ),
        scale = CardDefaults.scale(scale = 1f, focusedScale = 1.06f, pressedScale = 1f),
        border = CardDefaults.border(
            border = if (selected) focusBorder(2.dp, corner, ModaColors.AccentSoft) else Border.None,
            focusedBorder = focusBorder(3.dp, corner),
        ),
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(
                text = tier.displayName,
                style = TvBody.copy(fontSize = 17.sp),
                color = contentColor(),
                maxLines = 1,
            )
        }
    }
}

/** 搜索结果的图卡（横版，比首页竖版卡更省纵向空间） */
@Composable
fun SearchResultCard(
    item: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    preferHiRes: Boolean = false,
) {
    val corner = 12.dp
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = CardDefaults.shape(shape = RoundedCornerShape(corner)),
        colors = CardDefaults.colors(
            containerColor = ModaColors.Surface,
            contentColor = ModaColors.Text,
            focusedContainerColor = ModaColors.SurfaceHigh,
            focusedContentColor = ModaColors.Text,
            pressedContainerColor = AccentDeepHi,
            pressedContentColor = Color.White,
        ),
        scale = CardDefaults.scale(scale = 1f, focusedScale = 1.06f, pressedScale = 1f),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = focusBorder(3.dp, corner),
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(
                url = item.cover,
                title = item.title,
                cid = item.cid,
                preferHiRes = preferHiRes,
                modifier = Modifier
                    .width(150.dp)
                    .aspectRatio(3f / 4f),
                corner = 0.dp,
            )
            Column(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = item.title,
                    style = TvHeading.copy(fontSize = 22.sp),
                    color = contentColor(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = item.subtitle.ifBlank { item.desc }
                if (sub.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = sub,
                        style = TvCaption.copy(fontSize = 15.sp),
                        color = secondaryContentColor(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}