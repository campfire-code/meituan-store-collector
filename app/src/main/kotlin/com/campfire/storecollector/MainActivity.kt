package com.campfire.storecollector

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.campfire.storecollector.core.MERCHANT_PACKAGE
import com.campfire.storecollector.service.ExportFile
import com.campfire.storecollector.service.ExportService
import com.campfire.storecollector.service.FloatingPanelService
import com.campfire.storecollector.ui.AppRoot
import com.campfire.storecollector.ui.AppTheme
import com.campfire.storecollector.ui.HomeActions
import com.campfire.storecollector.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var pending: ExportFile? = null

    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = pending ?: return@registerForActivityResult
        pending = null
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = runCatching { contentResolver.openOutputStream(uri)?.use { it.write(f.bytes) } ?: error("无法写入") }.isSuccess
            withContext(Dispatchers.Main) { toast(if (ok) "已保存：${f.name}" else "保存失败") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(this)) }
            val owner = LocalLifecycleOwner.current
            DisposableEffect(owner) {
                val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) overlayOk = Settings.canDrawOverlays(this@MainActivity) }
                owner.lifecycle.addObserver(obs)
                onDispose { owner.lifecycle.removeObserver(obs) }
            }
            val actions = HomeActions(
                openAccessibility = { safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                openOverlay = { safeStart(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) },
                setFloating = { on -> if (on) FloatingPanelService.start(this) else FloatingPanelService.stop(this) },
                openMeituan = {
                    val i = packageManager.getLaunchIntentForPackage(MERCHANT_PACKAGE)
                    if (i == null) toast("没有找到美团商家版，请先安装并登录") else startActivity(i)
                },
                saveToPhone = { export { f -> pending = f; createDoc.launch(f.name) } },
                share = { export { f -> ExportService.share(this, f) } },
            )
            AppTheme { AppRoot(vm, overlayOk, actions) }
        }
    }

    private fun export(then: (ExportFile) -> Unit) {
        lifecycleScope.launch {
            val f = withContext(Dispatchers.IO) { vm.buildExport() }
            then(f)
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun safeStart(i: Intent) {
        try { startActivity(i) } catch (_: ActivityNotFoundException) {}
    }
}
