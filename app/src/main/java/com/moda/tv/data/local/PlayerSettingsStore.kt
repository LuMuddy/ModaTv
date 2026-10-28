package com.moda.tv.data.local

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.moda.tv.R
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 倍速档位；label 直接进 OSD，浮点格式化只在这里出现一次。
 * 这一档是全语言通用的数字（"0.5x"），没有翻译需求，所以不进资源表。
 */
data class SpeedOption(val value: Float, val label: String)

val PlayerSpeedOptions: List<SpeedOption> = listOf(
    SpeedOption(0.5f, "0.5x"),
    SpeedOption(0.75f, "0.75x"),
    SpeedOption(1.0f, "1.0x"),
    SpeedOption(1.25f, "1.25x"),
    SpeedOption(1.5f, "1.5x"),
    SpeedOption(2.0f, "2.0x"),
)

/**
 * 弹幕字号档位：sp 值同时是 Canvas 排版用的实际字号。
 * 存资源 id 而不是字符串：数据层不产出文案，文案要跟系统语言走。
 * 注解写成 @param: 是显式指定目标——构造器参数上的 @StringRes 目标未定，编译器会报 KT-73255 告警。
 */
data class DanmakuSizeOption(val sizeSp: Float, @param:StringRes val labelRes: Int)

val DanmakuSizeOptions: List<DanmakuSizeOption> = listOf(
    DanmakuSizeOption(18f, R.string.danmaku_size_small),
    DanmakuSizeOption(22f, R.string.danmaku_size_standard),
    DanmakuSizeOption(28f, R.string.danmaku_size_large),
    DanmakuSizeOption(34f, R.string.danmaku_size_xlarge),
)

/** 弹幕垂直区域：全部换算成屏幕高度的上/下占比，Custom 用可调的上下边距 */
enum class DanmakuRegion(@param:StringRes val labelRes: Int) {
    FullScreen(R.string.danmaku_region_full),
    TopThird(R.string.danmaku_region_top_third),
    MiddleHalf(R.string.danmaku_region_middle_half),
    BottomThird(R.string.danmaku_region_bottom_third),
    UpperHalf(R.string.danmaku_region_upper_half),
    Custom(R.string.danmaku_region_custom),
}

val DanmakuRegions: List<DanmakuRegion> = DanmakuRegion.entries

/**
 * 画面比例模式。
 * Fit/Fill/Zoom 直接映射到 PlayerView 自带的 resizeMode；
 * Ratio16x9 / Ratio4x3 是 PlayerView 不支持的档位，做法见 PlayerScreen 里的注释。
 */
enum class AspectMode(@param:StringRes val labelRes: Int) {
    Fit(R.string.aspect_fit),
    Fill(R.string.aspect_fill),
    Zoom(R.string.aspect_zoom),
    Ratio16x9(R.string.aspect_16x9),
    Ratio4x3(R.string.aspect_4x3),
}

val AspectModes: List<AspectMode> = AspectMode.entries

/** 边距调整步进与单侧上限（百分比）；上下加起来至少要给画面留 30% 的弹幕区 */
const val MarginStepPercent = 5f
const val MarginMaxPercent = 70f
const val MarginCombinedMaxPercent = 70f

/**
 * 播放内核。
 *
 * 这里是**默认值**：播放页里改的是「本剧覆盖」，存在 [PlaybackOverrideStore]，
 * 删历史时跟着一起清。两处不要混用——把播放页的改动写进这里，
 * 会让一次临时切换变成所有剧的新默认。
 */
enum class PlayerCore(@param:StringRes val labelRes: Int) {
    Exo(R.string.player_core_exo),
    Ijk(R.string.player_core_ijk),
}

/**
 * 解码方式。硬解省电、4K 更流畅；软解兼容性更好（老电视 / 编码特殊的老片）。
 * 具体怎么把这两个值交给播放器由播放侧决定，存储层只负责原样保存与回读。
 * 与 [PlayerCore] 一样，这里的值是默认值，单剧覆盖见 [PlaybackOverrideStore]。
 */
