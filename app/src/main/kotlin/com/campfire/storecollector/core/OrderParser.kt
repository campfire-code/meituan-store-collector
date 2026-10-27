package com.campfire.storecollector.core

import java.time.LocalDate

/**
 * 订单列表（订单工作台「今日 / 全部」）。
 * 字段模式来自参考仓库对订单卡片的解析：卡片以「#序号 月-日 预计送达时间」开头，另有「下单 N 次」「已出餐用时」「N:NN已送达」等。
 * 参考仓库读的是截图文字；本工具读无障碍节点文字，节点上文字的分行方式未在真机上核实，所以同时兼容「一行写完」和「分成几行」。
 * 顾客称呼、手机尾号、地址、备注一律不读取、不保存。
 */
object OrderParser {
    private val HEAD_SPACED = Regex("""^#\s*(\d{1,3})(?!\d)\s*(.*)$""")
    private val HEAD_GLUED = Regex("""^#(\d{1,3})(\d{2}-\d{2})(.*)$""")
    private val DATE = Regex("""(?<!\d)(\d{1,2})-(\d{1,2})(?!\d)""")
    private val SERVICE_TAGS = listOf("神抢手", "用户取消险", "放心吃", "准时宝", "15分钟闪电送")
    private val ORDER_NO = Regex("""订单号[:：]?(\d{10,30})""")
    private val INCOME = Regex("""预计收入[:：]?¥?(\d+(?:\.\d+)?)""")

    private val STATE_ORDER = listOf(
        "已取消" to "已取消", "用户已收餐" to "已送达（用户已收餐）", "订单已送达" to "已送达", "已送达" to "已送达",
        "配送中" to "配送中", "待取餐" to "待取餐", "待骑手接单" to "待骑手接单", "待出餐" to "待出餐", "待接单" to "待接单",
    )

    fun parse(lines: List<String>, today: LocalDate): ParsedPage {
        val all = lines.map { Text.norm(it) }.filter { it.isNotEmpty() }
        val whole = Text.compact(all.joinToString(" "))
        val summary = linkedMapOf<String, String>()
        fun put(label: String, p: Regex) { Text.firstInt(whole, p)?.let { summary[label] = it.toString() } }
        put("共（单）", Regex("""共(\d+)单"""))
        put("进行中", Regex("""进行中(\d+)单"""))
        put("已取消", Regex("""已取消(\d+)单"""))
        put("待接单", Regex("""待接单(\d+)"""))
        put("待出餐", Regex("""待出餐(\d+)"""))
        put("待骑手接单", Regex("""待骑手接单(\d+)"""))
        put("待取餐", Regex("""待取餐(\d+)"""))
        put("配送中", Regex("""配送中(\d+)"""))
        val expected = Text.firstInt(whole, Regex("""共(\d+)单"""))

        // 切卡片：以「#序号」开头的行为卡片起点
        data class Card(val seq: Int, val head: String, val body: MutableList<String>)
        val cards = ArrayList<Card>()
        for (l in all) {
            val glued = HEAD_GLUED.matchEntire(l)
            val spaced = HEAD_SPACED.matchEntire(l)
            when {
                glued != null -> cards += Card(glued.groupValues[1].toInt(), glued.groupValues[2] + " " + glued.groupValues[3], mutableListOf())
                spaced != null -> cards += Card(spaced.groupValues[1].toInt(), spaced.groupValues[2], mutableListOf())
                cards.isNotEmpty() -> cards.last().body += l
            }
        }

        var skipped = 0
        val records = LinkedHashMap<String, ParsedRecord>()
        for (c in cards) {
            val text = Text.compact(c.head + " " + c.body.joinToString(" "))
            // 日期和预计送达时间在卡片头里；卡片头被分成几个控件时，它们在后面的一两行
            val headText = Text.norm((listOf(c.head) + c.body.take(2)).joinToString(" "))
            val m = DATE.find(headText)
            val dateLabel = m?.let { "%02d-%02d".format(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            val date = if (m != null) Text.resolveMonthDay(m.groupValues[1].toInt(), m.groupValues[2].toInt(), today) else today
            if (date == null) { skipped++; continue }
            val afterDate = if (m != null) headText.substring(m.range.last + 1) else headText
            val expectedTime = Regex("""(\d{1,2}:\d{2})""").find(Text.compact(afterDate))?.groupValues?.get(1)
            val state = STATE_ORDER.firstOrNull { text.contains(it.first) }?.second ?: "进行中"
            val fields = linkedMapOf<String, String>()
            fields["displaySequence"] = c.seq.toString()
            dateLabel?.let { fields["orderDateLabel"] = it }
            expectedTime?.let { fields["expectedDeliveryTime"] = it }
            SERVICE_TAGS.filter { text.contains(it) }.takeIf { it.isNotEmpty() }?.let { fields["serviceTags"] = it.joinToString("、") }
            Regex("""(<?\d+(?:\.\d+)?km)""", RegexOption.IGNORE_CASE).find(text)?.let { fields["distance"] = it.groupValues[1] }
            Text.firstInt(text, Regex("""下单(\d+)次"""))?.let { fields["customerOrderCount"] = it.toString() }
            Regex("""已出餐用时(\d{1,2}:\d{2})""").find(text)?.let { fields["mealPreparationDuration"] = it.groupValues[1] }
            Regex("""参考出餐时长(\d+)分(\d+)秒""").find(text)?.let { fields["referenceMealSeconds"] = (it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt()).toString() }
            Regex("""(\d{1,2}:\d{2})已送达""").find(text)?.let { fields["deliveredAt"] = it.groupValues[1] }
            ORDER_NO.find(text)?.let { fields["orderNumber"] = it.groupValues[1] }
            INCOME.find(text)?.let { fields["expectedIncome"] = it.groupValues[1] }
            fields["status"] = state
            val iso = date.toString()
            val key = "$iso|${c.seq}"
            records[key] = ParsedRecord(Kind.ORDER, key, state, iso, fields)
        }
        return ParsedPage(PageType.ORDERS, records.values.toList(), summary = summary, expectedCount = expected, skipped = skipped)
    }
}
