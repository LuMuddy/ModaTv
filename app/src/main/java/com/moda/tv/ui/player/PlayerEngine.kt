package com.moda.tv.ui.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import com.moda.tv.data.Api
import com.moda.tv.model.PlayerError

/**
 * 播放器内核抽象层。
 *
 * 上层（PlayerScreen / TvViewModel）只依赖这个接口，换内核不动 UI。
 *
 * 参数刻意全部用 Boolean / String / 原生类型，不引用 data.local 里的 PlayerCore / DecoderMode：
 * 那两个枚举由另一条并行任务维护，这里一旦 import 就形成编译期耦合，
 * 任一侧改动都会连锁失败。「core 字符串」「decoder 枚举 → hardware Boolean」的映射留在上层做。
 * 代价只是这里用裸 String/Boolean 表达语义，收益是两侧可以独立演进。
 */
interface PlayerEngine {

    /**
     * 起播。
     * [hls] 为 true 时按 HLS 处理：Exo 必须显式给 MIME 才会选 HLS 解析器，
     * IJK 走 ffmpeg 自己嗅探不需要区分，但仍保留该参数让上层代码只有一份分支。
     */
    fun start(url: String, hls: Boolean, startMs: Long, speed: Float)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun setSpeed(speed: Float)

    /** 0f..1f，按线性音量换算 */
    fun setVolume(volume: Float)

    /**
     * 只有 IJK 需要（它把画面渲染到 SurfaceHolder 上）。
     * 其它实现是空体，签名保留是为了让上层不用按内核分支调用。
     * 参数用 Any? 而不是 SurfaceHolder，是为了不让接口依赖 android.view 的渲染细节，
     * 实现类内部自己判定类型，传错就是空操作而不是崩溃。
     */
    fun attachSurface(holder: Any?)

    val positionMs: Long
    val durationMs: Long
    val bufferedMs: Long
    val isPlaying: Boolean

    /** 幂等；调用后 [listener] 被清空，不再有任何回调 */
    fun release()

    var listener: Listener?

    interface Listener {
        fun onPlayingChanged(playing: Boolean)
        fun onBufferingChanged(buffering: Boolean)
        fun onEnded()

        /**
         * 失败原因只给错误码，不给文案：内核层不该知道界面说什么语言，
         * 文案由上层按当前系统语言从 string 资源里取（ui/components/ErrorText.kt）。
         */
        fun onError(error: PlayerError)

        fun onVideoSizeChanged(width: Int, height: Int)
    }
}

/**
 * media3 错误码 → 结构化播放错误。
 *
 * 只分「解码失败」与「片源/网络失败」两类，因为用户在播放页能做的动作只有
 * 换内核、换解码方式、换清晰度、重试——比这更细的分类对着 UI 没有意义。
 * [codeName]（ERROR_CODE_*）只作为日志线索塞进 detail，不参与界面文案。
 */
@OptIn(UnstableApi::class)
private fun classifyExoError(code: Int, codeName: String): PlayerError = when (code) {
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    -> PlayerError.Codec(codeName)

    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
    PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    -> PlayerError.Source(codeName)

    else -> PlayerError.Unknown(codeName)
}

/**
 * IJK 的 what/extra → 结构化播放错误。
 *
 * what 里只有 MEDIA_ERROR_* 这几码有可行动的含义；SERVER_DIED / UNKNOWN 一律归 Unknown，
 * 因为用户对它们能做的事和「解码失败」「片源坏了」都不同，只多一句「换内核试试」没意义。
 * what/extra 原样塞进 detail，只进日志。
 */
private fun classifyIjkError(what: Int, extra: Int): PlayerError {
    val detail = "what=$what extra=$extra"
    return when (what) {
        IMediaPlayer.MEDIA_ERROR_IO,
        IMediaPlayer.MEDIA_ERROR_MALFORMED,
        IMediaPlayer.MEDIA_ERROR_UNSUPPORTED,
        IMediaPlayer.MEDIA_ERROR_TIMED_OUT,
        IMediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK,
        -> PlayerError.Source(detail)

        else -> PlayerError.Unknown(detail)
    }
}

