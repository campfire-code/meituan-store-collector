package com.campfire.storecollector.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.campfire.storecollector.core.Kind
import com.campfire.storecollector.core.StoredRecord
import com.campfire.storecollector.core.StoredStore
import com.campfire.storecollector.data.RunRow
import com.campfire.storecollector.service.Collector
import com.campfire.storecollector.service.ExportFile
import com.campfire.storecollector.service.ExportFormat
import com.campfire.storecollector.service.ExportService
import com.campfire.storecollector.service.Runtime
import com.campfire.storecollector.service.SampleLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = Collector.db(app)

    var tab by mutableStateOf(0)
    var storeFilter by mutableStateOf<String?>(null)
    var kind by mutableStateOf(Kind.ORDER)
    var date by mutableStateOf<String?>(null)
    var exportFormat by mutableStateOf(ExportFormat.CSV)
    var statusFilter by mutableStateOf<String?>(null)

    var stores by mutableStateOf<List<StoredStore>>(emptyList())
        private set
    var records by mutableStateOf<List<StoredRecord>>(emptyList())
        private set
    var total by mutableStateOf(0)
        private set
    var dates by mutableStateOf<List<String>>(emptyList())
        private set
    var runs by mutableStateOf<List<RunRow>>(emptyList())
        private set
    var notice by mutableStateOf("")

    init { Runtime.loadCurrentStore(app) }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val st = db.stores()
            val ds = db.dates(storeFilter, kind)
            val effectiveDate = date?.takeIf { it in ds }
            val total = db.count(storeFilter, kind, effectiveDate)
            val rs = db.records(storeFilter, kind, effectiveDate, limit = 300)
            val runs = db.lastRuns(6)
            withContext(Dispatchers.Main) {
                stores = st; dates = ds; this@MainViewModel.total = total; records = rs; this@MainViewModel.runs = runs
                if (date != null && effectiveDate == null) date = null
            }
        }
    }

    fun loadSamples() {
        viewModelScope.launch(Dispatchers.IO) {
            val msg = SampleLoader.load(getApplication())
            withContext(Dispatchers.Main) { notice = msg }
            refresh()
        }
    }

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            db.clearAll()
            Runtime.setCurrentStore(getApplication(), com.campfire.storecollector.core.Ingest.UNASSIGNED_STORE)
            withContext(Dispatchers.Main) { notice = "已清空本机所有采集数据"; storeFilter = null; date = null }
            refresh()
        }
    }

    fun chooseCurrentStore(id: String) = Runtime.setCurrentStore(getApplication(), id)

    /** 按当前筛选条件取出全部记录并生成导出文件（在后台线程调用）。 */
    fun buildExport(): ExportFile {
        val all = db.records(storeFilter, kind, date)
        return ExportService.build(kind, all, db.stores(), exportFormat)
    }
}
