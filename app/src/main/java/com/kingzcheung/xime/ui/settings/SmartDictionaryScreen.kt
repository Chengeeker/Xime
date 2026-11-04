package com.kingzcheung.xime.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kingzcheung.xime.ui.theme.hapticAction
import com.kingzcheung.xime.viewmodel.SmartDictionaryViewModel
import com.kingzcheung.xime.settings.CloudDictionary
import com.kingzcheung.xime.settings.CatalogInstallationState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartDictionaryContent(onBack: () -> Unit) {
    val model: SmartDictionaryViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.load() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::importFrom) }
    var uninstallId by remember { mutableStateOf<String?>(null) }
    var showUrl by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf("") }
    var sha by rememberSaveable { mutableStateOf("") }
    var detail by remember { mutableStateOf<CloudDictionary?>(null) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("智能词库中心") }, navigationIcon = {
            IconButton(onClick = hapticAction(onBack)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("网络热词与云端词库", style = MaterialTheme.typography.titleLarge)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("自动更新（Wi-Fi）", Modifier.weight(1f))
                        Switch(checked = state.cloud.automatic, enabled = !state.busy,
                            modifier = Modifier.semantics { contentDescription = "自动更新词库" },
                            onCheckedChange = { model.configure(it, state.cloud.frequencyHours) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(6L to "6 小时", 24L to "每天", 168L to "每周").forEach { (hours, label) ->
                            FilterChip(selected = state.cloud.frequencyHours == hours, enabled = !state.busy,
                                onClick = { model.configure(state.cloud.automatic, hours) }, label = { Text(label) })
                        }
                    }
                    Text("最后编译成功：${dictionaryTime(state.snapshot.lastSuccess)}", style = MaterialTheme.typography.bodySmall)
                    Text("自动更新仅更新已安装的云端词库；新词库请先在目录中安装。", style = MaterialTheme.typography.bodySmall)
                    Text("最后检查：${dictionaryTime(state.cloud.lastCheck)}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !state.busy, onClick = { showUrl = true }) { Text("从 URL 安装") }
                        TextButton(enabled = !state.busy && state.cloud.subscriptions.isNotEmpty(), onClick = model::checkUpdates) { Text("检查更新") }
                    }
                }
                if (state.cloud.error.isNotBlank()) item { Text(state.cloud.error, color = MaterialTheme.colorScheme.error) }
                items(state.cloud.subscriptions, key = { "subscription:$it" }) { subscription ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(subscription, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !state.busy, onClick = { model.unsubscribe(subscription) }) { Text("移除订阅") }
                    }
                }
                if (state.cloud.subscriptions.isEmpty()) item { Text("添加 HTTPS manifest.json 目录后可查看网络热词和专题词库。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.cloud.catalog, key = { "${it.manifestUrl}:${it.id}" }) { entry ->
                    OutlinedCard(onClick = { detail = entry }, enabled = !state.busy) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(entry.name, style = MaterialTheme.typography.titleMedium)
                            Text("${entry.entryCount} 条 · ${entry.version} · ${entry.license}", style = MaterialTheme.typography.bodySmall)
                            Text(catalogInstallationText(state.snapshot.catalogState(entry)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Text("专题词库", style = MaterialTheme.typography.titleLarge)
                    Text("词典源文件在本机编译，供白霜全拼、小鹤双拼和混输使用。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !state.busy, onClick = { launcher.launch(arrayOf("text/*", "application/zip", "application/octet-stream")) }) { Text("从本地导入") }
                    }
                }
                if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (state.message.isNotBlank()) item { Text(state.message, color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                if (state.snapshot.pending != null) item {
                    Card { Column(Modifier.padding(16.dp)) {
                        Text("有待部署变更", style = MaterialTheme.typography.titleMedium)
                        Text("输入会话结束后自动应用。也可收起输入法后手动部署。")
                        TextButton(enabled = !state.busy, onClick = model::apply) { Text("应用待处理变更") }
                    } }
                }
                val packs = (state.snapshot.pending.orEmpty() + state.snapshot.installed).distinctBy { it.id }
                if (packs.isEmpty()) item { Text("尚未安装专题词库。支持 TXT、TSV、Rime dict.yaml 和 ZIP；文本词条需带逐字空格拼音。") }
                items(packs, key = { it.id }) { pack ->
                    val changing = state.snapshot.hasPendingChange(pack.id)
                    val removing = changing && state.snapshot.pending?.none { it.id == pack.id } == true
                    val installed = state.snapshot.installed.find { it.id == pack.id }
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(pack.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Switch(checked = pack.enabled && !removing, enabled = !state.busy && !removing, onCheckedChange = { model.toggle(pack.id, it) },
                                    modifier = Modifier.semantics { contentDescription = "启用${pack.name}" })
                            }
                            Text("${pack.entryCount} 条 · ${pack.version}", style = MaterialTheme.typography.bodyMedium)
                            Text("许可：${pack.license}", style = MaterialTheme.typography.bodySmall)
                            Text(when {
                                removing -> "等待卸载；完成部署前仍保留当前词库"
                                changing -> if (installed == null) "已暂存，等待编译" else "等待部署；当前仍使用 ${installed.version}"
                                pack.enabled -> "已编译并启用"
                                else -> "已停用"
                            }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (pack.sourceUrl.isNotBlank()) Text("订阅：${pack.sourceUrl}", style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = !state.busy && !removing, onClick = { uninstallId = pack.id }) { Text("卸载") }
                        }
                    }
                }
                item { OutlinedButton(enabled = !state.busy && state.snapshot.canRollback, onClick = model::rollback) { Text("恢复上一版本") } }
                if (state.snapshot.history.isNotEmpty()) item { Text("更新历史", style = MaterialTheme.typography.titleMedium) }
                items(state.snapshot.history.take(30)) { event ->
                    val display = remember(event) {
                        val stamp = event.substringBefore(' ').toLongOrNull()
                        if (stamp == null) event else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(stamp)) + " " + event.substringAfter(' ')
                    }
                    Text(display, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (showUrl) AlertDialog(onDismissRequest = { showUrl = false }, title = { Text("添加词库 URL") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("HTTPS manifest.json 或 ZIP URL") }, singleLine = true)
            OutlinedTextField(value = sha, onValueChange = { sha = it }, label = { Text("ZIP SHA-256（目录订阅留空）") }, singleLine = true)
            Text("目录可订阅更新；直接 ZIP 需要校验值，下载后先预览。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = url.isNotBlank() && !state.busy, onClick = { showUrl = false; model.fromUrl(url, sha) }) { Text("读取") } },
        dismissButton = { TextButton(onClick = { showUrl = false }) { Text("取消") } })
    detail?.let { entry ->
        val installation = state.snapshot.catalogState(entry)
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(entry.name) }, text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("版本：${entry.version}\n词条：${entry.entryCount}\n下载：${entry.size / 1024.0} KiB\n许可：${entry.license}")
                Text(entry.sources, style = MaterialTheme.typography.bodySmall)
                Text(catalogInstallationText(installation))
                Text("发布者签名：未验证。安装会校验 SHA-256，并在本机编译源词典。", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = {
            TextButton(enabled = !state.busy && installation != CatalogInstallationState.ENABLED && installation != CatalogInstallationState.WAITING_DEPLOYMENT,
                onClick = {
                    detail = null
                    if (installation == CatalogInstallationState.DISABLED) model.toggle(entry.id, true) else model.installCloud(entry)
                }) {
                Text(when (installation) {
                    CatalogInstallationState.AVAILABLE -> "校验并安装"
                    CatalogInstallationState.UPDATE_AVAILABLE -> "校验并更新"
                    CatalogInstallationState.WAITING_DEPLOYMENT -> "等待部署"
                    CatalogInstallationState.ENABLED -> "已编译并启用"
                    CatalogInstallationState.DISABLED -> "启用词库"
                })
            }
        },
            dismissButton = { TextButton(onClick = { detail = null }) { Text("关闭") } })
    }
    state.preview?.let { preview ->
        AlertDialog(onDismissRequest = { if (!state.busy) model.dismissPreview() }, title = { Text("导入预览：${preview.name}") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${preview.entries.size} 条有效词条 · 去重 ${preview.duplicates} 条 · ${preview.encoding}")
                if (preview.errors.isNotEmpty()) Text(preview.errors.take(5).joinToString("\n"), color = MaterialTheme.colorScheme.error)
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(preview.entries.take(40)) { Text("${it.word}  ${it.code}", style = MaterialTheme.typography.bodySmall) }
                }
                if (preview.entries.size > 40) Text("预览前 40 条，安装包含全部有效词条。", style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(enabled = preview.canInstall && !state.busy, onClick = model::install) { Text("安装词库") } },
            dismissButton = { TextButton(enabled = !state.busy, onClick = model::dismissPreview) { Text("取消") } })
    }
    uninstallId?.let { id ->
        AlertDialog(onDismissRequest = { uninstallId = null }, title = { Text("卸载专题词库？") }, text = { Text("卸载此专题的托管词典源文件；输入会话结束后生效。") },
            confirmButton = { TextButton(onClick = { uninstallId = null; model.uninstall(id) }) { Text("卸载") } },
            dismissButton = { TextButton(onClick = { uninstallId = null }) { Text("取消") } })
    }
}

private fun dictionaryTime(timestamp: Long): String = if (timestamp <= 0) "尚无记录" else
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))

private fun catalogInstallationText(state: CatalogInstallationState): String = when (state) {
    CatalogInstallationState.AVAILABLE -> "尚未安装"
    CatalogInstallationState.UPDATE_AVAILABLE -> "可更新为此版本"
    CatalogInstallationState.WAITING_DEPLOYMENT -> "有待部署变更，尚未生效；收起键盘后自动应用"
    CatalogInstallationState.ENABLED -> "已编译并启用"
    CatalogInstallationState.DISABLED -> "已安装，当前停用"
}
