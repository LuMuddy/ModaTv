package com.moda.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.moda.tv.data.Api
import com.moda.tv.data.local.AspectMode
import com.moda.tv.data.local.AspectModes
import com.moda.tv.data.local.IntroOutro
import com.moda.tv.data.local.IntroOutroStoreImpl
import com.moda.tv.data.local.PlayerSettings
import com.moda.tv.data.local.PlayerSettingsStoreImpl
import com.moda.tv.data.local.PlayerSpeedOptions
import com.moda.tv.model.Danmaku
import com.moda.tv.model.Episode
import com.moda.tv.model.Tier
import com.moda.tv.ui.player.DanmakuItems
import com.moda.tv.ui.player.DanmakuOverlay
import com.moda.tv.ui.player.OsdSlot
import com.moda.tv.ui.player.PlayerErrorPane
import com.moda.tv.ui.player.PlayerOsd
import com.moda.tv.ui.player.adjustDanmaku
import com.moda.tv.ui.player.visibleOsdSlots
import com.moda.tv.ui.theme.ModaColors
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
                text = "$seconds 秒",
                style = TvBody.copy(fontSize = 30.sp),
                color = Color.White.copy(alpha = 0.92f),
            )
        }
    }
}

/**
 * 播放缓冲指示器。
 *
 * 只放一行「正在加载…」不足以说明系统还活着：电视上加载 2~3 秒很常见，
 * 静态文字会让人以为卡死。这里用三点循环 + 细进度环的组合，
 * 动画本身不依赖网络状态，只要还在缓冲就一直在动，用户能看到「在努力」。
 */
@Composable
private fun BufferingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "buffering")
    // 三点依次亮起：节奏感比单个转圈更「轻」，不会盖过画面
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "dots",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { i ->
                    // 每个点在自己的 1/3 时段内最亮
                    val active = (phase.toInt() % 3) == i
                    Box(
                        Modifier
                            .padding(horizontal = 5.dp)
                            .size(if (active) 14.dp else 10.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) ModaColors.AccentSoft
                                else Color.White.copy(alpha = 0.35f),
                            ),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "正在加载…",
                style = TvBody.copy(fontSize = 18.sp),
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}
private const val SeekStepMs = 10_000L
private const val TickMs = 300L
private const val ProgressReportMs = 1000L
/** 长按多久算「要清除」，短于它按短按处理 */
private const val MarkLongPressMs = 800L
/** 判断「已经播过标记点」的容差：刚 seek 到位时位置可能差一点点 */
private const val MarkReachedToleranceMs = 1000L

/**
 * 复用 Api 的 OkHttpClient 建数据源：电视盒子的默认 HttpURLConnection 在 TLS 与
 * 重定向上的表现差异很大，大文件 Range 请求也没必要开第二套连接池。
 */
private fun buildPlayer(context: android.content.Context): ExoPlayer {
    val okHttpFactory = OkHttpDataSource.Factory(Api.http)
    val dataSourceFactory = DefaultDataSource.Factory(context, okHttpFactory)
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .build()
}

