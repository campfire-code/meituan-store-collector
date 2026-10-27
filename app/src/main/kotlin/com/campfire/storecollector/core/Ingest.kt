package com.campfire.storecollector.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class StoredStore(
    val id: String,
    val name: String,
    val status: String,
    val firstSeen: Long,
    val lastSeen: Long,
)

data class StoredRecord(
    val storeId: String,
    val kind: Kind,
    val key: String,
    val state: String,
    val bizDate: String?,
    val fields: Map<String, String>,
    val firstSeen: Long,
    val lastSeen: Long,
) {
    /** 按日期查看时用的日期：有业务日期用业务日期（订单、评价、财务、售后），没有的（商品）用最近一次采集的日期。 */
    fun effectiveDate(zone: ZoneId): String = bizDate ?: Instant.ofEpochMilli(lastSeen).atZone(zone).toLocalDate().toString()
}

/** 存储层接口：应用里是 SQLite 实现，单元测试里是内存实现。 */
interface Repo {
    fun findStore(id: String): StoredStore?
    fun putStore(store: StoredStore)
    fun addStoreStatusHistory(storeId: String, status: String, at: Long)
    fun findRecord(storeId: String, kind: Kind, key: String): StoredRecord?
    fun putRecord(record: StoredRecord)
    fun addRecordHistory(record: StoredRecord, at: Long)
}

data class IngestResult(val inserted: Int = 0, val updated: Int = 0, val unchanged: Int = 0) {
    val total get() = inserted + updated + unchanged
    operator fun plus(o: IngestResult) = IngestResult(inserted + o.inserted, updated + o.updated, unchanged + o.unchanged)
}

/**
 * 去重与合并规则：
 * 1. 同一门店、同一类型、同一记录键 = 同一条记录，只存一条。
 * 2. 再次读到时：字段全部相同则只刷新「最近采集时间」；
 *    有字段变化（如订单状态从「待出餐」变成「已送达」）则更新，并把变化前的状态留一条历史。
 * 3. 新读到的数据里没有某个字段（卡片被屏幕截断）时，保留库里已有的值，不清空。
 * 4. 同一页里重复出现的同键记录，先按第 3 条合并成一条再入库。
 */
object Ingest {
    const val UNASSIGNED_STORE = "未指定门店"

    fun storeId(name: String): String = Text.norm(name)

    fun merge(existing: StoredRecord?, incoming: ParsedRecord, storeId: String, now: Long): Pair<StoredRecord, Outcome> {
        if (existing == null) {
            return StoredRecord(storeId, incoming.kind, incoming.key, incoming.state, incoming.bizDate, incoming.fields, now, now) to Outcome.INSERTED
        }
        val fields = LinkedHashMap(existing.fields).also { it.putAll(incoming.fields) }
        val state = incoming.state.ifBlank { existing.state }
        val bizDate = incoming.bizDate ?: existing.bizDate
        val same = fields == existing.fields && state == existing.state && bizDate == existing.bizDate
        val merged = existing.copy(state = state, bizDate = bizDate, fields = fields, lastSeen = now)
        return merged to if (same) Outcome.UNCHANGED else Outcome.UPDATED
    }

    enum class Outcome { INSERTED, UPDATED, UNCHANGED }

    fun mergeWithinPage(records: List<ParsedRecord>): List<ParsedRecord> {
        val map = LinkedHashMap<String, ParsedRecord>()
        for (r in records) {
            val id = r.kind.key + "\u0000" + r.key
            val prev = map[id]
            map[id] = if (prev == null) r else r.copy(fields = LinkedHashMap(prev.fields).also { it.putAll(r.fields) }, bizDate = r.bizDate ?: prev.bizDate)
        }
        return map.values.toList()
    }

    fun ingestRecords(repo: Repo, storeId: String, records: List<ParsedRecord>, now: Long): IngestResult {
        var res = IngestResult()
        for (r in mergeWithinPage(records)) {
            val existing = repo.findRecord(storeId, r.kind, r.key)
            val (merged, outcome) = merge(existing, r, storeId, now)
            repo.putRecord(merged)
            if (outcome != Outcome.UNCHANGED) repo.addRecordHistory(merged, now)
            res += when (outcome) {
                Outcome.INSERTED -> IngestResult(inserted = 1)
                Outcome.UPDATED -> IngestResult(updated = 1)
                Outcome.UNCHANGED -> IngestResult(unchanged = 1)
            }
        }
        return res
    }

    /** 门店：新门店登记；状态变了留历史；没变只刷新最近看到的时间。 */
    fun ingestStores(repo: Repo, stores: List<ParsedStore>, now: Long): IngestResult {
        var res = IngestResult()
        for (s in stores.distinctBy { storeId(it.name) }) {
            val id = storeId(s.name)
            val old = repo.findStore(id)
            if (old == null) {
                repo.putStore(StoredStore(id, id, s.status, now, now))
                repo.addStoreStatusHistory(id, s.status, now)
                res += IngestResult(inserted = 1)
            } else if (old.status != s.status) {
                repo.putStore(old.copy(status = s.status, lastSeen = now))
                repo.addStoreStatusHistory(id, s.status, now)
                res += IngestResult(updated = 1)
            } else {
                repo.putStore(old.copy(lastSeen = now))
                res += IngestResult(unchanged = 1)
            }
        }
        return res
    }

    fun ensureUnassignedStore(repo: Repo, now: Long) {
        if (repo.findStore(UNASSIGNED_STORE) == null) repo.putStore(StoredStore(UNASSIGNED_STORE, UNASSIGNED_STORE, "", now, now))
    }

    fun today(now: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
}
