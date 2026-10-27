package com.campfire.storecollector.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.campfire.storecollector.core.Kind
import com.campfire.storecollector.core.Repo
import com.campfire.storecollector.core.StoredRecord
import com.campfire.storecollector.core.StoredStore
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.ZoneId

data class RunRow(val at: Long, val storeId: String, val pageType: String, val ok: Boolean, val message: String)

/** 本机 SQLite。数据只存在应用专属目录里，已关闭系统备份。 */
class Db(context: Context) : SQLiteOpenHelper(context.applicationContext, "collector.db", null, 1), Repo {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val fieldsSer = MapSerializer(String.serializer(), String.serializer())

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE stores(id TEXT PRIMARY KEY, name TEXT NOT NULL, status TEXT NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE store_status_history(store_id TEXT NOT NULL, status TEXT NOT NULL, at INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE records(store_id TEXT NOT NULL, kind TEXT NOT NULL, rec_key TEXT NOT NULL, state TEXT NOT NULL, biz_date TEXT, eff_date TEXT NOT NULL, " +
                "fields TEXT NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, PRIMARY KEY(store_id, kind, rec_key))",
        )
        db.execSQL("CREATE INDEX idx_records_view ON records(kind, store_id, eff_date)")
        db.execSQL("CREATE TABLE record_history(store_id TEXT NOT NULL, kind TEXT NOT NULL, rec_key TEXT NOT NULL, at INTEGER NOT NULL, state TEXT NOT NULL, fields TEXT NOT NULL)")
        db.execSQL("CREATE TABLE capture_runs(id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, store_id TEXT NOT NULL, page_type TEXT NOT NULL, ok INTEGER NOT NULL, message TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun <T> tx(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    // ---- Repo ----
    override fun findStore(id: String): StoredStore? =
        readableDatabase.rawQuery("SELECT id,name,status,first_seen,last_seen FROM stores WHERE id=?", arrayOf(id)).use {
            if (it.moveToFirst()) StoredStore(it.getString(0), it.getString(1), it.getString(2), it.getLong(3), it.getLong(4)) else null
        }

    override fun putStore(store: StoredStore) {
        val v = ContentValues().apply {
            put("id", store.id); put("name", store.name); put("status", store.status)
            put("first_seen", store.firstSeen); put("last_seen", store.lastSeen)
        }
        writableDatabase.insertWithOnConflict("stores", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun addStoreStatusHistory(storeId: String, status: String, at: Long) {
        writableDatabase.insert("store_status_history", null, ContentValues().apply { put("store_id", storeId); put("status", status); put("at", at) })
    }

    override fun findRecord(storeId: String, kind: Kind, key: String): StoredRecord? =
        readableDatabase.rawQuery(
            "SELECT state,biz_date,fields,first_seen,last_seen FROM records WHERE store_id=? AND kind=? AND rec_key=?",
            arrayOf(storeId, kind.key, key),
        ).use {
            if (it.moveToFirst()) StoredRecord(storeId, kind, key, it.getString(0), it.getString(1), decode(it.getString(2)), it.getLong(3), it.getLong(4)) else null
        }

    override fun putRecord(record: StoredRecord) {
        val v = ContentValues().apply {
            put("store_id", record.storeId); put("kind", record.kind.key); put("rec_key", record.key)
            put("state", record.state); put("biz_date", record.bizDate); put("eff_date", record.effectiveDate(zone))
            put("fields", encode(record.fields)); put("first_seen", record.firstSeen); put("last_seen", record.lastSeen)
        }
        writableDatabase.insertWithOnConflict("records", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun addRecordHistory(record: StoredRecord, at: Long) {
        writableDatabase.insert(
            "record_history", null,
            ContentValues().apply {
                put("store_id", record.storeId); put("kind", record.kind.key); put("rec_key", record.key)
                put("at", at); put("state", record.state); put("fields", encode(record.fields))
            },
        )
    }

    // ---- 查询 ----
    fun stores(): List<StoredStore> =
        readableDatabase.rawQuery("SELECT id,name,status,first_seen,last_seen FROM stores ORDER BY name", null).use {
            buildList { while (it.moveToNext()) add(StoredStore(it.getString(0), it.getString(1), it.getString(2), it.getLong(3), it.getLong(4))) }
        }

    /** 某门店（null = 全部门店）某类型的记录。[date] 为 yyyy-MM-dd 或 null（全部日期）。 */
    fun records(storeId: String?, kind: Kind, date: String?, limit: Int = 100000): List<StoredRecord> {
        val where = StringBuilder("kind=?")
        val args = arrayListOf(kind.key)
        if (storeId != null) { where.append(" AND store_id=?"); args += storeId }
        if (date != null) { where.append(" AND eff_date=?"); args += date }
        return readableDatabase.rawQuery(
            "SELECT store_id,rec_key,state,biz_date,fields,first_seen,last_seen FROM records WHERE $where ORDER BY eff_date DESC, store_id, rec_key LIMIT $limit",
            args.toTypedArray(),
        ).use {
            buildList { while (it.moveToNext()) add(StoredRecord(it.getString(0), kind, it.getString(1), it.getString(2), it.getString(3), decode(it.getString(4)), it.getLong(5), it.getLong(6))) }
        }
    }

    fun dates(storeId: String?, kind: Kind): List<String> {
        val where = StringBuilder("kind=?")
        val args = arrayListOf(kind.key)
        if (storeId != null) { where.append(" AND store_id=?"); args += storeId }
        return readableDatabase.rawQuery("SELECT DISTINCT eff_date FROM records WHERE $where ORDER BY eff_date DESC LIMIT 400", args.toTypedArray()).use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
    }

    fun count(storeId: String?, kind: Kind, date: String?): Int {
        val where = StringBuilder("kind=?")
        val args = arrayListOf(kind.key)
        if (storeId != null) { where.append(" AND store_id=?"); args += storeId }
        if (date != null) { where.append(" AND eff_date=?"); args += date }
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM records WHERE $where", args.toTypedArray()).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun addRun(at: Long, storeId: String, pageType: String, ok: Boolean, message: String) {
        writableDatabase.insert("capture_runs", null, ContentValues().apply {
            put("at", at); put("store_id", storeId); put("page_type", pageType); put("ok", if (ok) 1 else 0); put("message", message)
        })
    }

    fun lastRuns(n: Int): List<RunRow> =
        readableDatabase.rawQuery("SELECT at,store_id,page_type,ok,message FROM capture_runs ORDER BY id DESC LIMIT $n", null).use {
            buildList { while (it.moveToNext()) add(RunRow(it.getLong(0), it.getString(1), it.getString(2), it.getInt(3) == 1, it.getString(4))) }
        }

    fun clearAll() {
        val db = writableDatabase
        listOf("stores", "store_status_history", "records", "record_history", "capture_runs").forEach { db.delete(it, null, null) }
    }

    private fun encode(m: Map<String, String>) = Json.encodeToString(fieldsSer, m)
    private fun decode(s: String): Map<String, String> = Json.decodeFromString(fieldsSer, s)
}
