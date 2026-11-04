package com.kingzcheung.xime.settings

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.*

/** 显式设置 XIME_DICTIONARY_LIVE_MANIFEST 才访问网络，普通离线单测跳过。 */
class DictionaryPagesLiveTest {
    @Test fun `本地云端构建经Android共用校验代码转换真实热词包`() {
        val buildRoot = System.getenv("XIME_DICTIONARY_BUILD_ROOT")
        assumeTrue("没有请求本地云端构建验收", !buildRoot.isNullOrBlank())
        val root = File(buildRoot!!).canonicalFile
        val catalog = DictionaryCatalog.parse(File(root, "manifest.json").readBytes(), "https://example.invalid/manifest.json")
        val entry = catalog.single { it.id == "hotwords" }
        val archive = File(root, URI(entry.url).path.removePrefix("/")).canonicalFile
        require(archive.toPath().startsWith(root.toPath()))
        val verified = DictionaryCatalog.verify(entry, archive.readBytes())
        val expected = mapOf("丞磊" to "cheng lei", "张凌赫" to "zhang ling he", "田曦薇" to "tian xi wei", "逐玉" to "zhu yu")
        assertEquals(expected, verified.entries.associate { it.word to it.code })
        File("build/dictionary-pack-test/local_${verified.dictName}.dict.yaml").also {
            it.parentFile.mkdirs()
            it.writeText(verified.source())
        }
        println("Local build verified: hotwords=${verified.entries.size}")
    }

    @Test fun `线上Pages目录经Android共用HTTP校验代码下载并转换真实词典`() {
        val url = System.getenv("XIME_DICTIONARY_LIVE_MANIFEST")
        assumeTrue("没有请求线上验收", !url.isNullOrBlank())
        val previousProxy = ProxySelector.getDefault()
        val proxyUrl = System.getenv("HTTPS_PROXY") ?: System.getenv("HTTP_PROXY")
        if (!proxyUrl.isNullOrBlank()) {
            val parsed = URI(proxyUrl)
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(parsed.host, if (parsed.port > 0) parsed.port else 80))
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> = listOf(proxy)
                override fun connectFailed(uri: URI, address: SocketAddress, error: java.io.IOException) = Unit
            })
        }
        try {
            val catalog = DictionaryCatalog.parse(DictionaryCloudUpdates.fetch(url!!, DictionaryCatalog.MAX_MANIFEST), url)
            val entry = catalog.single { it.id == "genshin" }
            val preview = DictionaryCatalog.verify(entry, DictionaryCloudUpdates.fetch(entry.url, DictionaryPackFormat.MAX_DOWNLOAD))
            assertTrue("正式原神包不应仍是旧的小型示例", preview.entries.size >= 1000)
            assertTrue(preview.entries.any { it.word == "芙宁娜" && it.code == "fu ning na" })
            assertTrue(preview.entries.any { it.word == "血红之证" && it.code == "xue hong zhi zheng" })
            File("build/dictionary-pack-test/cloud_${preview.dictName}.dict.yaml").also { it.parentFile.mkdirs(); it.writeText(preview.source()) }
            catalog.filter { it.id != "genshin" }.forEach { dictionary ->
                val verified = DictionaryCatalog.verify(dictionary, DictionaryCloudUpdates.fetch(dictionary.url, DictionaryPackFormat.MAX_DOWNLOAD))
                assertTrue(verified.entries.isNotEmpty())
                File("build/dictionary-pack-test/cloud_${verified.dictName}.dict.yaml").writeText(verified.source())
            }
            println("Live Pages verified: " + catalog.joinToString { "${it.id}=${it.entryCount}" })
        } finally { ProxySelector.setDefault(previousProxy) }
    }
}
