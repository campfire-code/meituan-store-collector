package com.campfire.storecollector.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 页面控件的精简描述，和安卓的 AccessibilityNodeInfo 解耦，方便在 JVM 里测试。 */
data class NodeInfo(
    val className: String = "",
    val text: String? = null,
    val desc: String? = null,
    val viewId: String? = null,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val clickable: Boolean = false,
    val password: Boolean = false,
    val children: List<NodeInfo> = emptyList(),
)

/** 样例与测试用的节点树 JSON 格式（全是虚构数据）。 */
@Serializable
data class NodeJson(
    @SerialName("cls") val cls: String = "",
    val text: String? = null,
    val desc: String? = null,
    val id: String? = null,
    /** [left, top, right, bottom] */
    val b: List<Int> = listOf(0, 0, 0, 0),
    val click: Boolean = false,
    val children: List<NodeJson> = emptyList(),
) {
    fun toInfo(): NodeInfo = NodeInfo(
        className = cls, text = text, desc = desc, viewId = id,
        left = b.getOrElse(0) { 0 }, top = b.getOrElse(1) { 0 }, right = b.getOrElse(2) { 0 }, bottom = b.getOrElse(3) { 0 },
        clickable = click, children = children.map { it.toInfo() },
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): NodeInfo = json.decodeFromString<NodeJson>(text).toInfo()
    }
}

/** 页面上一行可见文字。 */
data class Line(val text: String, val top: Int, val left: Int)

object PageText {
    private const val MAX_DEPTH = 40
    private const val MAX_NODES = 4000
    private const val ROW_TOLERANCE = 10

    /**
     * 把节点树拍平成「按从上到下、从左到右」排列的文字行。
     * 文字取 text，没有再取 desc；密码框、零尺寸的节点跳过；同一位置的重复文字只留一份。
     */
    fun lines(root: NodeInfo?): List<Line> {
        if (root == null) return emptyList()
        val raw = ArrayList<Line>()
        var count = 0
        fun walk(n: NodeInfo, depth: Int) {
            if (count >= MAX_NODES || n.password) return
            count++
            val visible = n.right > n.left && n.bottom > n.top
            if (visible) {
                val content = n.text?.takeIf { it.isNotBlank() } ?: n.desc
                content?.split('\n', '\r')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach {
                    raw += Line(it, n.top, n.left)
                }
            }
            if (depth < MAX_DEPTH) n.children.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        // 先按 top 排序，再把 top 相差不大的并成一行，行内按 left 排序
        val sorted = raw.sortedWith(compareBy({ it.top }, { it.left }))
        val rows = ArrayList<MutableList<Line>>()
        var rowTop = Int.MIN_VALUE
        for (l in sorted) {
            if (rows.isEmpty() || l.top - rowTop > ROW_TOLERANCE) {
                rows += mutableListOf(l)
                rowTop = l.top
            } else {
                rows.last() += l
            }
        }
        val seen = HashSet<Triple<String, Int, Int>>()
        return rows.flatMap { row -> row.sortedBy { it.left } }.filter { seen.add(Triple(it.text, it.top, it.left)) }
    }

    fun texts(root: NodeInfo?): List<String> = lines(root).map { it.text }
}
