package com.campfire.storecollector.service

import android.content.Context
import com.campfire.storecollector.core.Ingest
import com.campfire.storecollector.core.NodeJson
import com.campfire.storecollector.core.PageClassifier
import java.time.ZoneId

/**
 * 「载入示例数据」：把 assets 里虚构的页面节点树，走和真实采集一样的解析、去重、入库流程。
 * 用来试用界面和导出，不需要打开美团。示例门店名都带「演示」二字。
 */
object SampleLoader {
    private val storeA = "示例门店 A 店（演示）"
    private val storeB = "示例门店 B 店（演示）"

    fun load(context: Context): String {
        val db = Collector.db(context)
        val now = System.currentTimeMillis()
        val today = Ingest.today(now, ZoneId.systemDefault())
        fun page(file: String) = PageClassifier.parse(
            NodeJson.parse(context.assets.open(file).bufferedReader(Charsets.UTF_8).use { it.readText() }), today,
        )
        db.tx {
            Ingest.ingestStores(db, page("stores.json").stores, now)
            for (f in listOf("orders.json", "orders-scroll2.json", "products.json", "reviews.json", "finance.json", "refunds.json")) {
                Ingest.ingestRecords(db, storeA, page(f).records, now)
            }
            Ingest.ingestRecords(db, storeB, page("orders-b.json").records, now)
        }
        Runtime.setCurrentStore(context, storeA)
        return "已载入示例数据：6 家演示门店，A 店有订单、商品、评价、财务、售后记录，B 店有 2 条订单"
    }
}
