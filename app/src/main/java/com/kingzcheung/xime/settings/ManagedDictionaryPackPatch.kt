package com.kingzcheung.xime.settings

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import org.json.JSONObject

internal object ManagedDictionaryPackPatch {
    private const val BEGIN = "# xime-dictionaries:begin"
    private const val END = "# xime-dictionaries:end"

    fun merge(original: String, names: List<String>, ownedNames: Set<String> = names.toSet()): String {
        require(names.all { it.matches(Regex("xime_pack_[a-z][a-z0-9_-]{0,63}")) }) { "非法托管词典名" }
        val newline = if (original.contains("\r\n")) "\r\n" else "\n"
        var inside = false
        val lines = original.lines().filter { line ->
            when (line.trim()) {
                BEGIN -> { require(!inside) { "托管补丁标记损坏" }; inside = true; false }
                END -> { require(inside) { "托管补丁标记损坏" }; inside = false; false }
                else -> !inside
            }
        }.toMutableList()
        require(!inside) { "托管补丁未完整写入；拒绝覆盖自定义配置" }
        while (lines.lastOrNull()?.isBlank() == true) lines.removeAt(lines.lastIndex)
        if (lines.isEmpty() && names.isEmpty()) return ""
        if (lines.all { it.isBlank() }) { lines.clear(); lines += "patch:" }
        val text = lines.joinToString("\n")
        val root = DictionaryPackFormat.yaml.parseToYamlNode(text) as? YamlMap ?: error("自定义配置不是 YAML 对象")
        val patchNode = root.get<YamlNode>("patch")
        require(patchNode == null || patchNode is YamlMap || patchNode is YamlNull) { "patch 必须是 YAML 对象" }
        var patchIndex = lines.indexOfFirst { it.matches(Regex("patch:\\s*(#.*)?")) }
        if (patchIndex < 0) {
            require(patchNode == null) { "请将内联 patch 展开为标准块式 YAML 后再导入" }
            val endDocument = lines.indexOfFirst { it.trim() == "..." }.let { if (it < 0) lines.size else it }
            lines.add(endDocument, "patch:")
            patchIndex = endDocument
        }
        val patchEnd = (patchIndex + 1 until lines.size).firstOrNull { index ->
            val line = lines[index]
            line.isNotBlank() && !line.trimStart().startsWith('#') && line.firstOrNull()?.isWhitespace() == false
        } ?: lines.size
        val plus = (patchNode as? YamlMap)?.get<YamlNode>("translator/packs/+")
        val userNames = when (plus) {
            null, is YamlNull -> emptyList()
            is YamlList -> plus.items.map { (it as? YamlScalar)?.content ?: error("现有 packs 列表含非字符串元素") }
            else -> error("现有 translator/packs/+ 必须是列表")
        }
        val patchMap = patchNode as? YamlMap
        val direct = listOf<YamlNode?>(patchMap?.get("translator/packs"), (patchMap?.get("translator") as? YamlMap)?.get("packs"))
            .filterIsInstance<YamlList>().flatMap { it.items }.mapNotNull { (it as? YamlScalar)?.content }
        require((userNames + direct).none { it in ownedNames }) { "现有非托管补丁已引用同名词典；请先为该词典调整 ID" }
        if (names.isEmpty()) return lines.joinToString(newline).trimEnd() + newline
        val plusIndex = (patchIndex + 1 until patchEnd).firstOrNull { index ->
            val line = lines[index]
            line.takeWhile(Char::isWhitespace).length == 2 &&
                line.trim().substringBefore(':').trim().removeSurrounding("\"").removeSurrounding("'") == "translator/packs/+"
        }
        val managed = names.distinct().map { "    - ${JSONObject.quote(it)}" }
        if (plusIndex != null) {
            val end = (plusIndex + 1 until patchEnd).firstOrNull { index ->
                val line = lines[index]
                line.isNotBlank() && !line.trimStart().startsWith('#') &&
                    line.takeWhile(Char::isWhitespace).length <= 2 && !line.trimStart().startsWith('-')
            } ?: patchEnd
            val comments = lines.subList(plusIndex + 1, end).filter { it.trimStart().startsWith('#') }
            val replacement = listOf("  \"translator/packs/+\":") + comments +
                userNames.map { "    - ${JSONObject.quote(it)}" } + listOf("    $BEGIN") + managed + listOf("    $END")
            repeat(end - plusIndex) { lines.removeAt(plusIndex) }
            lines.addAll(plusIndex, replacement)
        } else {
            require(plus == null) { "无法安全定位现有 packs 补丁" }
            lines.addAll(patchEnd, listOf("  $BEGIN", "  \"translator/packs/+\":") + managed + listOf("  $END"))
        }
        val result = lines.joinToString(newline).trimEnd() + newline
        DictionaryPackFormat.yaml.parseToYamlNode(result)
        return result
    }
}
