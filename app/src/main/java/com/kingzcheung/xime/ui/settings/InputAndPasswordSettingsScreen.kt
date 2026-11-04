package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.ui.theme.hapticTapFeedback

import com.kingzcheung.xime.ui.theme.hapticAction


import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.Ballot
import androidx.compose.material.icons.twotone.Keyboard
import androidx.compose.material.icons.twotone.TableChart
import androidx.compose.material.icons.twotone.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.password.BitwardenOperation
import com.kingzcheung.xime.password.BitwardenVaultRepository
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.viewmodel.SchemaSettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputSettingsContent(
    onBack: () -> Unit,
    onNavigateToSchemas: () -> Unit,
    onNavigateToDictionary: () -> Unit,
    onNavigateToCandidateSettings: () -> Unit,
) {
    val schemaViewModel: SchemaSettingsViewModel = viewModel()
    val uiState by schemaViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val schemaIds = listOf(
        "rime_frost" to ("全拼（默认）" to "白霜拼音，26 键中文全拼"),
        "rime_frost_double_pinyin_flypy" to ("小鹤双拼" to "白霜官方小鹤双拼方案"),
        "rime_frost_mixed_flypy" to ("全拼＋小鹤双拼混输（实验）" to "同一组合可逐音节混用；桌面 librime 已验证，Android 待设备确认"),
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("输入设置") },
                navigationIcon = {
                    IconButton(onClick = hapticAction(onBack)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingsSection("输入方式") {
                    schemaIds.forEach { (id, text) ->
                        val schema = uiState.allSchemas.firstOrNull { it.schemaId == id }
                        SettingsRadioItem(
                            title = text.first,
                            subtitle = text.second,
                            selected = uiState.currentSchema == id,
                            onSelect = { schema?.let(schemaViewModel::selectSchema) },
                        )
                    }
                }
            }
            item {
                SettingsSection("双拼方案") {
                    SettingsItem(
                        icon = Icons.TwoTone.Keyboard,
                        title = "小鹤双拼",
                        subtitle = "复用白霜官方 rime_frost_double_pinyin_flypy",
                        onClick = {},
                    )
                }
            }
            item {
                SettingsSection("管理") {
                    SettingsItem(
                        icon = Icons.TwoTone.Ballot,
                        title = "词库管理",
                        subtitle = "查看个人词库与快捷短语",
                        onClick = onNavigateToDictionary,
                        showArrow = true,
                    )
                    SettingsItem(
                        icon = Icons.TwoTone.TableChart,
                        title = "候选词和输入习惯",
                        subtitle = "候选显示、键盘布局及输入行为",
                        onClick = onNavigateToCandidateSettings,
                        showArrow = true,
                    )
                    SettingsItem(
                        icon = Icons.TwoTone.Keyboard,
                        title = "方案更新与管理",
                        subtitle = "查看已部署方案、更新或导入方案",
                        onClick = onNavigateToSchemas,
                        showArrow = true,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordSettingsContent(onBack: () -> Unit) {
    val context = LocalContext.current
    androidx.compose.runtime.DisposableEffect(context) {
        val window = (context as? android.app.Activity)?.window
        val alreadySecure = window?.attributes?.flags?.and(android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!alreadySecure) window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    var buttonVisible by remember {
        mutableStateOf(ToolbarButton.PASSWORDS.id in SettingsPreferences.getToolbarButtons(context))
    }
    var status by remember { mutableStateOf(BitwardenVaultRepository.status(appContext)) }
    var email by remember { mutableStateOf(status.email) }
    var password by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(status.serverUrl) }
    var twoFactorCode by remember { mutableStateOf("") }
    var needsTwoFactor by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var disconnectConfirmation by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        scope.launch {
            BitwardenVaultRepository.resumeSession(appContext)
            status = BitwardenVaultRepository.status(appContext)
        }
    }

    fun runOperation(operation: suspend () -> BitwardenOperation) {
        if (busy) return
        busy = true
        message = ""
        scope.launch {
            val result = runCatching { operation() }
                .getOrElse { BitwardenOperation.Failure("Bitwarden 操作失败，请检查网络并重试") }
            when (result) {
                is BitwardenOperation.Success -> {
                    status = BitwardenVaultRepository.status(appContext)
                    message = "已同步 ${result.itemCount} 个登录条目"
                    needsTwoFactor = false
                    twoFactorCode = ""
                    password = ""
                }
                BitwardenOperation.TwoFactorRequired -> {
                    needsTwoFactor = true
                    message = "请输入验证器中的二步验证码"
                }
                is BitwardenOperation.Failure -> message = result.message
            }
            status = BitwardenVaultRepository.status(appContext)
            busy = false
        }
    }

    fun openProviderSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 35) {
            Intent(Settings.ACTION_CREDENTIAL_PROVIDER).setData(Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:${context.packageName}"))
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "请在系统密码与自动填充设置中启用 Xime", Toast.LENGTH_LONG).show() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("密码管理") },
                navigationIcon = {
                    IconButton(onClick = hapticAction(onBack)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingsSection("工具栏") {
                    SettingsToggleItem(
                        icon = Icons.TwoTone.VpnKey,
                        title = "显示密码管理按钮",
                        subtitle = "在键盘顶栏手动打开当前会话的自动填充建议",
                        checked = buttonVisible,
                        onCheckedChange = { enabled ->
                            val buttons = SettingsPreferences.getToolbarButtons(context).toMutableList()
                            if (enabled && ToolbarButton.PASSWORDS.id !in buttons) buttons += ToolbarButton.PASSWORDS.id
                            if (!enabled) buttons.removeAll { it == ToolbarButton.PASSWORDS.id }
                            SettingsPreferences.setToolbarButtons(context, buttons)
                            buttonVisible = enabled
                        },
                    )
                }
            }
            item {
                SettingsSection("Bitwarden 账户") {
                    if (status.configured) {
                        Text(
                            "${status.email} · ${if (status.unlocked) "已解锁" else "已锁定"} · 保险库 ${status.itemCount} 条 / 登录 ${status.loginCount} 条 / 可自动填充 ${status.autofillCount} 条",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!status.unlocked) {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                modifier = (Modifier.fillMaxWidth().padding(horizontal = 16.dp)).hapticTapFeedback(),
                                label = { Text("Bitwarden 主密码") },
                                singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                enabled = !busy,
                            )
                            Button(
                                onClick = hapticAction(Button@ { runOperation { BitwardenVaultRepository.unlock(appContext, password) } }),
                                enabled = password.isNotEmpty() && !busy,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            ) { Text("解锁保险库") }
                        } else {
                            SettingsItem(
                                icon = Icons.TwoTone.VpnKey,
                                title = "打开保险库",
                                subtitle = "搜索、新增、编辑登录、笔记、银行卡和身份",
                                onClick = { context.startActivity(Intent(context, com.kingzcheung.xime.password.BitwardenVaultActivity::class.java)) },
                                showArrow = true,
                            )
                            SettingsItem(
                                icon = Icons.TwoTone.VpnKey,
                                title = "立即同步",
                                subtitle = "从 Bitwarden 账户获取最新的加密保险库",
                                onClick = { runOperation { BitwardenVaultRepository.sync(appContext) } },
                            )
                            SettingsItem(
                                icon = Icons.TwoTone.VpnKey,
                                title = "锁定保险库",
                                subtitle = "清除已解密凭据和记住的解锁状态；下次需要主密码",
                                onClick = {
                                    BitwardenVaultRepository.lock(context)
                                    status = BitwardenVaultRepository.status(appContext)
                                },
                            )
                        }
                        SettingsItem(
                            icon = Icons.TwoTone.VpnKey,
                            title = "断开账户",
                            subtitle = "删除本机加密缓存和登录状态",
                            onClick = { disconnectConfirmation = true },
                        )
                    } else {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            modifier = (Modifier.fillMaxWidth().padding(horizontal = 16.dp)).hapticTapFeedback(),
                            label = { Text("Bitwarden 邮箱") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
                            ),
                            enabled = !busy,
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = (Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)).hapticTapFeedback(),
                            label = { Text("Bitwarden 主密码") },
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                            ),
                            enabled = !busy,
                        )
                        OutlinedTextField(
                            value = server,
                            onValueChange = { server = it },
                            modifier = (Modifier.fillMaxWidth().padding(horizontal = 16.dp)).hapticTapFeedback(),
                            label = { Text("服务器地址") },
                            supportingText = { Text("默认 https://vault.bitwarden.com；自托管服务请输入服务器根地址") },
                            singleLine = true,
                            enabled = !busy,
                        )
                        if (needsTwoFactor) {
                            OutlinedTextField(
                                value = twoFactorCode,
                                onValueChange = { twoFactorCode = it },
                                modifier = (Modifier.fillMaxWidth().padding(horizontal = 16.dp)).hapticTapFeedback(),
                                label = { Text("验证器验证码") },
                                singleLine = true,
                                enabled = !busy,
                            )
                        }
                        Button(
                            onClick = hapticAction(Button@ {
                                runOperation {
                                    BitwardenVaultRepository.connect(appContext, email, password, server, twoFactorCode)
                                }
                            }),
                            enabled = email.isNotBlank() && password.isNotEmpty() && !busy,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) {
                            if (busy) CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp), strokeWidth = 2.dp)
                            Text(if (needsTwoFactor) "验证并连接" else "连接 Bitwarden")
                        }
                    }
                    if (message.isNotBlank()) {
                        Text(
                            message,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (message.contains("失败") || message.contains("不正确")) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                SettingsSection("系统凭据服务") {
                    SettingsItem(
                        icon = Icons.TwoTone.VpnKey,
                        title = "启用 Xime 自动填充与凭据服务",
                        subtitle = "在系统密码设置中选择 Xime；Android 14+ 可作为密码凭据提供方",
                        onClick = ::openProviderSettings,
                        showArrow = true,
                    )
                    Text(
                        "当前提供真实 Bitwarden 登录条目的系统密码自动填充；通行密钥创建、同步与断言尚未实现。密码不会写入 Xime 输入学习或剪贴板。",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (disconnectConfirmation) {
        AlertDialog(
            onDismissRequest = { disconnectConfirmation = false },
            title = { Text("断开 Bitwarden 账户？") },
            text = { Text("将删除本机加密的同步缓存和登录状态，不会修改服务器上的数据。") },
            confirmButton = {
                TextButton(onClick = hapticAction(TextButton@ {
                    BitwardenVaultRepository.disconnect(appContext)
                    status = BitwardenVaultRepository.status(appContext)
                    email = ""
                    password = ""
                    message = "已断开 Bitwarden 账户"
                    disconnectConfirmation = false
                })) { Text("断开") }
            },
            dismissButton = { TextButton(onClick = hapticAction(TextButton@ { disconnectConfirmation = false })) { Text("取消") } },
        )
    }
}
