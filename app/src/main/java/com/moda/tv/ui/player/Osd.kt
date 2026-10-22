package com.moda.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moda.tv.data.local.PlayerSettings
import com.moda.tv.model.Episode
import com.moda.tv.model.Tier
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption
import com.moda.tv.ui.theme.TvHeading
import kotlin.math.roundToLong

/** OSD 底部控制区占用的高度；弹幕层要按它让位，避免弹幕压在进度条上 */
val OsdBottomInset = 148.dp

/**
 * OSD 里可以用遥控器上下切换的一级入口。
 * 倍速与弹幕各自是独立入口，不再共用一个「设置」壳。
 * 片头/片尾是「一键记录」型入口：不展开面板，短按记录当前位置、长按清除记录。
 */
enum class OsdSlot {
    Quality,
    Episode,
    Speed,
    Danmaku,
    Aspect,
    Intro,
    Outro;

    /**
     * 展开这个槽位时，左右键归谁。
     *
     * 业内 TV 播放器按「模态层」分派按键：控制条这一层左右键仍然是快进快退；
     * 一旦某个浮窗展开，它就成为最上层，左右键交给它自己的导航轴。
     * 所以这里不是「所有浮窗一律静默」——那会让横向档位条按左右毫无反应，
     * 用户看到一排按钮却推不动，只能靠上下键反着操作。
     */
    val horizontalPanel: Boolean
        get() = when (this) {
            // 横向档位条：左右键与画面方向一致，就是换值
            Speed, Aspect -> true
            // 横滑选集条：左右键换集号，确认键才真正切集
            Episode -> true
            // 纵向列表：导航轴是上下，左右键在这层没有语义
            Quality, Danmaku -> false
            // 一键定位型，不展开浮窗
            Intro, Outro -> false
        }
}

/**
 * 上下键的循环顺序就是这个列表的顺序。
 * 播放/暂停归确认键，快进快退归左右键，退出归返回键——它们都不占槽位。
 * 展开某个槽位的浮窗后，左右键按该浮窗的导航轴分派，见 [OsdSlot.horizontalPanel]。
 */
val OsdSlots: List<OsdSlot> = listOf(
    OsdSlot.Quality,
    OsdSlot.Episode,
    OsdSlot.Speed,
    OsdSlot.Danmaku,
    OsdSlot.Aspect,
    OsdSlot.Intro,
    OsdSlot.Outro,
)

/**
 * OSD 面板内选项的三态配色（全站唯一来源）。
 *
 * 之前每个面板各写一套 when，结果「选中蓝底 + 聚焦白框 + 白字」三层叠加，
 * 蓝底白字再被白框一挤就糊成一团，根本分不清哪个是选中、哪个是焦点。
 * 现在两条互斥通道：
 *   焦点 → 纯白底 + 深字（"现在能按"）
 *   选中 → 深蓝底 + 白字，且不画白框（避免和焦点抢）
 * 同时成立时焦点优先——用户操作时最需要看到焦点在哪。
 */
data class ChipStyle(val container: Color, val content: Color, val borderWidth: Dp, val border: Color)

fun chipStyle(focused: Boolean, selected: Boolean): ChipStyle = when {
    focused -> ChipStyle(Color(0xFFFFFFFF), Color(0xFF0B0B0F), 0.dp, Color.Transparent)
    selected -> ChipStyle(Color(0xFF2A5FD6), Color(0xFFFFFFFF), 0.dp, Color.Transparent)
    else -> ChipStyle(Color(0x33000000), Color(0xFFF2F3F7), 1.dp, Color(0x26FFFFFF))
}

/** 没有集数表时选集入口无处可去，导航与渲染都跳过它 */
fun visibleOsdSlots(hasEpisodes: Boolean): List<OsdSlot> =
    if (hasEpisodes) OsdSlots else OsdSlots.filter { it != OsdSlot.Episode }

/**
 * 可选列表项的配色原则（所有 OSD 面板共用）：
 * 1. 聚焦项 = 底色变化 + 白色描边 + 状态条，**文字始终保持近白**；
 * 2. 未聚焦项 = 透明或深底，文字仍是可读的白，不用低对比灰；
 * 3. 全列表禁止「白底+深字」与「黑底+白字」来回跳变——电视观看距离远，
 *    整块反色会让按钮在黑白之间闪，观感刺眼且不易判断焦点移动。
 */
private val ItemTextColor = Color(0xFFF2F3F7)
private val ItemFocusedBg = Color(0xFF2A5FD6)
private val ItemIdleBg = Color(0x4D000000)
private val ItemCurrentAccent = Color(0xFF2A5FD6)

