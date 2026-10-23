package com.moda.tv.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.moda.tv.data.local.AspectMode
import com.moda.tv.data.local.AspectModes
import com.moda.tv.data.local.DecoderMode
import com.moda.tv.data.local.IntroOutro
import com.moda.tv.data.local.IntroOutroRules
import com.moda.tv.data.local.IntroOutroStoreImpl
import com.moda.tv.data.local.PlaybackOverride
import com.moda.tv.data.local.PlaybackOverrideStoreImpl
import com.moda.tv.data.local.PlayerCore
import com.moda.tv.data.local.PlayerSettings
import com.moda.tv.data.local.PlayerSettingsStoreImpl
import com.moda.tv.data.local.PlayerSpeedOptions
import com.moda.tv.model.Danmaku
import com.moda.tv.model.Episode
import com.moda.tv.model.Tier
import com.moda.tv.ui.player.DanmakuItems
import com.moda.tv.ui.player.DanmakuOverlay
import com.moda.tv.ui.player.OsdSlot
import com.moda.tv.ui.player.PlayerEngine
import com.moda.tv.ui.player.PlayerEngines
import com.moda.tv.ui.player.PlayerErrorPane
import com.moda.tv.ui.player.PlayerOsd
import com.moda.tv.ui.player.adjustDanmaku
import com.moda.tv.ui.player.visibleOsdSlots
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val OsdAutoHideMs = 5000L

/**
 * 快进/快退反馈。
 * 只在屏幕中央闪一个箭头和累计跳转量，不弹整块 OSD——
 * 用户按左右键的意图是「跳一段」，不是「打开菜单」。
 */
@Composable
private fun SeekHint(accumMs: Long, modifier: Modifier = Modifier) {
    val forward = accumMs >= 0
    val seconds = (kotlin.math.abs(accumMs) / 1000).toInt()
    Box(modifier, contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 方向箭头：连续按会累计，一眼看出往哪边跳、跳了多少
            Text(
                text = if (forward) "▶▶" else "◀◀",
                style = TvBody.copy(fontSize = 40.sp),
                color = Color.White.copy(alpha = 0.92f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = seconds.toString() + " 秒",
                style = TvBody.copy(fontSize = 30.sp),
                color = Color.White.copy(alpha = 0.92f),
            )
        }
    }
}

/**
 * 播放缓冲指示器。
 *
 * 形态按业内播放器的通用做法：半透明深色圆底 + 白色细环 + 90° 弧匀速转圈（900ms 一圈）。
 * 不用三点跳动：点阵静止下来和卡死长得一样，而转动的弧在余光里也能读出「还在转」。
 * 深色圆底是给亮画面兜底——纯白细环压在白墙镜头上会直接消失。
 * 文案只留在起播屏（PlayerUi.Loading），缓冲时再叠一行字属于重复信息。
 */
@Composable
private fun BufferingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "buffering")
    // 匀速：加减速的缓动会让环看起来一顿一顿；900ms 一圈是播放器里的常见节奏
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(64.dp)
                .background(Color.Black.copy(alpha = 0.28f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(40.dp)) {
                val strokePx = 3.dp.toPx()
                val inset = strokePx / 2f
                val ring = Size(size.width - strokePx, size.height - strokePx)
                drawCircle(
                    color = Color.White.copy(alpha = 0.20f),
                    radius = ring.width / 2f,
                    center = center,
                    style = Stroke(strokePx),
                )
                drawArc(
                    color = Color.White.copy(alpha = 0.92f),
                    startAngle = angle,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = ring,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }
    }
}
/** 缓冲持续多久才值得弹动画：短于此的抖动用户根本来不及看见，弹了反而更吵 */
private const val BufferingShowDelayMs = 400L

private const val SeekStepMs = 10_000L
private const val TickMs = 300L
private const val ProgressReportMs = 1000L
/** 长按多久算「要清除」，短于它按短按处理 */
private const val MarkLongPressMs = 800L

/**
 * 「这一集以前播过」的判定阈值。
 *
 * 需求原话是「跳片头的逻辑是从来没有播放过的视频才进行跳」，而播放页能拿到的唯一历史信号
 * 是 [PlayerScreen] 的 startPositionMs —— 它正是 TvViewModel.resumePosition() 的返回值：
 * 只有播放记录里存在 **同一个 episodeIndex** 的那一集时才给出 positionMs，否则恒为 0。
 * 所以 startPositionMs > 0 与「历史里有过这一集」严格等价，不必再读一次 DataStore。
 *
 * 再加一道 5 秒下限，是为了把「点进去立刻退出」从「看过」里摘出去：
 * 那种记录只说明用户点过，不代表他看过片头。
 */
private const val HasPlayedThresholdMs = 5_000L

/**
 * 拿到 ExoEngine 内部的 ExoPlayer 实例。
 *
 * 这是一处**被迫的反射**，不是随手写的取巧：PlayerEngine 接口没有暴露「播放器实例」
 * 或「渲染视图」，而 ExoEngine 的 attachSurface() 对 Exo 是空体 ——
 * 画面只有宿主把 PlayerView 绑到一个 ExoPlayer 上才会出。
 * 在「不许改 PlayerEngine.kt」的约束下，这是让默认内核（Exo）还能出画的唯一接法。
 *
 * 之所以安全：
 *  · release 构建 isMinifyEnabled = false，字段名 player 不会被裁剪；
 *  · 取不到就返回 null（PlayerView.player = null 只是黑屏，不会崩），并在日志里留一行。
 * 后续 PlayerEngine 若补上 videoView()/player 访问器，删掉这个函数改一行即可。
 */
private fun exoPlayerOf(engine: PlayerEngine): Player? = try {
    val field = engine.javaClass.getDeclaredField("player")
    field.isAccessible = true
    field.get(engine) as? Player
} catch (t: Throwable) {
    android.util.Log.w("PlayerScreen", "取 ExoPlayer 实例失败（渲染视图接口缺口）：" + t.message)
    null
}

