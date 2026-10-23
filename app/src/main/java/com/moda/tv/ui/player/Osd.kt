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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moda.tv.R
import com.moda.tv.data.local.DecoderMode
import com.moda.tv.data.local.PlayerCore
import com.moda.tv.data.local.PlayerSettings
import com.moda.tv.model.Episode
import com.moda.tv.model.Tier
import com.moda.tv.model.TierName
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
    Core,
    Decoder,
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
            Speed, Aspect, Core, Decoder -> true
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
    // 内核与解码紧挨着：两者都是「换实例才生效」的开关，排查黑屏时总是一起看
    OsdSlot.Core,
    OsdSlot.Decoder,
    OsdSlot.Intro,
    OsdSlot.Outro,
)

/**
 * 可选项底色（全站唯一来源）。焦点与选中是**两条不同的通道**，不许互相顶替（R5）：
 *
 *   焦点 → 3dp 白描边 + 底色提亮（"遥控器现在停在哪"）
 *   选中 → #2A5FD6 蓝 + 勾标（"当前生效的是哪个"）
 *
 * 两者同时落在同一格上时都要能看见：白框画在蓝底上，勾标留在蓝底里。
 *
 * 为什么不再是「焦点 = 纯白底反白 + 深字」：反白是"把这一格涂掉"，
 * 于是焦点格附近永远没有第二层可读的信息，而倍速/比例/内核这类
 * 「左右键选中即生效」的档位条里，焦点与生效值天然就是同一格——
 * 用户从头到尾看不到"生效"这一层，等于选中态不存在（详见 ChipLabel 的注释）。
 * 顺带也修掉了整排按钮在黑白之间来回闪的观感（3 米外看非常刺眼）。
 *
 * 文字一律近白：深字配浅底在电视上会变成"黑块"，与旁边的深底按钮来回跳。
 */
private val ItemTextColor = Color(0xFFF2F3F7)
/** 焦点底色：只提亮，不用蓝——蓝在本仓专指「选中/生效」 */
private val ItemFocusedBg = Color(0xFF3B4459)
private val ItemIdleBg = Color(0x33000000)
/** 选中/生效：全站唯一选中色 */
private val ItemSelectedBg = Color(0xFF2A5FD6)

data class ChipStyle(val container: Color, val content: Color, val borderWidth: Dp, val border: Color)

fun chipStyle(focused: Boolean, selected: Boolean): ChipStyle = when {
    // 焦点态：3dp 白描边是唯一焦点语言；底色在"生效蓝"与"提亮灰蓝"之间二选一，
    // 于是同一格上能同时读出"这是焦点"与"这是生效值"
    focused -> ChipStyle(
        container = if (selected) ItemSelectedBg else ItemFocusedBg,
        content = ItemTextColor,
        borderWidth = 3.dp,
        border = Color(0xFFFFFFFF),
    )
    selected -> ChipStyle(ItemSelectedBg, ItemTextColor, 1.dp, Color(0x66FFFFFF))
    else -> ChipStyle(ItemIdleBg, ItemTextColor, 1.dp, Color(0x26FFFFFF))
}

/**
 * 档位条上的一格：文字 + 生效勾标。
 *
 * 勾标是"生效值"的第二条通道，专门解决「焦点和生效永远同一格」的读法问题：
 * 倍速/比例的左右键是选中即生效，白框与勾会同时出现在那一格上——
 * 白框说"停在这"，勾说"已经在用这个"。两个信息缺一个，用户就会以为
 * 移动焦点只是预览、或者以为改值没生效。
 */
@Composable
internal fun ChipLabel(label: String, cs: ChipStyle, current: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = TvBody, color = cs.content)
        if (current) {
            Spacer(Modifier.width(8.dp))
            Text("✓", style = TvCaption, color = cs.content.copy(alpha = 0.85f))
        }
    }
}