/** 毫秒转 时:分:秒 / 分:秒 */
fun formatClock(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000f).roundToLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * 自绘播放器 OSD。
 * 焦点不用系统焦点树，而是由 PlayerScreen 维护一个 slot 索引：PlayerView 自己会抢方向键，
 * 两套焦点并存时遥控器行为会飘，统一索引才能保证每个键的语义固定。
 */
@Composable
fun PlayerOsd(
    title: String,
    episodeLabel: String,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    tiers: List<Tier>,
    selectedTier: Tier?,
    focusedSlot: OsdSlot,
    tierPanelVisible: Boolean,
    focusedTierIndex: Int,
    modifier: Modifier = Modifier,
    settings: PlayerSettings = PlayerSettings(),
    episodes: List<Episode> = emptyList(),
    currentEpisodeIndex: Int = 1,
    focusedEpisodeIndex: Int = 0,
    episodePanelVisible: Boolean = false,
    speedPanelVisible: Boolean = false,
    focusedSpeedIndex: Int = 0,
    danmakuPanelVisible: Boolean = false,
    focusedDanmakuItem: Int = 0,
    aspectPanelVisible: Boolean = false,
    focusedAspectIndex: Int = 0,
    clockText: String = "",
    // 片头/片尾记录：按剧存、不分集，0 表示还没记录
    introMs: Long = 0L,
    outroMs: Long = 0L,
) {
    val slots = visibleOsdSlots(episodes.isNotEmpty())
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    // 底部必须更早开始压暗：工具栏与进度条就在屏幕下缘往上约 1/4 处，
                    // 原来的渐变到 0.6 处还是全透明，视频字幕会直接压在按钮文字上，两者都看不清。
                    // 现在 0.55 就开始收黑，0.78 处已达 0.82 不透明度。
                    Brush.verticalGradient(
                        0f to Color(0xB3000000),
                        0.30f to Color(0x00000000),
                        0.55f to Color(0x99000000),
                        0.78f to Color(0xD1000000),
                        1f to Color(0xF2000000),
                    )
                )
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = TvHeading)
                if (episodeLabel.isNotBlank()) {
                    Spacer(Modifier.width(16.dp))
                    Text(episodeLabel, style = TvCaption)
                }
                Spacer(Modifier.weight(1f))
                // 用近白而不是蓝色：#3D7BFF 压深底只有约 2.7:1，3 米外看不清
                if (settings.speed != 1.0f) {
                    Text(settings.speedLabel, style = TvBody, color = ItemTextColor)
                    Spacer(Modifier.width(20.dp))
                }
                // 全屏看片时最常问的就是「几点了」，所以挂在顶部最右
                Text(clockText, style = TvBody, color = ItemTextColor)
            }
            Column {
                // 各面板互斥展开；都摆进同一个底部列里，天然不会盖住进度条与工具栏
                if (episodePanelVisible && episodes.isNotEmpty()) {
                    EpisodePanel(episodes, currentEpisodeIndex, focusedEpisodeIndex)
                    Spacer(Modifier.height(12.dp))
                }
                if (speedPanelVisible) {
                    SpeedPanel(settings.speed, focusedSpeedIndex)
                    Spacer(Modifier.height(12.dp))
                }
                if (danmakuPanelVisible) {
                    DanmakuPanel(settings, focusedDanmakuItem)
                    Spacer(Modifier.height(12.dp))
                }
                if (aspectPanelVisible) {
                    AspectPanel(settings.aspectMode, focusedAspectIndex)
                    Spacer(Modifier.height(12.dp))
                }
                if (tierPanelVisible) {
                    TierPanel(tiers, selectedTier, focusedTierIndex)
                    Spacer(Modifier.height(12.dp))
                }
                ProgressBar(positionMs, bufferedMs, durationMs, introMs, outroMs)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatClock(positionMs), style = TvCaption, color = ModaColors.Text)
                    Text(" / " + formatClock(durationMs), style = TvCaption)
                    Spacer(Modifier.width(14.dp))
                    slots.forEach { slot ->
                        OsdButton(
                            label = slotLabel(slot, selectedTier, settings, introMs, outroMs),
                            focused = focusedSlot == slot,
                            // 片头/片尾不做压暗：整个工具栏只有弹幕有「关」这个状态，
                            // 给它们单独压灰会显得两块按钮是同级的异类，与其它按钮不一致
                            muted = slot == OsdSlot.Danmaku && !settings.danmakuEnabled,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }
        }
    }
}

