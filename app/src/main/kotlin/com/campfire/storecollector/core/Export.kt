package com.campfire.storecollector.core

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 导出的列定义：中文表头 + 记录里的字段名。 */
object Columns {
    data class Col(val header: String, val field: String)

    fun forKind(kind: Kind): List<Col> = when (kind) {
        Kind.ORDER -> listOf(
            Col("序号", "displaySequence"), Col("订单状态", "status"), Col("日期标签", "orderDateLabel"), Col("预计送达", "expectedDeliveryTime"),
            Col("送达时间", "deliveredAt"), Col("出餐用时", "mealPreparationDuration"), Col("参考出餐秒数", "referenceMealSeconds"),
            Col("距离", "distance"), Col("顾客下单次数", "customerOrderCount"), Col("服务标签", "serviceTags"), Col("订单号", "orderNumber"), Col("预计收入", "expectedIncome"),
        )
        Kind.PRODUCT -> listOf(
            Col("商品名", "name"), Col("状态", "status"), Col("月售", "monthlySales"), Col("库存", "stock"), Col("价格", "price"),
            Col("原价", "originalPrice"), Col("折扣", "discount"), Col("标签", "tags"),
        )
        Kind.REVIEW -> listOf(
            Col("评价日期", "reviewDate"), Col("商品评分", "productScore"), Col("包装评分", "packagingScore"), Col("配送评价", "deliveryScore"),
            Col("评价内容", "content"), Col("图片数", "imageCount"), Col("是否回复", "replied"), Col("商家回复", "replyText"), Col("可见操作", "visibleActions"),
        )
        Kind.FINANCE -> listOf(
            Col("下单日期", "orderDate"), Col("序号", "displaySequence"), Col("订单状态", "orderStatus"), Col("结算金额", "settlementAmount"),
            Col("下单时间", "orderTime"), Col("标签", "visibleTags"),
        )
        Kind.REFUND -> listOf(
            Col("订单号", "orderNumber"), Col("卡片时间", "cardTime"), Col("下单时间", "orderPlacedAt"), Col("订单状态", "orderStates"),
            Col("退款状态", "refundStates"), Col("餐损状态", "mealLossStates"), Col("原因", "reason"), Col("顾客下单次数", "customerOrderCount"),
            Col("距离", "distance"), Col("可见操作", "visibleActions"),
        )
    }
}

object Tables {
    private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun fmt(ts: Long, zone: ZoneId): String = timeFmt.format(Instant.ofEpochMilli(ts).atZone(zone))

    /** 记录 → 表格（第一行是表头）。[storeStatus] 用于带出门店状态。 */
    fun records(kind: Kind, records: List<StoredRecord>, storeStatus: (String) -> String, zone: ZoneId): List<List<String>> {
        val cols = Columns.forKind(kind)
        val header = listOf("门店", "门店状态", "日期") + cols.map { it.header } + listOf("首次采集", "最近采集")
        val rows = records.map { r ->
            listOf(r.storeId, storeStatus(r.storeId), r.effectiveDate(zone)) + cols.map { r.fields[it.field].orEmpty() } + listOf(fmt(r.firstSeen, zone), fmt(r.lastSeen, zone))
        }
        return listOf(header) + rows
    }

    fun stores(stores: List<StoredStore>, zone: ZoneId): List<List<String>> =
        listOf(listOf("门店", "状态", "首次识别", "最近看到")) + stores.map { listOf(it.name, it.status, fmt(it.firstSeen, zone), fmt(it.lastSeen, zone)) }
}

object Csv {
    /** 以 `=` `@` 开头，或以 `+` `-` 开头但不是数字的单元格，前面补一个单引号，避免在表格软件里被当成公式执行。 */
    fun guard(cell: String): String {
        if (cell.isEmpty()) return cell
        val c = cell[0]
        val risky = c == '=' || c == '@' || c == '\t' || c == '\r' ||
            ((c == '+' || c == '-') && cell.toDoubleOrNull() == null)
        return if (risky) "'$cell" else cell
    }

    private fun quote(cell: String): String {
        val s = guard(cell)
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    /** UTF-8 带 BOM（Excel 直接打开不乱码），行尾 CRLF。 */
    fun encode(rows: List<List<String>>): ByteArray {
        val sb = StringBuilder().append(0xFEFF.toChar())
        rows.forEach { sb.append(it.joinToString(",") { c -> quote(c) }).append("\r\n") }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}

/** 最小的 .xlsx 写入：只写文字单元格，用 java.util.zip，不引入第三方库。 */
object Xlsx {
    data class Sheet(val name: String, val rows: List<List<String>>)

    fun sheetName(raw: String, used: Set<String> = emptySet()): String {
        var n = raw.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().take(31).ifEmpty { "Sheet" }
        var i = 2
        while (n in used) { val suffix = "($i)"; n = n.take(31 - suffix.length) + suffix; i++ }
        return n
    }

    fun column(index: Int): String {
        var n = index
        val sb = StringBuilder()
        do { sb.append('A' + n % 26); n = n / 26 - 1 } while (n >= 0)
        return sb.reverse().toString()
    }

    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            val ok = ch == '\t' || ch == '\n' || ch == '\r' || ch.code in 0x20..0xD7FF || ch.code in 0xE000..0xFFFD
            if (!ok) continue
            when (ch) {
                '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;"); '"' -> sb.append("&quot;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun build(sheets: List<Sheet>): ByteArray {
        val names = ArrayList<String>()
        sheets.forEach { names += sheetName(it.name, names.toSet()) }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            val head = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
            put("[Content_Types].xml", head + """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
                sheets.indices.joinToString("") { """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" } + "</Types>")
            put("_rels/.rels", head + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", head + """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
                names.mapIndexed { i, n -> """<sheet name="${esc(n)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("") + "</sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", head + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                sheets.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" } + "</Relationships>")
            sheets.forEachIndexed { si, sheet ->
                val sb = StringBuilder(head).append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
                sheet.rows.forEachIndexed { r, row ->
                    sb.append("""<row r="${r + 1}">""")
                    row.forEachIndexed { c, cell ->
                        if (cell.isNotEmpty()) sb.append("""<c r="${column(c)}${r + 1}" t="inlineStr"><is><t xml:space="preserve">${esc(cell)}</t></is></c>""")
                    }
                    sb.append("</row>")
                }
                sb.append("</sheetData></worksheet>")
                put("xl/worksheets/sheet${si + 1}.xml", sb.toString())
            }
        }
        return out.toByteArray()
    }
}
