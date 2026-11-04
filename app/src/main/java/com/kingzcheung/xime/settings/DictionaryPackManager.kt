package com.kingzcheung.xime.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.scheme.SchemeDeployer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class DictionaryPackSnapshot(
    val installed: List<InstalledDictionaryPack>,
    val pending: List<InstalledDictionaryPack>?,
    val history: List<String>,
    val canRollback: Boolean,
    val lastSuccess: Long = 0,
) {
    internal fun hasPendingChange(id: String): Boolean = pending?.let { desired ->
        desired.find { it.id == id } != installed.find { it.id == id }
    } ?: false

    internal fun catalogState(entry: CloudDictionary): CatalogInstallationState {
        if (hasPendingChange(entry.id)) return CatalogInstallationState.WAITING_DEPLOYMENT
        val current = installed.find { it.id == entry.id } ?: return CatalogInstallationState.AVAILABLE
        if (current.version != entry.version || current.packageHash != entry.sha256 || current.sourceUrl != entry.manifestUrl) {
            return CatalogInstallationState.UPDATE_AVAILABLE
        }
        return if (current.enabled) CatalogInstallationState.ENABLED else CatalogInstallationState.DISABLED
    }
}

internal enum class CatalogInstallationState { AVAILABLE, UPDATE_AVAILABLE, WAITING_DEPLOYMENT, ENABLED, DISABLED }

object DictionaryPackManager {
    private val mutex = Mutex()
    private val revision = MutableStateFlow(0L)
    internal val stateRevision = revision.asStateFlow()
    private val recoveryLock = Any()
    @Volatile private var inputActive = false

    fun setInputActive(active: Boolean) { inputActive = active }
    private fun store(context: Context) = DictionaryPackStore(File(context.filesDir, "dictionary-packs"), SchemaManager.getRimeDir(context))

    fun recoverBeforeInitialization(context: Context) {
        synchronized(recoveryLock) { if (!RimeEngine.isInitialized()) store(context).recover() }
    }

    suspend fun snapshot(context: Context): DictionaryPackSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock { store(context).let { DictionaryPackSnapshot(it.installed(), it.pending(), it.history(), it.canRollback(), it.lastSuccess()) } }
    }

    suspend fun preview(context: Context, uri: Uri): DictionaryPackPreview = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
        val bytes = context.contentResolver.openInputStream(uri)?.use { DictionaryPackFormat.readBounded(it, DictionaryPackFormat.MAX_DOWNLOAD) }
            ?: error("无法读取所选文件")
        DictionaryPackFormat.parse(bytes, name)
    }

    suspend fun stage(context: Context, preview: DictionaryPackPreview, sourceUrl: String = "") = withContext(Dispatchers.IO) {
        mutex.withLock { store(context).stage(preview, sourceUrl); revision.value += 1 }
    }
    suspend fun setEnabled(context: Context, id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock { store(context).setEnabled(id, enabled); revision.value += 1 }
    }
    suspend fun uninstall(context: Context, id: String) = withContext(Dispatchers.IO) {
        mutex.withLock { store(context).uninstall(id); revision.value += 1 }
    }
    suspend fun rollback(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock { store(context).rollback(); revision.value += 1 }
    }

    suspend fun applyPending(context: Context): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val store = store(context)
            val pending = store.pending() ?: return@withLock "没有待部署变更"
            if (inputActive || !RimeEngine.isInitialized()) return@withLock "已暂存，输入会话结束后部署"
            val engine = RimeEngine.getInstance()
            if (!RimeEngine.rimeLock.tryLock()) return@withLock "已暂存，等待输入法引擎空闲"
            try {
                if (inputActive || engine.isMaintaining() || engine.getInput().isNotEmpty()) return@withLock "已暂存，输入会话结束后部署"
                val schema = engine.getCurrentSchema()
                val managedNames = (store.installed() + pending).map { it.dictName }.toSet()
                val enabledFrostSchemas = store.schemas().filter { it in SchemaManager.getEnabledSchemas(context) }
                require(enabledFrostSchemas.isNotEmpty()) { "至少启用一个白霜方案后才能部署词库" }
                try {
                    store.apply(deploy = {
                        require(!inputActive) { "输入会话已开始，变更已回滚，请收起输入法后重试" }
                        SchemeDeployer.deploy(context, force = false).ok
                    }, verify = { schemas, active ->
                        if (schema.isNotBlank()) engine.switchSchema(schema)
                        enabledFrostSchemas.all { id ->
                            engine.getSchemaDictionary(id) == "rime_frost" &&
                                engine.getSchemaPacks(id).filter { it in managedNames }.toSet() == active.map { it.dictName }.toSet()
                        }
                    })
                    "词库已编译并部署"
                } finally {
                    revision.value += 1
                    if (schema.isNotBlank() && !engine.isMaintaining()) engine.switchSchema(schema)
                }
            } finally {
                RimeEngine.rimeLock.unlock()
            }
        }
    }
}
