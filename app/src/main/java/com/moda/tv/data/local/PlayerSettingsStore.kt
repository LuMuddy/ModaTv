package com.moda.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 倍速档位；label 直接进 OSD，浮点格式化只在这里出现一次 */
data class SpeedOption(val value: Float, val label: String)

val PlayerSpeedOptions: List<SpeedOption> = listOf(
    SpeedOption(0.5f, "0.5x"),
    SpeedOption(0.75f, "0.75x"),
    SpeedOption(1.0f, "1.0x"),
    SpeedOption(1.25f, "1.25x"),
    SpeedOption(1.5f, "1.5x"),
    SpeedOption(2.0f, "2.0x"),
)

/** 弹幕字号档位：sp 值同时是 Canvas 排版用的实际字号 */
data class DanmakuSizeOption(val sizeSp: Float, val label: String)

val DanmakuSizeOptions: List<DanmakuSizeOption> = listOf(
    DanmakuSizeOption(18f, "小"),
    DanmakuSizeOption(22f, "标准"),
    DanmakuSizeOption(28f, "大"),
    DanmakuSizeOption(34f, "特大"),
)

/** 弹幕垂直区域：全部换算成屏幕高度的上/下占比，Custom 用可调的上下边距 */
enum class DanmakuRegion(val label: String) {
    FullScreen("全屏"),
    TopThird("上 1/3"),
    MiddleHalf("中部 1/2"),
    BottomThird("下 1/3"),
    UpperHalf("仅上半屏"),
    Custom("自定义边距"),
}

val DanmakuRegions: List<DanmakuRegion> = DanmakuRegion.entries

/**
 * 画面比例模式。
 * Fit/Fill/Zoom 直接映射到 PlayerView 自带的 resizeMode；
 * Ratio16x9 / Ratio4x3 是 PlayerView 不支持的档位，做法见 PlayerScreen 里的注释。
 */
enum class AspectMode(val label: String) {
    Fit("原始比例"),
    Fill("拉伸"),
    Zoom("铺满"),
    Ratio16x9("16:9"),
    Ratio4x3("4:3"),
}

val AspectModes: List<AspectMode> = AspectMode.entries

/** 边距调整步进与单侧上限（百分比）；上下加起来至少要给画面留 30% 的弹幕区 */
const val MarginStepPercent = 5f
const val MarginMaxPercent = 70f
const val MarginCombinedMaxPercent = 70f

/**
 * 播放器设置。倍速、弹幕开关、字号、位置全部可自定义并持久化，
 * 下次进播放页沿用上次的值。
 */
data class PlayerSettings(
    val speed: Float = 1.0f,
    val danmakuEnabled: Boolean = true,
    val danmakuSizeSp: Float = 22f,
    val danmakuRegion: DanmakuRegion = DanmakuRegion.FullScreen,
    val danmakuTopPercent: Float = 0f,
    val danmakuBottomPercent: Float = 0f,
    val aspectMode: AspectMode = AspectMode.Fit,
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

    val sizeLabel: String
        get() = DanmakuSizeOptions.firstOrNull { it.sizeSp == danmakuSizeSp }?.label
            ?: "标准"
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
    )
}
