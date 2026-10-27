package com.campfire.storecollector.core

import java.time.LocalDate

/** 判断当前是哪种页面，并交给对应的解析函数。全部是纯函数，输入是节点树。 */
object PageClassifier {
    private val REVIEW_COUNT = Regex("""外卖评价\s*\d+\s*条""")
    private val REVIEW_DATE = Regex("""^20\d{2}[-/.]\d{1,2}[-/.]\d{1,2}""")
    private val ORDER_NO = Regex("""(?:订单号|单号)\s*[:：]?\s*\d[\d\s]{12,28}""")
    private val FINANCE_HEAD = Regex("""^#\s*\d{1,3}(?!\d)\s*.*(已完单|已取消|进行中)""")
    private val ORDER_HEAD = Regex("""^#\s*\d{1,3}""")
    private const val MIN_LINES = 3

    fun classify(root: NodeInfo?): PageType {
        if (root == null) return PageType.UNKNOWN
        if (StoreParser.hasStoreRows(root)) return PageType.STORES
        val lines = PageText.texts(root).map { Text.norm(it) }
        if (lines.size < MIN_LINES) return PageType.NO_TEXT
        val joined = lines.joinToString(" ")
        val compact = Text.compact(joined)
        return when {
            REVIEW_COUNT.containsMatchIn(joined) || (lines.any { REVIEW_DATE.containsMatchIn(it) } && compact.contains("商品") && compact.contains("包装")) -> PageType.REVIEWS
            lines.any { ORDER_NO.containsMatchIn(it) } && REFUND_WORDS.any { compact.contains(it) } -> PageType.REFUNDS
            lines.any { FINANCE_HEAD.containsMatchIn(it) } && compact.contains("¥") -> PageType.FINANCE
            lines.any { it.contains("月售") } -> PageType.PRODUCTS
            Regex("""共\d+单""").containsMatchIn(compact) || lines.count { ORDER_HEAD.containsMatchIn(it) } > 0 -> PageType.ORDERS
            else -> PageType.UNKNOWN
        }
    }

    private val REFUND_WORDS = listOf("退款", "餐损", "售后")

    fun parse(root: NodeInfo?, today: LocalDate): ParsedPage {
        val type = classify(root)
        val lines = PageText.texts(root)
        return when (type) {
            PageType.STORES -> ParsedPage(type, stores = StoreParser.parse(root))
            PageType.ORDERS -> OrderParser.parse(lines, today)
            PageType.PRODUCTS -> ProductParser.parse(lines)
            PageType.REVIEWS -> ReviewParser.parse(lines)
            PageType.FINANCE -> FinanceParser.parse(lines, today)
            PageType.REFUNDS -> RefundParser.parse(lines, today)
            PageType.NO_TEXT -> ParsedPage(type, note = "这个页面没有向无障碍服务提供文字（自绘页面常见），本工具不做截图识别，读不到。")
            PageType.UNKNOWN -> ParsedPage(type, note = "不是已知的采集页面。请先打开订单、商品管理、顾客评价、财务对账订单或售后退款页面，或门店切换页。")
        }
    }
}
