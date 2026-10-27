package com.campfire.storecollector

import com.campfire.storecollector.core.Ingest
import com.campfire.storecollector.core.IngestResult
import com.campfire.storecollector.core.Kind
import com.campfire.storecollector.core.ParsedRecord
import com.campfire.storecollector.core.ScrollSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class IngestTest {
    private val a = "示例门店 A 店（演示）"
    private val b = "示例门店 B 店（演示）"

    @Test fun 第一次读到全部是新增() {
        val repo = MemoryRepo()
        val r = Ingest.ingestRecords(repo, a, Fixtures.page("orders.json").records, 1000)
        assertEquals(IngestResult(inserted = 4), r)
        assertEquals(4, repo.records.size)
    }

    @Test fun 重复读同一页不产生重复记录() {
        val repo = MemoryRepo()
        val recs = Fixtures.page("orders.json").records
        Ingest.ingestRecords(repo, a, recs, 1000)
        val again = Ingest.ingestRecords(repo, a, recs, 2000)
        assertEquals(IngestResult(unchanged = 4), again)
        assertEquals(4, repo.records.size)
        // 没有变化：只刷新最近采集时间，首次采集时间不变，不新增历史
        val r = repo.records.values.first()
        assertEquals(1000L, r.firstSeen); assertEquals(2000L, r.lastSeen)
        assertEquals(4, repo.recordHistory.size)
    }

    @Test fun 滚动时相邻两屏重叠的记录只存一条() {
        val repo = MemoryRepo()
        val first = Ingest.ingestRecords(repo, a, Fixtures.page("orders.json").records, 1000)
        val second = Ingest.ingestRecords(repo, a, Fixtures.page("orders-scroll2.json").records, 2000)
        assertEquals(4, first.inserted)
        // 第二屏是 #3 #4 #5 #6：#3 #4 重复，#5 #6 新增
        assertEquals(2, second.inserted)
        assertEquals(6, repo.records.size)
    }

    @Test fun 状态变化时更新并留历史() {
        val repo = MemoryRepo()
        Ingest.ingestRecords(repo, a, Fixtures.page("orders.json").records, 1000)
        val r = Ingest.ingestRecords(repo, a, Fixtures.page("orders-later.json").records, 5000)
        assertEquals(1, r.updated) // 只有 #3 从待出餐变成已送达
        assertEquals(2, r.unchanged)
        val rec = repo.findRecord(a, Kind.ORDER, "2026-10-27|3")!!
        assertEquals("已送达", rec.state)
        assertEquals("12:02", rec.fields["deliveredAt"])
        assertEquals(1000L, rec.firstSeen)
        assertEquals(5, repo.recordHistory.size) // 首次 4 条 + 这次变化 1 条
    }

    @Test fun 不同门店的同序号订单互不覆盖() {
        val repo = MemoryRepo()
        Ingest.ingestRecords(repo, a, Fixtures.page("orders.json").records, 1000)
        val r = Ingest.ingestRecords(repo, b, Fixtures.page("orders-b.json").records, 1000)
        assertEquals(2, r.inserted)
        assertEquals(6, repo.records.size)
        assertEquals("待出餐", repo.findRecord(b, Kind.ORDER, "2026-10-27|2")!!.state)
        assertEquals("已送达", repo.findRecord(a, Kind.ORDER, "2026-10-27|1")!!.state.substringBefore('（'))
    }

    @Test fun 卡片被截断时补全字段且不清空已有字段() {
        val repo = MemoryRepo()
        Ingest.ingestRecords(repo, a, Fixtures.page("refunds-partial.json").records, 1000)
        val r1 = repo.findRecord(a, Kind.REFUND, "9900000000010002")!!
        assertNull(r1.fields["orderPlacedAt"])
        Ingest.ingestRecords(repo, a, Fixtures.page("refunds.json").records, 2000)
        val full = repo.findRecord(a, Kind.REFUND, "9900000000010002")!!
        assertEquals("2026-10-25 11:30", full.fields["orderPlacedAt"])
        // 再读到被截断的版本：已补全的字段保留
        val r = Ingest.ingestRecords(repo, a, Fixtures.page("refunds-partial.json").records, 3000)
        assertEquals(1, r.unchanged)
        assertEquals("2026-10-25 11:30", repo.findRecord(a, Kind.REFUND, "9900000000010002")!!.fields["orderPlacedAt"])
    }

    @Test fun 同一页里重复出现的同键记录合并成一条() {
        val repo = MemoryRepo()
        val x = ParsedRecord(Kind.PRODUCT, "k", "在售", null, mapOf("name" to "k", "price" to "1"))
        val y = ParsedRecord(Kind.PRODUCT, "k", "在售", null, mapOf("name" to "k", "stock" to "9"))
        val r = Ingest.ingestRecords(repo, a, listOf(x, y), 1000)
        assertEquals(IngestResult(inserted = 1), r)
        assertEquals(mapOf("name" to "k", "price" to "1", "stock" to "9"), repo.findRecord(a, Kind.PRODUCT, "k")!!.fields)
    }

    @Test fun 同键不同类型互不覆盖() {
        val repo = MemoryRepo()
        Ingest.ingestRecords(repo, a, listOf(ParsedRecord(Kind.ORDER, "1", "x", null, mapOf("a" to "1")), ParsedRecord(Kind.FINANCE, "1", "y", null, mapOf("b" to "2"))), 1)
        assertEquals(2, repo.records.size)
    }

    @Test fun 门店首次识别_状态不变_状态变化() {
        val repo = MemoryRepo()
        val first = Ingest.ingestStores(repo, Fixtures.page("stores.json").stores, 1000)
        assertEquals(6, first.inserted)
        val same = Ingest.ingestStores(repo, Fixtures.page("stores.json").stores, 2000)
        assertEquals(IngestResult(unchanged = 6), same)
        assertEquals(6, repo.storeHistory.size)
        val changed = Ingest.ingestStores(repo, Fixtures.page("stores-later.json").stores, 3000)
        assertEquals(1, changed.updated)
        assertEquals("已打烊", repo.stores[a]!!.status)
        assertEquals(1000L, repo.stores[a]!!.firstSeen)
        assertEquals(listOf("营业中", "已打烊"), repo.storeHistory.filter { it.first == a }.map { it.second })
    }

    @Test fun 按日期查看用业务日期_没有业务日期用最近采集日期() {
        val repo = MemoryRepo()
        val zone = ZoneId.of("Asia/Shanghai")
        Ingest.ingestRecords(repo, a, Fixtures.page("orders.json").records + Fixtures.page("products.json").records, 1_793_000_000_000L)
        val order = repo.findRecord(a, Kind.ORDER, "2026-10-27|1")!!
        val product = repo.findRecord(a, Kind.PRODUCT, "示例招牌豆浆")!!
        assertEquals("2026-10-27", order.effectiveDate(zone))
        assertEquals("2026-10-26", product.effectiveDate(zone)) // 1_793_000_000_000 = 2026-10-26 15:33 UTC+8
    }
}

