package com.campfire.storecollector

import com.campfire.storecollector.core.FinanceParser
import com.campfire.storecollector.core.Kind
import com.campfire.storecollector.core.NodeInfo
import com.campfire.storecollector.core.OrderParser
import com.campfire.storecollector.core.PageClassifier
import com.campfire.storecollector.core.PageText
import com.campfire.storecollector.core.PageType
import com.campfire.storecollector.core.ProductParser
import com.campfire.storecollector.core.RefundParser
import com.campfire.storecollector.core.ReviewParser
import com.campfire.storecollector.core.StoreParser
import com.campfire.storecollector.core.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PageTextTest {
    private fun tv(text: String?, top: Int, left: Int, w: Int = 100, h: Int = 40, pw: Boolean = false, desc: String? = null) =
        NodeInfo("TextView", text = text, desc = desc, top = top, left = left, right = left + w, bottom = top + h, password = pw)

    @Test fun 同一行的控件按从左到右排() {
        val root = NodeInfo("Frame", right = 1000, bottom = 1000, children = listOf(tv("右", 103, 300), tv("左", 100, 10), tv("下一行", 200, 10)))
        assertEquals(listOf("左", "右", "下一行"), PageText.texts(root))
    }

    @Test fun 密码框和零尺寸控件被跳过() {
        val root = NodeInfo("Frame", right = 1000, bottom = 1000, children = listOf(tv("secret", 10, 10, pw = true), tv("隐藏", 20, 10, w = 0), tv("可见", 30, 10)))
        assertEquals(listOf("可见"), PageText.texts(root))
    }

    @Test fun 没有文字时读无障碍描述并按换行拆成多行() {
        val root = NodeInfo("Frame", right = 1000, bottom = 1000, children = listOf(tv(null, 10, 10, desc = "第一行\n第二行\n\n")))
        assertEquals(listOf("第一行", "第二行"), PageText.texts(root))
    }

    @Test fun 同一位置的重复文字只留一份() {
        val root = NodeInfo("Frame", right = 1000, bottom = 1000, children = listOf(tv("甲", 10, 10), tv("甲", 10, 10), tv("甲", 10, 500)))
        assertEquals(2, PageText.texts(root).size)
    }
}

class ClassifierTest {
    @Test fun 各类样例页面识别正确() {
        val expect = mapOf(
            "stores.json" to PageType.STORES, "orders.json" to PageType.ORDERS, "orders-scroll2.json" to PageType.ORDERS,
            "products.json" to PageType.PRODUCTS, "reviews.json" to PageType.REVIEWS, "finance.json" to PageType.FINANCE,
            "refunds.json" to PageType.REFUNDS, "no-text.json" to PageType.NO_TEXT, "unknown.json" to PageType.UNKNOWN,
        )
        expect.forEach { (file, type) -> assertEquals(file, type, PageClassifier.classify(Fixtures.node(file))) }
    }

    @Test fun 没有节点树时按未识别处理() {
        assertEquals(PageType.UNKNOWN, PageClassifier.classify(null))
    }

    @Test fun 读不到文字的页面给出说明且没有记录() {
        val p = Fixtures.page("no-text.json")
        assertTrue(p.records.isEmpty())
        assertTrue(p.note.contains("没有向无障碍服务提供文字"))
    }
}

class StoreParserTest {
    @Test fun 识别门店名和状态() {
        val stores = StoreParser.parse(Fixtures.node("stores.json"))
        assertEquals(6, stores.size)
        assertEquals("示例门店 A 店（演示）", stores[0].name)
        assertEquals("营业中", stores[0].status)
        assertEquals(listOf("营业中", "仅接受预订", "停业中", "已打烊", "营业中", "门店上线中"), stores.map { it.status })
    }

    @Test fun 门店名重复只留一条() {
        val s = Fixtures.page("stores.json").stores
        assertEquals(s.map { it.name }.toSet().size, s.size)
    }

    @Test fun 没有门店行时不当作门店页() {
        assertFalse(StoreParser.hasStoreRows(Fixtures.node("orders.json")))
    }
}

class OrderParserTest {
    private val page = Fixtures.page("orders.json")

    @Test fun 解析出四张订单卡片和页面汇总() {
        assertEquals(4, page.records.size)
        assertEquals(6, page.expectedCount)
        assertEquals("6", page.summary["共（单）"])
        assertEquals("1", page.summary["已取消"])
        assertEquals(listOf("2026-10-27|1", "2026-10-27|2", "2026-10-27|3", "2026-10-27|4"), page.records.map { it.key })
    }

