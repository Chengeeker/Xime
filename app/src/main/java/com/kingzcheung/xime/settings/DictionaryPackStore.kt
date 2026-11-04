package com.kingzcheung.xime.settings

import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class InstalledDictionaryPack(
    val id: String, val name: String, val version: String, val enabled: Boolean,
    val hash: String, val entryCount: Int, val sourceUrl: String = "", val license: String = "未声明", val noticeHash: String = "", val packageHash: String = "",
) {
    val dictName: String get() = "xime_pack_$id"
    internal fun json(): JSONObject = JSONObject().put("id", id).put("name", name)
        .put("version", version).put("enabled", enabled).put("hash", hash)
        .put("entryCount", entryCount).put("sourceUrl", sourceUrl).put("license", license).put("noticeHash", noticeHash).put("packageHash", packageHash)

    companion object {
        internal fun from(json: JSONObject) = InstalledDictionaryPack(json.getString("id"), json.getString("name"),
            json.getString("version"), json.getBoolean("enabled"), json.getString("hash"),
            json.getInt("entryCount"), json.optString("sourceUrl", ""), json.optString("license", "未声明"), json.optString("noticeHash", ""), json.optString("packageHash", ""))
    }
}

/** 只管理 xime_pack_* 源词典、Frost custom 补丁及相应部署产物，永不访问 UserDB。 */
internal class DictionaryPackStore(private val root: File, private val rime: File) {
    private val stateFile = File(root, "installed.json")
    private val pendingFile = File(root, "pending.json")
    private val transaction = File(root, "transaction")
    private val previous = File(root, "previous")

    fun installed(): List<InstalledDictionaryPack> = packs(read(stateFile))
    fun pending(): List<InstalledDictionaryPack>? = pendingFile.takeIf(File::exists)?.let { packs(read(it)) }
    fun history(): List<String> = read(stateFile).optJSONArray("history")?.let { array ->
        (0 until array.length()).map(array::getString)
    }.orEmpty()
    fun canRollback(): Boolean = File(previous, "state.json").exists()
    fun lastSuccess(): Long = read(stateFile).optLong("lastSuccess", 0)

    fun stage(preview: DictionaryPackPreview, sourceUrl: String = "") {
        require(preview.canInstall) { "导入包含非法行，不能安装" }
        validateSyllables(preview.entries)
        val source = preview.source().toByteArray(Charsets.UTF_8)
        require(rime.usableSpace >= source.size * 3L + 8L * 1024 * 1024) { "可用存储不足，已停止词库暂存" }
        val hash = DictionaryPackFormat.sha256(source)
        val notices = JSONObject(preview.licenseNotices.toSortedMap()).toString().toByteArray()
        val noticeHash = DictionaryPackFormat.sha256(notices)
        val list = (pending() ?: installed()).toMutableList()
        val old = list.find { it.id == preview.id }
        list.removeAll { it.id == preview.id }
        list += InstalledDictionaryPack(preview.id, preview.name, preview.version, old?.enabled ?: true,
            hash, preview.entries.size, sourceUrl.ifBlank { old?.sourceUrl.orEmpty() }, preview.license, noticeHash, preview.packageHash)
        require(list.size <= 32) { "专题词库最多安装 32 个" }
        atomic(File(root, "sources/$hash.dict.yaml"), source)
        atomic(File(root, "notices/$noticeHash.json"), notices)
        stageList(list, "暂存 ${preview.name} ${preview.version}")
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val list = pending() ?: installed()
        require(list.any { it.id == id }) { "词库不存在" }
        stageList(list.map { if (it.id == id) it.copy(enabled = enabled) else it }, if (enabled) "启用 $id" else "停用 $id")
    }

    fun uninstall(id: String) {
        val list = pending() ?: installed()
        require(list.any { it.id == id }) { "词库不存在" }
        stageList(list.filterNot { it.id == id }, "卸载 $id")
    }

    fun rollback() {
        require(canRollback()) { "没有可恢复的上一版本" }
        stageList(packs(read(File(previous, "state.json"))), "恢复上一版本")
    }

    private fun stageList(list: List<InstalledDictionaryPack>, action: String) {
        require(!File(transaction, "journal.json").exists()) { "有未恢复的部署事务，请重新启动 Xime" }
        list.forEach {
            require(it.id.matches(Regex("[a-z][a-z0-9_-]{0,63}")) && it.hash.matches(Regex("[a-f0-9]{64}"))) { "托管词库状态损坏" }
            require(File(root, "sources/${it.hash}.dict.yaml").isFile) { "暂存词典源文件缺失" }
            require(it.noticeHash.isEmpty() || (it.noticeHash.matches(Regex("[a-f0-9]{64}")) && File(root, "notices/${it.noticeHash}.json").isFile)) { "词库许可文件缺失" }
        }
        atomic(pendingFile, JSONObject().put("packs", JSONArray(list.map { it.json() })).put("action", action).toString().toByteArray())
    }