class ScrollSessionTest {
    @Test fun 连续两屏一模一样就判定到底() {
        val s = ScrollSession()
        val p1 = Fixtures.page("orders.json")
        val lines1 = listOf("a", "b")
        assertTrue(s.onRead(p1, lines1))
        assertTrue(s.onRead(Fixtures.page("orders-scroll2.json"), listOf("c", "d")))
        assertEquals(false, s.onRead(Fixtures.page("orders-scroll2.json"), listOf("c", "d")))
        assertTrue(s.reachedBottom)
        assertEquals(3, s.steps)
        assertEquals(6, s.seenCount)
    }

    @Test fun 超过最大屏数也停() {
        val s = ScrollSession(maxSteps = 3)
        val p = Fixtures.page("orders.json")
        assertTrue(s.onRead(p, listOf("1")))
        assertTrue(s.onRead(p, listOf("2")))
        assertEquals(false, s.onRead(p, listOf("3")))
        assertEquals(false, s.reachedBottom)
        assertTrue(s.summary().contains("没有确认到底"))
    }

    @Test fun 和页面标示的总条数对照() {
        val s = ScrollSession()
        s.onRead(Fixtures.page("orders.json"), listOf("1"))
        s.onRead(Fixtures.page("orders-scroll2.json"), listOf("2"))
        s.onRead(Fixtures.page("orders-scroll2.json"), listOf("2"))
        // orders.json 标示共 6 单，读到 #1~#6 共 6 条
        assertTrue(s.summary(), s.summary().contains("页面标示 6 条，本次读到 6 条，数量一致"))
    }

    @Test fun 数量不一致时如实说明() {
        val s = ScrollSession()
        s.onRead(Fixtures.page("orders.json"), listOf("1"))
        assertTrue(s.summary().contains("数量不一致"))
    }

    @Test fun 页面没有总条数时不下结论() {
        val s = ScrollSession()
        s.onRead(Fixtures.page("finance.json"), listOf("1"))
        assertTrue(s.summary().contains("无法核对"))
    }
}
