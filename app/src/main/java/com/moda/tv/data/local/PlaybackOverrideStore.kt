package com.moda.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

/**
 * 一部剧在播放页里单独设过的内核 / 硬软解 / 弹幕开关。
 *
 * 字段都可空，null 表示「跟随设置页的默认值」——这是本文件存在的前提：
 * 播放页里改一次不该把所有剧一起改掉，也不该让这部剧从此钉死在某个值上再也回不去。
 */
data class PlaybackOverride(
    val core: PlayerCore? = null,
    val decoder: DecoderMode? = null,
    val danmakuEnabled: Boolean? = null,
) {
    val isEmpty: Boolean get() = core == null && decoder == null && danmakuEnabled == null
}

/**
 * 按 cid 存播放页的内核 / 解码覆盖。
 *
 * 为什么单开一个 DataStore 而不是塞进 PlayerSettings：
 *  · PlayerSettings 是「默认值」，全局唯一一份，被播放页改写就失去了默认值的意义；
 *  · 覆盖是每部剧一条，生命周期跟着观看记录走（删历史就删），和全局设置不是同一个维度。
 */
interface PlaybackOverrideStore {
    /** 没有记录 / 读失败一律返回空覆盖，播放页继续用默认值 */
    suspend fun get(cid: String): PlaybackOverride

    /** 两项都为空 = 删掉这条记录（恢复默认 = 删键）；否则整条覆盖写入并刷新 LRU 时间 */
    suspend fun save(cid: String, override: PlaybackOverride)

    /** 删一部剧的覆盖：与「删观看历史」联动 */
    suspend fun remove(cid: String)

    /** 清空全部：与「清空观看历史」联动 */
    suspend fun clear()

    companion object {
        /**
         * 条数上限。看完的剧会移出历史但**不**清覆盖（用户可能从「已完成」回看），
         * 于是这里必须有界，否则这是一张只增不减的表。取值与历史一致，够用且好解释。
         */
        const val MAX = 60
    }
}

// 与 player_settings / intro_outro 并列的又一个 DataStore，委托保证同一 context 复用一个实例
private val Context.playbackOverrideDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "moda_playback_override")

/**
 * 键名带 cid：与 IntroOutroStore 同一套做法，省掉一整套 JSON 编解码，
 * 也不会因为 cid 里有特殊字符而解析失败。枚举落盘存 name，不存中文 label。
 */
private const val CORE_PREFIX = "core_"
private const val DECODER_PREFIX = "decoder_"
private const val DANMAKU_PREFIX = "danmaku_"
private const val TS_PREFIX = "ts_"

private fun coreKey(cid: String) = stringPreferencesKey(CORE_PREFIX + cid)
private fun decoderKey(cid: String) = stringPreferencesKey(DECODER_PREFIX + cid)
private fun danmakuKey(cid: String) = booleanPreferencesKey(DANMAKU_PREFIX + cid)
private fun tsKey(cid: String) = longPreferencesKey(TS_PREFIX + cid)

object PlaybackOverrideStoreImpl {
    @Volatile
    private var instance: PlaybackOverrideStore? = null

    fun create(context: Context): PlaybackOverrideStore =
        instance ?: synchronized(this) {
            instance ?: DataStorePlaybackOverrideStore(context.applicationContext.playbackOverrideDataStore)
                .also { instance = it }
        }
}

private class DataStorePlaybackOverrideStore(
    private val dataStore: DataStore<Preferences>,
) : PlaybackOverrideStore {

    override suspend fun get(cid: String): PlaybackOverride {
        if (cid.isBlank()) return PlaybackOverride()
        val prefs = try {
            dataStore.data
                .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                .first()
        } catch (e: Exception) {
            return PlaybackOverride()
        }
        return PlaybackOverride(
            // 认不出的枚举值当作「没设过」：改过枚举名不该让播放页进错误态
            core = prefs[coreKey(cid)]?.let { name ->
                PlayerCore.entries.firstOrNull { it.name == name }
            },
            decoder = prefs[decoderKey(cid)]?.let { name ->
                DecoderMode.entries.firstOrNull { it.name == name }
            },
            danmakuEnabled = prefs[danmakuKey(cid)],
        )
    }

    override suspend fun save(cid: String, override: PlaybackOverride) {
        if (cid.isBlank()) return
        dataStore.edit { prefs ->
            if (override.isEmpty) {
                removeKeys(prefs, cid)
                return@edit
            }
            val core = override.core
            val decoder = override.decoder
            val danmakuEnabled = override.danmakuEnabled
            if (core != null) prefs[coreKey(cid)] = core.name else prefs.remove(coreKey(cid))
            if (decoder != null) prefs[decoderKey(cid)] = decoder.name else prefs.remove(decoderKey(cid))
            if (danmakuEnabled != null) prefs[danmakuKey(cid)] = danmakuEnabled else prefs.remove(danmakuKey(cid))
            prefs[tsKey(cid)] = System.currentTimeMillis()
            trim(prefs)
        }
    }

    override suspend fun remove(cid: String) {
        if (cid.isBlank()) return
        dataStore.edit { prefs -> removeKeys(prefs, cid) }
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            // 必须按原类型删：Preferences.Key 的相等性包含类型，类型不匹配会静默删不掉
            prefs.asMap().keys.forEach { key ->
                when {
                    key.name.startsWith(TS_PREFIX) -> prefs.remove(longPreferencesKey(key.name))
                    key.name.startsWith(CORE_PREFIX) || key.name.startsWith(DECODER_PREFIX) ->
                        prefs.remove(stringPreferencesKey(key.name))
                    key.name.startsWith(DANMAKU_PREFIX) ->
                        prefs.remove(booleanPreferencesKey(key.name))
                }
            }
        }
    }

    private fun removeKeys(prefs: MutablePreferences, cid: String) {
        prefs.remove(coreKey(cid))
        prefs.remove(decoderKey(cid))
        prefs.remove(danmakuKey(cid))
        prefs.remove(tsKey(cid))
    }

    /** 超出 [PlaybackOverrideStore.MAX] 时按时间戳丢最旧的，连带它的两个值键一起删 */
    private fun trim(prefs: MutablePreferences) {
        val stamps = prefs.asMap().keys
            .map { it.name }
            .filter { it.startsWith(TS_PREFIX) }
            .map { name -> name.removePrefix(TS_PREFIX) to (prefs[longPreferencesKey(name)] ?: 0L) }
            .sortedByDescending { it.second }
        stamps.drop(PlaybackOverrideStore.MAX).forEach { (cid, _) -> removeKeys(prefs, cid) }
    }
}