enum class DecoderMode(@param:StringRes val labelRes: Int) {
    Hardware(R.string.decoder_hardware),
    Software(R.string.decoder_software),
}

/**
 * 播放器设置。倍速、弹幕开关、字号、位置全部可自定义并持久化，
 * 下次进播放页沿用上次的值。
 */
data class PlayerSettings(
    val speed: Float = 1.0f,
    // 默认开：弹幕是这个播放器的主要看点之一，默认关等于用户每次都要先去 OSD 打开一次
    val danmakuEnabled: Boolean = true,
    val danmakuSizeSp: Float = 22f,
    val danmakuRegion: DanmakuRegion = DanmakuRegion.FullScreen,
    val danmakuTopPercent: Float = 0f,
    val danmakuBottomPercent: Float = 0f,
    val aspectMode: AspectMode = AspectMode.Fit,
    val playerCore: PlayerCore = PlayerCore.Exo,
    val decoderMode: DecoderMode = DecoderMode.Hardware,
    /**
     * 每次起播应用的默认倍速，与 [speed]（当前会话的倍速）刻意分开：
     * 用户在 OSD 里临时调到 2x 追一集，不该让下一集、下次开机也从 2x 开始。
     * 合法档位与 [PlayerSpeedOptions] 同一份，读回时用 minByOrNull 夹到最近档。
     */
    val defaultSpeed: Float = 1.0f,
) {
    /** 弹幕可绘制区域从屏幕顶部让出的比例 */
    val topRatio: Float
        get() = when (danmakuRegion) {
            DanmakuRegion.FullScreen, DanmakuRegion.TopThird, DanmakuRegion.UpperHalf -> 0f
            DanmakuRegion.MiddleHalf -> 0.25f
            DanmakuRegion.BottomThird -> 2f / 3f
            DanmakuRegion.Custom -> danmakuTopPercent / 100f
        }

    /** 弹幕可绘制区域从屏幕底部让出的比例 */
    val bottomRatio: Float
        get() = when (danmakuRegion) {
            DanmakuRegion.FullScreen, DanmakuRegion.BottomThird -> 0f
            DanmakuRegion.TopThird -> 2f / 3f
            DanmakuRegion.MiddleHalf -> 0.25f
            DanmakuRegion.UpperHalf -> 0.5f
            DanmakuRegion.Custom -> danmakuBottomPercent / 100f
        }

    val speedLabel: String
        get() = PlayerSpeedOptions.firstOrNull { it.value == speed }?.label
            ?: PlayerSpeedOptions.first().label

    val sizeLabelRes: Int
        get() = DanmakuSizeOptions.firstOrNull { it.sizeSp == danmakuSizeSp }?.labelRes
            ?: R.string.danmaku_size_standard
}

interface PlayerSettingsStore {
    val settings: Flow<PlayerSettings>
    suspend fun current(): PlayerSettings
    suspend fun save(settings: PlayerSettings)
}

// 独立于收藏/历史设置的 DataStore 文件，委托保证同一 context 复用同一实例
private val Context.playerSettingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "moda_player_settings")

/**
 * 键与默认值：
 * playback_speed = 1.0 / danmaku_enabled = true / danmaku_font_size_sp = 22
 * danmaku_region = FullScreen / danmaku_top_percent = 0 / danmaku_bottom_percent = 0
 * aspect_mode = Fit
 */
private object Keys {
    val SPEED = floatPreferencesKey("playback_speed")
    val DANMAKU_ENABLED = booleanPreferencesKey("danmaku_enabled")
    val DANMAKU_SIZE = floatPreferencesKey("danmaku_font_size_sp")
    val DANMAKU_REGION = stringPreferencesKey("danmaku_region")
    val DANMAKU_TOP = floatPreferencesKey("danmaku_top_percent")
    val DANMAKU_BOTTOM = floatPreferencesKey("danmaku_bottom_percent")
    val ASPECT_MODE = stringPreferencesKey("aspect_mode")

