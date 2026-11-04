package com.kingzcheung.xime.password

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

internal fun vaultTypeIcon(type: Int): ImageVector = when (type) {
    1 -> Icons.Outlined.Language
    2 -> Icons.Outlined.Description
    3 -> Icons.Outlined.CreditCard
    4 -> Icons.Outlined.Badge
    5 -> Icons.Outlined.Key
    6 -> Icons.Outlined.AccountBalance
    7 -> Icons.Outlined.DirectionsCar
    else -> Icons.Outlined.Article
}

internal fun LazyListScope.vaultOverview(
    entries: List<BitwardenVaultEntry>, folders: Map<String, String>, enabled: Boolean,
    onSelect: (VaultBrowseFilter) -> Unit,
) {
    val active = entries.filter { !it.archived && !it.deleted }
    item("totp-title") { VaultSectionTitle("TOTP (1)") }
    item("totp") { VaultGroup { VaultOverviewRow("验证码", Icons.Outlined.Schedule, active.count { it.values["totp"].orEmpty().isNotBlank() }, enabled) { onSelect(VaultBrowseFilter("验证码", VaultBrowseFilter.Kind.Totp)) } } }
    val typeNames = linkedMapOf(1 to "登录", 3 to "支付卡", 4 to "身份", 2 to "安全笔记", 5 to "SSH 密钥", 6 to "银行账户", 7 to "驾照", 8 to "护照")
    active.map { it.type }.distinct().filter { it !in typeNames }.sorted().forEach { typeNames[it] = "其他类型 ($it)" }
    item("types-title") { VaultSectionTitle("类型 (${typeNames.size})") }
    item("types") {
        VaultGroup {
            typeNames.entries.forEachIndexed { index, (type, title) ->
                VaultOverviewRow(title, vaultTypeIcon(type), active.count { it.type == type }, enabled) { onSelect(VaultBrowseFilter(title, VaultBrowseFilter.Kind.Type, type)) }
                if (index < typeNames.size - 1) HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    val displayedFolders = linkedMapOf("" to "无文件夹").apply {
        putAll(folders)
        active.map { it.folderId }.distinct().filter { it.isNotBlank() && it !in this }.forEach { put(it, "未知文件夹") }
    }
    item("folders-title") { VaultSectionTitle("文件夹 (${displayedFolders.size})") }
    item("folders") {
        VaultGroup {
            displayedFolders.entries.forEachIndexed { index, (id, title) ->
                VaultOverviewRow(title, Icons.Outlined.Folder, active.count { it.folderId == id }, enabled) { onSelect(VaultBrowseFilter(title, VaultBrowseFilter.Kind.Folder, folderId = id)) }
                if (index < displayedFolders.size - 1) HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    item("hidden-title") { VaultSectionTitle("隐藏的项目 (2)") }
    item("hidden") {
        VaultGroup {
            VaultOverviewRow("归档", Icons.Outlined.Inventory2, entries.count { it.archived && !it.deleted }, enabled) { onSelect(VaultBrowseFilter("归档", VaultBrowseFilter.Kind.Archived)) }
            HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
            VaultOverviewRow("回收站", Icons.Outlined.DeleteOutline, entries.count { it.deleted }, enabled) { onSelect(VaultBrowseFilter("回收站", VaultBrowseFilter.Kind.Trash)) }
        }
    }
    item("all") {
        VaultGroup {
            VaultOverviewRow("全部条目", Icons.Outlined.FolderOpen, active.size, enabled) { onSelect(VaultBrowseFilter("全部条目")) }
            HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
            VaultOverviewRow("收藏", Icons.Outlined.StarOutline, active.count { it.favorite }, enabled) { onSelect(VaultBrowseFilter("收藏", VaultBrowseFilter.Kind.Favorites)) }
        }
    }
}

@Composable
internal fun VaultEntryDetails(entry: BitwardenVaultEntry, showSecrets: Boolean, onToggleSecrets: () -> Unit) {
    val sensitive = setOf("password", "totp", "number", "code", "ssn", "passportNumber", "licenseNumber")
    LazyColumn(
        Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(BitwardenEntryCodec.typeNames[entry.type] ?: "其他类型", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onToggleSecrets) { Text(if (showSecrets) "隐藏敏感信息" else "显示敏感信息") }
            }
        }
        item {
            VaultGroup {
                BitwardenEntryCodec.fields[entry.type].orEmpty().forEach { (field, label) ->
                    val value = entry.values[field].orEmpty()
                    if (value.isNotBlank()) VaultDetailValue(label, if (!showSecrets && field in sensitive) "••••••••" else value)
                }
                if (entry.uris.isNotEmpty()) VaultDetailValue("网址", entry.uris.joinToString("\n"))
                if (entry.notes.isNotBlank()) VaultDetailValue("备注", if (showSecrets) entry.notes else "••••••••")
                if (entry.favorite) VaultDetailValue("收藏", "已收藏")
            }
        }
    }
}

@Composable
private fun VaultDetailValue(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun VaultSectionTitle(title: String) {
    Text(title, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun VaultGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(Modifier.fillMaxWidth(), content = content) }
}

@Composable
private fun VaultOverviewRow(title: String, icon: ImageVector, count: Int, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 72.dp).padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, Modifier.padding(start = 20.dp).weight(1f), style = MaterialTheme.typography.titleMedium)
        Text(count.toString(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
