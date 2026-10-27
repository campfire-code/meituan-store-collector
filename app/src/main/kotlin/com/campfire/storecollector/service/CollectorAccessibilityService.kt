package com.campfire.storecollector.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.campfire.storecollector.core.NodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 无障碍服务：只做两件事——读取美团商家版当前页面的控件文字，以及向上滑动一屏（用来读长列表）。
 * 不点击、不输入、不截图。配置里用 packageNames 把服务限定在美团商家版，其他应用的内容读取不到。
 * 读取和手势会阻塞等待，只能在后台线程调用。
 */
class CollectorAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Runtime.setAccessibility(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        if (instance === this) instance = null
        Runtime.setAccessibility(false)
    }

    /** 当前窗口的包名和精简节点树；读不到返回 null。 */
    fun snapshot(): Pair<String, NodeInfo>? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        val counter = intArrayOf(0)
        return pkg to toInfo(root, 0, counter)
    }

    private fun toInfo(node: AccessibilityNodeInfo, depth: Int, counter: IntArray): NodeInfo {
        counter[0]++
        val r = Rect().also { node.getBoundsInScreen(it) }
        val children = ArrayList<NodeInfo>()
        if (depth < MAX_DEPTH) {
            for (i in 0 until node.childCount) {
                if (counter[0] >= MAX_NODES) break
                val c = node.getChild(i) ?: continue
                children += toInfo(c, depth + 1, counter)
            }
        }
        return NodeInfo(
            className = node.className?.toString().orEmpty(),
            text = node.text?.toString(),
            desc = node.contentDescription?.toString(),
            viewId = node.viewIdResourceName,
            left = r.left, top = r.top, right = r.right, bottom = r.bottom,
            clickable = node.isClickable,
            password = node.isPassword,
            children = children,
        )
    }

    /** 在屏幕中部向上滑动一屏（手指从下往上），用来翻到列表的下一屏。 */
    fun swipeUp(): Boolean {
        val m = resources.displayMetrics
        val x = m.widthPixels * 0.62f
        val path = Path().apply { moveTo(x, m.heightPixels * 0.75f); lineTo(x, m.heightPixels * 0.35f) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 500)).build()
        val done = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { completed.set(true); done.countDown() }
            override fun onCancelled(g: GestureDescription?) { done.countDown() }
        }, null)
        if (!accepted) return false
        done.await(3, TimeUnit.SECONDS)
        return completed.get()
    }

    companion object {
        private const val MAX_DEPTH = 40
        private const val MAX_NODES = 4000

        @Volatile
        var instance: CollectorAccessibilityService? = null
            private set
    }
}