    @Test fun 已送达的订单字段齐全() {
        val r = page.records[0]
        assertEquals("已送达（用户已收餐）", r.state)
        assertEquals("2026-10-27", r.bizDate)
        assertEquals("11:20", r.fields["expectedDeliveryTime"])
        assertEquals("11:15", r.fields["deliveredAt"])
        assertEquals("03:20", r.fields["mealPreparationDuration"])
        assertEquals("480", r.fields["referenceMealSeconds"])
        assertEquals("<1.2km", r.fields["distance"])
        assertEquals("3", r.fields["customerOrderCount"])
        assertEquals("神抢手、用户取消险", r.fields["serviceTags"])
    }

    @Test fun 一行写完和分几行写的卡片头都能读() {
        assertEquals("11:40", page.records[1].fields["expectedDeliveryTime"]) // "#2 10-27 11:40前送达"
        assertEquals("12:25", page.records[3].fields["expectedDeliveryTime"]) // "#4" / "10-27" / "12:25前送达"
        assertEquals("配送中", page.records[3].state)
        assertEquals("待出餐", page.records[2].state)
    }

    @Test fun 不读取顾客称呼和手机尾号() {
        val keys = page.records.flatMap { it.fields.keys }.toSet()
        assertTrue(keys.none { it.contains("customerName") || it.contains("phone") || it.contains("address") })
    }

    @Test fun 序号和日期粘在一起的写法() {
        val p = OrderParser.parse(listOf("共1单", "#1210-27 12:30前送达", "下单2次"), LocalDate.of(2026, 10, 27))
        assertEquals("2026-10-27|12", p.records.single().key)
        assertEquals("12:30", p.records.single().fields["expectedDeliveryTime"])
    }

    @Test fun 没有日期时按今天算() {
        val p = OrderParser.parse(listOf("#7", "待出餐"), LocalDate.of(2026, 10, 27))
        assertEquals("2026-10-27|7", p.records.single().key)
    }

    @Test fun 跨年的日期补成去年() {
        assertEquals(LocalDate.of(2025, 12, 30), Text.resolveMonthDay(12, 30, LocalDate.of(2026, 1, 2)))
        assertEquals(LocalDate.of(2026, 10, 28), Text.resolveMonthDay(10, 28, LocalDate.of(2026, 10, 27)))
        assertNull(Text.resolveMonthDay(2, 30, LocalDate.of(2026, 10, 27)))
    }

    @Test fun 展开的卡片带订单号和预计收入() {
        val p = OrderParser.parse(listOf("#3 10-27 12:05前送达", "订单号 9900000000010003", "预计收入 ¥23.80"), LocalDate.of(2026, 10, 27))
        assertEquals("9900000000010003", p.records.single().fields["orderNumber"])
        assertEquals("23.80", p.records.single().fields["expectedIncome"])
    }
}

class ProductParserTest {
    private val page = Fixtures.page("products.json")

    @Test fun 解析五个商品和页面汇总() {
        assertEquals(listOf("经典示例牛肉饭", "示例双拼套餐", "示例小份卤味", "示例招牌豆浆", "示例老款饭团"), page.records.map { it.key })
        assertEquals("5", page.summary["全部"])
        assertEquals("1", page.summary["已下架"])
        assertEquals(5, page.expectedCount)
    }

    @Test fun 价格库存折扣() {
        val a = page.records[0].fields
        assertEquals("128", a["monthlySales"]); assertEquals("无限", a["stock"]); assertEquals("26.00", a["price"])
        val b = page.records[1].fields
        assertEquals("30", b["stock"]); assertEquals("32.00", b["price"]); assertEquals("38.00", b["originalPrice"])
        assertEquals("8.4", b["discount"]); assertEquals("套餐", b["tags"])
        assertEquals("单点不送", page.records[2].fields["tags"])
    }

    @Test fun 状态() {
        assertEquals(listOf("在售", "在售", "在售", "已售罄", "已下架"), page.records.map { it.state })
        assertTrue(page.records.all { it.bizDate == null })
    }
}

class ReviewParserTest {
    private val page = Fixtures.page("reviews.json")

    @Test fun 解析三条评价() {
        assertEquals(3, page.records.size)
        assertEquals(3, page.expectedCount)
        assertEquals(listOf("2026-10-26", "2026-10-25", "2026-10-25"), page.records.map { it.bizDate })
    }

    @Test fun 评分正文图片() {
        val f = page.records[0].fields
        assertEquals("5", f["productScore"]); assertEquals("5", f["packagingScore"]); assertEquals("满意", f["deliveryScore"])
        assertEquals("示例评价：分量足，味道不错，送得也快。", f["content"])
        assertEquals("2", f["imageCount"])
        assertEquals("回复、申诉", f["visibleActions"])
        assertEquals("未回复", page.records[0].state)
    }