/**
 * 内核枚举 → PlayerEngine 那一侧的字符串。
 *
 * PlayerEngine 刻意用裸 String 表达内核、不引用 data.local 的 PlayerCore，
 * 理由写在 PlayerEngine.kt 的注释里（两侧独立演进）。映射因此留在这层，
 * 且只出现一次；别处再拼就是第二份真相。
 */
private fun coreKeyOf(core: PlayerCore): String = when (core) {
    PlayerCore.Ijk -> "ijk"
    PlayerCore.Exo -> "exo"
}

/**
 * 视频渲染面。
 *
 * 两个内核的出画方式完全不同，没法共用一个 View：
 *  · Exo：画面挂在 PlayerView 上，PlayerView 必须绑定到具体的 ExoPlayer 实例；
 *  · IJK：画面直接渲染到 SurfaceHolder，用裸 SurfaceView + attachSurface(holder)。
 * 所以这里按内核分两支，而不是硬塞一个折中方案。
 *
 * [core] 传的必须是**实际生效**的内核（IJK 不可用时工厂会回退 Exo），
 * 否则会出现「以为在用 IJK 的 SurfaceView，其实引擎是 Exo」这种必然黑屏。
 */
@Composable
private fun VideoSurface(
    engine: PlayerEngine,
    core: PlayerCore,
    resizeMode: Int,
    modifier: Modifier = Modifier,
) {
    when (core) {
        // IJK：SurfaceHolder 一创建就把句柄交给引擎。
        // surfaceDestroyed 里不主动解绑，是因为接口签名是 Any? —— 传 null 会被实现类
        // 当成「类型不对」直接短路；真正的解绑发生在 release() 内部的 setDisplay(null)。
        PlayerCore.Ijk -> AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            engine.attachSurface(h)
                        }

                        override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) {
                            // 尺寸变化后部分盒子会换一个 Surface，重新交一次句柄才不出黑屏
                            engine.attachSurface(h)
                        }

                        override fun surfaceDestroyed(h: SurfaceHolder) = Unit
                    })
                }
            },
            modifier = modifier,
        )

        // Exo：PlayerView 自己挂 Surface，宿主只需要给它一个 Player
        PlayerCore.Exo -> AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    // 控制器关闭后 PlayerView 仍会抢方向键，必须明确退出焦点竞争
                    isFocusable = false
                    isFocusableInTouchMode = false
                    keepScreenOn = true
                }
            },
            // resizeMode 也要在 update 里设：重建引擎、切档位都会走到这里
            update = {
                it.player = exoPlayerOf(engine)
                it.resizeMode = resizeMode
            },
            onRelease = { it.player = null },
            modifier = modifier,
        )
    }
}

/**
 * TV 播放页。
 * 遥控器事件统一由外层 Box 处理：渲染视图自己会吃掉方向键与确认键，
 * 所以它被设成不可聚焦、控制器关闭，按键语义全部在这里固定下来。
 */
