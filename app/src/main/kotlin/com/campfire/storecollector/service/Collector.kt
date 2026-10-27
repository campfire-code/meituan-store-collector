package com.campfire.storecollector.service

import android.content.Context
import com.campfire.storecollector.core.Ingest
import com.campfire.storecollector.core.IngestResult
import com.campfire.storecollector.core.MERCHANT_PACKAGE
import com.campfire.storecollector.core.NodeInfo
import com.campfire.storecollector.core.PageClassifier
import com.campfire.storecollector.core.PageText
import com.campfire.storecollector.core.PageType
import com.campfire.storecollector.core.ParsedPage
import com.campfire.storecollector.core.ScrollSession
import com.campfire.storecollector.data.Db
import java.time.ZoneId

data class CaptureResult(val ok: Boolean, val message: String)

/** 采集流程：读当前页 → 解析 → 去重入库。只读，不点击页面里的任何东西。 */
object Collector {
    @Volatile private var db: Db? = null
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun db(context: Context): Db = db ?: synchronized(this) { db ?: Db(context.applicationContext).also { db = it } }

    private const val SETTLE_MS = 900L

    private fun fail(msg: String) = CaptureResult(false, msg)

    private fun snapshot(): Pair<NodeInfo?, String?> {
        val svc = CollectorAccessibilityService.instance ?: return null to "无障碍服务未开启，请先在系统设置里开启"
        val snap = svc.snapshot() ?: return null to "读不到当前页面，请先打开美团商家版"
        if (snap.first != MERCHANT_PACKAGE) return null to "当前不在美团商家版（现在是 ${snap.first}），请先切到美团商家版的目标页面"
        return snap.second to null
    }

    /** 读当前这一屏。 */
    fun readOnce(context: Context): CaptureResult {
        val (root, err) = snapshot()
        if (root == null) return log(context, "", PageType.UNKNOWN, fail(err ?: "读取失败"))
        val page = PageClassifier.parse(root, Ingest.today(System.currentTimeMillis(), zone))
        return store(context, page)
    }

    /** 一路向上滑动、每屏读一次，直到滑不动或达到上限。[shouldStop] 为 true 时立刻停。 */
    fun readToBottom(context: Context, shouldStop: () -> Boolean): CaptureResult {
        val session = ScrollSession()
        var total = IngestResult()
        var type = PageType.UNKNOWN
        while (true) {
            if (shouldStop()) return log(context, "", type, CaptureResult(true, "已手动停止；" + session.summary()))
            val (root, err) = snapshot()
            if (root == null) return log(context, "", type, fail((err ?: "读取失败") + "；" + session.summary()))
            val lines = PageText.texts(root)
            val page = PageClassifier.parse(root, Ingest.today(System.currentTimeMillis(), zone))
            if (page.type == PageType.UNKNOWN || page.type == PageType.NO_TEXT) {
                return log(context, "", page.type, fail(page.note + if (session.steps > 0) "；" + session.summary() else ""))
            }
            type = page.type
            total += ingest(context, page).second
            Runtime.setMessage("正在读第 ${session.steps + 1} 屏（${page.type.label}），已读到 ${session.seenCount} 条")
            if (!session.onRead(page, lines)) break
            val svc = CollectorAccessibilityService.instance ?: return log(context, "", type, fail("无障碍服务已断开；" + session.summary()))
            if (!svc.swipeUp()) return log(context, "", type, fail("滑动失败；" + session.summary()))
            Thread.sleep(SETTLE_MS)
        }
        val msg = "${type.label}：新增 ${total.inserted}，更新 ${total.updated}（含重复读到的未变记录 ${total.unchanged} 次）。${session.summary()}"
        return log(context, "", type, CaptureResult(true, msg))
    }

    private fun store(context: Context, page: ParsedPage): CaptureResult {
        if (page.type == PageType.UNKNOWN || page.type == PageType.NO_TEXT) return log(context, "", page.type, fail(page.note))
        val (storeId, r) = ingest(context, page)
        val head = if (page.type == PageType.STORES) "门店切换页：识别到 ${page.stores.size} 家门店" else "${page.type.label}（${storeId}）：读到 ${page.records.size} 条"
        val tail = "新增 ${r.inserted}，更新 ${r.updated}，未变 ${r.unchanged}"
        val extra = buildString {
            if (page.skipped > 0) append("；${page.skipped} 条缺少关键字段已跳过")
            page.expectedCount?.let { append("；页面标示 $it 条") }
        }
        return log(context, storeId, page.type, CaptureResult(true, "$head，$tail$extra"))
    }

    private fun ingest(context: Context, page: ParsedPage): Pair<String, IngestResult> {
        val db = db(context)
        val now = System.currentTimeMillis()
        val storeId = Runtime.currentStore.value
        val res = db.tx {
            if (page.type == PageType.STORES) {
                Ingest.ingestStores(db, page.stores, now)
            } else {
                Ingest.ensureUnassignedStore(db, now)
                Ingest.ingestRecords(db, storeId, page.records, now)
            }
        }
        return storeId to res
    }

    private fun log(context: Context, storeId: String, type: PageType, r: CaptureResult): CaptureResult {
        val id = storeId.ifEmpty { Runtime.currentStore.value }
        runCatching { db(context).addRun(System.currentTimeMillis(), id, type.label, r.ok, r.message) }
        Runtime.setMessage(r.message)
        return r
    }
}
