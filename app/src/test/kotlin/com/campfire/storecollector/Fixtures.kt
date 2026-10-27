package com.campfire.storecollector

import com.campfire.storecollector.core.Kind
import com.campfire.storecollector.core.NodeInfo
import com.campfire.storecollector.core.NodeJson
import com.campfire.storecollector.core.PageClassifier
import com.campfire.storecollector.core.ParsedPage
import com.campfire.storecollector.core.Repo
import com.campfire.storecollector.core.StoredRecord
import com.campfire.storecollector.core.StoredStore
import java.io.File
import java.time.LocalDate

/** 读取 samples/ 里虚构的页面节点树。单元测试的工作目录是 app/。 */
object Fixtures {
    val today: LocalDate = LocalDate.of(2026, 10, 27)

    fun node(name: String): NodeInfo = NodeJson.parse(File("../samples/$name").readText(Charsets.UTF_8))

    fun page(name: String): ParsedPage = PageClassifier.parse(node(name), today)
}

class MemoryRepo : Repo {
    val stores = LinkedHashMap<String, StoredStore>()
    val storeHistory = ArrayList<Triple<String, String, Long>>()
    val records = LinkedHashMap<Triple<String, Kind, String>, StoredRecord>()
    val recordHistory = ArrayList<StoredRecord>()

    override fun findStore(id: String) = stores[id]
    override fun putStore(store: StoredStore) { stores[store.id] = store }
    override fun addStoreStatusHistory(storeId: String, status: String, at: Long) { storeHistory += Triple(storeId, status, at) }
    override fun findRecord(storeId: String, kind: Kind, key: String) = records[Triple(storeId, kind, key)]
    override fun putRecord(record: StoredRecord) { records[Triple(record.storeId, record.kind, record.key)] = record }
    override fun addRecordHistory(record: StoredRecord, at: Long) { recordHistory += record }
}
