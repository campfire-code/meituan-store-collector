package com.campfire.storecollector.core

/**
 * 顾客评价（外卖）。字段模式来自参考仓库：页面标示「外卖评价 N 条」；每条评价有日期、打码昵称（***）、
 * 「商品 x 包装 y」「配送 满意/不满意/x」、评价正文、「N 图」、商家回复，以及「申诉 / 联系顾客 / 发券 / 修改回复 / 回复」等入口。
 * 平台没有向页面暴露评价 ID（参考仓库的结论），所以记录键由「日期 + 昵称 + 评分 + 正文」的指纹生成。
 * 昵称是平台已打码的显示名，不保存；正文保存在本机。
 */
object ReviewParser {
    private val COUNT = Regex("""外卖评价\s*(\d{1,6})\s*条""")
    private val DATE_HEAD = Regex("""^(20\d{2})[-/.](\d{1,2})[-/.](\d{1,2})(?!\d)\s*(.*)$""")
    private val BASE_SCORE = Regex("""商品\s*([0-5](?:[.,]\d)?).*?包装\s*([0-5](?:[.,]\d)?)""")
    private val DELIVERY_SCORE = Regex("""配送\s*(不满意|满意|[0-5](?:[.,]\d)?)""")
    private val IMAGES = Regex("""^(\d{1,3})\s*图$""")
    private val MASK = Regex("""[^\s*]{0,3}\*{2,}[^\s*]{0,3}""")
    val ACTIONS = listOf("申诉", "联系顾客", "发券", "修改回复", "回复")

    fun parse(lines: List<String>): ParsedPage {
        val all = lines.map { Text.norm(it) }.filter { it.isNotEmpty() }
        val expected = Text.firstInt(Text.compact(all.joinToString(" ")), Regex("""外卖评价(\d{1,6})条"""))
            ?: COUNT.find(all.joinToString(" "))?.groupValues?.get(1)?.toIntOrNull()

        data class Card(val date: String, val first: String, val body: MutableList<String>)
        val cards = ArrayList<Card>()
        for (l in all) {
            val m = DATE_HEAD.matchEntire(l)
            if (m != null) {
                val iso = Text.isoDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                if (iso != null) { cards += Card(iso, m.groupValues[4], mutableListOf()); continue }
            }
            if (cards.isNotEmpty()) cards.last().body += l
        }

        val seenBase = HashMap<String, Int>()
        val records = LinkedHashMap<String, ParsedRecord>()
        for (c in cards) {
            val lines2 = (listOf(c.first) + c.body).filter { it.isNotEmpty() }
            val joined = lines2.joinToString(" ")
            val mask = MASK.find(joined)?.value
            val base = BASE_SCORE.find(joined)
            val delivery = DELIVERY_SCORE.find(joined)?.groupValues?.get(1)
            val images = lines2.firstNotNullOfOrNull { IMAGES.matchEntire(it)?.groupValues?.get(1) }
            // 商家回复：从「商家回复」开头的行起，到这条评价结束
            val replyAt = lines2.indexOfFirst { it.startsWith("商家回复") }
            val replyText = if (replyAt >= 0) {
                lines2.drop(replyAt).map { it.removePrefix("商家回复").trimStart('：', ':', ' ') }.filter { it.isNotEmpty() && it !in ACTIONS }.joinToString(" ")
            } else ""
            val actions = lines2.filter { it in ACTIONS }.distinct()
            val contentLines = (if (replyAt >= 0) lines2.take(replyAt) else lines2).filter {
                it !in ACTIONS && !IMAGES.matches(it) && !BASE_SCORE.containsMatchIn(it) && !DELIVERY_SCORE.containsMatchIn(it) && !(mask != null && it == mask)
            }.map { if (mask != null) it.replace(mask, "").trim() else it }.filter { it.isNotEmpty() }
            val content = contentLines.joinToString(" ")
            val scores = listOfNotNull(base?.groupValues?.get(1), base?.groupValues?.get(2), delivery).joinToString("/")
            val identity = listOf(c.date, mask.orEmpty(), scores, Text.compact(content).ifEmpty { Text.compact(replyText) }).joinToString("|")
            val baseKey = Hashing.short(identity, 16)
            val n = (seenBase[baseKey] ?: 0) + 1
            seenBase[baseKey] = n
            val key = if (n == 1) baseKey else "$baseKey#$n"
            val fields = linkedMapOf<String, String>()
            fields["reviewDate"] = c.date
            base?.let { fields["productScore"] = it.groupValues[1].replace(',', '.'); fields["packagingScore"] = it.groupValues[2].replace(',', '.') }
            delivery?.let { fields["deliveryScore"] = it.replace(',', '.') }
            if (content.isNotEmpty()) fields["content"] = content
            images?.let { fields["imageCount"] = it }
            fields["replied"] = if (replyText.isNotEmpty()) "是" else "否"
            if (replyText.isNotEmpty()) fields["replyText"] = replyText
            if (actions.isNotEmpty()) fields["visibleActions"] = actions.joinToString("、")
            records[key] = ParsedRecord(Kind.REVIEW, key, if (replyText.isNotEmpty()) "已回复" else "未回复", c.date, fields)
        }
        return ParsedPage(PageType.REVIEWS, records.values.toList(), expectedCount = expected)
    }
}
