package com.moda.tv.data.local

/**
 * 版本号强弱的纯函数。
 *
 * 刻意不做语义化版本（SemVer）的完整实现：本仓库的 tag 就是 `v<versionName>`，
 * 版本来源只有我们自己这一个地方，用不上 caret/tilde 那套范围语法。
 *
 * **简化：预发布后缀不参与强弱比较。** 比较时按 `.` / `-` / `+` 切段，每段只取
 * 开头的数字前缀，因此 `1.0.0` 与 `1.0.0-beta` 被判为相等、`1.0.0-beta` 不会
 * 被视为比 `1.0.0` 更旧。取舍是故意的：这里的唯一用途是"有没有比本机装的新"，
 * 而本仓库从不发布预发布版；若按 SemVer 判 `1.0.0-rc1 < 1.0.0`，
 * 一次误打的 tag 会让用户永远收不到"比 rc 更新的正式版"提示（相等判 0 也意味着不弹窗）。
 *
 * 无 Context / DataStore / 协程：可以脱离 Android 直接验证边界。
 */
object VersionRules {

    /**
     * a < b 返回负数，相等返回 0，a > b 返回正数。只保证符号有意义，不保证是 ±1。
     */
    fun compare(a: String, b: String): Int {
        val left = segments(a)
        val right = segments(b)
        // 缺失段按 0：否则 "1.3" 与 "1.3.0" 会被判成不等，而它们其实是同一个版本
        for (i in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrElse(i) { 0L }
            val r = right.getOrElse(i) { 0L }
            if (l != r) return if (l < r) -1 else 1
        }
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    private fun segments(version: String): List<Long> {
        val trimmed = version.trim()
        // tag 叫 v1.2.3，VersionName 叫 1.2.3，两者必须能直接比
        val bare = if (trimmed.startsWith("v") || trimmed.startsWith("V")) trimmed.substring(1) else trimmed
        return bare.split('.', '-', '+').map { leadingNumber(it) }
    }

    /** 只取开头的数字前缀；没有数字（"beta"、空串）按 0 */
    private fun leadingNumber(segment: String): Long {
        var end = 0
        while (end < segment.length && segment[end] in '0'..'9') end++
        if (end == 0) return 0L
        // 构建号被塞进版本号时可能长到溢出 Long，饱和到 MAX 才能保持比较单调（不因溢出变负）
        return segment.substring(0, end).toLongOrNull() ?: Long.MAX_VALUE
    }
}
