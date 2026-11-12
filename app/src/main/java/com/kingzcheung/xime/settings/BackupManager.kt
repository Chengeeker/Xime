package com.kingzcheung.xime.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kingzcheung.xime.plugin.core.api.BackupPlugin
import com.kingzcheung.xime.scheme.SchemeStore
import com.kingzcheung.xime.plugin.core.api.RemoteBackupEntry
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 云备份引擎：备份包的生成与恢复在宿主，传输协议由 [BackupPlugin] 的 JS 实现承载。
 *
 * 分工（与剪贴板同步的 ClipboardSyncBridge 同构）：
 * - 备份 = [RimeExportManager.buildArchiveToFile] 流式打包到 cacheDir → 插件 pushBackup
 * - 恢复 = 插件 pullBackup（宿主流式下载到缓存文件）→ [restoreArchive] 校验路由落盘 → 释放句柄
 * - 插件永不接触宿主文件系统：JS 侧只拿到/交回**不透明 blob 句柄**，字节由宿主流式读写
 *   （大包不经过 JS 堆，避免整包 base64 进 JS 源码导致 OOM）
 *
 * 备份包格式（v1，兼容旧格式：`_xime_backup/` 前缀外的条目一律视为 rime 目录相对路径）：
 * - rime 目录文件（含 librime userdb 与 t9_digit.userdb，即自造词）在包根
 * - `_xime_backup/settings.json`                    设置项（kime_settings，类型保留）
 * - `_xime_backup/plugin_configs/<pluginId>.json`   旧插件配置（同设备兼容，无法解密时保留当前配置）
 * - `_xime_backup/plugins.xml`                      插件注册表（含启用状态）
 * - `_xime_backup/plugins/<pluginId>/…`             插件包（仅完整备份，包体可能数 MB）
 *
 * 恢复后：rime 文件重启输入会话生效；插件/设置在重启应用后生效（插件随 Application 重建加载）。
 * 注意：userdb 为 leveldb，运行中打快照存在一致性风险（文件级覆盖合并），
 * 建议在非输入状态执行备份/恢复。
 */
object BackupManager {

    private const val TAG = "BackupManager"

    /** 备份包内元数据条目前缀（保留字，rime 目录不应有同名顶层文件）。 */
    const val META_PREFIX = "_xime_backup/"

    /** 设置项 SharedPreferences 文件名（SettingsPreferences.PREFS_NAME，此处避免依赖私有常量）。 */
    private const val PREFS_NAME_SETTINGS = "kime_settings"

    private const val PREFS_PREFIX_PLUGIN_CFG = "plugin_cfg_"

    /** 方案清单条目（仅完整方案档）：文件所有权/校验记录，随方案本体一起恢复。 */
    private const val SCHEME_REGISTRY_ENTRY = "schemas/registry.json"
    private const val SCHEME_MANIFESTS_ENTRY = "schemas/manifests/"

    /** v3 插件注册表条目（仅完整方案档，与插件包同档）：安装记录 + 启用状态。 */
    private const val PLUGINS_REGISTRY_ENTRY = "plugins.json"

    /**
     * 云备份到远端。返回 BackupResult（失败含原因）。
     *
     * 打包前先触发一次引擎 sync：librime 把各 userdb 导出成
     * `rime/sync/<installation_id>/` 下的 `*.userdb.txt` 文本快照，随包上传（恢复侧再合并）。
     * 引擎未就绪时按"仅打包现有快照"降级，并在成功消息里说明，不阻断备份。
     */
    suspend fun backupNow(
        context: Context,
        plugin: BackupPlugin,
        backupPassword:CharArray?=null
    ): com.kingzcheung.xime.plugin.core.api.BackupResult {
        // 先刷新自造词快照（引擎未就绪则用现有快照并在成功消息里说明）
        val dictSnapshotNote = if (SyncManager.syncNow(context).isSuccess) {
            Log.i(TAG, "Backup: 自造词快照已刷新")
            null
        } else {
            Log.w(TAG, "Backup: 引擎未就绪，使用现有自造词快照（可能非最新）")
            tr(R.string.s_zizaocikuaizhaoweishuaxin_xuxi_b6784)
        }

        // 流式打包到 cacheDir：包体不驻留内存（含方案词典与插件包时可达数十 MB）
        val (fileName, archive) = RimeExportManager.buildArchiveToFile(context,backupPassword)
            .getOrElse { return com.kingzcheung.xime.plugin.core.api.BackupResult(ok = false, message = it.message) }
        return try {
            Log.i(TAG, "Backup: name=$fileName size=${archive.length()}")
            val result = plugin.pushBackup(fileName, archive)
            if (result.ok) {
                // 上传成功即一次完整备份，刷新卡片状态
                SettingsPreferences.setLastBackupAt(context, System.currentTimeMillis())
                if (dictSnapshotNote != null) result.copy(message = dictSnapshotNote) else result
            } else result
        } finally {
            archive.delete()
        }
    }