/**
 * 引擎工厂。
 *
 * 创建逻辑收在这里而不是让上层直接 new，原因只有一个：
 * IJK 的类在 so 缺失时会抛 UnsatisfiedLinkError / NoClassDefFoundError，
 * 只有集中在一处做「探测 + 回退」才能保证这条路不漏，且上层永远拿得到一个能播的引擎。
 */
object PlayerEngines {

    private const val TAG = "PlayerEngines"

    /** IJK 主类全名：探测用字符串，避免在工厂里硬引用它的类字面量 */
    private const val IJK_PLAYER_CLASS = "tv.danmaku.ijk.media.player.IjkMediaPlayer"

    /**
     * 探测结果只算一次。
     * 原因是每次建引擎都重新探测要读 so + 走反射，换内核时会明显卡顿；
     * 而「这个包里有没有 IJK」在一个进程生命周期内不会变。
     * 注意 by lazy 默认同步，多线程首次访问也只会执行一次。
     */
    private val ijkAvailable: Boolean by lazy { probeIjk() }

    private fun probeIjk(): Boolean = try {
        Class.forName(IJK_PLAYER_CLASS, true, PlayerEngines::class.java.classLoader)
        true
    } catch (t: Throwable) {
        // NoClassDefFoundError / UnsatisfiedLinkError 都不是「异常路径」，
        // 只是说明这次打包没带 IJK 或 ABI 被裁掉，用 warn 记录即可
        Log.w(TAG, "IJK 不可用：${t.javaClass.simpleName}: ${t.message}")
        false
    }

    /**
     * 当前包里 IJK 是否真的可用。
     *
     * 「类能加载」不等于「能播」：classes.jar 一定会被 dex 进去，
     * 但 jni 目录下的 so 可能被 abiFilters 裁掉或图片里压根没解压，
     * 那种情况下 new IjkMediaPlayer() 会在 System.loadLibrary 处抛 UnsatisfiedLinkError。
     * 所以这里实际构造一次再立刻 release —— 这是唯一能证明「三件 so 都齐」的检测方式，
     * 且开销只付一次。
     */
    fun isIjkAvailable(): Boolean {
        if (!ijkAvailable) return false
        return nativeProbe
    }

