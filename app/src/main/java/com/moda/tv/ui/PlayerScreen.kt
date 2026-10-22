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
import androidx.compose.ui.input.key.KeyEvent
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
/** 片头/片尾的「长按清除」判定时长：短于它按短按处理 */
private const val MarkLongPressMs = 800L

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
    // 换集不重建播放页，但 cid 不变，所以按 cid 读一次即可；清除/记录后由本地状态即时反映
    LaunchedEffect(cid) {
        marks = if (cid.isBlank()) IntroOutro() else introOutroStore.get(cid)
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

    fun anyPanelVisible(): Boolean = tierPanelVisible || speedPanelVisible ||
        danmakuPanelVisible || episodePanelVisible || aspectPanelVisible

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

    /**
     * 面板展开时上下键移动面板焦点：上键 [delta] = -1、下键 +1。
     * 左右键在播放页永远属于进度条（用户要的就是"任何弹窗下都能拖进度"），
     * 所以面板里的选项只能走这条路径，返回值表示这次按键是否被面板吃掉。
     */
    fun movePanelFocus(delta: Int): Boolean = when {
        episodePanelVisible -> {
            focusedEpisodeIndex = (focusedEpisodeIndex + delta)
                .coerceIn(0, episodes.lastIndex.coerceAtLeast(0))
            true
        }
        // 倍速 / 比例是「选中即生效」的单行档位，移动焦点就是改值
        speedPanelVisible -> { cycleSpeed(delta); true }
        aspectPanelVisible -> { cycleAspect(delta); true }
        danmakuPanelVisible -> {
            focusedDanmakuItem = (focusedDanmakuItem + delta).mod(DanmakuItems.size)
            true
        }
        tierPanelVisible -> {
            focusedTierIndex = (focusedTierIndex + delta).mod(tiers.size.coerceAtLeast(1))
            true
        }
        else -> false
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

    LaunchedEffect(player) {
        var sinceReport = 0L
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.coerceAtLeast(0L)
            bufferedMs = player.bufferedPosition.coerceAtLeast(0L)
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

    /**
     * 片头/片尾的短按语义（用户原话）：
     * 还没记录 → 把当前播放位置记下来；已经记录 → 跳到记录的位置。
     */
    fun markOrJump(isIntro: Boolean) {
        val recorded = if (isIntro) marks.introMs else marks.outroMs
        if (recorded <= 0L) {
            val now = player.currentPosition.coerceAtLeast(0L)
            // 0 秒没有意义：既不能当标记跳，写进去也只是个空记录
            if (now <= 0L) return
            marks = if (isIntro) marks.copy(introMs = now) else marks.copy(outroMs = now)
            scope.launch { persistMark(isIntro, now) }
        } else {
            val target = if (durationMs > 0L) recorded.coerceAtMost(durationMs) else recorded
            player.seekTo(target)
            positionMs = target
        }
    }

    /** 长按清除：写 0 即删键，下次再短按就是「重新记录」 */
    fun clearMark(isIntro: Boolean) {
        marks = if (isIntro) marks.copy(introMs = 0L) else marks.copy(outroMs = 0L)
        scope.launch { persistMark(isIntro, 0L) }
    }

    /**
     * 遥控器长按的判定：DOWN 起计时，同键重复事件刷新，UP 时看总时长是否越过阈值。
     * 必须在 DOWN 时就绑上，否则第一次 DOWN 记不到起点，长按永远判不出来。
     */
    var markDownAtMs by remember { mutableLongStateOf(0L) }
    var markPressed by remember { mutableStateOf<OsdSlot?>(null) }
    var markJob by remember { mutableStateOf<Job?>(null) }
    var markLongFired by remember { mutableStateOf(false) }

    fun bindMarkLongPress(slot: OsdSlot?) {
        markJob?.cancel()
        markJob = null
        markPressed = slot
        markDownAtMs = System.currentTimeMillis()
        markLongFired = false
        if (slot == null) return
        // 定时器兜底：长按期间按键会进入重复模式，某些遥控器最后不发 UP，
        // 只靠 UP 判定会让「长按」永远等不到，用户以为按键失灵
        markJob = scope.launch {
            delay(MarkLongPressMs)
            if (!markLongFired) {
                markLongFired = true
                clearMark(slot == OsdSlot.Intro)
            }
        }
    }

    /** 同键重复事件：控件已经把长按吃掉了，这里只负责取消 UP 时的短按 */
    fun onMarkRepeat() {
        if (System.currentTimeMillis() - markDownAtMs >= MarkLongPressMs) markLongFired = true
    }

    /** UP / CLEAR：没被长按吃掉才执行短按 */
    fun finishMarkPress() {
        val slot = markPressed
        markJob?.cancel()
        markJob = null
        markPressed = null
        if (slot != null && !markLongFired) markOrJump(slot == OsdSlot.Intro)
        markLongFired = false
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
                // 片头/片尾的长按判定要在 KeyDown 之前挂上：DOWN 就是计时起点。
                // 其余按键一律清掉挂起态，避免上下键移走焦点后长按还记在旧槽位上
                val isCenterKey = event.key == Key.DirectionCenter || event.key == Key.Enter
                val markSlot = focusedSlot.takeIf {
                    osdVisible && errorMessage == null && !anyPanelVisible() && isCenterKey &&
                        (it == OsdSlot.Intro || it == OsdSlot.Outro)
                }
                when (event.type) {
                    KeyEventType.KeyDown -> if (markPressed != markSlot) bindMarkLongPress(markSlot)
                    KeyEventType.KeyUp -> {
                        if (markPressed != null) {
                            finishMarkPress()
                            // KeyUp 不再往下走，否则 OK 键会顺手把播放暂停掉
                            return@onKeyEvent true
                        }
                    }
                    else -> Unit
                }
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
                        // 片头/片尾已经由上面的长按追踪接管：短按在这里执行，长按留给 KeyUp 判定
                        if (markPressed != null) {
                            onMarkRepeat()
                            showOsd()
                            return@onKeyEvent true
                        }
                        when {
                            // 选集浮层里确认键直接切集
                            episodePanelVisible -> {
                                episodes.getOrNull(focusedEpisodeIndex)?.let { episode ->
                                    episodePanelVisible = false
                                    closePanels()
                                    onSelectEpisode(episode)
                                }
                            }
                            // 弹幕面板：上下键选子项，确认键改值（开关翻转、档位 +1、边距 +5%）。
                            // 左右键已交给进度条，这里必须能改值，否则字号/位置根本调不动
                            danmakuPanelVisible -> adjustDanmakuItem(1)
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
                                // 片头/片尾不展开面板：短按由 markPressed 分支处理，这里不可达
                                OsdSlot.Intro, OsdSlot.Outro -> Unit
                            }
                            // 没有面板在展开时，OK 键就是播放/暂停（用户明确要求的全局行为）
                            else -> togglePlay()
                        }
                        showOsd()
                        true
                    }
                    Key.DirectionLeft -> {
                        // 左右键在播放页只做一件事：拖动进度。
                        // 之前面板展开时左右键被面板吃掉，用户「开着面板想快进」按下去毫无反应，
                        // 面板里的选项也因此只能靠盲按——现在面板选项统一交给上下键。
                        seekBy(-SeekStepMs)
                        if (osdVisible) showOsd()
                        true
                    }
                    Key.DirectionRight -> {
                        seekBy(SeekStepMs)
                        if (osdVisible) showOsd()
                        true
                    }
                    Key.DirectionUp -> {
                        if (!movePanelFocus(-1)) {
                            val i = slots.indexOf(focusedSlot)
                            focusedSlot = slots[(i - 1 + slots.size) % slots.size]
                        }
                        showOsd()
                        true
                    }
                    Key.DirectionDown -> {
                        if (!movePanelFocus(1)) {
                            val i = slots.indexOf(focusedSlot)
                            focusedSlot = slots[(i + 1) % slots.size]
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