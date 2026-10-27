package com.campfire.storecollector.core

/** 列表里每条记录显示的两行文字。 */
object RecordView {
    fun title(r: StoredRecord): String = when (r.kind) {
        Kind.ORDER -> "#${r.fields["displaySequence"].orEmpty()}  ${r.state}"
        Kind.PRODUCT -> r.fields["name"].orEmpty()
        Kind.REVIEW -> "${r.fields["reviewDate"].orEmpty()}  ${r.state}"
        Kind.FINANCE -> "#${r.fields["displaySequence"].orEmpty()}  ${r.fields["orderStatus"].orEmpty()}" +
            (r.fields["settlementAmount"]?.let { "  ¥$it" } ?: "")
        Kind.REFUND -> r.fields["orderNumber"].orEmpty()
    }

    fun subtitle(r: StoredRecord): String = when (r.kind) {
        Kind.ORDER -> listOfNotNull(
            r.fields["expectedDeliveryTime"]?.let { "预计 $it" }, r.fields["deliveredAt"]?.let { "$it 送达" },
            r.fields["distance"], r.fields["customerOrderCount"]?.let { "顾客下单 $it 次" }, r.fields["serviceTags"],
        ).joinToString(" · ")
        Kind.PRODUCT -> listOfNotNull(
            r.state, r.fields["price"]?.let { "¥$it" }, r.fields["monthlySales"]?.let { "月售 $it" }, r.fields["stock"]?.let { "库存 $it" }, r.fields["discount"]?.let { "${it}折" },
        ).joinToString(" · ")
        Kind.REVIEW -> listOfNotNull(
            r.fields["productScore"]?.let { "商品 $it" }, r.fields["packagingScore"]?.let { "包装 $it" }, r.fields["deliveryScore"]?.let { "配送 $it" }, r.fields["content"],
        ).joinToString(" · ")
        Kind.FINANCE -> listOfNotNull(r.fields["orderDate"], r.fields["orderTime"], r.fields["visibleTags"]).joinToString(" · ")
        Kind.REFUND -> listOfNotNull(r.fields["cardTime"], r.fields["refundStates"], r.fields["orderStates"], r.fields["mealLossStates"], r.fields["reason"]).joinToString(" · ")
    }
}
