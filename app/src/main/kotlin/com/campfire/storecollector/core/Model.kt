package com.campfire.storecollector.core

import java.security.MessageDigest

/** 美团商家版（外卖）的应用包名。无障碍服务只对这个应用生效，其他应用的内容读取不到。 */
const val MERCHANT_PACKAGE = "com.sankuai.meituan.meituanwaimaibusiness"

/** 记录类型：订单、商品、评价、财务（对账里的订单流水）、售后（退款历史）。 */
enum class Kind(val key: String, val label: String) {
    ORDER("order", "订单"),
    PRODUCT("product", "商品"),
    REVIEW("review", "评价"),
    FINANCE("finance", "财务"),
    REFUND("refund", "售后");

    companion object {
        fun fromKey(key: String): Kind? = entries.firstOrNull { it.key == key }
    }
}

/** 识别出的页面类型。NO_TEXT 表示页面属于美团但没有向无障碍服务暴露文字（常见于自绘页面）。 */
enum class PageType(val label: String) {
    STORES("门店切换页"),
    ORDERS("订单列表"),
    PRODUCTS("商品管理"),
    REVIEWS("顾客评价"),
    FINANCE("财务对账订单"),
    REFUNDS("售后退款"),
    NO_TEXT("读不到文字的页面"),
    UNKNOWN("未识别的页面"),
}

/** 解析出的一条业务记录。[key] 在同一门店、同一类型内唯一，是去重的依据。 */
data class ParsedRecord(
    val kind: Kind,
    val key: String,
    val state: String,
    /** 业务日期 yyyy-MM-dd；页面上没有日期的记录（如商品）为 null。 */
    val bizDate: String?,
    val fields: Map<String, String>,
)

data class ParsedStore(val name: String, val status: String)

/** 一页（一个屏幕）解析的结果。[summary] 是页面顶部的汇总数字，[expectedCount] 是页面标示的总条数（如「共 23 单」）。 */
data class ParsedPage(
    val type: PageType,
    val records: List<ParsedRecord> = emptyList(),
    val stores: List<ParsedStore> = emptyList(),
    val summary: Map<String, String> = emptyMap(),
    val expectedCount: Int? = null,
    val note: String = "",
    val skipped: Int = 0,
)

object Hashing {
    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun short(text: String, n: Int = 16): String = sha256Hex(text).take(n)
}