/** 没有集数表时选集入口无处可去，导航与渲染都跳过它 */
fun visibleOsdSlots(hasEpisodes: Boolean): List<OsdSlot> =
    if (hasEpisodes) OsdSlots else OsdSlots.filter { it != OsdSlot.Episode }


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
    // 不可用档被按下确认时的可见回执；null 表示没有要说的
    tierHint: String? = null,
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
    corePanelVisible: Boolean = false,
    focusedCoreIndex: Int = 0,
    decoderPanelVisible: Boolean = false,
    focusedDecoderIndex: Int = 0,
    // 实际生效的内核：IJK 缺 so 时工厂会回退 Exo，工具栏要显示「真正在跑的那个」，
    // 否则用户看到 IJK 却在用 ExoPlayer 解码，排查黑屏时会被这条假信息带偏
    effectiveCore: PlayerCore = PlayerCore.Exo,
    // 本剧实际生效的解码方式：播放页里的覆盖优先于设置页的默认值
    effectiveDecoder: DecoderMode = DecoderMode.Hardware,
    ijkUsable: Boolean = true,
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
                if (corePanelVisible) {
                    // 高亮「实际在跑的那个」而不是设置里的偏好：IJK 缺 so 时工厂已回退 Exo，
                    // 面板再把 IJK 标成当前，用户排查解码问题时会以为内核换了，其实没换
                    CorePanel(effectiveCore, focusedCoreIndex, ijkUsable)
                    Spacer(Modifier.height(12.dp))
                }
                if (decoderPanelVisible) {
                    DecoderPanel(effectiveDecoder, focusedDecoderIndex)
                    Spacer(Modifier.height(12.dp))
                }
                if (tierPanelVisible) {
                    TierPanel(tiers, selectedTier, focusedTierIndex, tierHint)
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
                            label = slotLabel(
                                slot,
                                selectedTier,
                                settings,
                                effectiveCore,
                                effectiveDecoder,
                                introMs,
                                outroMs,
                            ),
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

/**
 * 档位显示名的唯一解析点：资源走 stringResource，上游原文原样返回。
 * 只放在 UI 侧——数据层（[TierName]）只带资源 id，不带任何语言的句子。
 */
@Composable
fun tierNameText(name: TierName): String = when (name) {
    is TierName.Upstream -> name.text
    is TierName.Local -> stringResource(name.resId)
}

/** 标签一律精简到最短：清晰度只留档位值，其余两字或纯数值 */
@Composable
private fun slotLabel(
    slot: OsdSlot,
    tier: Tier?,
    settings: PlayerSettings,
    effectiveCore: PlayerCore,
    effectiveDecoder: DecoderMode,
    introMs: Long,
    outroMs: Long,
): String = when (slot) {
    // 清晰度只留档位值，省掉「清晰度」三个字
    OsdSlot.Quality -> tier?.let { tierNameText(it.display) } ?: stringResource(R.string.player_auto)
    OsdSlot.Episode -> stringResource(R.string.player_episodes)
    OsdSlot.Speed -> settings.speedLabel
    OsdSlot.Danmaku -> stringResource(R.string.player_danmaku)
    OsdSlot.Aspect -> stringResource(R.string.player_aspect)
    // 内核用短名（ExoPlayer 九个字符会把工具栏撑开），解码直接用枚举 label
    OsdSlot.Core -> if (effectiveCore == PlayerCore.Ijk) "IJK" else "Exo"
    OsdSlot.Decoder -> stringResource(effectiveDecoder.labelRes)
    // 有记录就只显示时间，不再重复「片头/片尾」四个字；没记录时才用文字占位
    OsdSlot.Intro -> if (introMs > 0L) formatClock(introMs) else stringResource(R.string.player_intro)
    OsdSlot.Outro -> if (outroMs > 0L) formatClock(outroMs) else stringResource(R.string.player_outro)
}

/**
 * 工具栏按钮：焦点 = 3dp 白描边 + 底色提亮，文字始终近白。
 *
 * 这里曾经用 #2A5FD6 当焦点底色，而同一个蓝在下面的档位条里表示"当前生效"，
 * 一个颜色两层相反的语义——用户在工具栏上看到蓝，会以为这一项已经被选中/生效了。
 * 现在蓝只留给"选中/生效"，焦点统一由白描边表达（R5）。
 */
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

/**
 * 清晰度档位表。
 *
 * 不可用档必须自带标识：PlayerScreen.applyTier 对不可用档是直接 return
 * （切过去只会黑屏），面板上若不给压暗与「不可用」字样，用户停在它上面按确认
 * 毫无反应，只会以为遥控器坏了——「按了没反应」是电视上最难诊断的故障。
 * [hint] 是那一下确认键的可见回执，由 PlayerScreen 在拒绝切换时写入。
 */
@Composable
private fun TierPanel(
    tiers: List<Tier>,
    selected: Tier?,
    focusedIndex: Int,
    hint: String? = null,
) {
    if (tiers.isEmpty()) {
        Text(stringResource(R.string.player_no_quality), style = TvCaption)
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
        Text(stringResource(R.string.player_quality), style = TvCaption)
        Spacer(Modifier.height(8.dp))
        tiers.forEachIndexed { index, tier ->
            val focused = index == focusedIndex
            val isCurrent = selected != null && selected.fno == tier.fno && selected.defn == tier.defn
            // 与倍速/比例/内核/解码面板共用同一套三态配色（chipStyle）：
            // 焦点=3dp 白描边+提亮底、选中=蓝底+勾标、其余半透明。面板之间各写一套是
            // "清晰度里蓝底算焦点、倍速里蓝底算选中"的根源，用户会读反。
            val cs = chipStyle(focused = focused, selected = isCurrent)
            // 不可用档整行压暗，但白描边照画：焦点永远可见，压暗的只是"这一档能不能用"
            val content = if (tier.available) cs.content else cs.content.copy(alpha = 0.45f)
            val rowShape = RoundedCornerShape(8.dp)
            Row(
                Modifier
                    .width(IntrinsicSize.Max)
                    .clip(rowShape)
                    .background(cs.container)
                    .border(cs.borderWidth, cs.border, rowShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tierNameText(tier.display),
                    style = TvBody,
                    color = content,
                )
                if (!tier.available) {
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.player_unavailable), style = TvCaption, color = content)
                }
                if (isCurrent) {
                    Spacer(Modifier.width(10.dp))
                    Text("✓", style = TvCaption, color = content.copy(alpha = 0.85f))
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        if (hint != null) {
            Spacer(Modifier.height(2.dp))
            Text(hint, style = TvCaption, color = ModaColors.Danger)
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
            Text(stringResource(R.string.player_play_failed), style = TvHeading, color = ModaColors.Danger)
            Spacer(Modifier.height(12.dp))
            Text(message, style = TvCaption)
            Spacer(Modifier.height(28.dp))
            // 与 OSD 其它聚焦元素同一套：底色提亮 + 白字 + 3dp 白描边（不做整块反白）
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .clip(shape)
                    .background(ItemFocusedBg)
                    .border(3.dp, Color(0xFFFFFFFF), shape)
                    .padding(horizontal = 34.dp, vertical = 12.dp)
            ) {
                Text(
                    if (retrying) stringResource(R.string.player_retrying) else stringResource(R.string.player_retry),
                    style = TvBody,
                    color = ItemTextColor,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.player_error_hint), style = TvCaption)
        }
    }
}