    // 新增键一律「缺失 = 默认值」：旧版本升级上来的用户没有这些键，
    // 读不到时落到 data class 的默认值上，不需要任何迁移代码
    val PLAYER_CORE = stringPreferencesKey("player_core")
    val DECODER_MODE = stringPreferencesKey("decoder_mode")
    val DEFAULT_SPEED = floatPreferencesKey("default_speed")
}

object PlayerSettingsStoreImpl {
    @Volatile
    private var instance: PlayerSettingsStore? = null

    fun create(context: Context): PlayerSettingsStore =
        instance ?: synchronized(this) {
            instance ?: DataStorePlayerSettingsStore(context.applicationContext.playerSettingsDataStore)
                .also { instance = it }
        }
}

private class DataStorePlayerSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : PlayerSettingsStore {

    override val settings: Flow<PlayerSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .distinctUntilChanged()

    override suspend fun current(): PlayerSettings = settings.first()

    override suspend fun save(settings: PlayerSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.SPEED] = settings.speed
            prefs[Keys.DANMAKU_ENABLED] = settings.danmakuEnabled
            prefs[Keys.DANMAKU_SIZE] = settings.danmakuSizeSp
            prefs[Keys.DANMAKU_REGION] = settings.danmakuRegion.name
            prefs[Keys.DANMAKU_TOP] = settings.danmakuTopPercent
            prefs[Keys.DANMAKU_BOTTOM] = settings.danmakuBottomPercent
            prefs[Keys.ASPECT_MODE] = settings.aspectMode.name
            prefs[Keys.PLAYER_CORE] = settings.playerCore.name
            prefs[Keys.DECODER_MODE] = settings.decoderMode.name
            prefs[Keys.DEFAULT_SPEED] = settings.defaultSpeed
        }
    }
}

/** 反序列化一律夹到合法区间：旧版本写坏的键不该让播放页崩掉 */
private fun Preferences.toSettings(): PlayerSettings {
    val speed = this[Keys.SPEED] ?: 1.0f
    val size = this[Keys.DANMAKU_SIZE] ?: 22f
    val regionName = this[Keys.DANMAKU_REGION]
    return PlayerSettings(
        speed = PlayerSpeedOptions.minByOrNull { kotlin.math.abs(it.value - speed) }?.value ?: 1.0f,
        danmakuEnabled = this[Keys.DANMAKU_ENABLED] ?: true,
        danmakuSizeSp = DanmakuSizeOptions.minByOrNull { kotlin.math.abs(it.sizeSp - size) }?.sizeSp ?: 22f,
        danmakuRegion = DanmakuRegion.entries.firstOrNull { it.name == regionName }
            ?: DanmakuRegion.FullScreen,
        danmakuTopPercent = (this[Keys.DANMAKU_TOP] ?: 0f).coerceIn(0f, MarginMaxPercent),
        danmakuBottomPercent = (this[Keys.DANMAKU_BOTTOM] ?: 0f).coerceIn(0f, MarginMaxPercent),
        aspectMode = AspectMode.entries.firstOrNull { it.name == this[Keys.ASPECT_MODE] }
            ?: AspectMode.Fit,
        // 枚举按 name 匹配，匹配不上（改过枚举 / 脏数据）就回落默认值，绝不抛
        playerCore = PlayerCore.entries.firstOrNull { it.name == this[Keys.PLAYER_CORE] }
            ?: PlayerCore.Exo,
        decoderMode = DecoderMode.entries.firstOrNull { it.name == this[Keys.DECODER_MODE] }
            ?: DecoderMode.Hardware,
        // 倍速必须落在合法档位上：手写 SharedPreferences 或旧版本可能留下 1.7x 这种值，
        // 直接用会让 OSD 的选中态没有一项匹配（cycleSpeed 里 indexOfFirst 返回 -1）
        defaultSpeed = PlayerSpeedOptions
            .minByOrNull { kotlin.math.abs(it.value - (this[Keys.DEFAULT_SPEED] ?: 1.0f)) }
            ?.value ?: 1.0f,
    )
}
