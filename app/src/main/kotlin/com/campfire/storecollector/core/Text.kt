package com.campfire.storecollector.core

import java.text.Normalizer
import java.time.LocalDate

object Text {
    /** 全角转半角、去掉所有空白，便于用正则匹配。 */
    fun compact(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC).replace(Regex("\\s+"), "").replace('￥', '¥')

    /** 只合并空白，不改字符（门店名、评价正文里的全角标点要原样保存）。匹配用 [compact]。 */
    fun norm(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    fun firstInt(text: String, pattern: Regex): Int? = pattern.find(text)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * 把页面上没有年份的「月-日」补成完整日期：默认取今年；如果这样算出来比今天晚一天以上（说明是跨年的旧数据），取去年。
     * 日期不合法（如 2-30）返回 null。
     */
    fun resolveMonthDay(month: Int, day: Int, today: LocalDate): LocalDate? {
        val thisYear = runCatching { LocalDate.of(today.year, month, day) }.getOrNull()
        if (thisYear != null) {
            return if (thisYear.isAfter(today.plusDays(1))) runCatching { LocalDate.of(today.year - 1, month, day) }.getOrNull() ?: thisYear else thisYear
        }
        // 2 月 29 日这类：今年没有，去年也可能没有
        return runCatching { LocalDate.of(today.year - 1, month, day) }.getOrNull()
    }

    fun isoDate(y: Int, m: Int, d: Int): String? = runCatching { LocalDate.of(y, m, d).toString() }.getOrNull()
}