    fun schemas(): List<String> = rime.listFiles().orEmpty().filter { it.name.endsWith(".schema.yaml") }.mapNotNull { file ->
        val config = runCatching { DictionaryPackFormat.yaml.parseToYamlNode(file.readText()) as? YamlMap }.getOrNull()
        val translator = config?.get("translator") as? YamlMap
        if ((translator?.get("dictionary") as? YamlScalar)?.content != "rime_frost") return@mapNotNull null
        val id = ((config?.get("schema") as? YamlMap)?.get("schema_id") as? YamlScalar)?.content ?: return@mapNotNull null
        id.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) && file.name == "$it.schema.yaml" }
    }.sorted()

    fun apply(deploy: () -> Boolean, verify: (List<String>, List<InstalledDictionaryPack>) -> Boolean): Boolean {
        if (!pendingFile.exists()) return false
        require(!File(transaction, "journal.json").exists()) { "部署事务尚未恢复" }
        val desiredState = read(pendingFile)
        val desired = packs(desiredState)
        val current = installed()
        val schemaIds = schemas()
        require(schemaIds.isNotEmpty()) { "未安装使用 rime_frost 主词典的白霜方案" }
        val active = desired.filter { it.enabled }
        val patches = schemaIds.associateWith { id ->
            val file = File(rime, "$id.custom.yaml")
            ManagedDictionaryPackPatch.merge(if (file.exists()) file.readText() else "", active.map { it.dictName }, (current + desired).map { it.dictName }.toSet())
        }
        (current + desired).distinctBy { it.id }.forEach { pack ->
            val live = File(rime, "${pack.dictName}.dict.yaml")
            if (live.exists()) {
                val installed = current.find { it.id == pack.id } ?: error("已有非托管同名源词典：${live.name}")
                require(DictionaryPackFormat.sha256(live.readBytes()) == installed.hash) { "${live.name} 已被外部修改；拒绝覆盖" }
            }
        }
        val paths = linkedSetOf<String>()
        (current + desired).distinctBy { it.id }.forEach {
            paths += "${it.dictName}.dict.yaml"
            paths += "build/${it.dictName}.table.bin"
            paths += "build/${it.dictName}.reverse.bin"
        }
        schemaIds.forEach { paths += "$it.custom.yaml"; paths += "build/$it.schema.yaml"; paths += "build/$it.prism.bin" }
        paths += "build/rime_frost.prism.bin"
        File(rime, "build").listFiles().orEmpty().filter { it.name.endsWith(".prism.bin") || it.name.endsWith(".schema.yaml") }
            .forEach { paths += "build/${it.name}" }
        val spaceNeeded = paths.sumOf { target(it).length() } + desired.sumOf { File(root, "sources/${it.hash}.dict.yaml").length() } + 16L * 1024 * 1024
        require(rime.usableSpace >= spaceNeeded) { "存储空间不足以保留回退快照，已停止部署" }
        transaction.mkdirs()
        atomic(File(transaction, "state.json"), read(stateFile).toString().toByteArray())
        val files = JSONArray()
        paths.forEach { path ->
            val original = target(path)
            files.put(JSONObject().put("path", path).put("exists", original.exists()))
            if (original.exists()) original.copyTo(File(transaction, "files/$path").also { it.parentFile?.mkdirs() }, overwrite = true)
        }
        atomic(File(transaction, "journal.json"), JSONObject().put("files", files).put("committed", false).toString().toByteArray())
        try {
            current.filter { it.id !in desired.map { pack -> pack.id } }.forEach {
                target("${it.dictName}.dict.yaml").delete()
                target("build/${it.dictName}.table.bin").delete()
                target("build/${it.dictName}.reverse.bin").delete()
            }
            desired.forEach { pack -> atomic(target("${pack.dictName}.dict.yaml"), File(root, "sources/${pack.hash}.dict.yaml").readBytes()) }
            patches.forEach { (id, text) -> atomic(target("$id.custom.yaml"), text.toByteArray()) }
            // librime 的配置时间戳精确到秒；仅移除目标编译配置，避免快速启停复用旧 packs。
            schemaIds.forEach { id ->
                val compiled = target("build/$id.schema.yaml")
                check(!compiled.exists() || compiled.delete()) { "无法更新方案编译配置：$id" }
            }
            // pack 编译出错可能仍返回 true；移除旧托管产物，避免把旧表误判为新版本。
            active.forEach { pack ->
                listOf("table.bin", "reverse.bin").forEach { suffix ->
                    val compiled = target("build/${pack.dictName}.$suffix")
                    check(!compiled.exists() || compiled.delete()) { "无法更新词库编译产物：${pack.id}" }
                }
            }
            require(deploy()) { "Rime 未完成增量部署" }
            require(active.all { pack -> validTable(target("build/${pack.dictName}.table.bin")) } && verify(schemaIds, active)) { "扩展词典编译或方案挂载校验失败" }
            val newState = read(stateFile).put("packs", JSONArray(desired.map { it.json() }))
                .put("lastSuccess", System.currentTimeMillis())
            addHistory(newState, desiredState.optString("action", "词库变更") + "：部署成功")
            atomic(stateFile, newState.toString().toByteArray())
            atomic(File(transaction, "journal.json"), JSONObject().put("files", files).put("committed", true).toString().toByteArray())
            pendingFile.delete()
            previous.deleteRecursively()
            check(transaction.renameTo(previous)) { "无法保存上一版本回退快照" }
            pruneSources()
            return true
        } catch (failure: Exception) {
            restore(transaction)
            pendingFile.delete()
            val state = read(stateFile)
            addHistory(state, "部署失败并恢复原文件：${failure.message}")
            atomic(stateFile, state.toString().toByteArray())
            transaction.deleteRecursively()
            pruneSources()
            throw failure
        }
    }

    fun recover() {
        val journal = File(transaction, "journal.json")
        if (!journal.exists()) { transaction.deleteRecursively(); return }
        if (!read(journal).optBoolean("committed", false)) {
            restore(transaction)
            transaction.deleteRecursively()
        } else {
            previous.deleteRecursively()
            check(transaction.renameTo(previous)) { "无法恢复已提交的词库快照" }
        }
        pendingFile.delete()
    }

    private fun restore(directory: File) {
        val files = read(File(directory, "journal.json")).getJSONArray("files")
        for (index in 0 until files.length()) {
            val record = files.getJSONObject(index)
            val path = record.getString("path")
            val live = target(path)
            if (record.getBoolean("exists")) atomic(live, File(directory, "files/$path").readBytes()) else live.delete()
        }
        atomic(stateFile, File(directory, "state.json").readBytes())
    }

    private fun target(relative: String): File {
        require(relative.matches(Regex("(?:xime_pack_[a-z][a-z0-9_-]*\\.dict\\.yaml|[A-Za-z0-9_-]+\\.custom\\.yaml|build/[A-Za-z0-9_-]+\\.(?:schema\\.yaml|prism\\.bin|table\\.bin|reverse\\.bin))"))) { "非法托管文件路径" }
        return File(rime, relative).also { require(it.canonicalPath.startsWith(rime.canonicalPath + File.separator)) }
    }

    private fun validateSyllables(entries: List<DictEntry>) {
        val seen = hashSetOf<String>()
        val syllables = hashSetOf<String>()
        fun collect(name: String) {
            if (!seen.add(name)) return
            val source = File(rime, "$name.dict.yaml")
            require(source.canonicalPath.startsWith(rime.canonicalPath + File.separator) && source.isFile) { "白霜主词典来源缺失：$name" }
            val header = StringBuilder()
            var data = false
            source.bufferedReader().useLines { lines -> lines.forEach { line ->
                if (!data) {
                    if (line.trim() == "...") data = true
                    else { require(header.length + line.length <= 65536) { "主词典头部异常" }; header.appendLine(line) }
                }
                else if (!line.startsWith('#')) line.split('\t').getOrNull(1)?.trim()?.split(Regex("\\s+"))?.let(syllables::addAll)
            } }
            DictionaryHelper.parseImportTables(header.toString()).forEach(::collect)
        }
        collect("rime_frost")
        val missing = entries.flatMap { it.code.split(' ') }.toSet() - syllables
        require(missing.isEmpty()) { "拼音不在本机白霜主词典音节表中：${missing.take(20).joinToString()}" }
    }

    private fun pruneSources() {
        val referenced = (installed() + packs(read(File(previous, "state.json")))).map { it.hash }.toSet()
        File(root, "sources").listFiles().orEmpty().filter { it.name.removeSuffix(".dict.yaml") !in referenced }.forEach(File::delete)
        val noticeHashes = (installed() + packs(read(File(previous, "state.json")))).map { it.noticeHash }.toSet()
        File(root, "notices").listFiles().orEmpty().filter { it.name.removeSuffix(".json") !in noticeHashes }.forEach(File::delete)
    }

    private fun packs(state: JSONObject): List<InstalledDictionaryPack> = state.optJSONArray("packs")?.let { list ->
        (0 until list.length()).map { InstalledDictionaryPack.from(list.getJSONObject(it)) }
    }.orEmpty()

    private fun read(file: File): JSONObject = if (file.exists()) JSONObject(file.readText()) else JSONObject()
    private fun addHistory(state: JSONObject, event: String) {
        val old = state.optJSONArray("history") ?: JSONArray()
        val history = JSONArray().put("${System.currentTimeMillis()} $event")
        for (index in 0 until minOf(old.length(), 99)) history.put(old.getString(index))
        state.put("history", history)
    }

    private fun atomic(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        java.io.FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun validTable(file: File): Boolean = file.isFile && file.inputStream().use { input ->
        val magic = "Rime::Table/".toByteArray()
        val actual = ByteArray(magic.size)
        input.read(actual) == actual.size && actual.contentEquals(magic)
    }
}
