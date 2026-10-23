package com.moda.tv.data.local

/**
 * 片头/片尾的判定规则（纯逻辑）。
 *
 * 这里**刻意不碰 Context / DataStore / 协程**：整套阈值是需求里最容易出错、也最难在真机上
 * 复现的部分（要等一集 45 分钟播到 95%），把它做成无副作用的纯函数后，
 * 可以在 JVM 里直接调用来验证边界，不需要跑模拟器、也不需要等播放。
 *
 * 核心原则：**只跳用户亲手记录的点，没有任何无记录兜底**。
 * 没记录的剧正常播——宁可不跳，也不能拿一个猜出来的位置把用户往前拽
 * （历史上兜底 8%/90s 曾让没记录的长剧第一次起播都从 1:30 开始，就是这个教训）。
 */
object IntroOutroRules {

    /** 完成线：播到片长 95% 就算看完；跳片尾同样归入完成 */
    const val COMPLETE_PERCENT = 0.95f

    /**
     * 记录点落在片长 1% 以内视为误触。
     * 用户点「记录片头」时播放器往往还在片头甚至第 0 秒，这种记录没有任何信息量；
     * 直接弃用（按没记录处理），比拿它当片头（= 每次起播都跳到 1%）合理得多。
     */
    const val MARK_MIN_PERCENT = 0.01f

    /**
     * 记录点超过片长 10% 视为无效。
     * 片头不可能在这么靠后的位置，用户记到 20 分钟处多半是记错了剧或记错了集；
     * 拿它当片头每次起播都吞掉大片正片，弃用（按没记录处理）。
     */
    const val MARK_MAX_PERCENT = 0.10f

    /**
     * 片头与片尾之间至少留 60 秒。
     * 两个标记都来自用户手点，点错、点反都会出现「片尾在片头前面 3 秒」这种数据；
     * 没有这条下限时一集的正常播放区间会被压成几秒，直接连跳到下一集。
     */
    const val MIN_GAP_MS = 60_000L

    /**
     * 判断「已经走到标记点」的容差。播放进度是 300ms 一采样、UI 又按秒显示，
     * 严格大于等于会让刚好落在标记前几百毫秒的位置永远差一点点，所以留 1 秒。
     * 与 PlayerScreen 的 MarkReachedToleranceMs 同一口径。
     */
    const val INTRO_SKIP_TOLERANCE_MS = 1_000L

    /**
     * 有效片头：记录可信就用记录，否则返回 0（表示不跳，没有兜底）。
     *
     * 参数是「记录值 + 片长」而不是「整条记录」，是因为调用方在起播那一刻才知道片长；
     * 函数必须是纯的，读盘的事交给调用方（PlayerScreen / TvViewModel）。
     */
    fun effectiveIntro(recordedMs: Long, durationMs: Long): Long =
        if (isTrustedMark(recordedMs, durationMs)) recordedMs else 0L

    /**
     * 有效片尾：记录可信就用记录；否则返回 0（不跳）。
     * 且必须 < 片长 × [COMPLETE_PERCENT]：片尾点 ≥ 完成线时，跳片尾等于「刚开播就看完」，
     * 宁可返回 0 让调用方「不跳」，也不要跳到一个会让这一集瞬间变成已看完的位置。
     */
    fun effectiveOutro(recordedMs: Long, durationMs: Long): Long =
        // 两参版本拿不到片头记录，把片头参照置 0：间隔判据退化成「片尾至少在 60 秒之后」，
        // 调用方手里同时有片头标记时，用下面的三参版本更准。
        effectiveOutroWithIntro(recordedMs, durationMs, 0L)

    /**
     * 同上，但片头由调用方显式给出（播放页同时持有片头与片尾两个标记）。
     * 片头点错了（用户把片头记在 20 分钟处）时，片尾必须跟着失效，
     * 否则「片尾早于片头」会让这一集刚起播就命中片尾、直接连播下一集。
     */
    fun effectiveOutroWithIntro(recordedMs: Long, durationMs: Long, introMs: Long): Long {
        val candidate = if (isTrustedMark(recordedMs, durationMs)) recordedMs else 0L
        if (candidate <= 0L || durationMs <= 0L) return 0L
        // 完成线：片尾一旦落在它之后就不能用了
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
     * 这时候再把他往回拽，等于把一次主动回看变成了「找不到片头在哪」。
     */
    fun shouldSkipIntro(hasPlayedBefore: Boolean, positionMs: Long, effectiveIntroMs: Long): Boolean {
        if (hasPlayedBefore) return false
        if (effectiveIntroMs <= 0L) return false
        return effectiveIntroMs > positionMs + INTRO_SKIP_TOLERANCE_MS
    }

    /**
     * 记录值是否可信，三条同时成立：
     *  · 片长有效（脏数据 -1 / Long.MAX_VALUE 直接挡掉）；
     *  · 记录点在片长 1%（误触线）与 10%（上限线）之间，见两个常量的注释；
     *  · 记录点严格小于片长本身。
     * 不可信一律按「没记录」处理：调用方拿到的 0 就是「正常播，不跳」。
     */
    fun isTrustedMark(recordedMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        if (recordedMs <= durationMs * MARK_MIN_PERCENT) return false
        if (recordedMs >= durationMs * MARK_MAX_PERCENT) return false
        return recordedMs < durationMs
    }
}