@Composable
fun PlayerScreen(
    url: String,
    title: String,
    // 片头/片尾按剧记录、不分集，cid 是这部剧的唯一键
    cid: String,
    episodeLabel: String,
    tiers: List<Tier>,
    selectedTier: Tier?,
    danmaku: List<Danmaku>,
    startPositionMs: Long,
    onTierChange: (Tier) -> Unit,
    onProgress: (Long, Long) -> Unit,
    onEnded: () -> Unit,
    onBack: () -> Unit,
    // 可选兜底流：默认不启用，仅当 MP4 直链起播失败时切过去重播一次
    hlsUrl: String? = null,
    // 选集浮层用的集数表；为空时选集槽位自动隐藏
    episodes: List<Episode> = emptyList(),
    // 当前正在播的集号（Episode.index），用于浮层高亮
    currentEpisodeIndex: Int = 1,
    onSelectEpisode: (Episode) -> Unit = {},
) {
    val context = LocalContext.current
    // 重试即换 key 重建引擎：播放器进入错误态后内部状态不可靠
    var attempt by remember { mutableIntStateOf(0) }
    // 默认走 MP4 直链：它严格对应所选档位；hls_url 是服务端固定档的整片流，画质与所选档位无关
    // 只有 MP4 起播失败才切 HLS，且只切一次，避免两路地址之间反复循环
    var fellBackToHls by remember(url, hlsUrl) { mutableStateOf(false) }
    val useHls = fellBackToHls && hlsUrl != null
    val playUrl = if (useHls) hlsUrl!! else url
    val focusRequester = remember { FocusRequester() }

    var playing by remember { mutableStateOf(false) }
    // 是否正在缓冲：控制加载动画的显隐
    var buffering by remember { mutableStateOf(true) }
    // 真正把动画亮出来的闸门：切片、微卡常常只有一两百毫秒，一抖就全屏弹一次再立刻消失，
    // 看起来像 UI 在抽搐。持续 [BufferingShowDelayMs] 才认为「用户真的在等」，恢复时立刻降下。
    var bufferingShown by remember { mutableStateOf(false) }

    // key 用布尔值是故意的：缓冲在 400ms 内恢复时，协程被取消，
    // 动画就完全不会出现——这正是要的效果，别改成记 tick。
    LaunchedEffect(buffering) {
        if (buffering) {
            delay(BufferingShowDelayMs)
            bufferingShown = true
        } else {
            bufferingShown = false
        }
    }
    // 左右键累计快进量：正向为正、倒退为负，用于箭头提示
    var seekAccumMs by remember { mutableLongStateOf(0L) }
    var seekHintTick by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableLongStateOf(startPositionMs) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var errorMessage by remember(url, attempt) { mutableStateOf<String?>(null) }
    // 失败重试要接着上次位置播，而不是回到片头
    var resumeFrom by remember(url) { mutableLongStateOf(startPositionMs) }
    // 引擎没有 playbackState，播放到结尾这件事只能自己记：
    // 它只用于「播完后按确认键要回到开头重播」这一个判断
    var ended by remember { mutableStateOf(false) }
    // 进播放页默认不显示 OSD：用户是来看剧的，不是来看菜单的。
    // 只有按确认键（播放/暂停）或方向键时才唤出。
    var osdVisible by remember { mutableStateOf(false) }
    var osdTick by remember { mutableIntStateOf(0) }
    // OSD 顶部时间：只显示分钟，所以每分钟对齐刷新一次，不按秒重组
    var clockText by remember { mutableStateOf("") }
    var focusedSlot by remember { mutableStateOf(OsdSlot.Quality) }
    var tierPanelVisible by remember { mutableStateOf(false) }
    var focusedTierIndex by remember { mutableIntStateOf(0) }
    // 倍速 / 弹幕 / 选集三块各自独立展开，任一展开都挂起 OSD 自动隐藏
    var speedPanelVisible by remember { mutableStateOf(false) }
    var focusedSpeedIndex by remember { mutableIntStateOf(0) }
    var danmakuPanelVisible by remember { mutableStateOf(false) }
    var focusedDanmakuItem by remember { mutableIntStateOf(0) }
    var episodePanelVisible by remember { mutableStateOf(false) }
    var focusedEpisodeIndex by remember { mutableIntStateOf(0) }
    var aspectPanelVisible by remember { mutableStateOf(false) }
    var focusedAspectIndex by remember { mutableIntStateOf(0) }
    var corePanelVisible by remember { mutableStateOf(false) }
    var focusedCoreIndex by remember { mutableIntStateOf(0) }
    var decoderPanelVisible by remember { mutableStateOf(false) }
    var focusedDecoderIndex by remember { mutableIntStateOf(0) }

    // 设置从 DataStore 读进来，读到之前先用默认值，读到之后整页沿用上次的配置
    val settingsStore = remember(context) { PlayerSettingsStoreImpl.create(context) }
    val introOutroStore = remember(context) { IntroOutroStoreImpl.create(context) }
    // 播放页里改的内核/软硬解只记在本剧（moda_playback_override），全局设置只当默认值用
    val overrideStore = remember(context) { PlaybackOverrideStoreImpl.create(context) }
    var settings by remember { mutableStateOf(PlayerSettings()) }
    LaunchedEffect(settingsStore) {
        val loaded = settingsStore.current()
        // 起播倍速的取舍：
        // defaultSpeed 是「每次起播用哪个倍速」的偏好，speed 是「这次会话正在用哪个倍速」。
        // 两者的唯一真相仍然是 PlayerSettings 里的那两个字段，不另起一份内存态：
        // 会话一开始把 speed 播种成 defaultSpeed，之后用户在 OSD 里改的只动 speed。
        // 于是「这一集用 2x 追完」不会污染下次开机的起点，也不会出现两套互相打架的状态。
        settings = loaded.copy(speed = loaded.defaultSpeed)
    }

    // 本剧的覆盖值；空覆盖 = 跟随设置页默认（读取是异步的，读到之前先按默认播）
    var playbackOverride by remember(cid) { mutableStateOf(PlaybackOverride()) }
    LaunchedEffect(cid) {
        playbackOverride = if (cid.isBlank()) PlaybackOverride() else overrideStore.get(cid)
    }

    var marks by remember(cid) { mutableStateOf(IntroOutro()) }
    // 记录是否已经从磁盘读到（读完了才允许自动跳，否则「还没读到」会被当成「没有记录」）
    var marksReady by remember(cid) { mutableStateOf(false) }
    // 换集不重建播放页，但 cid 不变，所以按 cid 读一次即可；记录/清除后由本地状态即时反映。
    // 读盘是异步的，下面的起播循环以 marks 为 key，读到记录后会重开一轮再判断，
    // 因此不会把「还没读到」误当成「没有记录」
    LaunchedEffect(cid) {
        marks = if (cid.isBlank()) IntroOutro() else introOutroStore.get(cid)
        marksReady = true
    }

    /**
     * 「这一集以前播过吗」——本集起播那一刻的 startPositionMs 快照。
     *
     * 为什么不直接用 startPositionMs：切清晰度会让 TvViewModel 把它改写成「当前进度」
     * （见 changeTier 里的 startPositionMs = resumeAt），那时它必然大于 0，
     * 同一集播到一半就突然变成「播过」，片头再也不会跳。
     *
     * 所以 key 必须是「这一集的身份」而不是「这次播放的参数」：
     *  · cid 换 = 换了一部剧，必然重置；
     *  · episodeLabel 里带集号（TvViewModel.labelOf 的兜底是「第 N 集」），
     *    播放页内的换集（selectEpisodeFromPlayer）一定会改它。
     * 刻意**不把 url 放进 key**：换清晰度会改 url，而那时 startPositionMs 已被改写成
     * 当前进度，重新取快照会把「没播过」错判成「播过」。
     */
    val initialStartMs = remember(cid, episodeLabel) { startPositionMs }
    val hasPlayedBefore = initialStartMs >= HasPlayedThresholdMs

    // 写盘统一走这里：UI 立刻生效，持久化异步跟上，不阻塞按键响应
    val scope = rememberCoroutineScope()
    // 标记写失败只是这一次没记住，播放页不该因此进错误态
    suspend fun persistMark(isIntro: Boolean, ms: Long) {
        if (cid.isBlank()) return
        try {
            if (isIntro) introOutroStore.setIntro(cid, ms) else introOutroStore.setOutro(cid, ms)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 静默：本地标记丢了最多再记一次
        }
    }

    /**
     * IJK 是否真的可用。isIjkAvailable() 首次调用会真的 new 一个 IjkMediaPlayer 去验 so，
     * 包进 remember 是为了避免每次重组都重复付这笔开销。
     */
    val ijkUsable = remember(context) { PlayerEngines.isIjkAvailable() }

    /**
     * 实际生效的内核。
     * 用户选了 IJK 但包里没有 so 时工厂会静默回退 Exo，渲染视图也必须跟着回退，
     * 否则会拿 SurfaceView 去配一个 Exo 引擎——必然黑屏。
     */
    /**
     * 本剧实际要用的内核 / 解码：本剧覆盖优先，没有覆盖才回落到设置页的默认值。
     * 单一出口，任何地方都不要再直接读 settings.playerCore / settings.decoderMode。
     */
    val wantedCore = playbackOverride.core ?: settings.playerCore
    val wantedDecoder = playbackOverride.decoder ?: settings.decoderMode

    val effectiveCore = if (wantedCore == PlayerCore.Ijk && ijkUsable) {
        PlayerCore.Ijk
    } else {
        PlayerCore.Exo
    }
    val hardware = wantedDecoder == DecoderMode.Hardware

    /**
     * 引擎重建的 key：地址、重试次数、内核、软硬解。
     *
     * 后两个也进 key，是因为它们只能在构造时决定（Exo 的 MediaCodecSelector 与
     * IJK 的 mediacodec 选项都是 init 期写死的），换值必须换实例。
     * 进度不会因此丢：onDispose 里先 flush 一次到 resumeFrom，新实例用它当 startMs。
     */
    val engine = remember(playUrl, attempt, effectiveCore, hardware) {
        PlayerEngines.create(context, coreKeyOf(effectiveCore), hardware)
    }

    // 没有集数表时 Episode 槽位不参与导航，焦点不能停在看不见的槽位上
    val slots = visibleOsdSlots(episodes.isNotEmpty())
    LaunchedEffect(slots) {
        if (focusedSlot !in slots) focusedSlot = slots.first()
    }

    /**
     * 当前展开的浮窗（L2）；没有浮窗返回 null（此时是 L1 控制条或 L0 纯净播放）。
     * 五个 boolean 按互斥关系收敛成一个值，按键分派只认它，
     * 避免每加一个浮窗就要在左右键/上下键/确认键/返回键四处各改一遍。
     */
    fun activePanelSlot(): OsdSlot? = when {
        tierPanelVisible -> OsdSlot.Quality
        episodePanelVisible -> OsdSlot.Episode
        speedPanelVisible -> OsdSlot.Speed
        danmakuPanelVisible -> OsdSlot.Danmaku
        aspectPanelVisible -> OsdSlot.Aspect
        corePanelVisible -> OsdSlot.Core
        decoderPanelVisible -> OsdSlot.Decoder
        else -> null
    }

    fun anyPanelVisible(): Boolean = activePanelSlot() != null

    fun closePanels() {
        tierPanelVisible = false
        speedPanelVisible = false
        danmakuPanelVisible = false
        episodePanelVisible = false
        aspectPanelVisible = false
        corePanelVisible = false
        decoderPanelVisible = false
    }

    fun updateSettings(next: PlayerSettings) {
        val previous = settings
        settings = next
        scope.launch { settingsStore.save(next) }
        // 倍速要实时生效；换内核/切清晰度/回退 HLS 重建引擎时会重新应用，见 LaunchedEffect(engine)
        if (next.speed != previous.speed) engine.setSpeed(next.speed)
    }

    fun cycleAspect(delta: Int) {
        val i = AspectModes.indexOf(settings.aspectMode).coerceAtLeast(0)
        focusedAspectIndex = (i + delta).mod(AspectModes.size)
        updateSettings(settings.copy(aspectMode = AspectModes[focusedAspectIndex]))
    }

    fun cycleSpeed(delta: Int) {
        val i = PlayerSpeedOptions.indexOfFirst { it.value == settings.speed }.coerceAtLeast(0)
        focusedSpeedIndex = (i + delta).mod(PlayerSpeedOptions.size)
        updateSettings(settings.copy(speed = PlayerSpeedOptions[focusedSpeedIndex].value))
    }

    /**
     * 写入本剧覆盖。
     *
     * 两个约定：
     *  · 与设置页默认相同 → 存 null（删键），否则「恢复默认」就只能靠再进设置页改回来，
     *    而且会留下一堆等值记录（AGENTS：恢复默认 = 删键）；
     *  · 覆盖值进的是 engine 的 remember key（解码方式只能在构造时决定），
     *    所以写一次就等于「重建引擎并从原位置续播」。
     */
    fun applyOverride(next: PlaybackOverride) {
        playbackOverride = next
        if (cid.isBlank()) return
        scope.launch {
            try {
                overrideStore.save(cid, next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 静默：这一集继续按刚选的值播，下次进来回到默认而已
            }
        }
    }

    fun cycleCore(delta: Int) {
        val entries = PlayerCore.entries
        val i = entries.indexOf(wantedCore).coerceAtLeast(0)
        val next = entries[(i + delta).mod(entries.size)]
        focusedCoreIndex = entries.indexOf(next)
        applyOverride(playbackOverride.copy(core = next.takeIf { it != settings.playerCore }))
    }

    fun cycleDecoder(delta: Int) {
        val entries = DecoderMode.entries
        val i = entries.indexOf(wantedDecoder).coerceAtLeast(0)
        val next = entries[(i + delta).mod(entries.size)]
        focusedDecoderIndex = entries.indexOf(next)
        applyOverride(playbackOverride.copy(decoder = next.takeIf { it != settings.decoderMode }))
    }

    fun adjustDanmakuItem(delta: Int) {
        val item = DanmakuItems.getOrNull(focusedDanmakuItem) ?: return
        updateSettings(adjustDanmaku(settings, item, delta))
    }

    /** 槽位在 L1（控制条层）里上下循环，[delta] = -1 向上、+1 向下 */
    fun moveSlot(delta: Int) {
        val i = slots.indexOf(focusedSlot)
        focusedSlot = slots[(i + delta + slots.size) % slots.size]
    }

    /** 收起当前浮窗，回到 L1 控制条层 */
    fun closeActivePanel() {
        when (activePanelSlot()) {
            OsdSlot.Quality -> tierPanelVisible = false
            OsdSlot.Episode -> episodePanelVisible = false
            OsdSlot.Speed -> speedPanelVisible = false
            OsdSlot.Danmaku -> danmakuPanelVisible = false
            OsdSlot.Aspect -> aspectPanelVisible = false
            OsdSlot.Core -> corePanelVisible = false
            OsdSlot.Decoder -> decoderPanelVisible = false
            OsdSlot.Intro, OsdSlot.Outro, null -> Unit
        }
    }

    /**
     * L2 浮窗里的左右键。返回 false 表示当前这一层不认左右键。
     *
     * 是否认键由 [OsdSlot.horizontalPanel] 唯一的声明决定，这里只负责执行：
     * 横向浮窗（倍速/比例/选集）左右键有意义，纵向浮窗（清晰度/弹幕）没有。
     */
    fun movePanelSelection(delta: Int): Boolean {
        val slot = activePanelSlot() ?: return false
        if (!slot.horizontalPanel) return false
        when (slot) {
            // 档位条选中即生效：移动就是改值，不需要再按确认
            OsdSlot.Speed -> cycleSpeed(delta)
            OsdSlot.Aspect -> cycleAspect(delta)
            // 内核/解码只有两档：左右键就是来回翻，选中即生效
            OsdSlot.Core -> cycleCore(delta)
            OsdSlot.Decoder -> cycleDecoder(delta)
            // 横滑选集：左右只移焦点，确认键才真正切集
            OsdSlot.Episode -> focusedEpisodeIndex = (focusedEpisodeIndex + delta)
                .coerceIn(0, episodes.lastIndex.coerceAtLeast(0))
            else -> Unit
        }
        return true
    }

    /**
     * L2 浮窗里的上下键，语义与 [movePanelSelection] 对称：
     * 纵向浮窗（清晰度/弹幕）里它是导航；横向浮窗里它不导航，交给 L1。
     */
    fun movePanelFocus(delta: Int): Boolean {
        val slot = activePanelSlot() ?: return false
        if (slot.horizontalPanel) return false
        when (slot) {
            OsdSlot.Danmaku ->
                focusedDanmakuItem = (focusedDanmakuItem + delta).mod(DanmakuItems.size)
            OsdSlot.Quality ->
                focusedTierIndex = (focusedTierIndex + delta).mod(tiers.size.coerceAtLeast(1))
            else -> Unit
        }
        return true
    }

    fun openEpisodePanel() {
        if (episodes.isEmpty()) return
        closePanels()
        episodePanelVisible = true
        focusedEpisodeIndex = episodes.indexOfFirst { it.index == currentEpisodeIndex }
            .coerceAtLeast(0)
    }

    /**
     * 回调参数名与 [PlayerEngine.Listener] 保持一致（否则 Kotlin 会报命名不一致的告警），
     * 但那样就会遮住同名的状态变量，所以经这两个小函数落回状态。
     */
    fun setPlayingState(value: Boolean) {
        playing = value
    }

    fun setBufferingState(value: Boolean) {
        buffering = value
    }

    DisposableEffect(engine) {
        val listener = object : PlayerEngine.Listener {
            override fun onPlayingChanged(playing: Boolean) {
                setPlayingState(playing)
            }

            override fun onBufferingChanged(buffering: Boolean) {
                // 缓冲状态要单独跟踪：起播、拖动进度、切清晰度都会进入缓冲，
                // 没有这个状态用户只看到一片黑，不知道是在加载还是卡死了
                setBufferingState(buffering)
            }

            override fun onEnded() {
                ended = true
                onEnded()
            }

            override fun onError(message: String) {
                // 直链先挂且有 HLS 兜底时静默切换：保住能看，比先弹报错体验好
                if (!useHls && hlsUrl != null) {
                    val last = engine.positionMs
                    if (last > 0L) resumeFrom = last
                    fellBackToHls = true
                    errorMessage = null
                } else {
                    errorMessage = message
                }
                osdVisible = true
            }

            override fun onVideoSizeChanged(width: Int, height: Int) {
                // 画面比例目前由 resizeMode / 盒子自己处理，这里不需要额外动作。
                // 回调保留是为了将来做「按实际画幅自动切比例」。
            }
        }
        engine.listener = listener
        onDispose {
            // 退出/换内核前补一次进度，外部据此写播放记录。
            // 引擎已出错时位置可能是 0，别覆盖断点
            val last = engine.positionMs
            val dur = engine.durationMs
            if (last > 0L) resumeFrom = last
            onProgress(resumeFrom, dur)
            // 先摘 listener 再 release：release 期间可能同步回调，那时本页可能已在销毁
            engine.listener = null
            engine.release()
        }
    }

    LaunchedEffect(engine, playUrl) {
        ended = false
        engine.start(playUrl, hls = useHls, startMs = resumeFrom, speed = settings.speed)
    }

    // 换内核 / 软硬解 / 切清晰度 / 回退 HLS / 重试都会重建引擎，
    // 新实例必须重新套上当前会话倍速；同一个 Effect 也负责首次起播时应用播种后的 defaultSpeed
    LaunchedEffect(engine, settings.speed) {
        engine.setSpeed(settings.speed)
    }
    /**
     * 自动跳片头/片尾的判定循环。
     *
     * 它同时承担进度上报，所以任何分支都**不能 return**——一旦退出，
     * 整页就再也写不进断点了。
     *
     * 以 [liveMarks] 与两个标记值作为 key：用户刚记录的片头/片尾要立刻生效，
     * 否则记录完什么都不发生。key 变化会重开一轮，闸门随之复位，
     * 于是「记录完立刻用上」，而普通播放过程中又不会被反复触发。
     */
    val liveMarks = if (marksReady) marks else IntroOutro()
    LaunchedEffect(engine, liveMarks.introMs, liveMarks.outroMs) {
        var sinceReport = 0L
        // 片头只自动跳一次：跳完再判会跟用户的回看拉扯，用户往回拖一点就被拽回去
        var introDone = false
        // 片尾同理，只触发一次，避免 onEnded 之前本帧内反复调用
        var outroFired = false
        // 本集是否已经上报过完成。幂等的**权威闸门在 TvViewModel.completedVids**，
        // 这里只是前置闸：上报过一次就不再重复上报，省掉每秒一次的无效调用。
        var completedReported = false
        while (true) {
            positionMs = engine.positionMs
            durationMs = engine.durationMs
            bufferedMs = engine.bufferedMs

            // 片头/片尾的比例计算必须等到片长就绪。
            // duration 一开始是 0，此时算 8%/6% 只会得到 0；更糟的是把这次计算记成
            // 「已经判定过」，片长真正到了也不会再跳一次。所以整段都放在这个 if 里。
            if (durationMs > 0L) {
                // 片头先算，因为它还要给片尾当参照（片尾不能压在片头 MIN_GAP_MS 以内）。
                // 每轮重算而不是缓存：片长可能在起播后继续被修正（HLS 估算时长误差），
                // 缓存下来的旧比例会让片尾点落在错的位置。两个函数都是纯函数，开销可忽略。
                val effectiveIntro = IntroOutroRules.effectiveIntro(
                    liveMarks.introMs,
                    durationMs,
                )

                if (!introDone && settings.skipIntroEnabled) {
                    introDone = true
                    // 「从未播过」的判据见 HasPlayedThresholdMs / hasPlayedBefore 的注释
                    if (IntroOutroRules.shouldSkipIntro(
                            hasPlayedBefore = hasPlayedBefore,
                            positionMs = positionMs,
                            effectiveIntroMs = effectiveIntro,
                        )
                    ) {
                        engine.seekTo(effectiveIntro)
                        positionMs = effectiveIntro
                    }
                }

                // 片尾：到点就算这一集看完，交给外部连播下一集。
                // 受 skipOutroEnabled 控制的是「跳」，不是「算看完」——
                // 播到 95% 就该算看完，和用户想不想跳片尾无关。
                val outro = IntroOutroRules.effectiveOutroWithIntro(
                    recordedMs = liveMarks.outroMs,
                    durationMs = durationMs,
                    introMs = effectiveIntro,
                )
                if (settings.skipOutroEnabled && outro > 0L && !outroFired &&
                    positionMs >= outro - IntroOutroRules.INTRO_SKIP_TOLERANCE_MS
                ) {
                    outroFired = true
                    // 不暂停：暂停会让 currentPosition 钉在片尾点上，新循环重新判断时又命中一次。
                    // 交给外部切集，旧引擎随 key 变化被 release。
                    //
                    // 完成结算：播放页没有 markCompleted 的通道（那是 TvViewModel 的方法），
                    // 唯一入口是 onProgress —— 这里显式上报一次「位置 = 片长」，
                    // 让 VM 内部的 isCompleted 判定成立，从而走它那条唯一的 markCompleted 路径。
                    // 这样 95% 与跳片尾两条来源共用同一个落点，去重仍只由 VM 负责。
                    if (!completedReported) {
                        completedReported = true
                        onProgress(durationMs, durationMs)
                    }
                    onEnded()
                    // 这里**不能 return**：这个循环同时负责每秒一次进度上报，
                    // 退出后整页就再也写不进断点了（原实现的注释同样强调这一点）。
                    // outroFired 已置位，后续循环不会再命中片尾分支。
                }

                // 走满 95% 也结算一次，与上面是两条独立的路；
                // completedReported 保证同一集不会两条路各报一次
                if (!completedReported && IntroOutroRules.isCompleted(positionMs, durationMs)) {
                    completedReported = true
                    onProgress(positionMs, durationMs)
                }
            }

            sinceReport += TickMs
            if (sinceReport >= ProgressReportMs) {
                sinceReport = 0L
                onProgress(positionMs, durationMs)
            }
            delay(TickMs)
        }
    }

    LaunchedEffect(
        osdVisible,
        osdTick,
        tierPanelVisible,
        speedPanelVisible,
        danmakuPanelVisible,
        episodePanelVisible,
        aspectPanelVisible,
        corePanelVisible,
        decoderPanelVisible,
    ) {
        // 任一面板展开时都不自动收起，否则用户还没选完 OSD 就消失了
        if (!osdVisible || anyPanelVisible()) return@LaunchedEffect
        delay(OsdAutoHideMs)
        osdVisible = false
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // 对齐到整分再刷新：OSD 展开期间每分钟只重组一次，不浪费帧
    LaunchedEffect(osdVisible) {
        if (!osdVisible) return@LaunchedEffect
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            clockText = formatter.format(Date())
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
        }
    }

    fun showOsd() {
        osdVisible = true
        osdTick++
    }

    // 快进/快退时不要弹整块 OSD：用户只是想跳一段，不需要看到所有菜单。
    // 改为一个居中飘出的箭头 + 累计秒数，松手后自然消失，画面保持干净。
    fun seekBy(deltaMs: Long) {
        val from = engine.positionMs
        val target = (from + deltaMs)
            .coerceAtLeast(0L)
            .let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
        // 累计的是「实际跳了多远」，不是按键请求的距离：片头/片尾被夹住时按 10 秒只走 3 秒，
        // 提示却报 10 秒就是在骗人。实际位移为 0 就别 seek，也别刷新提示——到头了不该再飘数字。
        val actual = target - from
        if (actual == 0L) return
        engine.seekTo(target)
        positionMs = target
        seekAccumMs += actual
        seekHintTick++
    }

    /** 左右键的唯一出口：浮窗认键就交给浮窗，不认就静默，只有 L1/L0 才快进快退 */
    fun dispatchHorizontal(delta: Int) {
        if (movePanelSelection(delta)) {
            showOsd()
            return
        }
        if (anyPanelVisible()) return
        seekBy(delta * SeekStepMs)
        if (osdVisible) showOsd()
    }

    /**
     * 按钮的职责是「记录当前时间」，跳转由播放器自动完成。
     * 重新按一次就是重新量一遍：把记录改成现在的位置。
     */
    fun recordMark(isIntro: Boolean) {
        val now = engine.positionMs
        // 0 秒没有意义：既不能当标记跳，写进去也只是个空记录
        if (now <= 0L) return
        marks = if (isIntro) marks.copy(introMs = now) else marks.copy(outroMs = now)
        scope.launch { persistMark(isIntro, now) }
    }

    /** 长按清除：删掉这一项，按钮回到文字占位 */
    fun clearMark(isIntro: Boolean) {
        marks = if (isIntro) marks.copy(introMs = 0L) else marks.copy(outroMs = 0L)
        scope.launch { persistMark(isIntro, 0L) }
    }

    /**
     * 长按计时器。
     * 按下瞬间就记录，按住越过 [MarkLongPressMs] 再清除——
     * 动作全部挂在 DOWN 上，是因为遥控器的 UP 不保证送达，
     * 把短按放在 UP 上会出现「按下没反应」。
     */
    var markHoldJob by remember { mutableStateOf<Job?>(null) }

    fun armLongPress(isIntro: Boolean) {
        markHoldJob?.cancel()
        markHoldJob = scope.launch {
            delay(MarkLongPressMs)
            clearMark(isIntro)
        }
    }

    fun cancelLongPress() {
        markHoldJob?.cancel()
        markHoldJob = null
    }

    fun togglePlay() {
        if (errorMessage != null) return
        if (engine.isPlaying) {
            engine.pause()
        } else {
            // 播到结尾后确认键是「重播这一集」，得先把游标拨回开头
            if (ended) {
                engine.seekTo(0L)
                ended = false
            }
            engine.play()
        }
    }

    fun applyTier(index: Int) {
        val tier = tiers.getOrNull(index) ?: return
        if (!tier.available) return
        tierPanelVisible = false
        onTierChange(tier)
    }

    BackHandler {
        when {
            // 分层退出：先关面板，再关 OSD，最后才离开播放页
            anyPanelVisible() -> closePanels()
            osdVisible -> osdVisible = false
            else -> onBack()
        }
        osdTick++
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent { event ->
                // 片头/片尾：按下即记录，按住越过阈值则清除。
                // 两个动作都挂在 DOWN 上——遥控器的 UP 不保证送达，
                // 挂在 UP 上会变成「按下没反应」；重复事件只吞掉，不重复记录
                val isCenterKey = event.key == Key.DirectionCenter || event.key == Key.Enter
                val markSlot = focusedSlot.takeIf {
                    isCenterKey && osdVisible && errorMessage == null && !anyPanelVisible() &&
                        (it == OsdSlot.Intro || it == OsdSlot.Outro)
                }
                if (markSlot != null) {
                    if (event.type == KeyEventType.KeyDown &&
                        !event.nativeKeyEvent.isLongPress
                    ) {
                        recordMark(markSlot == OsdSlot.Intro)
                        armLongPress(markSlot == OsdSlot.Intro)
                    }
                    if (event.type == KeyEventType.KeyUp) cancelLongPress()
                    showOsd()
                    return@onKeyEvent true
                }
                // 焦点已离开片头/片尾时，任何按键都要撤销悬挂的长按计时
                if (event.type == KeyEventType.KeyUp) cancelLongPress()
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (errorMessage != null) {
                    return@onKeyEvent when (event.key) {
                        Key.DirectionCenter, Key.Enter -> {
                            errorMessage = null
                            attempt++
                            true
                        }
                        Key.Back -> {
                            onBack()
                            true
                        }
                        else -> true
                    }
                }
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        when {
                            // 选集是横滑列表：左右移焦点，确认键在这里真正切集
                            episodePanelVisible -> {
                                episodes.getOrNull(focusedEpisodeIndex)?.let { episode ->
                                    episodePanelVisible = false
                                    closePanels()
                                    onSelectEpisode(episode)
                                }
                            }
                            // 弹幕面板是纵向表：上下选子项，确认键改值
                            // （开关翻转、档位 +1、边距 +5%）
                            danmakuPanelVisible -> adjustDanmakuItem(1)
                            // 清晰度是纵向档位表：上下选档，确认键应用并关闭浮窗
                            tierPanelVisible -> applyTier(focusedTierIndex)
                            // OSD 可见时确认键展开当前槽位的面板
                            osdVisible -> when (focusedSlot) {
                                // 打开任何一个面板前都先关掉其它面板。
                                // 之前各面板独立开合，会出现「画面比例」和「清晰度」同时挂在屏幕上、
                                // 互相叠压的情况——电视上一屏本来就不大，两个浮层直接把画面盖掉大半。
                                OsdSlot.Quality -> {
                                    closePanels()
                                    tierPanelVisible = true
                                    focusedTierIndex =
                                        tiers.indexOfFirst { it.fno == selectedTier?.fno }
                                            .coerceAtLeast(0)
                                }
                                OsdSlot.Episode -> openEpisodePanel()
                                OsdSlot.Speed -> {
                                    closePanels()
                                    speedPanelVisible = true
                                    focusedSpeedIndex = PlayerSpeedOptions
                                        .indexOfFirst { it.value == settings.speed }
                                        .coerceAtLeast(0)
                                }
                                OsdSlot.Danmaku -> {
                                    closePanels()
                                    danmakuPanelVisible = true
                                    focusedDanmakuItem = 0
                                }
                                OsdSlot.Aspect -> {
                                    closePanels()
                                    aspectPanelVisible = true
                                    focusedAspectIndex = AspectModes.indexOf(settings.aspectMode)
                                        .coerceAtLeast(0)
                                }
                                // 内核/解码同样是横向档位条：开面板时焦点落在当前值上
                                OsdSlot.Core -> {
                                    closePanels()
                                    corePanelVisible = true
                                    focusedCoreIndex = PlayerCore.entries
                                        .indexOf(wantedCore)
                                        .coerceAtLeast(0)
                                }
                                OsdSlot.Decoder -> {
                                    closePanels()
                                    decoderPanelVisible = true
                                    focusedDecoderIndex = DecoderMode.entries
                                        .indexOf(wantedDecoder)
                                        .coerceAtLeast(0)
                                }
                                // 片头/片尾不展开面板：记录动作已在 onKeyEvent 出口处理
                                OsdSlot.Intro, OsdSlot.Outro -> Unit
                            }
                            // 没有面板在展开时，OK 键就是播放/暂停（用户明确要求的全局行为）
                            else -> togglePlay()
                        }
                        showOsd()
                        true
                    }
                    // 按键按「最上层接管」分派（同业内 TV 播放器的模态栈）：
                    //   L2 横向浮窗 → 左右归浮窗（换档/换集），不快进
                    //   L2 纵向浮窗 → 左右在这层没有语义，静默吃掉；绝不穿透到进度条，
                    //                 否则用户以为在选东西，画面却在跳
                    //   L1 控制条 / L0 纯净播放 → 左右就是快退快进
                    Key.DirectionLeft -> {
                        dispatchHorizontal(-1)
                        true
                    }
                    Key.DirectionRight -> {
                        dispatchHorizontal(1)
                        true
                    }
                    Key.DirectionUp -> {
                        // 纵向浮窗吃上下键（选档/选子项）；横向浮窗不吃——
                        // 上下键此时表示「离开这一行」，先收起浮窗回 L1，再换槽位
                        if (!movePanelFocus(-1)) {
                            closeActivePanel()
                            moveSlot(-1)
                        }
                        showOsd()
                        true
                    }
                    Key.DirectionDown -> {
                        if (!movePanelFocus(1)) {
                            closeActivePanel()
                            moveSlot(1)
                        }
                        showOsd()
                        true
                    }
                    else -> false
                }
            }
    ) {
        // 只承担焦点：真正接收按键的是外层 Box 的 onKeyEvent
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
        )

        // 画面比例：
        // Fit/Fill/Zoom 交给 Exo 的 resizeMode（IJK 没有这个概念，Surface 自己铺满）；
        // 16:9 / 4:3 是两边都不支持的档位，做法是给渲染视图套一个锁定宽高比的盒子，
        // 盒内仍用 FILL —— 盒子决定画幅，FILL 让它铺满盒子，
        // 于是宽银幕内容会被压成 4:3、4:3 老片会被拉成 16:9，正好是本档要的效果，
        // 并且不用碰 Surface 尺寸或 TextureView 变换，切档时不会重建引擎。
        val fixedRatio: Float? = when (settings.aspectMode) {
            AspectMode.Ratio16x9 -> 16f / 9f
            AspectMode.Ratio4x3 -> 4f / 3f
            else -> null
        }
        val resizeMode = when (settings.aspectMode) {
            AspectMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            AspectMode.Fill -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectMode.Ratio16x9, AspectMode.Ratio4x3 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // AndroidView 的 factory 只在节点首次进入组合时跑一次，换内核就换了 View 类型，
            // 不换节点会留下上一个内核的 View。key(engine) 强制重建整棵渲染子树。
            key(engine) {
                VideoSurface(
                    engine = engine,
                    core = effectiveCore,
                    resizeMode = resizeMode,
                    modifier = if (fixedRatio != null) {
                        Modifier
                            .fillMaxSize()
                            .aspectRatio(fixedRatio)
                    } else {
                        Modifier.fillMaxSize()
                    },
                )
            }
        }

        // 弹幕关闭时 enabled=false，DanmakuOverlay 内部直接返回：
        // 没有 Canvas、没有帧循环、没有文字测量，绘制开销为零
        DanmakuOverlay(
            danmaku = danmaku,
            positionMs = positionMs,
            playing = playing,
            modifier = Modifier.fillMaxSize(),
            enabled = settings.danmakuEnabled,
            fontSizeSp = settings.danmakuSizeSp,
            topRatio = settings.topRatio,
            bottomRatio = settings.bottomRatio,
        )

        if (osdVisible && errorMessage == null) {
            PlayerOsd(
                title = title,
                episodeLabel = episodeLabel,
                playing = playing,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                tiers = tiers,
                selectedTier = selectedTier,
                focusedSlot = focusedSlot,
                tierPanelVisible = tierPanelVisible,
                focusedTierIndex = focusedTierIndex,
                modifier = Modifier.fillMaxSize(),
                settings = settings,
                episodes = episodes,
                currentEpisodeIndex = currentEpisodeIndex,
                focusedEpisodeIndex = focusedEpisodeIndex,
                episodePanelVisible = episodePanelVisible,
                speedPanelVisible = speedPanelVisible,
                focusedSpeedIndex = focusedSpeedIndex,
                danmakuPanelVisible = danmakuPanelVisible,
                focusedDanmakuItem = focusedDanmakuItem,
                aspectPanelVisible = aspectPanelVisible,
                focusedAspectIndex = focusedAspectIndex,
                corePanelVisible = corePanelVisible,
                focusedCoreIndex = focusedCoreIndex,
                decoderPanelVisible = decoderPanelVisible,
                focusedDecoderIndex = focusedDecoderIndex,
                effectiveCore = effectiveCore,
                effectiveDecoder = wantedDecoder,
                ijkUsable = ijkUsable,
                clockText = clockText,
                introMs = marks.introMs,
                outroMs = marks.outroMs,
            )
        }

        // 缓冲动画：不挂在 OSD 上，OSD 收起时也要能看见。
        // 何时显示：正在缓冲，或第一帧还没出来（durationMs 仍为 0）。
        // 何时隐藏：已在播放且数据足够（onPlayingChanged 回调会清 buffering）。
        // 快进/快退提示：居中飘出一个箭头 + 累计秒数，1.2 秒后自动消失。
        // 这是一个「瞬时反馈」，不能做成常驻面板——用户按左右键只是想跳一段。
        if (seekHintTick > 0) {
            LaunchedEffect(seekHintTick) {
                delay(1200)
                seekAccumMs = 0L
                seekHintTick = 0
            }
            SeekHint(accumMs = seekAccumMs, modifier = Modifier.fillMaxSize())
        }

        // 缓冲指示：只在「还没有出画面」时显示。
        // 起播阶段（durationMs 仍为 0）已经由 PlayerUi.Loading 覆盖，
        // 这里再叠一个「正在加载…」就重复了，用户会觉得多余。
        // 显隐走淡入淡出（进场 160ms / 退场 220ms）：硬切会在每次卡顿时闪一下，
        // 退场比进场慢一点，是因为「突然消失」比「慢慢出现」更刺眼。
        AnimatedVisibility(
            visible = errorMessage == null && bufferingShown && durationMs > 0L,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(220)),
        ) {
            BufferingIndicator(modifier = Modifier.fillMaxSize())
        }

        if (fellBackToHls) {
            Text(
                "已回退到 HLS 流",
                style = TvCaption,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp),
            )
        }

        errorMessage?.let { message ->
            PlayerErrorPane(message = message, retrying = false, modifier = Modifier.fillMaxSize())
        }
    }
}
