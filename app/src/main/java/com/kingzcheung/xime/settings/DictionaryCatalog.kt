package com.kingzcheung.xime.settings

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.net.InetAddress

data class CloudDictionary(
    val id: String, val name: String, val version: String, val url: String,
    val sha256: String, val size: Int, val entryCount: Int, val license: String,
    val manifestUrl: String, val sources: String,
)

internal object DictionaryCatalog {
    const val MAX_MANIFEST = 1024 * 1024

    fun httpsUrl(value: String): URI {
        val uri = runCatching { URI(value.trim()) }.getOrElse { error("URL 格式不正确") }
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.fragment == null) { "词库地址必须为不含账户信息的 HTTPS URL" }
        require(value.length <= 2048) { "URL 过长" }
        if (uri.host.matches(Regex("[0-9.]+")) || ':' in uri.host) require(publicAddress(InetAddress.getByName(uri.host))) { "词库 URL 不能指向本机或私有网络" }
        return uri
    }

    fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        if (bytes.size == 16) return (bytes[0].toInt() and 0xfe) != 0xfc
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first in 1..223 && !(first == 100 && second in 64..127)
    }

    fun needsUpdate(entry: CloudDictionary, current: InstalledDictionaryPack): Boolean {
        if (current.version != entry.version) return true
        require(current.packageHash.isEmpty() || current.packageHash == entry.sha256) { "同一版本的包校验值发生变化，本次更新已停止" }
        return current.packageHash.isEmpty()
    }

    fun parse(bytes: ByteArray, manifestUrl: String): List<CloudDictionary> {
        require(bytes.size <= MAX_MANIFEST) { "Manifest 超过 1 MiB" }
        val base = httpsUrl(manifestUrl)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        val root = DictionaryPackFormat.jsonObject(text)
        require(root.getInt("schema_version") == 1) { "不支持此 manifest 版本" }
        val array = root.getJSONArray("dictionaries")
        require(array.length() <= 64) { "云端目录过大" }
        val ids = hashSetOf<String>()
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            require(id.matches(Regex("[a-z][a-z0-9_-]{0,63}")) && ids.add(id)) { "词库 ID 不合法或重复" }
            require(item.getString("format") == "rime-dict-source") { "仅接受 Rime 源词典包" }
            val version = item.getString("version")
            require(version.matches(Regex("[A-Za-z0-9.+_-]{1,96}"))) { "版本格式错误" }
            val hash = item.getString("sha256").lowercase()
            require(hash.matches(Regex("[a-f0-9]{64}"))) { "缺少合法 SHA-256 校验值" }
            val size = item.getLong("size")
            require(size in 1..DictionaryPackFormat.MAX_DOWNLOAD.toLong()) { "云端词库下载大小超过限制" }
            val expanded = item.getLong("uncompressed_size")
            require(expanded in 1..DictionaryPackFormat.MAX_EXPANDED.toLong()) { "云端词库解压大小超过限制" }
            val count = item.getInt("entry_count")
            require(count in 1..100_000) { "云端词条数超过限制" }
            val license = item.getString("license")
            require(license.isNotBlank() && license.length <= 128) { "缺少词库许可声明" }
            val sources = item.getJSONArray("sources")
            require(sources.length() in 1..32) { "来源清单格式错误" }
            val notices = (0 until sources.length()).joinToString("\n") { i ->
                val source = sources.getJSONObject(i)
                "${source.getString("name")} · ${source.getString("license")}\n${source.getString("attribution")}"
            }
            val url = httpsUrl(base.resolve(item.getString("url")).toString()).toString()
            CloudDictionary(id, item.getString("name").take(100), version, url, hash, size.toInt(), count, license, manifestUrl, notices.take(8192))
        }
    }

    fun verify(entry: CloudDictionary, bytes: ByteArray): DictionaryPackPreview {
        require(bytes.size == entry.size && DictionaryPackFormat.sha256(bytes) == entry.sha256) { "词库大小或 SHA-256 不匹配，已拒绝安装" }
        val preview = DictionaryPackFormat.parse(bytes, "${entry.id}.zip")
        require(preview.canInstall && preview.id == entry.id && preview.version == entry.version &&
            preview.entries.size == entry.entryCount && preview.license == entry.license) { "词库包与目录声明不一致，已拒绝安装" }
        return preview
    }
}