    private val nativeProbe: Boolean by lazy {
        try {
            val cls = Class.forName(IJK_PLAYER_CLASS)
            val instance = cls.getConstructor().newInstance()
            cls.getMethod("release").invoke(instance)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "IJK so 加载失败，判定为不可用：${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * [core]: exo 或 ijk，未知值一律回落 exo（宁可播得保守，也不要黑屏）
     * [hardware]: true 优先硬解，false 尽量软解
     */
    fun create(context: Context, core: String, hardware: Boolean): PlayerEngine {
        if (!core.equals("ijk", ignoreCase = true)) return ExoEngine(context, hardware)

        if (!isIjkAvailable()) {
            Log.w(TAG, "请求 IJK 但当前包内不可用，回退 ExoPlayer")
            return ExoEngine(context, hardware)
        }
        return try {
            IjkEngine(context, hardware)
        } catch (t: Throwable) {
            // 兜住「探测通过但真正构造仍失败」的极小窗口（例如 so 被杀毒/加固拦截），
            // 这里绝不能让异常冒到 UI 层变成闪退
            Log.e(TAG, "IJK 初始化失败，回退 ExoPlayer", t)
            ExoEngine(context, hardware)
        }
    }
}

/**
 * ExoPlayer 实现。
 *
 * 数据源配置照抄 PlayerScreen 里原有的 buildPlayer()：
 * OkHttpDataSource + DefaultDataSource，全应用共用 Api.http 这一个 OkHttpClient。
 * 电视盒子的默认 HttpURLConnection 在 TLS 与重定向上差异很大，大文件 Range 请求
 * 也没必要再开一套连接池，所以必须保持一致。
 */
@OptIn(UnstableApi::class)
internal class ExoEngine(
    context: Context,
    private val hardware: Boolean,
) : PlayerEngine {

    private companion object {
        const val TAG = "ExoEngine"
    }

    private val player: ExoPlayer

    override var listener: PlayerEngine.Listener? = null

    /** release 后置 true，所有回调和 setter 直接短路，保证释放后不再有任何副作用 */
    private var released = false

    private var pendingSpeed = 1f

    init {
        val okHttpFactory = OkHttpDataSource.Factory(Api.http)
        val dataSourceFactory = DefaultDataSource.Factory(context, okHttpFactory)

        /**
         * 软硬解的落点。
         *
         * media3 的视频解码只有 MediaCodec 一条路：ExoPlayer 本身不打包任何软件解码器，
         * 真正的软解只能来自 media3-decoder-ffmpeg 这个独立扩展（要自己编 .so，几十 MB）。
         * 所以这里说的「软解」只能是「在 MediaCodec 里挑系统自带的软件编解码器」
         * （c2.android.* / OMX.google.*，AOSP 里确实存在），而不是 ffmpeg 软解。
         *
         * 这个区别必须如实告知上层：选软解并不等于「一定能解所有格式」，
         * 遇到系统没带软件解码器的格式仍会失败，那种情况需要另配 ffmpeg 扩展。
         */
        val selector = DecoderSelector(preferHardware = hardware)
        val renderersFactory = DefaultRenderersFactory(context)
            .setMediaCodecSelector(selector)
            // 硬解模式下允许回退到更低优先级的解码器；软解模式下也同样打开，
            // 否则一旦系统没有软件解码器会直接失败（黑屏），而不是退到一个能用的解码器上
            .setEnableDecoderFallback(true)

        player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (released) return
                listener?.onPlayingChanged(isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (released) return
                when (playbackState) {
                    Player.STATE_BUFFERING -> listener?.onBufferingChanged(true)
                    Player.STATE_READY -> listener?.onBufferingChanged(false)
                    Player.STATE_ENDED -> listener?.onEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (released) return
                // errorCodeName 比 message 更稳定；message 里常带一长串 URL 与签名，
                // 那类技术信息只进日志，交给 UI 的只有错误码
                Log.w(TAG, "播放失败：${error.errorCodeName}: ${error.message}")
                listener?.onError(classifyExoError(error.errorCode, error.errorCodeName))
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (released) return
                if (videoSize.width > 0 && videoSize.height > 0) {
                    listener?.onVideoSizeChanged(videoSize.width, videoSize.height)
                }
            }
        })
    }

    override fun start(url: String, hls: Boolean, startMs: Long, speed: Float) {
        if (released) return
        // HLS 必须显式声明 MIME：扩展名常常是 .m3u8 但也可以完全没有后缀，
        // 让 Exo 靠内容嗅探会在部分服务端上失败
        val item = if (hls) {
            MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        } else {
            MediaItem.fromUri(url)
        }
        player.setMediaItem(item)
        pendingSpeed = speed
        player.setPlaybackSpeed(speed)
        if (startMs > 0L) player.seekTo(startMs)
        player.prepare()
        player.play()
    }

    override fun play() {
        if (released) return
        player.play()
    }

    override fun pause() {
        if (released) return
        player.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun setSpeed(speed: Float) {
        if (released) return
        pendingSpeed = speed
        player.setPlaybackSpeed(speed)
    }

    override fun setVolume(volume: Float) {
        if (released) return
        player.volume = volume.coerceIn(0f, 1f)
    }

    /** Exo 的画面由 PlayerView 自己挂 Surface，这里没有可做的事 */
    override fun attachSurface(holder: Any?) = Unit

    override val positionMs: Long
        get() = if (released) 0L else player.currentPosition.coerceAtLeast(0L)

    override val durationMs: Long
        get() {
            if (released) return 0L
            val d = player.duration
            // C.TIME_UNSET 是个极小的负数，直接喂给 UI 会算出离谱的进度条
            return if (d == C.TIME_UNSET || d < 0L) 0L else d
        }

    override val bufferedMs: Long
        get() = if (released) 0L else player.bufferedPosition.coerceAtLeast(0L)

    override val isPlaying: Boolean
        get() = !released && player.isPlaying

    override fun release() {
        // 幂等：换档位/重试/退出页面都可能重复调用
        if (released) return
        released = true
        // 先清 listener 再 release：release 期间可能同步派发 STATE_IDLE 之类的回调，
        // 那时上层可能已经在销毁，回调进去就是空指针或越界写
        listener = null
        try {
            player.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release 异常（已忽略）：${t.message}")
        }
    }
}

/**
 * 按软/硬解偏好重排 MediaCodec 列表。
 *
 * 为什么不直接「过滤掉硬解」：很多盒子只带硬解 MediaCodec，
 * 把硬解全部滤掉会让列表为空，Exo 随后抛出解码器初始化失败 —— 表现就是黑屏。
 * 所以策略是「优先排序」，只有在软解模式下、且确实存在软件解码器时才真正收窄候选，
 * 否则原样返回并在日志里说明降级事实，绝不伪造软解能力。
 */
@OptIn(UnstableApi::class)
private class DecoderSelector(private val preferHardware: Boolean) : MediaCodecSelector {

    private companion object {
        const val TAG = "DecoderSelector"
        /** 每条 mime 只提示一次，避免 300ms tick 反复刷 log */
        val warned = HashSet<String>()
    }

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val all = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        if (all.isEmpty()) return all

        return if (preferHardware) {
            // 硬解优先：hardwareAccelerated 的排前面，其余保持原有优先级作为回退链
            all.sortedByDescending { it.hardwareAccelerated }
        } else {
            val software = all.filter { it.softwareOnly }
            if (software.isEmpty()) {
                // 如实降级：本机没有可用的软件解码器（media3 未打包 ffmpeg 扩展时这很常见），
                // 退回硬解而不是让列表为空把播放器搞崩
                if (warned.add(mimeType)) {
                    Log.w(
                        TAG,
                        "$mimeType 无可用软件解码器，降级为硬解。" +
                            "若要真软解需打包 media3-decoder-ffmpeg 扩展并启用 EXTENSION_RENDERER_MODE",
                    )
                }
                all.sortedByDescending { it.hardwareAccelerated }
            } else {
                software
            }
        }
    }
}

/**
 * IJK 实现。
 *
 * 所有对 IjkMediaPlayer 的调用都可能因为 so 缺失而抛 UnsatisfiedLinkError，
 * 所以每个入口都包了 try/catch，失败一律转成 onError 回调而不是崩掉播放页。
 */
internal class IjkEngine(
    context: Context,
    private val hardware: Boolean,
) : PlayerEngine {

    private companion object {
        const val TAG = "IjkEngine"

        /**
         * IJK 没有进度回调，只能自己轮询。
         * 500ms 是需求给定的值：比 300ms UI tick 略慢，位置读数会有最多半秒的滞后，
         * 但换内核时这点滞后无感，却能把主线程唤醒次数压下来（盒子的 CPU 很弱）。
         */
        const val POLL_INTERVAL_MS = 500L
    }

    private val appContext: Context = context.applicationContext

    private var player: IjkMediaPlayer? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override var listener: PlayerEngine.Listener? = null

    private var released = false

    /** 缓存的读数：轮询线程写，UI 线程读，用 volatile 保证可见性 */
    @Volatile private var cachedPosition = 0L
    @Volatile private var cachedDuration = 0L
    @Volatile private var cachedBuffered = 0L
    @Volatile private var cachedPlaying = false

    private var pendingSpeed = 1f
    private var pendingVolume = 1f
    private var pendingSeek = -1L
    private var autoStart = true

    /** 轮询任务自己续期，release 后不再重排，避免线程/消息队列泄漏 */
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (released) return
            refreshFromPlayer()
            if (!released) mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    init {
        val p = IjkMediaPlayer()
        player = p
        configure(p)
        bindListeners(p)
    }

    private fun configure(p: IjkMediaPlayer) {
        // 软硬解开关：mediacodec=1 走 MediaCodec 硬解，0 走 ffmpeg 自带的软解。
        // 这是 IJK 相对 ExoPlayer 的真正优势——它的软解是打包在 libijkffmpeg.so 里的，
        // 不依赖系统是否提供软件解码器，所以电视盒子上格式兼容性明显更好。
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", if (hardware) 1L else 0L)
        // 硬解时让 IJK 自己处理分辨率切换，否则换台会花屏/拉伸
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", if (hardware) 1L else 0L)
        // 丢帧策略：盒子解码跟不上时宁可丢帧也不要让音画不同步越拖越远
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L)
        // 由本类显式调 start()，避免 prepared 后立刻出声再被 pause 打断
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0L)
        // 网络超时（微秒）：盒子连 CDN 偶发慢，默认值偏短
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 15_000_000L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1L)
        // 关键：让 ffmpeg 在 http 头里带 Range，否则渐进式 MP4 无法 seek
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 1L)
        p.setScreenOnWhilePlaying(true)
    }

    private fun bindListeners(p: IjkMediaPlayer) {
        p.setOnPreparedListener { mp ->
            if (released) return@setOnPreparedListener
            cachedDuration = mp.duration.coerceAtLeast(0L)
            // 倍速/音量必须在 prepared 之后才生效，之前设置会被 IJK 丢掉。
            // 注意监听器拿到的是 IMediaPlayer 接口，而 setSpeed 只声明在 IjkMediaPlayer 上，
            // 所以这里必须走自己持有的实现类实例，不能用回调参数 mp
            player?.setSpeed(pendingSpeed)
            mp.setVolume(pendingVolume, pendingVolume)
            if (pendingSeek >= 0L) {
                mp.seekTo(pendingSeek)
                pendingSeek = -1L
            }
            if (autoStart) mp.start() else {
                cachedPlaying = false
                listener?.onPlayingChanged(false)
            }
            listener?.onBufferingChanged(false)
            mainHandler.removeCallbacks(pollRunnable)
            mainHandler.post(pollRunnable)
        }

        p.setOnCompletionListener {
            if (released) return@setOnCompletionListener
            cachedPlaying = false
            mainHandler.removeCallbacks(pollRunnable)
            listener?.onPlayingChanged(false)
            listener?.onEnded()
        }

        p.setOnErrorListener { _, what, extra ->
            if (released) return@setOnErrorListener true
            mainHandler.removeCallbacks(pollRunnable)
            cachedPlaying = false
            listener?.onBufferingChanged(false)
            // what/extra 是技术串，只进日志；UI 只收错误码
            Log.w(TAG, "IJK 播放失败 what=$what extra=$extra")
            listener?.onError(classifyIjkError(what, extra))
            // 返回 true 表示已处理，避免 IJK 再抛到自己的默认分支里
            true
        }

        p.setOnInfoListener { _, what, _ ->
            if (released) return@setOnInfoListener true
            when (what) {
                IMediaPlayer.MEDIA_INFO_BUFFERING_START -> listener?.onBufferingChanged(true)
                IMediaPlayer.MEDIA_INFO_BUFFERING_END -> listener?.onBufferingChanged(false)
                // 真正出画才算 playing：prepared 阶段还没渲染，UI 不该收工
                IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START,
                IMediaPlayer.MEDIA_INFO_VIDEO_DECODED_START,
                -> {
                    cachedPlaying = true
                    listener?.onPlayingChanged(true)
                }
            }
            true
        }

        p.setOnVideoSizeChangedListener { _, width, height, _, _ ->
            if (released) return@setOnVideoSizeChangedListener
            if (width > 0 && height > 0) listener?.onVideoSizeChanged(width, height)
        }

        p.setOnBufferingUpdateListener { _, percent ->
            if (released) return@setOnBufferingUpdateListener
            // percent 是相对总时长的百分比；duration 可能还没就绪，此时先不算
            if (percent in 0..100 && cachedDuration > 0L) {
                cachedBuffered = cachedDuration * percent / 100L
            }
        }
    }

    private fun refreshFromPlayer() {
        val p = player ?: return
        if (released) return
        try {
            cachedPosition = p.currentPosition.coerceAtLeast(0L)
            val d = p.duration
            if (d > 0L) cachedDuration = d
            val playing = p.isPlaying
            if (playing != cachedPlaying) {
                cachedPlaying = playing
                listener?.onPlayingChanged(playing)
            }
            // IJK 只给「已缓存时长」而不给缓存到的绝对位置，所以按 当前位置+已缓存 估算；
            // 这是近似值，UI 只拿它画缓冲条，误差几百毫秒无所谓
            val cachedAhead = maxOf(p.videoCachedDuration, p.audioCachedDuration)
            if (cachedAhead > 0L && cachedDuration > 0L) {
                cachedBuffered = (cachedPosition + cachedAhead).coerceAtMost(cachedDuration)
            }
        } catch (t: Throwable) {
            // 轮询里绝不能抛：player 可能正好在 release，逐帧 log 会刷屏，只记一次级别即可
            Log.w(TAG, "刷新状态失败：${t.message}")
        }
    }

    override fun start(url: String, hls: Boolean, startMs: Long, speed: Float) {
        if (released) return
        val p = player ?: return
        // hls 参数在这里无需分支：IJK 走 ffmpeg 的 applehttp 协议自己嗅探，
        // 显式指定 format 反而会把非 m3u8 的兜底流打死
        pendingSpeed = speed
        pendingSeek = if (startMs > 0L) startMs else -1L
        autoStart = true
        try {
            p.setDataSource(appContext, android.net.Uri.parse(url))
            p.prepareAsync()
        } catch (t: Throwable) {
            // setDataSource/prepareAsync 抛说明连片源都没接上（地址失效、so 缺失等），
            // 归到片源失败：用户在播放页能做的只有换清晰度 / 换内核 / 重试
            Log.w(TAG, "IJK 起播失败：${t.javaClass.simpleName}: ${t.message}")
            listener?.onError(PlayerError.Source(t.javaClass.simpleName))
        }
    }

    override fun play() {
        if (released) return
        autoStart = true
        try {
            player?.start()
        } catch (t: Throwable) {
            Log.w(TAG, "play 失败：${t.message}")
        }
    }

    override fun pause() {
        if (released) return
        autoStart = false
        try {
            player?.pause()
            cachedPlaying = false
            listener?.onPlayingChanged(false)
        } catch (t: Throwable) {
            Log.w(TAG, "pause 失败：${t.message}")
        }
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        val p = player ?: return
        try {
            p.seekTo(positionMs.coerceAtLeast(0L))
        } catch (t: Throwable) {
            Log.w(TAG, "seek 失败：${t.message}")
        }
    }

    override fun setSpeed(speed: Float) {
        if (released) return
        pendingSpeed = speed
        try {
            player?.setSpeed(speed)
        } catch (t: Throwable) {
            Log.w(TAG, "setSpeed 失败：${t.message}")
        }
    }

    override fun setVolume(volume: Float) {
        if (released) return
        pendingVolume = volume.coerceIn(0f, 1f)
        try {
            player?.setVolume(pendingVolume, pendingVolume)
        } catch (t: Throwable) {
            Log.w(TAG, "setVolume 失败：${t.message}")
        }
    }

    /** SurfaceHolder 来自上层 Compose 的 AndroidView；类型不对就当没传，不崩 */
    override fun attachSurface(holder: Any?) {
        if (released) return
        val surfaceHolder = holder as? SurfaceHolder ?: return
        try {
            player?.setDisplay(surfaceHolder)
        } catch (t: Throwable) {
            Log.w(TAG, "attachSurface 失败：${t.message}")
        }
    }

    override val positionMs: Long get() = if (released) 0L else cachedPosition
    override val durationMs: Long get() = if (released) 0L else cachedDuration
    override val bufferedMs: Long get() = if (released) 0L else cachedBuffered
    override val isPlaying: Boolean get() = !released && cachedPlaying

    override fun release() {
        if (released) return
        released = true
        // 顺序很重要：先停轮询（否则它会在 release 之后再去读 native 指针），
        // 再清 listener（release 过程可能派发 onError），最后才真正释放 native 资源
        mainHandler.removeCallbacks(pollRunnable)
        listener = null
        val p = player
        player = null
        try {
            p?.setOnPreparedListener(null)
            p?.setOnCompletionListener(null)
            p?.setOnErrorListener(null)
            p?.setOnInfoListener(null)
            p?.setOnVideoSizeChangedListener(null)
            p?.setOnBufferingUpdateListener(null)
            p?.setDisplay(null)
            p?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release 异常（已忽略）：${t.message}")
        }
    }
}