    @Test fun 商家回复() {
        val r = page.records[1]
        assertEquals("已回复", r.state)
        assertEquals("不满意", r.fields["deliveryScore"])
        assertTrue(r.fields["replyText"]!!.startsWith("抱歉"))
        assertEquals("示例评价：饭有点凉了。", r.fields["content"])
    }

    @Test fun 不保存打码昵称() {
        assertTrue(page.records.none { r -> r.fields.values.any { it.contains("张**") || it.contains("李**") } })
    }

    @Test fun 记录键互不相同且重复读取保持稳定() {
        assertEquals(3, page.records.map { it.key }.toSet().size)
        assertEquals(page.records.map { it.key }, Fixtures.page("reviews.json").records.map { it.key })
    }

    @Test fun 一页里两条完全一样的评价靠序号区分() {
        val lines = listOf("2026-10-20", "赵**", "商品 5 包装 5", "配送 满意", "示例：很好", "2026-10-20", "赵**", "商品 5 包装 5", "配送 满意", "示例：很好")
        val p = ReviewParser.parse(lines)
        assertEquals(2, p.records.size)
        assertTrue(p.records[1].key.endsWith("#2"))
    }
}

class FinanceParserTest {
    private val page = Fixtures.page("finance.json")

    @Test fun 同一天同序号的取消单和完单是两条() {
        assertEquals(4, page.records.size)
        val nines = page.records.filter { it.fields["displaySequence"] == "9" }
        assertEquals(2, nines.size)
        assertEquals(setOf("已完单", "已取消"), nines.map { it.state }.toSet())
        assertEquals(2, nines.map { it.key }.toSet().size)
    }

    @Test fun 金额时间日期标签() {
        val r = page.records.first { it.fields["displaySequence"] == "5" }
        assertEquals("2026-10-27", r.bizDate)
        assertEquals("28.50", r.fields["settlementAmount"])
        assertEquals("12:40", r.fields["orderTime"])
        assertEquals("神抢手", r.fields["visibleTags"])
        assertEquals("部分退", page.records.first { it.key.startsWith("2026-10-26|9|18:02") }.fields["visibleTags"])
    }

    @Test fun 序号不连续不影响解析() {
        assertEquals(setOf("5", "6", "9"), page.records.map { it.fields["displaySequence"] }.toSet())
    }

    @Test fun 没有日期的卡片被跳过并计数() {
        val p = FinanceParser.parse(listOf("#1 已完单 ¥ 10.00", "下单时间 10:00"), LocalDate.of(2026, 10, 27))
        assertEquals(0, p.records.size)
        assertEquals(1, p.skipped)
    }

    @Test fun 状态归一() {
        assertEquals("已取消", FinanceParser.statusOf("已 取消"))
        assertEquals("已完单", FinanceParser.statusOf("已完单"))
        assertEquals("进行中", FinanceParser.statusOf("进行中"))
        assertEquals("未知", FinanceParser.statusOf("???"))
    }
}

class RefundParserTest {
    private val page = Fixtures.page("refunds.json")

    @Test fun 以订单号为键解析两张卡片() {
        assertEquals(listOf("9900000000010001", "9900000000010002"), page.records.map { it.key })
    }

    @Test fun 状态时间原因() {
        val a = page.records[0]
        assertEquals("顾客取消退款申请", a.state)
        assertEquals("2026-10-26 19:20", a.fields["cardTime"])
        assertEquals("2026-10-26 18:40", a.fields["orderPlacedAt"])
        assertEquals("示例原因，顾客临时有事", a.fields["reason"])
        val b = page.records[1]
        assertEquals("部分退款", b.state)
        assertEquals("餐损可获赔", b.fields["mealLossStates"])
        assertEquals("展开", b.fields["visibleActions"])
        assertEquals("2026-10-25", b.bizDate)
    }

    @Test fun 订单号里的空格会去掉() {
        val p = RefundParser.parse(listOf("10-25 12:10", "订单号 9900 0000 0001 0009"), LocalDate.of(2026, 10, 27))
        assertEquals("9900000000010009", p.records.single().key)
    }

    @Test fun 被截断的卡片只有部分字段() {
        val p = Fixtures.page("refunds-partial.json")
        assertEquals(1, p.records.size)
        assertNotNull(p.records[0].fields["cardTime"])
        assertNull(p.records[0].fields["orderPlacedAt"])
    }
}
