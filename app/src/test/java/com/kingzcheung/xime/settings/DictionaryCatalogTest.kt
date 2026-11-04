package com.kingzcheung.xime.settings

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class DictionaryCatalogTest {
    @Test fun catalogStateRequiresCommittedCompilation() {
        val entry = DictionaryCatalog.parse(manifest(), manifestUrl).single()
        val pack = InstalledDictionaryPack(entry.id, entry.name, entry.version, true, "a".repeat(64), entry.entryCount,
            sourceUrl = manifestUrl, packageHash = entry.sha256)
        fun snapshot(installed: List<InstalledDictionaryPack>, pending: List<InstalledDictionaryPack>? = null) =
            DictionaryPackSnapshot(installed, pending, emptyList(), false)

        assertEquals(CatalogInstallationState.AVAILABLE, snapshot(emptyList()).catalogState(entry))
        assertEquals(CatalogInstallationState.WAITING_DEPLOYMENT, snapshot(emptyList(), listOf(pack)).catalogState(entry))
        assertEquals(CatalogInstallationState.ENABLED, snapshot(listOf(pack)).catalogState(entry))
        assertEquals(CatalogInstallationState.DISABLED, snapshot(listOf(pack.copy(enabled = false))).catalogState(entry))
        assertEquals(CatalogInstallationState.UPDATE_AVAILABLE, snapshot(listOf(pack.copy(version = "old"))).catalogState(entry))
        assertEquals(CatalogInstallationState.WAITING_DEPLOYMENT, snapshot(listOf(pack), emptyList()).catalogState(entry))
        assertEquals(CatalogInstallationState.ENABLED, snapshot(listOf(pack), listOf(pack, pack.copy(id = "other"))).catalogState(entry))
    }

    @Test fun localImportNeedsAnExplicitCloudInstallationBeforeAutomaticUpdates() {
        val entry = DictionaryCatalog.parse(manifest(), manifestUrl).single()
        val local = InstalledDictionaryPack(entry.id, entry.name, entry.version, true, "a".repeat(64), entry.entryCount,
            packageHash = entry.sha256)
        val snapshot = DictionaryPackSnapshot(listOf(local), null, emptyList(), false)
        assertEquals(CatalogInstallationState.UPDATE_AVAILABLE, snapshot.catalogState(entry))
        assertFalse(snapshot.hasPendingChange(entry.id))
        assertTrue(snapshot.copy(pending = listOf(local.copy(enabled = false))).hasPendingChange(entry.id))
    }

    private val manifestUrl = "https://dictionaries.example/manifest.json"
    private fun manifest(): ByteArray = javaClass.classLoader!!.getResourceAsStream("dictionary-catalog.json")!!.use { it.readBytes() }
    private fun sample(): ByteArray = javaClass.classLoader!!.getResourceAsStream("dictionary-packs/genshin.zip")!!.use { it.readBytes() }
    private fun fails(block: () -> Unit) {
        try { block(); fail("应拒绝不安全目录或下载") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
    }
    @Test fun `云端目录解析与实际 ZIP 完整校验一致`() {
        val entry = DictionaryCatalog.parse(manifest(), manifestUrl).single()
        assertTrue(entry.url.startsWith("https://dictionaries.example/dictionaries/"))
        val preview = DictionaryCatalog.verify(entry, sample())
        assertEquals(16, preview.entries.size)
        assertEquals("genshin", preview.id)
        assertEquals(entry.sha256, preview.packageHash)
    }
    @Test fun `下载被篡改或目录与包不一致不能安装`() {
        val entry = DictionaryCatalog.parse(manifest(), manifestUrl).single()
        fails { DictionaryCatalog.verify(entry, sample().also { it[10] = (it[10].toInt() xor 1).toByte() }) }
        fails { DictionaryCatalog.verify(entry.copy(id = "other"), sample()) }
        fails { DictionaryCatalog.verify(entry.copy(entryCount = 999), sample()) }
    }
    @Test fun `拒绝非HTTPS地址凭据和私有网络`() {
        listOf("http://example.com/a.zip", "https://user:secret@example.com/manifest.json", "https://127.0.0.1/a", "https://10.0.0.1/a", "https://[fd00::1]/a").forEach { url ->
            fails { DictionaryCatalog.httpsUrl(url) }
        }
        assertFalse(DictionaryCatalog.publicAddress(InetAddress.getByName("100.64.0.1")))
        assertTrue(DictionaryCatalog.publicAddress(InetAddress.getByName("8.8.8.8")))
    }
    @Test fun `目录重复ID超限包及降级链接会被拒绝`() {
        fun change(block: (JSONObject) -> Unit): ByteArray = JSONObject(String(manifest())).also(block).toString().toByteArray()
        fails { DictionaryCatalog.parse(change { it.getJSONArray("dictionaries").put(it.getJSONArray("dictionaries").getJSONObject(0)) }, manifestUrl) }
        fails { DictionaryCatalog.parse(change { it.getJSONArray("dictionaries").getJSONObject(0).put("size", 99_000_000) }, manifestUrl) }
        fails { DictionaryCatalog.parse(change { it.getJSONArray("dictionaries").getJSONObject(0).put("url", "http://example.com/a.zip") }, manifestUrl) }
    }
    @Test fun `版本不变跳过更新同版本更换包则拒绝`() {
        val entry = DictionaryCatalog.parse(manifest(), manifestUrl).single()
        val current = InstalledDictionaryPack(entry.id, entry.name, entry.version, true, "a".repeat(64), entry.entryCount, packageHash = entry.sha256)
        assertFalse(DictionaryCatalog.needsUpdate(entry, current))
        assertTrue(DictionaryCatalog.needsUpdate(entry.copy(version = "2.0.0+new"), current))
        fails { DictionaryCatalog.needsUpdate(entry.copy(sha256 = "b".repeat(64)), current) }
    }
}
