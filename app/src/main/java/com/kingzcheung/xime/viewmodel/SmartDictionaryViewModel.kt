package com.kingzcheung.xime.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.settings.DictionaryPackManager
import com.kingzcheung.xime.settings.DictionaryPackPreview
import com.kingzcheung.xime.settings.DictionaryPackSnapshot
import com.kingzcheung.xime.settings.DictionaryCloudState
import com.kingzcheung.xime.settings.DictionaryCloudUpdates
import com.kingzcheung.xime.settings.CloudDictionary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class SmartDictionaryState(
    val snapshot: DictionaryPackSnapshot = DictionaryPackSnapshot(emptyList(), null, emptyList(), false),
    val preview: DictionaryPackPreview? = null,
    val busy: Boolean = false,
    val message: String = "",
    val failed: Boolean = false,
    val cloud: DictionaryCloudState = DictionaryCloudState(emptyList(), false, 24, emptyList(), 0, ""),
)

class SmartDictionaryViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val mutableState = MutableStateFlow(SmartDictionaryState())
    val state = mutableState.asStateFlow()
    init {
        viewModelScope.launch {
            DictionaryPackManager.stateRevision.drop(1).collect {
                val before = mutableState.value.snapshot
                refresh()
                val after = mutableState.value.snapshot
                if (before.pending != null && after.pending == null) {
                    val succeeded = after.lastSuccess > before.lastSuccess
                    mutableState.update { it.copy(
                        message = if (succeeded) "词库已编译并部署" else "部署失败，已恢复原词库；请查看更新历史",
                        failed = !succeeded,
                    ) }
                }
            }
        }
        load()
    }

    fun load() = operation {
        refresh()
        viewModelScope.launch {
            runCatching { DictionaryCloudUpdates.check(context) }
            refresh()
        }
    }
    fun importFrom(uri: Uri) = operation {
        val preview = DictionaryPackManager.preview(context, uri)
        mutableState.update { it.copy(preview = preview) }
    }
    fun dismissPreview() { mutableState.update { it.copy(preview = null) } }
    fun install() = operation {
        val preview = mutableState.value.preview ?: return@operation
        DictionaryPackManager.stage(context, preview)
        mutableState.update { it.copy(preview = null) }
        applyAndRefresh()
    }
    fun toggle(id: String, enabled: Boolean) = operation {
        DictionaryPackManager.setEnabled(context, id, enabled)
        applyAndRefresh()
    }
    fun uninstall(id: String) = operation {
        DictionaryPackManager.uninstall(context, id)
        applyAndRefresh()
    }
    fun rollback() = operation { DictionaryPackManager.rollback(context); applyAndRefresh() }
    fun apply() = operation { applyAndRefresh() }
    fun fromUrl(url: String, hash: String) = operation {
        if (hash.isBlank()) {
            DictionaryCloudUpdates.subscribe(context, url)
            mutableState.update { it.copy(message = "目录订阅已添加") }
        } else {
            val preview = DictionaryCloudUpdates.previewDirect(url.trim(), hash.trim())
            mutableState.update { it.copy(preview = preview) }
        }
        refresh()
    }
    fun checkUpdates() = operation {
        val count = DictionaryCloudUpdates.check(context)
        mutableState.update { it.copy(message = if (count == 0) "目录已刷新；已安装词库没有可用更新" else "发现 $count 个词库更新，请在目录中选择安装") }
        refresh()
    }
    fun installCloud(entry: CloudDictionary) = operation {
        mutableState.update { it.copy(message = "正在下载并校验词库…") }
        val message = DictionaryCloudUpdates.install(context, entry)
        mutableState.update { it.copy(message = message) }
        refresh()
    }
    fun configure(automatic: Boolean, hours: Long) = operation {
        DictionaryCloudUpdates.configure(context, automatic, hours)
        refresh()
    }
    fun unsubscribe(url: String) = operation { DictionaryCloudUpdates.unsubscribe(context, url); refresh() }

    private suspend fun applyAndRefresh() {
        val message = DictionaryPackManager.applyPending(context)
        mutableState.update { it.copy(message = message) }
        refresh()
    }
    private suspend fun refresh() {
        val snapshot = DictionaryPackManager.snapshot(context)
        val cloud = DictionaryCloudUpdates.state(context)
        mutableState.update { it.copy(snapshot = snapshot, cloud = cloud) }
    }
    private fun operation(block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, message = "", failed = false) }
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutableState.update { it.copy(message = failure.message ?: "词库操作失败", failed = true) }
                runCatching { refresh() }
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }
}