    /** 列出远端备份条目（透传插件；失败返回 null）。 */
    suspend fun listRemote(plugin: BackupPlugin): List<RemoteBackupEntry>? = plugin.listBackups()

    /** 删除远端备份条目。 */
    suspend fun deleteRemote(plugin: BackupPlugin, id: String): Boolean = plugin.deleteBackup(id)

    /**
     * 从远端恢复：宿主流式下载到缓存文件 → 按条目前缀路由落盘 → 释放句柄。
     *
     * 落盘后还有两步收尾（[afterRestore]）：包内含自造词快照时逐个直接合并进用户词库
     * （引擎未就绪则记 pending，等引擎起来再合并）；并把 default.custom.yaml 里的
     * 启用方案列表重新镜像进 default.yaml。
     *
     * @return 成功 true；失败返回错误消息
     */
    suspend fun restore(context: Context, plugin: BackupPlugin, id: String,backupPassword:CharArray?=null): Result<String> {
        val download = plugin.pullBackup(id)
            ?: return Result.failure(Exception(tr(R.string.s_xiazaibeifenbaoshibai)))
        return try {
            restoreFromArchive(context, download.file,backupPassword)
        } finally {
            // 恢复完释放落盘句柄（删除宿主缓存文件；幂等）
            plugin.releaseBackup(download.blobId)
        }
    }