/**
 * TV 播放页。
 * 遥控器事件统一由外层 Box 处理：PlayerView 自己会吃掉方向键与确认键，
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
    // 重试即换 key 重建 ExoPlayer：播放器进入错误态后内部状态不可靠
    var attempt by remember { mutableIntStateOf(0) }
    // 默认走 MP4 直链：它严格对应所选档位；hls_url 是服务端固定档的整片流，画质与所选档位无关
    // 只有 MP4 起播失败才切 HLS，且只切一次，避免两路地址之间反复循环
    var fellBackToHls by remember(url, hlsUrl) { mutableStateOf(false) }
    val useHls = fellBackToHls && hlsUrl != null
    val playUrl = if (useHls) hlsUrl!! else url
    val player = remember(playUrl, attempt) { buildPlayer(context) }
    val focusRequester = remember { FocusRequester() }

    var playing by remember { mutableStateOf(false) }
    // 是否正在缓冲：控制加载动画的显隐
    var buffering by remember { mutableStateOf(true) }
    // 左右键累计快进量：正向为正、倒退为负，用于箭头提示
    var seekAccumMs by remember { mutableLongStateOf(0L) }
    var seekHintTick by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableLongStateOf(startPositionMs) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var errorMessage by remember(url, attempt) { mutableStateOf<String?>(null) }
    // 失败重试要接着上次位置播，而不是回到片头
    var resumeFrom by remember(url) { mutableLongStateOf(startPositionMs) }
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

    // 设置从 DataStore 读进来，读到之前先用默认值，读到之后整页沿用上次的配置
    val settingsStore = remember(context) { PlayerSettingsStoreImpl.create(context) }
    val introOutroStore = remember(context) { IntroOutroStoreImpl.create(context) }
    var settings by remember { mutableStateOf(PlayerSettings()) }
    LaunchedEffect(settingsStore) {
        settings = settingsStore.current()
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
    fun updateSettings(next: PlayerSettings) {
        val previous = settings
        settings = next
        scope.launch { settingsStore.save(next) }
        // 倍速要实时生效；清晰度切换重建播放器时会重新应用，见下面的 LaunchedEffect(player)
        if (next.speed != previous.speed) player.setPlaybackSpeed(next.speed)
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
        else -> null
    }

    fun anyPanelVisible(): Boolean = activePanelSlot() != null

    fun closePanels() {
        tierPanelVisible = false
        speedPanelVisible = false
        danmakuPanelVisible = false
        episodePanelVisible = false
        aspectPanelVisible = false
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

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                durationMs = player.duration.coerceAtLeast(0L)
                // 缓冲状态要单独跟踪：起播、拖动进度、切清晰度都会进入 BUFFERING，
                // 没有这个状态用户只看到一片黑，不知道是在加载还是卡死了
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED) onEnded()
            }
            override fun onPlayerError(error: PlaybackException) {
                // 直链先挂且有 HLS 兜底时静默切换：保住能看，比先弹报错体验好
                if (!useHls && hlsUrl != null) {
                    val last = player.currentPosition.coerceAtLeast(0L)
                    if (last > 0L) resumeFrom = last
                    fellBackToHls = true
                    errorMessage = null
                } else {
                    errorMessage = error.message ?: error.errorCodeName
                }
                osdVisible = true
            }
        }
        player.addListener(listener)
        onDispose {
            // 退出前补一次进度，外部据此写播放记录；播放器已出错时位置可能是 0，别覆盖断点
            val last = player.currentPosition.coerceAtLeast(0L)
            if (last > 0L) resumeFrom = last
            onProgress(resumeFrom, player.duration.coerceAtLeast(0L))
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player, playUrl) {
        val item = if (useHls) {
            MediaItem.Builder()
                .setUri(hlsUrl)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
        } else {
            MediaItem.fromUri(url)
        }
        player.setMediaItem(item)
        if (resumeFrom > 0) player.seekTo(resumeFrom)
        player.prepare()
        player.playWhenReady = true
    }

    // 换清晰度 / 回退 HLS / 重试都会重建 ExoPlayer，新实例必须重新套上当前倍速；
    // 同一个 Effect 也负责首次起播时把持久化的倍速应用上去
    LaunchedEffect(player, settings.speed) {
        player.setPlaybackSpeed(settings.speed)
    }

    /**
     * 自动跳读的是「实时标记」，并且以标记值本身作为 effect 的 key：
     * 用户刚记录的片头/片尾要立刻生效，否则记录完什么都不发生。
     * key 变化会重开一轮循环，introApplied/outroFired 随之复位，
     * 于是「记录完立刻用上」，而普通播放过程中又不会被反复触发。
     */
    val liveMarks = if (marksReady) marks else IntroOutro()
    LaunchedEffect(player, liveMarks.introMs, liveMarks.outroMs) {
        var sinceReport = 0L
        // 片头只自动跳一次：跳完再判会跟用户的回看拉扯，用户往回拖一点就被拽回去
        var introApplied = false
        // 片尾同理，只触发一次，避免 onEnded 之前本帧内反复调用
        var outroFired = false
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.coerceAtLeast(0L)
            bufferedMs = player.bufferedPosition.coerceAtLeast(0L)

            // 片头：起播后只要还没走到记录点，就自动跳到记录点。
            // 断点续播在同一集的中间时，now 已经大于标记，这里自然不触发
            val intro = liveMarks.introMs
            if (intro > 0L && !introApplied) {
                introApplied = true
                if (positionMs + MarkReachedToleranceMs < intro) {
                    player.seekTo(intro)
                    positionMs = intro
                }
            }

            // 片尾：播到记录点就算本集看完，交给外部连播下一集。
            // 不能 return：这个循环同时负责进度上报，退出后整页就再也写不进断点了
            val outro = liveMarks.outroMs
            if (outro > 0L && !outroFired &&
                positionMs >= outro - MarkReachedToleranceMs
            ) {
                outroFired = true
                // 不暂停：暂停会让 currentPosition 钉在片尾点上，新循环重新判断时又命中一次。
                // 交给外部切集，旧播放器随 key 变化被 release
                onEnded()
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
        val target = (player.currentPosition + deltaMs)
            .coerceAtLeast(0L)
            .let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
        player.seekTo(target)
        positionMs = target
        seekAccumMs += deltaMs
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
        val now = player.currentPosition.coerceAtLeast(0L)
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
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
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
        // Fit/Fill/Zoom 直接交给 PlayerView.resizeMode（它内部就是 AspectRatioFrameLayout）；
        // 16:9 / 4:3 是 PlayerView 不支持的档位，做法是给 PlayerView 套一个锁定宽高比的盒子，
        // 盒内仍用 RESIZE_MODE_FILL —— 盒子决定画幅，FILL 让它铺满盒子，
        // 于是宽银幕内容会被压成 4:3、4:3 老片会被拉成 16:9，正好是本档要的效果，
        // 并且不用碰 Surface 尺寸或 TextureView 变换，切档时不会重建播放器。
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
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        // 控制器关闭后 PlayerView 仍会抢方向键，必须明确退出焦点竞争
                        isFocusable = false
                        isFocusableInTouchMode = false
                        keepScreenOn = true
                    }
                },
                // resizeMode 也要在 update 里设：重建播放器、切档位都会走到这里
                update = {
                    it.player = player
                    it.resizeMode = resizeMode
                },
                onRelease = { it.player = null },
                modifier = if (fixedRatio != null) {
                    Modifier
                        .fillMaxSize()
                        .aspectRatio(fixedRatio)
                } else {
                    Modifier.fillMaxSize()
                },
            )
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
                clockText = clockText,
                introMs = marks.introMs,
                outroMs = marks.outroMs,
            )
        }

        // 缓冲动画：不挂在 OSD 上，OSD 收起时也要能看见。
        // 何时显示：正在缓冲，或第一帧还没出来（durationMs 仍为 0）。
        // 何时隐藏：已在播放且数据足够（onIsPlayingChanged 会清 buffering）。
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
        if (errorMessage == null && buffering && durationMs > 0L) {
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