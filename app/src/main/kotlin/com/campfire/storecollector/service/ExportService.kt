package com.campfire.storecollector.service

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.campfire.storecollector.core.Csv
import com.campfire.storecollector.core.Kind
import com.campfire.storecollector.core.StoredRecord
import com.campfire.storecollector.core.StoredStore
import com.campfire.storecollector.core.Tables
import com.campfire.storecollector.core.Xlsx
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    CSV("CSV", "csv", "text/csv"),
    XLSX("Excel", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
}

class ExportFile(val name: String, val bytes: ByteArray, val format: ExportFormat)

object ExportService {
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    fun build(kind: Kind, records: List<StoredRecord>, stores: List<StoredStore>, format: ExportFormat): ExportFile {
        val zone = ZoneId.systemDefault()
        val status = stores.associate { it.id to it.status }
        val rows = Tables.records(kind, records, { status[it].orEmpty() }, zone)
        val name = "美团门店采集_${kind.label}_${stamp.format(java.time.LocalDateTime.now())}.${format.ext}"
        val bytes = if (format == ExportFormat.CSV) Csv.encode(rows) else Xlsx.build(listOf(Xlsx.Sheet(kind.label, rows)))
        return ExportFile(name, bytes, format)
    }

    /** 写到缓存目录并拉起系统分享（微信文件传输助手、邮件、隔空投送类应用都能选）。 */
    fun share(context: Context, file: ExportFile) {
        val dir = File(context.cacheDir, "exports").also { it.mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, file.name).also { it.writeBytes(file.bytes) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = file.format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享导出文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