/** 标签一律精简到最短：清晰度只留档位值，其余两字或纯数值 */
private fun slotLabel(
    slot: OsdSlot,
    tier: Tier?,
    settings: PlayerSettings,
    introMs: Long,
    outroMs: Long,
): String = when (slot) {
    // 清晰度只留档位值，省掉「清晰度」三个字
    OsdSlot.Quality -> tier?.displayName ?: "自动"
    OsdSlot.Episode -> "选集"
    OsdSlot.Speed -> settings.speedLabel
    OsdSlot.Danmaku -> "弹幕"
    OsdSlot.Aspect -> "比例"
    // 有记录就只显示时间，不再重复「片头/片尾」四个字；没记录时才用文字占位
    OsdSlot.Intro -> if (introMs > 0L) formatClock(introMs) else "片头"
    OsdSlot.Outro -> if (outroMs > 0L) formatClock(outroMs) else "片尾"
}

/** 工具栏按钮：聚焦只换底色与描边，文字始终白色 */
@Composable
private fun OsdButton(label: String, focused: Boolean, muted: Boolean = false) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (focused) ItemFocusedBg else ItemIdleBg)
            .border(
                if (focused) 3.dp else 1.dp,
                if (focused) Color(0xFFFFFFFF) else Color(0x33FFFFFF),
                shape,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            label,
            style = TvBody,
            // 弹幕关闭时压暗，只靠亮度区分开关态，不额外占字
            color = if (muted && !focused) Color(0xFF8A8F9C) else ItemTextColor,
        )
    }
}

@Composable
private fun TierPanel(tiers: List<Tier>, selected: Tier?, focusedIndex: Int) {
    if (tiers.isEmpty()) {
        Text("暂无可用清晰度", style = TvCaption)
        return
    }
    val shape = RoundedCornerShape(14.dp)
    // width(IntrinsicSize.Max) 让面板宽度由内容决定，不再被 fillMaxWidth 撑满整屏
    Column(
        Modifier
            .width(IntrinsicSize.Max)
            .clip(shape)
            .background(Color(0xE6111218))
            .border(1.dp, Color(0x33FFFFFF), shape)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Text("清晰度", style = TvCaption)
        Spacer(Modifier.height(8.dp))
        tiers.forEachIndexed { index, tier ->
            val focused = index == focusedIndex
            val isCurrent = selected != null && selected.fno == tier.fno && selected.defn == tier.defn
            Row(
                Modifier
                    .width(IntrinsicSize.Max)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (focused) ItemFocusedBg else Color(0x00000000))
                    .border(
                        if (focused) 2.dp else 0.dp,
                        if (focused) Color(0xFFFFFFFF) else Color(0x00000000),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tier.displayName,
                    style = TvBody,
                    color = ItemTextColor,
                )
                if (isCurrent) {
                    Spacer(Modifier.width(10.dp))
                    Text("当前", style = TvCaption, color = ItemTextColor.copy(alpha = 0.75f))
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun ProgressBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    introMs: Long = 0L,
    outroMs: Long = 0L,
) {
    val total = if (durationMs > 0) durationMs.toFloat() else 1f
    val played = (positionMs / total).coerceIn(0f, 1f)
    val buffered = (bufferedMs / total).coerceIn(0f, 1f)
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(shape)
            .background(Color(0x59FFFFFF))
    ) {
        Box(Modifier.fillMaxWidth(buffered).fillMaxHeight().background(Color(0x40FFFFFF)))
        Box(Modifier.fillMaxWidth(played).fillMaxHeight().background(ModaColors.Accent))
        // 片头/片尾位置在进度条上打一根白线：除了按钮文字，用户还能看出「跳到哪里去」
        listOf(introMs, outroMs).forEach { mark ->
            if (mark > 0L && durationMs > 0L && mark < durationMs) {
                val fraction = (mark / total).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    // 2dp 白线压在 6dp 进度条上，比 3dp 更不像「已播进度」
                    Box(Modifier.width(2.dp).fillMaxHeight().background(Color(0xE6FFFFFF)))
                }
            }
        }
    }
}

/** 加载失败面板：文案 + 重试（重试用遥控器确认键触发，由 PlayerScreen 统一分发） */
@Composable
fun PlayerErrorPane(message: String, retrying: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("播放失败", style = TvHeading, color = ModaColors.Danger)
            Spacer(Modifier.height(12.dp))
            Text(message, style = TvCaption)
            Spacer(Modifier.height(28.dp))
            // 与 OSD 其它聚焦元素同一套：深蓝底 + 白字 + 白描边，不做整块反色
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .clip(shape)
                    .background(ItemFocusedBg)
                    .border(3.dp, Color(0xFFFFFFFF), shape)
                    .padding(horizontal = 34.dp, vertical = 12.dp)
            ) {
                Text(if (retrying) "重试中…" else "重 试", style = TvBody, color = ItemTextColor)
            }
            Spacer(Modifier.height(14.dp))
            Text("按确认键重试，按返回键退出", style = TvCaption)
        }
    }
}
