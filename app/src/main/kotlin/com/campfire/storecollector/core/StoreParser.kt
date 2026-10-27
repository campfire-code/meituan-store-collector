package com.campfire.storecollector.core

/**
 * 门店切换页：美团商家版原生列表，每一行有两个带 id 的文字控件，
 * 门店名 `tv_poi_name`、门店状态 `shop_status`（id 名来自参考仓库的实机采集脚本，本工具未在真机上再次核实）。
 */
object StoreParser {
    const val NAME_ID = "$MERCHANT_PACKAGE:id/tv_poi_name"
    const val STATUS_ID = "$MERCHANT_PACKAGE:id/shop_status"

    /** 参考仓库观察到的六种状态；遇到别的状态原样保留。 */
    val KNOWN_STATUSES = listOf("营业中", "仅接受预订", "停业中", "已下线", "门店上线中", "已打烊")

    fun hasStoreRows(root: NodeInfo?): Boolean = root != null && containsId(root, NAME_ID) && containsId(root, STATUS_ID)

    private fun containsId(n: NodeInfo, id: String): Boolean = n.viewId == id || n.children.any { containsId(it, id) }

    private fun firstText(n: NodeInfo, id: String): String? {
        if (n.viewId == id) return (n.text ?: n.desc)?.trim()
        for (c in n.children) firstText(c, id)?.let { return it }
        return null
    }

    fun parse(root: NodeInfo?): List<ParsedStore> {
        if (root == null) return emptyList()
        val out = LinkedHashMap<String, ParsedStore>()
        fun rows(n: NodeInfo): List<ParsedStore> {
            val fromChildren = n.children.flatMap { rows(it) }
            if (fromChildren.isNotEmpty()) return fromChildren
            val name = firstText(n, NAME_ID)
            val status = firstText(n, STATUS_ID)
            return if (!name.isNullOrBlank() && !status.isNullOrBlank()) listOf(ParsedStore(Text.norm(name), Text.norm(status))) else emptyList()
        }
        rows(root).forEach { out[it.name] = it }
        return out.values.toList()
    }
}
