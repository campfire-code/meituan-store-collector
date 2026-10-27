package com.campfire.storecollector.core

/**
 * 商品管理列表。字段模式来自参考仓库对商品卡片的解析：「月售 N」「库存无限 / 库存 N」「¥价格」「N 折」，
 * 页面顶部有「全部 N / 已下架 N / 折扣 N / 单点不送 N / 已售罄 N」。商品没有稳定 ID，记录键用商品名。
 */
object ProductParser {
    private val SALES = Regex("""月售\s*(\d+)""")
    private val MONEY = Regex("""[¥￥]\s*(\d+(?:\.\d+)?)""")
    private val DISCOUNT = Regex("""(\d+(?:\.\d+)?)\s*折""")
    private val CONTROLS = setOf("促销", "库存", "下架", "外送", "单点不送", "商品管理", "编辑", "上架", "套餐")
    private val HEADER_WORDS = listOf("全部", "已下架", "折扣", "单点不送", "已售罄")
    /** 夹在商品名和「月售」之间的状态 / 标签行，不是名称的一部分。 */
    private val MARKERS = setOf("已下架", "已售罄", "套餐", "单点不送")

    private fun isNameLine(s: String): Boolean {
        if (s in CONTROLS || s in MARKERS) return false
        if (SALES.containsMatchIn(s) || MONEY.containsMatchIn(s) || s.startsWith("库存")) return false
        if (HEADER_WORDS.any { s.startsWith(it) && s.any { c -> c.isDigit() } }) return false
        return s.any { it in '一'..'鿿' || it.isLetter() }
    }

    fun parse(lines: List<String>): ParsedPage {
        val all = lines.map { Text.norm(it) }.filter { it.isNotEmpty() }
        val whole = Text.compact(all.joinToString(" "))
        val summary = linkedMapOf<String, String>()
        fun put(label: String, p: Regex) { Text.firstInt(whole, p)?.let { summary[label] = it.toString() } }
        put("全部", Regex("""全部(\d+)"""))
        put("已下架", Regex("""已下架(\d+)"""))
        put("折扣", Regex("""折扣(\d+)"""))
        put("单点不送", Regex("""单点不送(\d+)"""))
        put("已售罄", Regex("""已售罄(\d+)"""))

        val salesIdx = all.indices.filter { SALES.containsMatchIn(all[it]) }
        // 每张卡片的名称起点：从「月售」行往上取最多 3 行名称行
        fun nameStart(i: Int): Int {
            var j = i - 1
            var n = 0
            while (j >= 0 && n < 3) {
                when {
                    n == 0 && all[j] in MARKERS -> j--
                    isNameLine(all[j]) -> { j--; n++ }
                    else -> break
                }
            }
            return j + 1
        }
        val records = LinkedHashMap<String, ParsedRecord>()
        var skipped = 0
        salesIdx.forEachIndexed { k, i ->
            val start = nameStart(i)
            val end = if (k + 1 < salesIdx.size) nameStart(salesIdx[k + 1]) else all.size
            val front = all.subList(start, i)
            val name = front.filter { it !in MARKERS }.joinToString("").trim().trimEnd('>', '》', '<')
            if (name.isEmpty()) { skipped++; return@forEachIndexed }
            val region = all.subList(i, maxOf(i + 1, end)).joinToString(" ")
            val rc = Text.compact(region + " " + front.filter { it in MARKERS }.joinToString(" "))
            val stockUnlimited = rc.contains("库存无限")
            val stockCount = if (stockUnlimited) null else Text.firstInt(rc, Regex("""库存(\d+)"""))
            val prices = MONEY.findAll(region).map { it.groupValues[1] }.toList()
            val fields = linkedMapOf<String, String>()
            fields["name"] = name
            SALES.find(region)?.let { fields["monthlySales"] = it.groupValues[1] }
            if (stockUnlimited) fields["stock"] = "无限" else stockCount?.let { fields["stock"] = it.toString() }
            prices.getOrNull(0)?.let { fields["price"] = it }
            prices.getOrNull(1)?.let { fields["originalPrice"] = it }
            DISCOUNT.find(region)?.let { fields["discount"] = it.groupValues[1] }
            listOf("套餐", "单点不送").filter { rc.contains(it) }.takeIf { it.isNotEmpty() }?.let { fields["tags"] = it.joinToString("、") }
            val state = when {
                rc.contains("已售罄") -> "已售罄"
                rc.contains("已下架") -> "已下架"
                else -> "在售"
            }
            fields["status"] = state
            records[name] = ParsedRecord(Kind.PRODUCT, name, state, null, fields)
        }
        return ParsedPage(PageType.PRODUCTS, records.values.toList(), summary = summary, expectedCount = summary["全部"]?.toIntOrNull(), skipped = skipped)
    }
}
