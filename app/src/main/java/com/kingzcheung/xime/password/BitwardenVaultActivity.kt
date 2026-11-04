package com.kingzcheung.xime.password

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.kingzcheung.xime.MainActivity
import com.kingzcheung.xime.settings.SettingsPreferences
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.ui.theme.hapticAction
import com.kingzcheung.xime.ui.theme.hapticToggle
import com.kingzcheung.xime.ui.theme.hapticTapFeedback
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.lifecycle.repeatOnLifecycle
import java.security.SecureRandom

internal class VaultEditorState : ViewModel() {
    var entries by mutableStateOf<List<BitwardenVaultEntry>>(emptyList())
    var folders by mutableStateOf<Map<String, String>>(emptyMap())
    var draft by mutableStateOf<BitwardenVaultEntry?>(null)
    var viewingEntry by mutableStateOf<BitwardenVaultEntry?>(null)
    var query by mutableStateOf("")
    var message by mutableStateOf("")
    var revision by mutableIntStateOf(0)
    var busy by mutableStateOf(false)
    var unlocked by mutableStateOf(BitwardenVaultRepository.hasSession())
    var masterPassword by mutableStateOf("")
    var showSecret by mutableStateOf(false)
    fun clear() { entries = emptyList(); folders = emptyMap(); draft = null; viewingEntry = null; query = ""; message = ""; masterPassword = ""; showSecret = false; unlocked = false }
    override fun onCleared() { clear() }
}

class BitwardenVaultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        val darkMode = SettingsPreferences.getDarkMode(this)
        setContent {
            XimeTheme(
                darkTheme = when (darkMode) {
                    2 -> isSystemInDarkTheme()
                    1 -> true
                    else -> false
                },
                themeId = SettingsPreferences.getKeyboardTheme(this),
            ) { VaultScreen() }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun VaultScreen(state: VaultEditorState = viewModel()) {
        val context = this@BitwardenVaultActivity
        val scope = state.viewModelScope
        val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        var busy by state::busy
        var unlocked by state::unlocked
        var masterPassword by state::masterPassword
        var showSecret by state::showSecret
        var confirmDiscard by remember { mutableStateOf(false) }
        var confirmTrash by remember { mutableStateOf(false) }
        var filter by remember { mutableStateOf<VaultBrowseFilter?>(null) }
        var tab by remember { mutableIntStateOf(0) }
        var showMenu by remember { mutableStateOf(false) }
        var searchVisible by remember { mutableStateOf(false) }
        var totpSeconds by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
        val draft = state.draft
        val viewingEntry = state.viewingEntry

        fun back() {
            if (state.draft != null) confirmDiscard = true
            else if (state.viewingEntry != null) { state.viewingEntry = null; showSecret = false }
            else if (filter != null) { filter = null; state.query = ""; searchVisible = false }
            else if (tab != 0) tab = 0 else finish()
        }
        fun operate(operation: suspend () -> BitwardenOperation, closeEditor: Boolean = false) {
            if (busy) return
            busy = true
            state.message = ""
            scope.launch {
                try {
                    when (val result = operation()) {
                        is BitwardenOperation.Success -> {
                            unlocked = BitwardenVaultRepository.hasSession()
                            if (closeEditor) state.draft = null
                            state.revision++
                            state.message = if (closeEditor) "已保存到 Bitwarden 账户" else "保险库已更新"
                            masterPassword = ""
                            showSecret = false
                        }
                        is BitwardenOperation.Failure -> state.message = result.message
                        BitwardenOperation.TwoFactorRequired -> state.message = "请在密码设置中完成账户验证"
                    }
                } finally { busy = false }
            }
        }

        LaunchedEffect(unlocked, state.revision) {
            if (!unlocked) unlocked = BitwardenVaultRepository.resumeSession(context)
            state.entries = if (unlocked) BitwardenVaultRepository.vaultEntries() else emptyList()
            state.folders = if (unlocked) BitwardenVaultRepository.vaultFolders() else emptyMap()
        }
        LaunchedEffect(filter?.kind, unlocked) {
            if (unlocked && filter?.kind == VaultBrowseFilter.Kind.Totp) lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while (isActive) { totpSeconds = System.currentTimeMillis() / 1000; delay(1000) }
            }
        }
        DisposableEffect(Unit) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    scope.launch {
                        unlocked = BitwardenVaultRepository.hasSession() || BitwardenVaultRepository.resumeSession(context)
                        if (unlocked) state.revision++ else state.clear()
                    }
                }
                if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && !isChangingConfigurations) {
                    state.entries = emptyList()
                    state.draft = null
                    state.viewingEntry = null
                    state.query = ""
                    state.message = ""
                    masterPassword = ""
                    showSecret = false
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        BackHandler(enabled = !busy, onBack = ::back)
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text(if (draft != null) { if (draft.id.isEmpty()) "新增条目" else "编辑条目" } else viewingEntry?.title ?: if (tab == 1) "生成器" else filter?.title ?: "我的密码库", style = MaterialTheme.typography.headlineSmall) },
                    navigationIcon = { IconButton(onClick = hapticAction { if (!busy) back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                    actions = {
                        if (viewingEntry != null && draft == null) {
                            TextButton(onClick = hapticAction { state.draft = viewingEntry; state.viewingEntry = null; state.message = ""; showSecret = false }, enabled = !busy) { Text("编辑") }
                        } else if (unlocked && draft == null && tab == 0) {
                            FilledTonalIconButton(onClick = hapticAction { showMenu = true }, modifier = Modifier.semantics { contentDescription = "当前 Bitwarden 账户" }) {
                                Text(BitwardenVaultRepository.status(context).email.substringBefore('@').take(2).uppercase())
                            }
                            IconButton(onClick = hapticAction { filter = VaultBrowseFilter("搜索密码库"); searchVisible = true }) { Icon(Icons.Outlined.Search, "搜索密码库") }
                            Box {
                                IconButton(onClick = hapticAction { showMenu = true }) { Icon(Icons.Outlined.MoreVert, "更多操作") }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    DropdownMenuItem(text = { Text(BitwardenVaultRepository.status(context).email) }, enabled = false, onClick = {})
                                    DropdownMenuItem(text = { Text("立即同步") }, enabled = !busy, onClick = hapticAction { showMenu = false; operate({ BitwardenVaultRepository.sync(context) }) })
                                    DropdownMenuItem(text = { Text("锁定密码库") }, enabled = !busy, onClick = hapticAction { showMenu = false; BitwardenVaultRepository.lock(context); state.clear(); unlocked = false })
                                }
                            }
                        }
                    },
                )
            },
            floatingActionButton = {
                if (unlocked && draft == null && viewingEntry == null && tab == 0 && filter?.kind !in listOf(VaultBrowseFilter.Kind.Archived, VaultBrowseFilter.Kind.Trash)) {
                    FloatingActionButton(onClick = hapticAction { state.draft = BitwardenVaultEntry(); state.message = "" }, containerColor = MaterialTheme.colorScheme.primaryContainer) { Icon(Icons.Outlined.Add, "新增条目", Modifier.size(32.dp)) }
                }
            },
            bottomBar = {
                if (draft == null && viewingEntry == null) {
                    val bottomInset = WindowInsets.navigationBars.getBottom(LocalDensity.current)
                    val destinations = remember {
                        listOf(
                        Triple("我的密码库", Icons.Outlined.Lock, Icons.Filled.Lock),
                        Triple("生成器", Icons.Outlined.Autorenew, Icons.Filled.Autorenew),
                        )
                    }
                    Box(
                        Modifier.fillMaxWidth().navigationBarsPadding()
                            .padding(bottom = if (bottomInset > 0) 6.dp else 14.dp),
                        contentAlignment = androidx.compose.ui.Alignment.BottomCenter,
                    ) {
                        Row(
                            Modifier.width(264.dp).height(64.dp).shadow(
                                16.dp, RoundedCornerShape(32.dp), clip = false,
                                ambientColor = Color.Black.copy(alpha = if (darkTheme) .35f else .12f),
                                spotColor = Color.Black.copy(alpha = if (darkTheme) .35f else .12f),
                            ).clip(RoundedCornerShape(32.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(4.dp),
                        ) {
                            destinations.forEachIndexed { index, (label, icon, selectedIcon) ->
                                val selected = tab == index
                                val indicatorAlpha by animateFloatAsState(
                                    targetValue = if (selected) 1f else 0f,
                                    animationSpec = tween(160, easing = androidx.compose.animation.core.CubicBezierEasing(.215f, .61f, .355f, 1f)),
                                    label = "vaultNavSelection",
                                )
                                Box(
                                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(28.dp))
                                        .clickable(onClick = hapticAction { tab = index; filter = null; state.query = "" })
                                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = indicatorAlpha)),
                                    contentAlignment = androidx.compose.ui.Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                        Icon(
                                            if (selected) selectedIcon else icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(22.dp),
                                            tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            label,
                                            fontSize = 11.5.sp,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
        ) { insets ->
            val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
            val bodyInsets = PaddingValues(
                start = insets.calculateStartPadding(layoutDirection),
                top = insets.calculateTopPadding(),
                end = insets.calculateEndPadding(layoutDirection),
                bottom = if (draft == null && viewingEntry == null) 0.dp else insets.calculateBottomPadding(),
            )
            Box(Modifier.fillMaxSize().padding(bodyInsets).consumeWindowInsets(bodyInsets).imePadding(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
                if (viewingEntry != null && draft == null && unlocked) {
                    VaultEntryDetails(viewingEntry, showSecret, { showSecret = !showSecret })
                }
                else LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
                    if (state.message.isNotBlank()) item { Text(state.message, color = MaterialTheme.colorScheme.primary) }
                    if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (tab == 1 && draft == null) {
                        item { VaultPasswordGenerator { password -> tab = 0; state.draft = BitwardenVaultEntry(values = mapOf("password" to password)) } }
                    } else if (!unlocked) {
                        item { Text("解锁后记住本机登录状态。主密码不会保存；手动锁定或断开账户后需重新解锁。") }
                        if (!BitwardenVaultRepository.status(context).configured) {
                            item {
                                Button(onClick = hapticAction {
                                    startActivity(Intent(context, MainActivity::class.java)
                                        .putExtra("open_fragment", "passwords")
                                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                                }) { Text("前往密码管理设置") }
                            }
                        } else {
                            item {
                                OutlinedTextField(masterPassword, { masterPassword = it }, Modifier.fillMaxWidth().hapticTapFeedback(), label = { Text("主密码") }, singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !busy)
                            }
                            item { Button(onClick = hapticAction { operate({ BitwardenVaultRepository.unlock(context, masterPassword) }) }, enabled = masterPassword.isNotEmpty() && !busy) { Text("解锁") } }
                        }
                    } else if (draft == null) {
                        if (filter == null) vaultOverview(state.entries, state.folders, !busy) { filter = it; searchVisible = false }
                        else {
                        if (searchVisible) item { SecureField(state.query, { state.query = it }, "搜索名称、用户名或网址", true, false) }
                        val query = state.query.trim()
                        val filtered = state.entries.filter { filter!!.accepts(it) && (query.isEmpty() || it.title.contains(query, true) || it.values["username"].orEmpty().contains(query, true) || it.uris.any { uri -> uri.contains(query, true) }) }
                        item { Text("${filtered.size} 个条目", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (filtered.isEmpty()) item { Text("此分类没有条目", Modifier.padding(16.dp)) }
                        items(filtered, key = { it.id }) { entry ->
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                leadingContent = { Icon(vaultTypeIcon(entry.type), null) },
                                headlineContent = { Text(entry.title) },
                                supportingContent = { Text(entry.unavailableReason.ifEmpty { entry.values["username"].orEmpty().ifBlank { BitwardenEntryCodec.typeNames[entry.type] ?: "其他类型" } }) },
                                trailingContent = if (filter?.kind == VaultBrowseFilter.Kind.Totp) ({
                                    val code = VaultTotp.code(entry.values["totp"].orEmpty(), totpSeconds)
                                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                        Text(code?.value?.chunked(3)?.joinToString(" ") ?: "不支持的格式", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                        if (code != null) Text("${code.remaining} 秒", style = MaterialTheme.typography.labelSmall)
                                    }
                                }) else null,
                                modifier = Modifier.clickable(enabled = !busy && entry.unavailableReason.isEmpty()) { state.viewingEntry = entry; state.message = ""; showSecret = false },
                            )
                        }
                        }
                    } else {
                        if (draft.id.isEmpty()) item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                BitwardenEntryCodec.typeNames.filterKeys { it in BitwardenEntryCodec.fields }.forEach { (type, name) ->
                                    FilterChip(selected = draft.type == type, onClick = hapticAction { state.draft = draft.copy(type = type, values = emptyMap(), uris = emptyList()) }, label = { Text(name) }, enabled = !busy)
                                }
                            }
                        }
                        item { SecureField(draft.title, { state.draft = draft.copy(title = it) }, "名称", !busy, false) }
                        BitwardenEntryCodec.fields.getValue(draft.type).forEach { (field, label) ->
                            item(key = field) {
                                SecureField(draft.values[field].orEmpty(), { state.draft = draft.copy(values = draft.values + (field to it)) }, label, !busy, !showSecret && field in setOf("password", "totp", "number", "code", "ssn", "passportNumber", "licenseNumber"))
                            }
                        }
                        if (draft.type == 1) {
                            item { SecureField(draft.uris.joinToString("\n"), { state.draft = draft.copy(uris = it.split('\n')) }, "网址 / androidapp://包名（每行一个）", !busy, false, false) }
                            item {
                                TextButton(onClick = hapticAction { val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%+-_"; val random = SecureRandom(); state.draft = draft.copy(values = draft.values + ("password" to (1..20).map { alphabet[random.nextInt(alphabet.length)] }.joinToString(""))) }, enabled = !busy) { Text("生成 20 位随机密码") }
                            }
                        }
                        item { SecureField(draft.notes, { state.draft = draft.copy(notes = it) }, "备注", !busy, !showSecret, false) }
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("显示敏感内容")
                                Switch(showSecret, hapticToggle { showSecret = it }, enabled = !busy)
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("收藏")
                                Switch(draft.favorite, hapticToggle { state.draft = draft.copy(favorite = it) }, enabled = !busy)
                            }
                        }
                        item { Button(onClick = hapticAction { operate({ BitwardenVaultRepository.saveEntry(context, draft) }, true) }, enabled = draft.title.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) { Text("保存到 Bitwarden") } }
                        if (draft.id.isNotEmpty()) item { TextButton(onClick = hapticAction { confirmTrash = true }, enabled = !busy) { Text("移入 Bitwarden 回收站", color = MaterialTheme.colorScheme.error) } }
                    }
                }
            }
        }
        if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("放弃当前编辑？") },
            confirmButton = { TextButton(onClick = hapticAction { state.draft = null; confirmDiscard = false; state.message = ""; showSecret = false }) { Text("放弃") } },
            dismissButton = { TextButton(onClick = hapticAction { confirmDiscard = false }) { Text("继续编辑") } })
        if (confirmTrash && draft != null) AlertDialog(onDismissRequest = { confirmTrash = false }, title = { Text("移入回收站？") }, text = { Text("条目将从 Bitwarden 云端保险库移入回收站，可在其他客户端恢复。") },
            confirmButton = { TextButton(onClick = hapticAction { confirmTrash = false; operate({ BitwardenVaultRepository.trashEntry(context, draft) }, true) }) { Text("移入回收站") } },
            dismissButton = { TextButton(onClick = hapticAction { confirmTrash = false }) { Text("取消") } })
    }
}

@Composable
private fun SecureField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean, masked: Boolean, singleLine: Boolean = true) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().hapticTapFeedback(), label = { Text(label) }, enabled = enabled, singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None)
}
