package com.campfire.storecollector.core

/**
 * 「滚动读到底」的停止判断（纯逻辑）。每滚一屏读一次：
 * - 连续两次读到的整屏文字完全一样，说明滑不动了，到底了（参考仓库叫 stable_bottom）；
 * - 超过最大屏数也停，避免无限滚动；
 * - 记下读到过的记录键，最后和页面标示的总条数对照，给出「读全了 / 少了几条」。
 */
class ScrollSession(private val maxSteps: Int = 80) {
    private var lastSignature: Int? = null
    private var sameCount = 0
    private val seen = LinkedHashSet<String>()
    var steps = 0
        private set
    var expectedCount: Int? = null
        private set
    var reachedBottom = false
        private set

    val seenCount get() = seen.size

    /** 记一屏的结果。返回 true 表示应该继续滚，false 表示停。 */
    fun onRead(page: ParsedPage, lines: List<String>): Boolean {
        steps++
        page.records.forEach { seen += it.kind.key + "|" + it.key }
        page.stores.forEach { seen += "store|" + it.name }
        page.expectedCount?.let { expectedCount = it }
        val sig = lines.hashCode()
        if (sig == lastSignature) sameCount++ else sameCount = 0
        lastSignature = sig
        if (sameCount >= 1) { reachedBottom = true; return false }
        return steps < maxSteps
    }

    fun summary(): String {
        val end = if (reachedBottom) "已滚到底" else "达到最大屏数 $maxSteps 后停止，没有确认到底"
        val exp = expectedCount
        val check = when {
            exp == null -> "页面没有标示总条数，无法核对是否读全"
            seen.size == exp -> "页面标示 $exp 条，本次读到 ${seen.size} 条，数量一致"
            else -> "页面标示 $exp 条，本次读到 ${seen.size} 条，数量不一致（可能没滚全，或页面标示包含其他类别）"
        }
        return "共读 $steps 屏，$end；$check"
    }
}
