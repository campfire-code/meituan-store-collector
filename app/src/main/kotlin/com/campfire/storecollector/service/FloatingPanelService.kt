package com.campfire.storecollector.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.Executors

/** 悬浮采集窗：显示当前门店，按钮「读取当前页」「滚动读到底」「上一家 / 下一家」。可拖动。 */
class FloatingPanelService : Service() {
    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var storeLabel: TextView
    private lateinit var msgLabel: TextView
    private lateinit var scrollButton: Button
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val worker = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Runtime.loadCurrentStore(this)
        show()
        Runtime.setFloating(true)
        Runtime.currentStore.onEach { storeLabel.text = "门店：$it" }.launchIn(scope)
        Runtime.message.onEach { if (it.isNotEmpty()) msgLabel.text = it }.launchIn(scope)
        Runtime.busy.onEach { scrollButton.text = if (it) "停止" else "滚动读到底" }.launchIn(scope)
    }

    override fun onDestroy() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        Runtime.setFloating(false)
        scope.cancel()
        worker.shutdown()
        super.onDestroy()
    }

    private fun show() {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply { setColor(Color.argb(230, 22, 32, 27)); cornerRadius = dp(12).toFloat() }
        }
        p.addView(TextView(this).apply { text = "美团门店采集（只读）"; setTextColor(Color.WHITE); textSize = 13f })
        storeLabel = TextView(this).apply { textSize = 13f; setTextColor(Color.rgb(111, 221, 170)); setPadding(0, dp(4), 0, 0); maxWidth = dp(190) }
        p.addView(storeLabel)
        msgLabel = TextView(this).apply { text = "打开要读的页面，点「读取当前页」"; textSize = 12f; setTextColor(Color.rgb(200, 210, 204)); setPadding(0, dp(2), 0, dp(6)); maxWidth = dp(190) }
        p.addView(msgLabel)
        p.addView(button("读取当前页") { readOnce() })
        scrollButton = button("滚动读到底") { toggleScroll() }
        p.addView(scrollButton)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("上一家") { cycleStore(-1) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("下一家") { cycleStore(1) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        p.addView(row)
        p.addView(button("关闭") { stopSelf() })

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = dp(120) }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        p.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; false }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - downX).toInt()
                    params.y = startY + (e.rawY - downY).toInt()
                    wm.updateViewLayout(p, params)
                    true
                }
                else -> false
            }
        }
        panel = p
        wm.addView(p, params)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        minHeight = dp(36)
        minimumHeight = dp(36)
        setOnClickListener { onClick() }
    }

    private fun readOnce() {
        if (Runtime.busy.value) return
        Runtime.setBusy(true)
        worker.execute {
            try { Collector.readOnce(this) } finally { Runtime.setBusy(false) }
        }
    }

    private fun toggleScroll() {
        if (Runtime.busy.value) {
            Runtime.cancelRequested = true
            return
        }
        Runtime.cancelRequested = false
        Runtime.setBusy(true)
        worker.execute {
            try { Collector.readToBottom(this) { Runtime.cancelRequested } } finally { Runtime.setBusy(false); Runtime.cancelRequested = false }
        }
    }

    private fun cycleStore(step: Int) {
        worker.execute {
            val names = Collector.db(this).stores().map { it.id }
            if (names.isEmpty()) return@execute
            val cur = names.indexOf(Runtime.currentStore.value)
            val next = names[((if (cur < 0) 0 else cur + step) % names.size + names.size) % names.size]
            Runtime.setCurrentStore(this, next)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun start(context: Context) = context.startService(Intent(context, FloatingPanelService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, FloatingPanelService::class.java))
    }
}