    /**
     * 从本地文件导入完整方案包（覆盖式恢复；[isBackupPackage] 判定后的入口）。
     *
     * 大包不走内存：先流式拷进 cacheDir 临时文件再解包，用完删除。
     */
    suspend fun importLocal(context: Context, uri: android.net.Uri,backupPassword:CharArray?=null): Result<String> {
        val temp = File(File(context.cacheDir, "local-import").apply { mkdirs() }, "${java.util.UUID.randomUUID()}.zip")
        val job=kotlinx.coroutines.currentCoroutineContext()
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer=ByteArray(65536);var size=0L
                    while(true){job.ensureActive();val n=input.read(buffer);if(n<0)break;size+=n;require(size<=512L*1024*1024){"备份超过 512 MB 上限"};output.write(buffer,0,n)}
                }
            } ?: return Result.failure(Exception(tr(R.string.s_wufaduqusuoxuanwenjian)))
            if (!isBackupPackage(temp)) {
                return Result.failure(
                    IllegalArgumentException(tr(R.string.s_zhebushiwanzhengfanganbao_ques_352a8, META_PREFIX))
                )
            }
            restoreFromArchive(context, temp,backupPassword)
        } catch (e: Exception) {
            if(e is kotlinx.coroutines.CancellationException)throw e
            Log.e(TAG, "importLocal failed: ${e.javaClass.simpleName}")
            Result.failure(e)
        } finally {
            temp.delete()
        }
    }

    /** 判定一个 zip 是否是本应用的备份包（看中央目录里有没有 `_xime_backup/` 条目，不解压）。 */
    fun isBackupPackage(archive: File): Boolean {
        if (!archive.isFile) return false
        return try {
            java.util.zip.ZipFile(archive).use { zip ->
                zip.entries().asSequence().any { it.name.startsWith(META_PREFIX) }
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 备份包落盘 + 收尾（云端恢复与本地导入共用）。 */
    internal suspend fun restoreFromArchive(context:Context,archive:File,backupPassword:CharArray?=null,cipher:com.kingzcheung.xime.plugin.ValueCipher=com.kingzcheung.xime.plugin.SecureValueCipher):Result<String> {
        val previous=linkedMapOf<String,String>();val applied=linkedMapOf<String,String>();var skipped=0
        val job=kotlinx.coroutines.currentCoroutineContext()
        fun prepare(name:String,data:ByteArray):List<Pair<String,ByteArray>> {
            if(name=="portable_plugin_configs") {
                val password=backupPassword?:error("此全量备份需要输入独立备份密码，未恢复任何资料")
                val plain=PortablePluginConfigs.decrypt(data,password)
                val root=try{org.json.JSONObject(String(plain,Charsets.UTF_8))}finally{plain.fill(0)}
                require(root.getString("app")=="com.kingzcheung.xime" && root.getInt("version")==1){"插件配置备份标识无效"}
                val plugins=root.getJSONObject("plugins")
                val result=mutableListOf<Pair<String,ByteArray>>()
                for(id in plugins.keys()) {
                    require(isSafeId(id)){"插件标识无效"}
                    val prefsName=PREFS_PREFIX_PLUGIN_CFG+id
                    val prefs=context.getSharedPreferences(prefsName,0)
                    previous.putIfAbsent(prefsName,prefsToJson(prefs))
                    try {prefs.all.values.forEach{require(it is String);cipher.decrypt(it)}}
                    catch(e:com.kingzcheung.xime.plugin.SecretReadFailure){
                        if(!e.recoverable)throw e
                        com.kingzcheung.xime.plugin.archiveEncryptedFile(context,File(context.applicationInfo.dataDir,"shared_prefs/$prefsName.xml"))
                    }
                    val values=plugins.getJSONObject(id);val encoded=org.json.JSONObject()
                    for(key in values.keys()){
                        require(values.get(key) is String){"插件配置字段类型无效"}
                        encoded.put(key,org.json.JSONObject().put("t","s").put("v",cipher.encrypt(values.getString(key))))
                    }
                    result+=prefsName to encoded.toString().toByteArray(Charsets.UTF_8)
                }
                return result
            }
            if(name.startsWith(PREFS_PREFIX_PLUGIN_CFG)) {
                val values=org.json.JSONObject(String(data,Charsets.UTF_8))
                try {for(key in values.keys())cipher.decrypt(values.getJSONObject(key).getString("v"))}
                catch(_:com.kingzcheung.xime.plugin.SecretReadFailure){skipped++;return emptyList()}
            }
            previous.putIfAbsent(name,prefsToJson(context.getSharedPreferences(name,0)))
            return listOf(name to data)
        }
        val outcome=restoreArchiveOutcome(context.filesDir,{name,data->
            val prefs=context.getSharedPreferences(name,0)
            synchronized(prefs){check(prefsToJson(prefs)==previous.getValue(name)){"设置已改变，请重新恢复"}
            try{restorePrefsJson(prefs,data)}finally{applied[name]=prefsToJson(prefs)}}
        },archive.inputStream(),::prepare,checkCancelled={job.ensureActive()})
        if(outcome.isFailure) {
            val failure=outcome.exceptionOrNull()!!
            try {for((name,expected) in applied){val prefs=context.getSharedPreferences(name,0)
                if(prefsToJson(prefs)==expected){restorePrefsJson(prefs,previous.getValue(name).toByteArray(Charsets.UTF_8),replaceAll=true)}}}
            catch(rollback:Exception){failure.addSuppressed(rollback)}
            if(failure is kotlinx.coroutines.CancellationException)throw failure
            return Result.failure(failure)
        }
        val followUp=afterRestore(context,outcome.getOrThrow())
        return Result.success(if(followUp.isNotEmpty())"资料已写入，但"+followUp.joinToString("；")+"。请重启应用后重试。"+(if(skipped>0)"部分旧插件配置无法解密，已保留当前配置。"else"") else if(skipped>0)"方案与设置已恢复；$skipped 个旧插件配置无法用本机密钥解密，已保留当前配置，请从原设备重新制作有密码的备份。重启应用后生效。"else"方案与设置已恢复，重启应用后生效。插件服务是否接受恢复的凭据，需通过对应插件连接测试确认。")
    }

    /**
     * 恢复后收尾：自造词快照合并 + 启用方案列表镜像。
     *
     * 词库不直接覆盖 leveldb，而是把落盘的 `*.userdb.txt` 快照交给 librime
     * `UserDictManager::Restore` 按时间戳合并（与词库同步同语义）：备份后本机新打的词
     * 不会被旧备份吃掉；目标库名取自快照元数据，本机还没有该词库时会创建
     * （换机/清数据场景，见 [SyncManager.mergeSnapshots]）。
     * 引擎未就绪（冷启动直接进设置页）时记 pending 标记，由 IME 服务引擎就绪后补做。
     */
    private suspend fun afterRestore(context: Context, outcome: RestoreOutcome):List<String> {
        val notes=mutableListOf<String>()
        if (outcome.dictSnapshotCount > 0) {
            // 词库不直接覆盖 leveldb：快照交给 librime `UserDictManager::Restore` 按时间戳合并
            // （与词库同步同语义）：备份后本机新打的词不会被旧备份吃掉。
            //
            // 这里**不能**只调 syncNow()：librime 的 `Synchronize` 只处理本机已有
            // `<词典名>.userdb` 的词典，换机/清数据时本机一个用户词库都没有，快照会被静默
            // 跳过（且 sync 仍返回成功）——曾是"显示恢复成功但词库是空的"的根因。
            val snapshots = SyncManager.snapshotFiles(context)
            SyncManager.mergeSnapshots(context, snapshots)
                .onSuccess { mergedCount ->
                    Log.i(TAG, "Restore: 自造词快照已合并（$mergedCount/${snapshots.size} 个文件）")
                    // 一个都没并进去（包内快照损坏等）时留 pending，交给引擎就绪后重试
                    SettingsPreferences.setPendingDictMerge(context, mergedCount < snapshots.size)
                    if(mergedCount<snapshots.size)notes+="部分词库快照尚待合并"
                }
                .onFailure { e ->
                    if(e is kotlinx.coroutines.CancellationException)throw e
                    notes+="引擎尚未就绪，词库快照待合并"
                    Log.w(TAG, "Restore: 自造词快照待合并")
                    SettingsPreferences.setPendingDictMerge(context, true)
                }
        }
        // default.custom.yaml 已随包恢复。旧格式包内含 app 托管的 schema_list：
        // 强制迁移一次（列表进 prefs、从文件剥离；新格式文件无块则为无操作），
        // 再从 prefs 镜像进 default.yaml。default.yaml 本身不进包（派生基座），
        // 避免恢复时把旧版本基座一起盖回来。
        try {
            SchemaManager.migrateDefaultCustomSchemaList(context, force = true)
            // 备份可能来自旧版本（没有 active_package_id）或另一台设备（活动包 id
            // 对应的包不存在）：丢弃缓存，按恢复后的实际文件重新推断一次。
            SchemeStore.invalidate()
            SchemeStore.ensure(context)
            SchemaManager.applyEnabledSchemasToDefaultYaml(context)
            // 备份可能带回旧方案的模糊拼音块；按恢复后的 prefs 重新对账。
            FuzzyPinyinManager.reconcile(context)
        } catch (e: Exception) {
            if(e is kotlinx.coroutines.CancellationException)throw e
            notes+="启用方案列表同步未完成"
            Log.e(TAG, "Restore: 重新镜像 schema_list 失败")
        }
        return notes
    }

    /**
     * 恢复结果概要（收尾步骤据此判断要不要合并词库快照）。
     *
     * @param entryCount        落盘条目总数（0 → 视为空包失败）
     * @param dictSnapshotCount 落盘的自造词快照文件数（`sync/<id>/` 下的 `*.userdb.txt`）
     */
    internal data class RestoreOutcome(val entryCount: Int, val dictSnapshotCount: Int)

    /**
     * 恢复核心（纯 JVM，便于单测）：META 条目按前缀路由，其余条目进 filesDir/rime。
     *
     * @param prefsRestorer 宿主注入的 prefs 恢复器（Android 用 SharedPreferences；测试可注入假实现）
     */
    fun restoreArchive(
        filesDir: File,
        prefsRestorer: (prefsName: String, json: ByteArray) -> Unit,
        archive: File
    ): Result<Unit> = restoreArchiveOutcome(filesDir, prefsRestorer, archive.inputStream()).map { }

    /** 字节入口：语义与文件入口一致（校验规则共用一份实现）。 */
    fun restoreArchive(
        filesDir: File,
        prefsRestorer: (prefsName: String, json: ByteArray) -> Unit,
        bytes: ByteArray
    ): Result<Unit> = restoreArchiveOutcome(filesDir, prefsRestorer, bytes.inputStream()).map { }

    private fun restoreArchiveOutcome(
        filesDir: File,
        prefsRestorer: (prefsName: String, json: ByteArray) -> Unit,
        archive: File
    ): Result<RestoreOutcome> = restoreArchiveOutcome(filesDir, prefsRestorer, archive.inputStream())

    /** 字节入口（internal 供单测断言条目计数）：语义与文件入口一致。 */
    internal fun restoreArchiveOutcome(
        filesDir: File,
        prefsRestorer: (prefsName: String, json: ByteArray) -> Unit,
        bytes: ByteArray
    ): Result<RestoreOutcome> = restoreArchiveOutcome(filesDir, prefsRestorer, bytes.inputStream())

    private val restoreLock=Any()
    private fun restoreArchiveOutcome(
        filesDir:File,prefsRestorer:(String,ByteArray)->Unit,rawInput:java.io.InputStream,
        preparePrefs:(String,ByteArray)->List<Pair<String,ByteArray>> = {name,data->listOf(name to data)},
        checkCancelled:()->Unit = {}
    ):Result<RestoreOutcome> = synchronized(restoreLock) {
        val stage=File(filesDir.parentFile,".xime-restore-${java.util.UUID.randomUUID()}")
        val staged=File(stage,"new");val rollback=File(stage,"old");staged.mkdirs();rollback.mkdirs()
        val incoming=linkedMapOf<String,ByteArray>();val written=mutableListOf<Pair<File,File?>>()
        fun hash(file:File):String?=if(!file.exists())null else file.inputStream().use{input->
            val digest=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
            while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}
            digest.digest().joinToString(""){"%02x".format(it)}
        }
        fun copyAtomic(from:File,to:File){
            check(to.parentFile!!.isDirectory || to.parentFile!!.mkdirs())
            val temp=File(to.parentFile,".${to.name}.${java.util.UUID.randomUUID()}.new")
            try {
                java.io.FileOutputStream(temp).use { output -> from.inputStream().use { it.copyTo(output) }; output.fd.sync() }
                java.nio.file.Files.move(temp.toPath(),to.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
        }
        try {
            val extracted=extractArchiveOutcome(staged,{name,data->
                check(!incoming.containsKey(name)){"备份包含重复设置"};incoming[name]=data
            },rawInput,checkCancelled).getOrThrow()
            val prepared=incoming.flatMap{(name,data)->checkCancelled();preparePrefs(name,data)}
            require(prepared.map{it.first}.distinct().size==prepared.size){"备份包含重复配置"}
            for((_,bytes) in prepared)validatePrefsJson(bytes)
            checkCancelled()
            val files=staged.walkTopDown().filter{it.isFile}.toList()
            val originals=files.associate{it.relativeTo(staged).invariantSeparatorsPath to hash(File(filesDir,it.relativeTo(staged).path))}
            for(file in files){
                checkCancelled()
                val relative=file.relativeTo(staged).path;val target=File(filesDir,relative)
                check(target.canonicalPath.startsWith(filesDir.canonicalPath+File.separator)){"备份目标路径无效"}
                check(hash(target)==originals[file.relativeTo(staged).invariantSeparatorsPath]){"本地资料已改变，请重新恢复"}
                val old=if(target.isFile)File(rollback,relative).also{it.parentFile!!.mkdirs();target.copyTo(it)}else null
                written+=target to old
                copyAtomic(file,target)
            }
            prepared.forEach{(name,data)->checkCancelled();prefsRestorer(name,data)}
            checkCancelled()
            Result.success(extracted)
        }catch(failure:Exception){
            try{for((target,old) in written.asReversed()){
                val stagedFile=File(staged,target.relativeTo(filesDir).path)
                if(hash(target)==hash(stagedFile)){if(old!=null)copyAtomic(old,target)else check(target.delete())}
            }}catch(error:Exception){failure.addSuppressed(error)}
            Result.failure(failure)
        }finally{check(stage.canonicalPath.startsWith(filesDir.parentFile!!.canonicalPath+File.separator));stage.deleteRecursively()}
    }

    private fun extractArchiveOutcome(
        filesDir: File,
        prefsRestorer: (prefsName: String, json: ByteArray) -> Unit,
        rawInput: java.io.InputStream,
        checkCancelled:()->Unit = {}
    ): Result<RestoreOutcome> {
        try {
            val rimeDir = File(filesDir, "rime")
            if (!rimeDir.exists()) rimeDir.mkdirs()
            val pluginsDir = File(filesDir, "plugins")
            var entryCount = 0
            var dictSnapshotCount = 0
            var skippedUserDbEntries = 0
            var expanded=0L;val seen=mutableSetOf<String>();var mode:String?=null;var portable=false;var legacyConfigs=false
            ZipInputStream(rawInput).use { zis ->
                fun copyEntry(output:java.io.OutputStream,limit:Long=512L*1024*1024) {
                    var size=0L;val buffer=ByteArray(65536)
                    while(true){checkCancelled();val count=zis.read(buffer);if(count<0)break;size+=count;expanded+=count
                        require(size<=limit && expanded<=512L*1024*1024){"解压后的备份超过 512 MB 上限"};output.write(buffer,0,count)}
                }
                while (true) {
                    checkCancelled()
                    val entry = zis.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name
                    require(name.replace('\\','/').split('/').none{it=="." || it==".."}){"备份路径无效"}
                    require(seen.add(name.replace('\\','/')) && seen.size<=100000){"备份条目重复或过多"}
                    if (name.startsWith(META_PREFIX)) {
                        val rel = name.removePrefix(META_PREFIX)
                        val data = java.io.ByteArrayOutputStream().also{copyEntry(it,32L*1024*1024)}.toByteArray()
                        when {
                            rel == "portable-plugin-configs-v1.bin" -> {portable=true;prefsRestorer("portable_plugin_configs",data)}
                            rel == "backup-manifest.json" -> {
                                val info=org.json.JSONObject(String(data,Charsets.UTF_8))
                                require(info.getString("app")=="com.kingzcheung.xime" && info.getInt("version")==2 && info.getString("backup_mode") in setOf("safe","full")){"备份标识或版本不兼容"}
                                mode=info.getString("backup_mode")
                            }
                            rel == "settings.json" -> prefsRestorer(PREFS_NAME_SETTINGS, data)
                            rel == "dictionary_updates.json" -> prefsRestorer("xime_dictionary_updates", data)
                            rel.startsWith("dictionary-packs/") -> {
                                val path = rel.removePrefix("dictionary-packs/")
                                require(path.matches(Regex("(?:installed\\.json|sources/[a-f0-9]{64}\\.dict\\.yaml|notices/[a-f0-9]{64}\\.json)"))) { "非法词库备份元数据路径" }
                                val base = File(filesDir, "dictionary-packs")
                                writeChecked(File(base, path), base, data)
                            }
                            rel.startsWith("plugin_configs/") && rel.endsWith(".json") -> {
                                legacyConfigs=true
                                val pluginId = rel.removePrefix("plugin_configs/").removeSuffix(".json")
                                if (isSafeId(pluginId)) {
                                    prefsRestorer(PREFS_PREFIX_PLUGIN_CFG + pluginId, data)
                                }
                            }
                            rel == "plugins.xml" -> writeChecked(File(filesDir, "plugins.xml"), filesDir, data)
                            // v3 插件注册表（安装记录 + 启用状态）：与插件包同档（完整方案）
                            rel == "plugins.json" -> writeChecked(File(filesDir, "plugins.json"), filesDir, data)
                            // 旧版方案清单/注册表（文件所有权追踪）：模型已废弃，恢复时忽略
                            rel == SCHEME_REGISTRY_ENTRY ||
                                rel.startsWith(SCHEME_MANIFESTS_ENTRY) -> Unit
                            rel.startsWith("plugins/") -> writeChecked(
                                File(pluginsDir, rel.removePrefix("plugins/")), pluginsDir, data
                            )
                            // 当前方案集的单文件导入（用户资产），与插件同档
                            rel.startsWith("scheme-overlay/") -> writeChecked(
                                File(filesDir, rel), filesDir, data
                            )
                            else -> { /* 未知元数据条目跳过（向前兼容） */ }
                        }
                        entryCount++
                        zis.closeEntry()
                        continue
                    }
                    val target = File(rimeDir, name)
                    // 显式拒绝绝对路径（File(parent, "/abs") 在 Unix 下会被拼接为 parent/abs，
                    // canonical 检查拦不住；此处按契约直接拒绝）
                    // ZIP 路径跨平台，File.isAbsolute 只按当前宿主判断根路径。
                    val hasWindowsDriveRoot = name.length >= 3 &&
                        name[0].lowercaseChar() in 'a'..'z' && name[1] == ':' &&
                        (name[2] == '/' || name[2] == '\\')
                    if (File(name).isAbsolute || name.startsWith('/') || name.startsWith('\\') || hasWindowsDriveRoot) {
                        return Result.failure(Exception(tr(R.string.s_beifenbaobaohanfeifalujing, name)))
                    }
                    val canonicalRoot = rimeDir.canonicalPath + File.separator
                    if (target.canonicalPath != canonicalRoot.removeSuffix(File.separator) &&
                        !target.canonicalPath.startsWith(canonicalRoot)
                    ) {
                        return Result.failure(Exception(tr(R.string.s_beifenbaobaohanfeifalujing, name)))
                    }
                    // 词库**只合并不覆盖**：包内若带 leveldb 用户词典（旧版本打的包会带），
                    // 落盘就是整库覆盖，本机新打的词当场消失。词库统一由
                    // `sync/*.userdb.txt` 快照按时间戳合并进来（见 [afterRestore]）。
                    if (isUserDbLeveldbEntry(name)) {
                        skippedUserDbEntries++
                        copyEntry(object:java.io.OutputStream(){override fun write(value:Int){};override fun write(bytes:ByteArray,offset:Int,length:Int){}})
                        zis.closeEntry()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { copyEntry(it) }
                    if (name.startsWith("sync/") && name.endsWith(".userdb.txt")) dictSnapshotCount++
                    entryCount++
                    zis.closeEntry()
                }
            }
            require(mode==null || (mode=="full")==portable && !legacyConfigs){"备份模式与凭据条目不一致"}
            if (entryCount == 0) return Result.failure(Exception("备份包为空"))
            if (skippedUserDbEntries > 0) {
                Log.i(TAG, "Restore: 跳过包内 leveldb 用户词典 $skippedUserDbEntries 个条目（词库走快照合并）")
            }
            return Result.success(RestoreOutcome(entryCount, dictSnapshotCount))
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /** 元数据 id 安全性：非空且不含路径分隔符（防 marketplace/manifests 目录穿越）。 */
    private fun isSafeId(id: String): Boolean =
        id.isNotBlank() && !id.contains('/') && !id.contains('\\') && id != "." && id != ".."

    /**
     * 包内条目是否是 leveldb 用户词典（`<词典名>.userdb/` 目录下的文件，或 `*.db*`）。
     *
     * 恢复时**一律跳过**：这类文件落盘等于整库覆盖，会吃掉本机新打的词。
     * 词库只走 `sync/<id>/<词典名>.userdb.txt` 快照，由 librime 按时间戳合并
     * （`UserDbMerger` 取 commits/dee 最大值，只增不减）。纯函数，单测锚定。
     *
     * 注意别误伤快照 `.userdb.txt`：它以 `.userdb.txt` 结尾，不是 `.userdb` 段。
     */
    internal fun isUserDbLeveldbEntry(relativePath: String): Boolean {
        val path = relativePath.replace('\\', '/')
        if (path.split('/').any { it.endsWith(".userdb") }) return true
        return path.endsWith(".db") || path.endsWith(".db-wal") || path.endsWith(".db-shm")
    }

    /** 目标必须落在 baseDir 内（防元数据条目穿越），否则抛异常中止恢复。 */
    private fun writeChecked(target: File, baseDir: File, data: ByteArray) {
        val canonicalBase = baseDir.canonicalPath + File.separator
        if (File(target.name).isAbsolute ||
            !(target.canonicalPath + File.separator).startsWith(canonicalBase)
        ) {
            throw SecurityException(tr(R.string.s_beifenbaobaohanfeifalujing, target.name))
        }
        target.parentFile?.mkdirs()
        target.writeBytes(data)
    }

    // ---- prefs 备份/恢复（JSON，类型保留：b/i/l/f/s/ss） ----

    /** 序列化 prefs 全部键值（插件配置值为密文字符串，原样存取，不在备份链路解密）。 */
    fun prefsToJson(prefs: SharedPreferences): String {
        val root = org.json.JSONObject()
        for ((key, value) in prefs.all) {
            val entry = org.json.JSONObject()
            when (value) {
                is Boolean -> entry.put("t", "b").put("v", value)
                is Int -> entry.put("t", "i").put("v", value)
                is Long -> entry.put("t", "l").put("v", value)
                is Float -> entry.put("t", "f").put("v", value.toDouble())
                is String -> entry.put("t", "s").put("v", value)
                is Set<*> -> entry.put("t", "ss").put("v", org.json.JSONArray(value))
                else -> continue
            }
            root.put(key, entry)
        }
        return root.toString()
    }

    /** 按 [prefsToJson] 的类型标注恢复 prefs（合并写，不删除备份中不存在的新增键）。 */
    fun restorePrefsJson(prefs: SharedPreferences, json: ByteArray, replaceAll:Boolean=false) {
        validatePrefsJson(json)
        val root = org.json.JSONObject(json.toString(Charsets.UTF_8))
        val editor = prefs.edit().apply { if(replaceAll)clear() }
        for (key in root.keys()) {
            val entry = root.optJSONObject(key) ?: continue
            when (entry.optString("t")) {
                "b" -> editor.putBoolean(key, entry.getBoolean("v"))
                "i" -> editor.putInt(key, entry.getInt("v"))
                "l" -> editor.putLong(key, entry.getLong("v"))
                "f" -> editor.putFloat(key, entry.getDouble("v").toFloat())
                "s" -> editor.putString(key, entry.getString("v"))
                "ss" -> {
                    val arr = entry.getJSONArray("v")
                    editor.putStringSet(key, (0 until arr.length()).mapTo(mutableSetOf()) { arr.getString(it) })
                }
            }
        }
        check(editor.commit()){"备份设置保存失败"}
    }

    private fun validatePrefsJson(data:ByteArray) {
        val root=org.json.JSONObject(String(data,Charsets.UTF_8))
        for(key in root.keys()){
            val row=root.getJSONObject(key);val value=row.get("v")
            require(when(row.getString("t")){
                "b"->value is Boolean;"i"->value is Int;"l"->value is Int || value is Long
                "f"->value is Number && value.toDouble().isFinite();"s"->value is String
                "ss"->value is org.json.JSONArray && (0 until value.length()).all{value.get(it) is String};else->false
            }){"备份设置类型无效"}
        }
    }

    /**
     * 收集备份包元数据条目（设置项、插件配置、插件注册表、方案清单）。
     *
     * 包根前缀外的条目一律视为 rime 目录相对路径（见 [RimeExportManager.includeRimeEntry]）；
     * 历史 `plugins.xml`（v2 Lua 注册表，v3 已不读）不再产出，但恢复侧仍兼容旧包。
     */
    internal fun collectMetaEntries(context: Context,backupPassword:CharArray?=null,cipher:com.kingzcheung.xime.plugin.ValueCipher=com.kingzcheung.xime.plugin.SecureValueCipher): List<Pair<String, ByteArray>> {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        val settingsPrefs = context.getSharedPreferences(PREFS_NAME_SETTINGS, Context.MODE_PRIVATE)
        entries += (META_PREFIX + "settings.json") to prefsToJson(settingsPrefs).toByteArray(Charsets.UTF_8)
        entries += (META_PREFIX + "dictionary_updates.json") to prefsToJson(
            context.getSharedPreferences("xime_dictionary_updates", Context.MODE_PRIVATE)
        ).toByteArray(Charsets.UTF_8)

        entries+=(META_PREFIX+"backup-manifest.json") to org.json.JSONObject().put("app","com.kingzcheung.xime").put("version",2)
            .put("backup_mode",if(backupPassword==null)"safe"else"full").put("accountMode","GUEST").toString().toByteArray(Charsets.UTF_8)
        if(backupPassword!=null) {
            val plugins=org.json.JSONObject();val snapshots=linkedMapOf<String,Map<String,*>>()
            val sharedPrefsDir=File(context.applicationInfo.dataDir,"shared_prefs")
            sharedPrefsDir.listFiles()?.filter{it.name.startsWith(PREFS_PREFIX_PLUGIN_CFG) && it.name.endsWith(".xml")}?.forEach{file->
                val name=file.name.removeSuffix(".xml");val id=name.removePrefix(PREFS_PREFIX_PLUGIN_CFG);require(isSafeId(id))
                val all=context.getSharedPreferences(name,0).all.toMap();snapshots[name]=all
                val values=org.json.JSONObject()
                for((key,value) in all){require(value is String);values.put(key,cipher.decrypt(value)?:error("插件配置无法读取，已停止全量备份"))}
                plugins.put(id,values)
            }
            val plain=org.json.JSONObject().put("app","com.kingzcheung.xime").put("version",1).put("plugins",plugins).toString().toByteArray(Charsets.UTF_8)
            val encrypted=try{PortablePluginConfigs.encrypt(plain,backupPassword)}finally{plain.fill(0)}
            check(snapshots.all{(name,values)->context.getSharedPreferences(name,0).all==values}){"插件配置已改变，请重新备份"}
            entries+=(META_PREFIX+"portable-plugin-configs-v1.bin") to encrypted
        }

        // 插件注册表（安装记录 + 启用状态）：与 META plugins/ 下的插件包同档才有意义
        val pluginsRegistry = File(context.filesDir, PLUGINS_REGISTRY_ENTRY)
        if (pluginsRegistry.exists()) {
            entries += (META_PREFIX + PLUGINS_REGISTRY_ENTRY) to pluginsRegistry.readBytes()
        }

        return entries
    }
}
