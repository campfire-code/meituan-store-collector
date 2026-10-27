package com.campfire.storecollector.core

import java.time.LocalDate

/**
 * 财务对账 → 订单流水。规则来自参考仓库的「已确认的页面规则」：
 * 卡片有「#显示序号」、状态（已完单 / 已取消 / 进行中）、结算金额、下单时间；日期以「月-日」或「月-日下单」的分组标题给出；
 * 同一天可能出现相同的显示序号（取消单与完单共享序号），所以记录键用「日期 + 序号 + 下单时间」，缺时间时再加状态和金额；
 * 序号不连续是正常的，不能据此判断漏单。账期可能跨自然月，页面没有年份时按「不晚于今天」补年份。
 */
object FinanceParser {
    private val HEAD = Regex("""^#\s*(\d{1,3})(?!\d)\s*(.*)$""")
    private val DATE_ONLY = Regex("""^(\d{1,2})[-/.](\d{1,2})(?:\s*下单)?$""")
    private val AMOUNT = Regex("""[¥￥Y]\s*(\d+(?:[.,]\d{1,2})?)""")
    private val TIME = Regex("""(?<!\d)([0-2]?\d:[0-5]\d(?::[0-5]\d)?)(?!\d)""")
    private val TAGS = listOf("神会员红包", "神抢手", "保险", "部分退")

    fun statusOf(raw: String): String {
        val c = Text.compact(raw)
        return when {
            c.contains("取消") -> "已取消"
            c.contains("完") -> "已完单"
            c.contains("进行") -> "进行中"
            else -> "未知"
        }
    }

    fun parse(lines: List<String>, today: LocalDate): ParsedPage {
        val all = lines.map { Text.norm(it) }.filter { it.isNotEmpty() }
        var currentDate: LocalDate? = null
        data class Card(val seq: Int, val date: LocalDate?, val head: String, val body: MutableList<String>)
        val cards = ArrayList<Card>()
        for (l in all) {
            val d = DATE_ONLY.matchEntire(l)
            val h = HEAD.matchEntire(l)
            when {
                d != null -> currentDate = Text.resolveMonthDay(d.groupValues[1].toInt(), d.groupValues[2].toInt(), today)
                h != null -> cards += Card(h.groupValues[1].toInt(), currentDate, h.groupValues[2], mutableListOf())
                cards.isNotEmpty() -> cards.last().body += l
            }
        }
        var skipped = 0
        val records = LinkedHashMap<String, ParsedRecord>()
        for (c in cards) {
            val date = c.date
            if (date == null) { skipped++; continue }
            val whole = (listOf(c.head) + c.body).joinToString(" ")
            val status = statusOf(c.head.ifBlank { whole })
            val amount = AMOUNT.find(whole)?.groupValues?.get(1)?.replace(',', '.')
            val time = TIME.find(whole)?.groupValues?.get(1)
            val tags = TAGS.filter { Text.compact(whole).contains(it) }
            val iso = date.toString()
            val key = if (time != null) "$iso|${c.seq}|$time" else "$iso|${c.seq}|$status|${amount.orEmpty()}"
            val fields = linkedMapOf<String, String>()
            fields["orderDate"] = iso
            fields["displaySequence"] = c.seq.toString()
            fields["orderStatus"] = status
            amount?.let { fields["settlementAmount"] = it }
            time?.let { fields["orderTime"] = it }
            if (tags.isNotEmpty()) fields["visibleTags"] = tags.joinToString("、")
            records[key] = ParsedRecord(Kind.FINANCE, key, status, iso, fields)
        }
        return ParsedPage(PageType.FINANCE, records.values.toList(), skipped = skipped)
    }
}
