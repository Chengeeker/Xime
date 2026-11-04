package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.Extension
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.theme.hapticAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolbarSettingsContent(onBack: () -> Unit) {
    val context = LocalContext.current
    var enabledIds by remember { mutableStateOf(SettingsPreferences.getToolbarButtons(context).toSet()) }
    var pluginButtons by remember { mutableStateOf(emptyList<Pair<String, String>>()) }

    LaunchedEffect(Unit) {
        pluginButtons = withContext(Dispatchers.IO) {
            ExtensionManager.getAllInstalledPlugins()
                .filter { SettingsPreferences.isPluginEnabled(context, it.id) }
                .flatMap { plugin -> plugin.toolbarButtons.map { it.id to "${plugin.name} · ${it.label.ifBlank { it.id }}" } }
        }
    }

    fun setVisible(id: String, visible: Boolean) {
        val updated = SettingsPreferences.getToolbarButtons(context).toMutableSet()
        if (visible) updated += id else updated -= id
        enabledIds = updated
        SettingsPreferences.setToolbarButtons(context, updated.toList())
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("工具栏按钮") },
                navigationIcon = {
                    IconButton(onClick = hapticAction(onBack)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingsSection("内置功能") {
                    ToolbarButton.entries
                        .filter { it != ToolbarButton.HANDWRITING_LOOKUP || com.kingzcheung.xime.handwriting.HandwritingEngine.hasModel(context) }
                        .forEach { button ->
                            SettingsToggleItem(
                                icon = button.icon,
                                title = button.label,
                                subtitle = "在键盘工具栏中显示",
                                checked = button.id in enabledIds,
                                onCheckedChange = { setVisible(button.id, it) },
                            )
                        }
                }
            }
            if (pluginButtons.isNotEmpty()) {
                item {
                    SettingsSection("插件功能") {
                        pluginButtons.forEach { (id, label) ->
                            SettingsToggleItem(
                                icon = Icons.TwoTone.Extension,
                                title = label,
                                subtitle = "在键盘工具栏中显示",
                                checked = id in enabledIds,
                                onCheckedChange = { setVisible(id, it) },
                            )
                        }
                    }
                }
            }
        }
    }
}
