package com.kingzcheung.xime.settings

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import java.util.zip.ZipInputStream

data class DictionaryPackPreview(
    val id: String,
    val name: String,
    val version: String,
    val entries: List<DictEntry>,
    val duplicates: Int,
    val errors: List<String>,
    val encoding: String,
    val license: String = "未声明",
    val licenseNotices: Map<String, String> = emptyMap(),
    val packageHash: String = "",
) {
    val dictName: String get() = "xime_pack_$id"
    val canInstall: Boolean get() = entries.isNotEmpty() && errors.isEmpty()

    fun source(): String = buildString {
        append("---\nname: $dictName\nversion: \"$version\"\nsort: by_weight\n")
        append("use_preset_vocabulary: false\ncolumns: [text, code, weight]\n...\n")
        entries.forEach { append("${it.word}\t${it.code}\t${it.weight ?: 100}\n") }
    }
}

object DictionaryPackFormat {
    const val MAX_DOWNLOAD = 12 * 1024 * 1024
    const val MAX_EXPANDED = 32 * 1024 * 1024
    private const val MAX_ENTRIES = 100_000
    internal val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))
    internal fun jsonObject(text: String): JSONObject {
        var depth = 0
        var quoted = false
        var escaped = false
        text.forEach { char ->
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 16) { "JSON 嵌套过深" } }
                '}', ']' -> depth--
            }
        }
        return JSONObject(text)
    }
    private val safeId = Regex("[a-z][a-z0-9_-]{0,63}")

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun readBounded(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size().toLong() + count <= limit) { "文件超过大小限制（${limit / 1024 / 1024} MiB）" }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    fun parse(bytes: ByteArray, fileName: String): DictionaryPackPreview {
        require(bytes.size <= MAX_DOWNLOAD) { "词库文件过大" }
        val sources = mutableListOf<Pair<String, ByteArray>>()
        var metadata: JSONObject? = null
        val notices = linkedMapOf<String, String>()
        if (fileName.endsWith(".zip", true) || bytes.take(2) == listOf(0x50.toByte(), 0x4b.toByte())) {
            var expanded = 0L
            val seen = hashSetOf<String>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var count = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= 128) { "ZIP 文件数量超过限制" }
                    val path = entry.name
                    require(path.isNotEmpty() && !path.startsWith('/') && '\\' !in path && ':' !in path &&
                        path.split('/').none { it == ".." || it == "." } && seen.add(path)) { "ZIP 路径非法或重复：$path" }
                    if (entry.isDirectory) continue
                    val data = readBounded(zip, MAX_EXPANDED - expanded.toInt())
                    expanded += data.size
                    require(expanded <= MAX_EXPANDED && expanded <= bytes.size.toLong().coerceAtLeast(1) * 300) { "ZIP 解压体积或压缩比超过限制" }
                    val base = path.substringAfterLast('/')
                    when {
                        path == "metadata.json" -> {
                            require(data.size <= 256 * 1024) { "元数据文件过大" }
                            metadata = jsonObject(decode(data).first)
                        }
                        base.startsWith("LICENSE", true) || path.startsWith("LICENSES/") -> {
                            require('.' !in base || base.substringAfterLast('.').lowercase() in setOf("txt", "md")) { "许可证目录不接受代码文件" }
                            require(data.size <= 256 * 1024) { "许可证文件过大" }
                            notices[path] = decode(data).first
                            require(notices.values.sumOf { it.length } <= 1024 * 1024) { "来源声明总大小超过限制" }
                        }
                        base.equals("README.md", true) -> Unit
                        base.endsWith(".dict.yaml", true) || base.endsWith(".tsv", true) || base.endsWith(".txt", true) -> sources += path to data
                        else -> error("ZIP 含不允许的文件：$path；只接受词典源文件与说明/许可证")
                    }
                }
            }
        } else {
            require(fileName.endsWith(".txt", true) || fileName.endsWith(".tsv", true) || fileName.endsWith(".dict.yaml", true)) { "仅支持 TXT、TSV、dict.yaml 和 ZIP" }
            sources += fileName to bytes
        }
        require(sources.isNotEmpty()) { "未找到词典源文件" }
        val entries = linkedMapOf<Pair<String, String>, DictEntry>()
        val errors = mutableListOf<String>()
        val encodings = linkedSetOf<String>()
        var duplicates = 0
        var invalid = 0
        for ((path, data) in sources) {
            val (text, encoding) = decode(data)
            encodings += encoding
            val lines = text.lineSequence().take(MAX_ENTRIES * 2 + 1025).toList()
            require(lines.size <= MAX_ENTRIES * 2 + 1024 && lines.all { it.length <= 2048 }) { "文本行数或单行长度超过限制" }
            var offset = 0
            if (path.endsWith(".dict.yaml", true)) {
                val end = lines.indexOfFirst { it.trim() == "..." }
                require(end >= 0) { "$path 缺少 Rime 词典头结束标记 ..." }
                val headerLines = lines.take(end)
                val headerText = headerLines.joinToString("\n")
                require(end <= 64 && headerText.length <= 16384 && headerText.count { it == '[' || it == '{' } <= 4 &&
                    headerLines.all { it.takeWhile(Char::isWhitespace).length <= 8 } &&
                    !Regex("(^|\\s)[&*][A-Za-z0-9_]+", RegexOption.MULTILINE).containsMatchIn(headerText)) { "$path 词典头过大或含复杂 YAML 引用" }
                val header = yaml.parseToYamlNode(headerText) as? YamlMap
                    ?: error("$path 词典头格式错误")
                val allowed = setOf("name", "version", "sort", "use_preset_vocabulary", "columns")
                require(header.entries.keys.all { it.content in allowed }) { "$path 包含外部依赖或不支持的词典指令；请导出独立词条表" }
                val columns = header["columns"] as? YamlList
                require(columns == null || columns.items.map { (it as? YamlScalar)?.content } in
                    listOf(listOf("text", "code", "weight"), listOf("text", "code"))) { "$path 的 columns 必须为 text, code[, weight]" }
                offset = end + 1
            }
            for ((index, raw) in lines.withIndex().drop(offset)) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith('#')) continue
                val parts = line.split('\t').map(String::trim).let {
                    if (it.size == 1) line.split(Regex("\\s+"), limit = 2) else it
                }
                if (index == offset && parts.first().lowercase() in setOf("phrase", "text", "词条", "词语")) continue
                val word = Normalizer.normalize(parts.first(), Normalizer.Form.NFC)
                val pinyin = parts.getOrNull(1)?.lowercase()?.replace("ü", "v")?.replace("u:", "v")
                    ?.split(Regex("\\s+"))?.map { it.replace(Regex("[1-5]$"), "") }.orEmpty()
                val weight = parts.getOrNull(2)?.takeIf(String::isNotBlank)?.toIntOrNull() ?: 100
                val hanCount = word.codePoints().count().toInt()
                val valid = hanCount in 1..64 && word.codePoints().allMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN } &&
                    pinyin.size == hanCount && pinyin.all { it.matches(Regex("[a-zv]{1,8}")) } &&
                    weight in 1..1_000_000_000 && (parts.getOrNull(2).isNullOrBlank() || parts[2].toIntOrNull() != null)
                if (!valid) {
                    invalid++
                    if (errors.size < 50) errors += "$path:${index + 1}：词条、逐字空格拼音或权重格式错误"
                    continue
                }
                val entry = DictEntry(word, pinyin.joinToString(" "), weight)
                val key = word to entry.code
                val existing = entries[key]
                if (existing != null) duplicates++
                if (existing == null || weight > (existing.weight ?: 100)) entries[key] = entry
                require(entries.size <= MAX_ENTRIES) { "词条超过 $MAX_ENTRIES 条限制" }
            }
        }
        if (invalid > errors.size) errors += "另有 ${invalid - errors.size} 行格式错误"
        val sorted = entries.values.sortedWith(compareBy<DictEntry> { it.word }.thenBy { it.code })
        val digest = sha256(sorted.joinToString("\n") { "${it.word}\t${it.code}\t${it.weight}" }.toByteArray())
        fun meta(key: String): String? = metadata?.opt(key)?.let { it as? String }?.takeIf(String::isNotBlank)
        metadata?.let { notices["SOURCE-NOTICES.json"] = it.toString() }
        val id = meta("id")?.also { require(it.matches(safeId)) { "词库 ID 不合法" } }
            ?: "local_${sha256(fileName.lowercase().toByteArray()).take(16)}"
        val version = meta("version")?.also { require(it.matches(Regex("[A-Za-z0-9.+_-]{1,96}"))) { "词库版本格式不合法" } } ?: digest.take(16)
        return DictionaryPackPreview(id, meta("name")?.take(100) ?: fileName.substringBeforeLast('.'), version,
            sorted, duplicates, errors, encodings.joinToString(" / "), meta("license")?.take(128) ?: "未声明", notices, sha256(bytes))
    }

    private fun decode(bytes: ByteArray): Pair<String, String> {
        val (charset, offset) = when {
            bytes.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) -> Charsets.UTF_8 to 3
            bytes.take(2) == listOf(0xff.toByte(), 0xfe.toByte()) -> Charsets.UTF_16LE to 2
            bytes.take(2) == listOf(0xfe.toByte(), 0xff.toByte()) -> Charsets.UTF_16BE to 2
            else -> Charsets.UTF_8 to 0
        }
        val text = try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            error("编码错误：请使用 UTF-8 或带 BOM 的 UTF-16 文本")
        }
        require('\u0000' !in text) { "文本含空字符，可能使用了无 BOM 的 UTF-16 或二进制文件" }
        return text to charset.name()
    }
}
