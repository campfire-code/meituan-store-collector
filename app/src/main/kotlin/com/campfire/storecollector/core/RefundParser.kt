package com.campfire.storecollector.core

import java.time.LocalDate

/**
 * 订单 → 售后 → 退款（近 14 天）历史。规则来自参考仓库：
 * 卡片顶部的「#数字」是订单列表序号，不是退款记录主键；每张卡片底部有「订单号」，记录键就用平台订单号；
 * 一张卡片会跨好几屏，所以把「上一个订单号行之后、当前订单号行为止」的文字归入当前卡片，卡片被屏幕截断时先存已有字段，滚到后面再补全。
 * 只读取状态、时间、原因等；顾客称呼、手机尾号、地址不保存。
 */
object RefundParser {
    private val ORDER_NO = Regex("""(?:订单号|单号)\s*[:：]?\s*([0-9][0-9\s]{12,28})""")
    private val HEADER_TIME = Regex("""(?<!\d)(0?[1-9]|1[0-2])[-/.](3[01]|[12]\d|0?[1-9])\s*([01]?\d|2[0-3])[:：]([0-5]\d)""")
    private val PLACED = Regex("""下单\s*[:：]?\s*(0?[1-9]|1[0-2])[-/.](3[01]|[12]\d|0?[1-9])\s*([01]?\d|2[0-3])[:：]([0-5]\d)""")
    private val REASON = Regex("""(?:取消|退款|申请)原因\s*[:：]?\s*(.*)""")
    private val DISTANCE = Regex("""(\d+(?:[.,]\d+)?)\s*km""", RegexOption.IGNORE_CASE)
    private val ORDER_STATES = listOf("订单已取消", "订单取消", "用户已收餐", "用户未收餐", "配送完成", "配送中")
    private val REFUND_STATES = listOf("系统自动退款", "商家驳回退款", "客服介入退款", "顾客取消退款申请", "退款成功", "退款失败", "全部退款", "部分退款")
    private val MEAL_LOSS = listOf("餐损不可赔付", "餐损可获赔", "赔付中")
    private val ACTIONS = listOf("展开", "收起", "退款处理", "联系顾客", "申诉", "餐损申诉")


    fun parse(lines: List<String>, today: LocalDate): ParsedPage {
        val all = lines.map { Text.norm(it) }.filter { it.isNotEmpty() }
        val records = LinkedHashMap<String, ParsedRecord>()
        var cardLines = ArrayList<String>()
        for (l in all) {
            cardLines += l
            val m = ORDER_NO.find(l) ?: continue
            val orderNo = m.groupValues[1].filter { it.isDigit() }
            val card = cardLines
            cardLines = ArrayList()
            val text = card.joinToString(" ")
            val compact = Text.compact(text)
            fun stamp(r: MatchResult?): String? = r?.let {
                val d = Text.resolveMonthDay(it.groupValues[1].toInt(), it.groupValues[2].toInt(), today) ?: return null
                "%s %02d:%s".format(d.toString(), it.groupValues[3].toInt(), it.groupValues[4])
            }
            val placed = stamp(PLACED.find(text))
            val header = stamp(HEADER_TIME.findAll(text).firstOrNull { PLACED.find(text)?.range?.let { r -> it.range.first !in r } != false })
            val orderStates = ORDER_STATES.filter { compact.contains(it) }
            val refundStates = REFUND_STATES.filter { compact.contains(it) }
            val mealLoss = MEAL_LOSS.filter { compact.contains(it) }
            val reason = card.firstNotNullOfOrNull { REASON.find(it)?.groupValues?.get(1)?.trim() }.orEmpty()
            val fields = linkedMapOf<String, String>()
            fields["orderNumber"] = orderNo
            header?.let { fields["cardTime"] = it }
            placed?.let { fields["orderPlacedAt"] = it }
            if (orderStates.isNotEmpty()) fields["orderStates"] = orderStates.joinToString("、")
            if (refundStates.isNotEmpty()) fields["refundStates"] = refundStates.joinToString("、")
            if (mealLoss.isNotEmpty()) fields["mealLossStates"] = mealLoss.joinToString("、")
            if (reason.isNotEmpty()) fields["reason"] = reason
            Text.firstInt(compact, Regex("""下单(\d{1,5})次"""))?.let { fields["customerOrderCount"] = it.toString() }
            DISTANCE.find(text)?.let { fields["distance"] = it.groupValues[1] + "km" }
            ACTIONS.filter { a -> card.any { it == a } }.takeIf { it.isNotEmpty() }?.let { fields["visibleActions"] = it.joinToString("、") }
            val state = refundStates.firstOrNull() ?: orderStates.firstOrNull() ?: "已观察"
            val biz = (header ?: placed)?.take(10)
            records[orderNo] = ParsedRecord(Kind.REFUND, orderNo, state, biz, fields)
        }
        return ParsedPage(PageType.REFUNDS, records.values.toList())
    }

}
