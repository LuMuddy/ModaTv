package com.moda.tv.data.local

/**
 * 片头/片尾的判定规则（纯逻辑）。
 *
 * 这里**刻意不碰 Context / DataStore / 协程**：整套阈值是需求里最容易出错、也最难在真机上
 * 复现的部分（要等一集 45 分钟播到 95%），把它做成无副作用的纯函数后，
 * 可以在 JVM 里直接调用来验证边界，不需要跑模拟器、也不需要等播放。
 *
 * 三条需求落在这里：
 *  3. 片头只对「从来没播过的视频」自动跳——回看时把用户往回拽是最恼人的行为；
 *  4. 片尾到点就跳（交给播放页去连播下一集）；
 *  6. 必须有一套兜底比例，否则没记录过的剧永远不会跳；
 *     但记录点若落在片长 1% 以内，那多半是误触（刚起播就点了「记录片头」），
 *     拿它当片头等于每次起播都跳到 1%，比没有兜底还糟。
 */
object IntroOutroRules {

    /** 完成线：播到片长 95% 就算看完；跳片尾同样归入完成 */
    const val COMPLETE_PERCENT = 0.95f

    /**
     * 兜底只对「正片时长」成立。短片（预告、花絮、单片、<5 分钟的小片段）一律不兜底：
     * 6% 的片尾 + 8% 的片头放在 3 分钟的片子上只有几秒，随便一跳就跨过了整段内容，
     * 用户会看到「刚开播就直接下一集」。
     */
    const val MIN_DURATION_MS = 5 * 60_000L

    /** 兜底片头 = 片长 8% */
    const val INTRO_FALLBACK_PERCENT = 0.08f

    /**
     * 兜底片头最多 90 秒。
     * 8% 对 45 分钟的剧是 216 秒——那是三分钟的正片内容，不是片头；
     * 90 秒足够覆盖绝大多数国产剧/动画的主题曲，超出的部分宁可让用户看，也不能吞掉剧情。
     */
    const val INTRO_FALLBACK_CAP_MS = 90_000L

    /** 兜底片尾 = 片长往前 6%（也就是 94% 处开始算片尾） */
    const val OUTRO_FALLBACK_PERCENT = 0.06f

    /**
     * 兜底片尾的「往前封顶」保留在这里做记录，但**刻意不参与计算**：
     *
     * 6% 的比例档恒等于片长的 94%，本来就落在 95% 完成线之内，是安全的一侧；
     * 一旦再按 120 秒硬封顶，只要片长超过 2000 秒（33 分钟），兜底片尾就会被推到 94% 之后，
     * 超过 2400 秒（40 分钟）时直接越过 95% 完成线——[effectiveOutro] 会按「与完成线打架」
     * 的规则返回 0，于是**所有正常长度的剧集都失去跳片尾**。两害相权，比例是安全的那一侧，
     * 所以这里只按 [OUTRO_FALLBACK_PERCENT] 算。
     */
    const val OUTRO_CAP_MS = 120_000L

    /**
     * 记录点落在片长 1% 以内视为误触。
     * 用户点「记录片头」时播放器往往还在片头甚至第 0 秒，这种记录没有任何信息量；
     * 直接弃用并回落到兜底，比拿它当片头（= 每次起播都跳 1%）合理得多。
     */
    const val MARK_MIN_PERCENT = 0.01f

    /**
     * 片头与片尾之间至少留 60 秒。
     * 两个标记都来自用户手点，点错、点反都会出现「片尾在片头前面 3 秒」这种数据；
     * 没有这条下限时 hóld 一集的正常播放区间会被压成几秒，直接连跳到下一集。
     */
    const val MIN_GAP_MS = 60_000L

    /**
     * 判断「已经走到标记点」的容差。播放进度是 300ms 一采样、UI 又按秒显示，
     * 严格大于等于会让刚好落在标记前几百毫秒的位置永远差一点点，所以留 1 秒。
     * 与 PlayerScreen 的 MarkReachedToleranceMs 同一口径。
     */
    const val INTRO_SKIP_TOLERANCE_MS = 1_000L

    /**
     * 有效片头：有可信记录用记录，否则用兜底；两边都不成立返回 0（表示不跳）。
     *
     * 参数是「记录值 + 片长」而不是「整条记录」，是因为调用方在起播那一刻才知道片长；
     * 函数必须是纯的，读盘的事交给调用方（PlayerScreen / TvViewModel）。
     */
    fun effectiveIntro(recordedMs: Long, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        if (isTrustedMark(recordedMs, durationMs)) return recordedMs
        return fallbackIntro(durationMs)
    }

