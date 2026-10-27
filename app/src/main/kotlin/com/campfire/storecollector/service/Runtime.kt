package com.campfire.storecollector.service

import android.content.Context
import com.campfire.storecollector.core.Ingest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 进程内共享的运行状态。 */
object Runtime {
    private val _accessibility = MutableStateFlow(false)
    val accessibility: StateFlow<Boolean> = _accessibility.asStateFlow()

    private val _floating = MutableStateFlow(false)
    val floating: StateFlow<Boolean> = _floating.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    private val _currentStore = MutableStateFlow(Ingest.UNASSIGNED_STORE)
    val currentStore: StateFlow<String> = _currentStore.asStateFlow()

    @Volatile var cancelRequested = false

    fun setAccessibility(v: Boolean) { _accessibility.value = v }
    fun setFloating(v: Boolean) { _floating.value = v }
    fun setBusy(v: Boolean) { _busy.value = v }
    fun setMessage(m: String) { _message.value = m }

    fun loadCurrentStore(context: Context) {
        _currentStore.value = prefs(context).getString(KEY_STORE, Ingest.UNASSIGNED_STORE) ?: Ingest.UNASSIGNED_STORE
    }

    fun setCurrentStore(context: Context, id: String) {
        prefs(context).edit().putString(KEY_STORE, id).apply()
        _currentStore.value = id
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("collector", Context.MODE_PRIVATE)
    private const val KEY_STORE = "current_store"
}
