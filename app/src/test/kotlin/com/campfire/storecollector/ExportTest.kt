package com.campfire.storecollector

import com.campfire.storecollector.core.Csv
import com.campfire.storecollector.core.Ingest
import com.campfire.storecollector.core.Kind
import com.campfire.storecollector.core.RecordView
import com.campfire.storecollector.core.Tables
import com.campfire.storecollector.core.Xlsx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.ZoneId
import java.util.zip.ZipInputStream

class ExportTest {
    private val BOM = 0xFEFF.toChar()
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun csvText(rows: List<List<String>>) = String(Csv.encode(rows), Charsets.UTF_8)

    @Test fun CSV带BOM_行尾CRLF() {
        val t = csvText(listOf(listOf("a", "b"), listOf("1", "2")))
        assertTrue(t.startsWith("${BOM}"))
        assertEquals("${BOM}a,b\r\n1,2\r\n", t)
    }

    @Test fun CSV里的逗号引号换行被正确转义() {
        val t = csvText(listOf(listOf("含,逗号", "含\"引号\"", "两\n行")))
        assertEquals("${BOM}\"含,逗号\",\"含\"\"引号\"\"\",\"两\n行\"\r\n", t)
    }

    @Test fun 以公式字符开头的文字被中和_数字不受影响() {
        assertEquals("'=SUM(A1)", Csv.guard("=SUM(A1)"))
        assertEquals("'@cmd", Csv.guard("@cmd"))
        assertEquals("'+abc", Csv.guard("+abc"))
        assertEquals("'-abc", Csv.guard("-abc"))
        assertEquals("-12.5", Csv.guard("-12.5"))
        assertEquals("28.50", Csv.guard("28.50"))
        assertEquals("", Csv.guard(""))
    }

    @Test fun 订单表格有中文表头和门店列() {
        val repo = MemoryRepo()
        Ingest.ingestRecords(repo, "示例门店 A 店（演示）", Fixtures.page("orders.json").records, 1_793_000_000_000L)
        val rows = Tables.records(Kind.ORDER, repo.records.values.toList(), { "营业中" }, zone)
        assertEquals(5, rows.size)
        assertEquals(listOf("门店", "门店状态", "日期", "序号"), rows[0].take(4))
        assertEquals("首次采集", rows[0][rows[0].size - 2])
        assertEquals(listOf("示例门店 A 店（演示）", "营业中", "2026-10-27", "1"), rows[1].take(4))
        assertEquals(rows[0].size, rows[1].size)
        assertEquals("2026-10-26 15:33:20", rows[1].last())
    }

    @Test fun 每种记录的列定义字段名不重复() {
        Kind.entries.forEach { k ->
            val cols = com.campfire.storecollector.core.Columns.forKind(k)
            assertEquals(k.label, cols.size, cols.map { it.field }.toSet().size)
            assertEquals(k.label, cols.size, cols.map { it.header }.toSet().size)
        }
    }

    @Test fun xlsx是合法的zip_内容正确转义() {
        val bytes = Xlsx.build(listOf(Xlsx.Sheet("订单", listOf(listOf("门店", "备注"), listOf("A&B", "<x> \"y\"\u0001")))))
        val entries = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; entries[e.name] = String(z.readBytes(), Charsets.UTF_8) }
        }
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/worksheets/sheet1.xml"), entries.keys)
        val sheet = entries["xl/worksheets/sheet1.xml"]!!
        assertTrue(sheet.contains("""<c r="A1" t="inlineStr"><is><t xml:space="preserve">门店</t></is></c>"""))
        assertTrue(sheet.contains("A&amp;B"))
        assertTrue(sheet.contains("&lt;x&gt; &quot;y&quot;")) // 控制字符 \u0001 被丢弃
        assertTrue(entries["xl/workbook.xml"]!!.contains("""<sheet name="订单" sheetId="1" r:id="rId1"/>"""))
    }

    @Test fun xlsx空单元格不写_多个工作表() {
        val bytes = Xlsx.build(listOf(Xlsx.Sheet("甲", listOf(listOf("", "x"))), Xlsx.Sheet("甲", listOf(listOf("y")))))
        val names = ArrayList<String>()
        var sheet1 = ""
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; names += e.name; if (e.name.endsWith("sheet1.xml")) sheet1 = String(z.readBytes()) }
        }
        assertTrue(names.contains("xl/worksheets/sheet2.xml"))
        assertTrue(!sheet1.contains("""r="A1""""))
        assertTrue(sheet1.contains("""r="B1""""))
    }

    @Test fun 工作表名和列号() {
        assertEquals("a b c", Xlsx.sheetName("a/b:c"))
        assertEquals(31, Xlsx.sheetName("x".repeat(40)).length)
        assertEquals("甲(2)", Xlsx.sheetName("甲", setOf("甲")))
        assertEquals("A", Xlsx.column(0)); assertEquals("Z", Xlsx.column(25)); assertEquals("AA", Xlsx.column(26)); assertEquals("AB", Xlsx.column(27))
    }

    @Test fun 列表显示文字() {
        val repo = MemoryRepo()
        Ingest.ingestRecords(repo, "A", Fixtures.page("orders.json").records + Fixtures.page("finance.json").records + Fixtures.page("products.json").records, 1)
        val o = repo.findRecord("A", Kind.ORDER, "2026-10-27|3")!!
        assertEquals("#3  待出餐", RecordView.title(o))
        assertTrue(RecordView.subtitle(o).contains("预计 12:05"))
        val f = repo.records.values.first { it.kind == Kind.FINANCE && it.fields["displaySequence"] == "5" }
        assertEquals("#5  已完单  ¥28.50", RecordView.title(f))
        val p = repo.findRecord("A", Kind.PRODUCT, "示例双拼套餐")!!
        assertEquals("在售 · ¥32.00 · 月售 56 · 库存 30 · 8.4折", RecordView.subtitle(p))
    }
}