    /**
     * 有效片尾：同上；且必须 < 片长 × [COMPLETE_PERCENT]。
     *
     * 最后这一条是整套规则里最要紧的：片尾点 ≥ 完成线时，跳片尾等于「刚开播就看完」，
     * 用户点开一集会被立刻切走。所以宁可返回 0 让调用方「不跳」，
     * 也不要跳到一个会让这一集瞬间变成已看完的位置。
     */
    fun effectiveOutro(recordedMs: Long, durationMs: Long): Long =
        // 两参版本拿不到片头记录，用**兜底片头**当参照：它只会比真实片头短，
        // 于是这条判据退化成「至少留够 60 秒间隔」的下限，不会误杀正常数据。
        // 调用方手里同时有片头标记时，用下面的三参版本更准。
        effectiveOutroWithIntro(recordedMs, durationMs, effectiveIntro(0L, durationMs))

    /**
     * 同上，但片头由调用方显式给出（播放页同时持有片头与片尾两个标记）。
     * 片头点错了（用户把片头记在 20 分钟处）时，片尾必须跟着失效，
     * 否则「片尾早于片头」会让这一集刚起播就命中片尾、直接连播下一集。
     */
    fun effectiveOutroWithIntro(recordedMs: Long, durationMs: Long, introMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val candidate = if (isTrustedMark(recordedMs, durationMs)) {
            recordedMs
        } else {
            fallbackOutro(durationMs)
        }
        if (candidate <= 0L) return 0L
        // 完成线：片尾一旦落在它之后就不能用了，见函数的说明
        if (candidate >= durationMs * COMPLETE_PERCENT) return 0L
        // 与片头之间至少留 MIN_GAP_MS，否则正常播放区间被压成几秒
        if (candidate <= introMs + MIN_GAP_MS) return 0L
        return candidate
    }

    /** 是否达到完成线：片长无效（直播 / 还没拿到 duration）时一律返回 false，绝不误判为看完 */
    fun isCompleted(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        return positionMs.toFloat() >= durationMs * COMPLETE_PERCENT
    }

    /**
     * 片头是否该跳：**从未播放过** 且 有效片头在当前位置之后（留 1 秒容差）。
     *
     * 「从未播放过」是需求原话：回看同一集时用户往往就是想看片头，
     * 这时候再把他拽到 90 秒后，等于把一次主动回看变成了「找不到片头在哪」。
     */
    fun shouldSkipIntro(hasPlayedBefore: Boolean, positionMs: Long, effectiveIntroMs: Long): Boolean {
        if (hasPlayedBefore) return false
        if (effectiveIntroMs <= 0L) return false
        return effectiveIntroMs > positionMs + INTRO_SKIP_TOLERANCE_MS
    }

    /**
     * 记录值是否可信：必须严格大于片长 1%（误触）且严格小于片长本身。
     * 这两个界同时也要挡住脏数据：-1、Long.MAX_VALUE、超过片长的标记都不该生效。
     */
    fun isTrustedMark(recordedMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        if (recordedMs <= durationMs * MARK_MIN_PERCENT) return false
        return recordedMs < durationMs
    }

    /** 兜底片头：片长 8% 与 90 秒取小；短片直接不放 */
    private fun fallbackIntro(durationMs: Long): Long {
        if (durationMs < MIN_DURATION_MS) return 0L
        val byPercent = (durationMs * INTRO_FALLBACK_PERCENT).toLong()
        val ms = minOf(byPercent, INTRO_FALLBACK_CAP_MS)
        // 理论上必然成立，仍然显式挡住：任何"跳到片尾之后"的位置都不该被当成片头
        return ms.takeIf { it > 0L && it < durationMs } ?: 0L
    }

    /** 兜底片尾：片长 94% 处；短片直接不放 */
    private fun fallbackOutro(durationMs: Long): Long {
        if (durationMs < MIN_DURATION_MS) return 0L
        val back = (durationMs * OUTRO_FALLBACK_PERCENT).toLong()
        val ms = durationMs - back
        return ms.takeIf { it > 0L && it < durationMs } ?: 0L
    }
}
