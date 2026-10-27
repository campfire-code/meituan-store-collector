package com.campfire.storecollector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.campfire.storecollector.core.Kind
import com.campfire.storecollector.core.RecordView
import com.campfire.storecollector.core.StoredStore
import com.campfire.storecollector.core.Tables
import com.campfire.storecollector.service.ExportFormat
import com.campfire.storecollector.service.Runtime
import kotlinx.coroutines.delay
import java.time.ZoneId

class HomeActions(
    val openAccessibility: () -> Unit,
    val openOverlay: () -> Unit,
    val setFloating: (Boolean) -> Unit,
    val openMeituan: () -> Unit,
    val saveToPhone: () -> Unit,
    val share: () -> Unit,
)

private val tabs = listOf("采集", "数据", "门店")

@Composable
fun AppRoot(vm: MainViewModel, overlayOk: Boolean, actions: HomeActions) {
    LaunchedEffect(Unit) {
        while (true) { vm.refresh(); delay(1500) }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            Text("美团门店采集", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("只读采集，数据只存在这台手机上", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        TabRow(selectedTabIndex = vm.tab, containerColor = MaterialTheme.colorScheme.surface) {
            tabs.forEachIndexed { i, t -> Tab(selected = vm.tab == i, onClick = { vm.tab = i }, text = { Text(t) }) }
        }
        when (vm.tab) {
            0 -> CollectTab(vm, overlayOk, actions)
            1 -> DataTab(vm, actions)
            else -> StoresTab(vm)
        }
    }
}

@Composable
private fun CollectTab(vm: MainViewModel, overlayOk: Boolean, actions: HomeActions) {
    val accessibility by Runtime.accessibility.collectAsState()
    val floating by Runtime.floating.collectAsState()
    val current by Runtime.currentStore.collectAsState()
    val message by Runtime.message.collectAsState()
    var pickStore by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel {
            Text("运行条件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            PermRow("无障碍服务（读取美团页面文字）", accessibility, "去开启", actions.openAccessibility)
            PermRow("悬浮窗权限（在美团上方显示采集按钮）", overlayOk, "去开启", actions.openOverlay)
        }
        Panel {
            SwitchRow("悬浮采集窗", "打开后切到美团商家版，用悬浮窗读取当前页", floating, enabled = overlayOk) { actions.setFloating(it) }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = actions.openMeituan, Modifier.fillMaxWidth()) { Text("打开美团商家版") }
        }
        Panel {
            Text("当前门店", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(current, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "读到的订单、商品等会记在这家门店名下。在美团里切换门店后，请在这里或悬浮窗里同步切换。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { pickStore = true }) { Text("选择当前门店") }
        }
        Panel {
            Text("最近读取", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            if (vm.runs.isEmpty() && message.isEmpty() && vm.notice.isEmpty()) Muted("还没有记录")
            if (vm.notice.isNotEmpty()) Muted(vm.notice)
            vm.runs.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                    Dot(if (r.ok) okColor() else MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.message, style = MaterialTheme.typography.bodyMedium)
                        Text(Tables.fmt(r.at, ZoneId.systemDefault()).substring(11, 19) + " · " + r.pageType, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Panel {
            Text("试一试", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Muted("载入虚构的示例门店和数据（不连美团），用来看界面、试导出。示例数据和真实数据存在同一个库里，试完可以清空。")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.loadSamples() }) { Text("载入示例数据") }
                OutlinedButton(onClick = { confirmClear = true }) { Text("清空本机数据") }
            }
        }
        Text(
            "风险提示：本工具用无障碍服务自动读取第三方 App 的页面，可能违反平台的使用规则，账号风险由使用者自行承担。只读取你有权访问的门店数据；不点击、不改价、不接单、不退款。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (pickStore) StorePickDialog(vm.stores, current, onPick = { vm.chooseCurrentStore(it); pickStore = false }, onDismiss = { pickStore = false })
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("清空本机数据？") },
        text = { Text("所有门店、订单、商品、评价、财务、售后记录和读取记录都会删除，无法恢复。") },
        confirmButton = { TextButton(onClick = { vm.clearAll(); confirmClear = false }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
    )
}

@Composable
private fun StorePickDialog(stores: List<StoredStore>, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择当前门店") },
        text = {
            Column {
                if (stores.isEmpty()) Muted("还没有门店。先在美团里打开门店切换页，用悬浮窗「滚动读到底」识别门店；或先载入示例数据。")
                OutlinedTextField(q, { q = it }, label = { Text("搜索门店名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Column(Modifier.height(280.dp).verticalScroll(rememberScrollState())) {
                    stores.filter { q.isBlank() || it.name.contains(q.trim()) }.take(200).forEach { s ->
                        TextButton(onClick = { onPick(s.id) }, Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(s.name + if (s.id == current) "（当前）" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (s.status.isNotEmpty()) Text(s.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun DataTab(vm: MainViewModel, actions: HomeActions) {
    var storeMenu by remember { mutableStateOf(false) }
    var dateMenu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kind.entries.forEach { k -> FilterChip(selected = vm.kind == k, onClick = { vm.kind = k; vm.date = null }, label = { Text(k.label) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(onClick = { storeMenu = true }) { Text(vm.storeFilter ?: "全部门店", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(150.dp)) }
                DropdownMenu(expanded = storeMenu, onDismissRequest = { storeMenu = false }) {
                    DropdownMenuItem(text = { Text("全部门店") }, onClick = { vm.storeFilter = null; vm.date = null; storeMenu = false })
                    vm.stores.forEach { s -> DropdownMenuItem(text = { Text(s.name) }, onClick = { vm.storeFilter = s.id; vm.date = null; storeMenu = false }) }
                }
            }
            Box {
                OutlinedButton(onClick = { dateMenu = true }) { Text(vm.date ?: "全部日期") }
                DropdownMenu(expanded = dateMenu, onDismissRequest = { dateMenu = false }) {
                    DropdownMenuItem(text = { Text("全部日期") }, onClick = { vm.date = null; dateMenu = false })
                    vm.dates.forEach { d -> DropdownMenuItem(text = { Text(d) }, onClick = { vm.date = d; dateMenu = false }) }
                }
            }
        }
        Muted(
            "共 ${vm.total} 条" + if (vm.total > vm.records.size) "，列表只显示前 ${vm.records.size} 条，导出包含全部" else "" +
                if (vm.kind == Kind.PRODUCT) "。商品没有业务日期，「日期」是最近一次读到的日期" else "",
        )
        if (vm.records.isEmpty()) Panel { Muted("没有记录。去「采集」页读取，或载入示例数据。") }
        vm.records.forEach { r ->
            Card(
                Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                elevation = CardDefaults.cardElevation(0.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(RecordView.title(r), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = RecordView.subtitle(r)
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(r.storeId + " · " + r.effectiveDate(ZoneId.systemDefault()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Panel {
            Text("导出当前筛选的${vm.kind.label}记录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportFormat.entries.forEach { f -> FilterChip(selected = vm.exportFormat == f, onClick = { vm.exportFormat = f }, label = { Text(f.label) }) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.saveToPhone, enabled = vm.total > 0) { Text("保存到手机") }
                OutlinedButton(onClick = actions.share, enabled = vm.total > 0) { Text("分享给电脑") }
            }
            Muted("CSV 用 Excel 打开不乱码；「分享」可选微信文件传输助手、邮件、网盘等发到电脑。")
        }
    }
}

@Composable
private fun StoresTab(vm: MainViewModel) {
    val statuses = vm.stores.map { it.status }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
    val shown = vm.stores.filter { vm.statusFilter == null || it.status == vm.statusFilter }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Muted("共 ${vm.stores.size} 家。门店和状态来自美团的「门店切换页」，在悬浮窗里点「滚动读到底」识别；状态变化会留下历史。")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = vm.statusFilter == null, onClick = { vm.statusFilter = null }, label = { Text("全部 ${vm.stores.size}") })
            statuses.entries.sortedByDescending { it.value }.forEach { (s, n) ->
                FilterChip(selected = vm.statusFilter == s, onClick = { vm.statusFilter = s }, label = { Text("$s $n") })
            }
        }
        if (shown.isEmpty()) Panel { Muted("还没有门店。") }
        shown.forEach { s ->
            Card(
                Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                elevation = CardDefaults.cardElevation(0.dp),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(statusColor(s.status))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (s.status.ifEmpty { "状态未知" }) + " · 最近看到 " + Tables.fmt(s.lastSeen, ZoneId.systemDefault()).substring(0, 16),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { vm.chooseCurrentStore(s.id) }) { Text("设为当前") }
                }
            }
        }
    }
}

@Composable
private fun statusColor(status: String): Color = when (status) {
    "营业中" -> okColor()
    "仅接受预订", "门店上线中" -> warnColor()
    "" -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(0.dp),
    ) { Column(Modifier.padding(16.dp)) { content() } }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(12.dp).clip(CircleShape).background(color))
}

@Composable
private fun PermRow(label: String, ok: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(if (ok) okColor() else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(if (ok) "已开启" else "未开启", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!ok) OutlinedButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
