package com.kingzcheung.xime.settings

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DictionaryPackTest {
    @Test fun reopeningStorePreservesInstalledSourceAndRecoversFailedUpdate() = withStore { store, root, rime ->
        val first = preview()
        val subscription = "https://dictionaries.example/manifest.json"
        val table = java.io.File(rime, "build/${first.dictName}.table.bin").also { it.parentFile.mkdirs() }
        store.stage(first, subscription)
        store.apply({ table.writeText("Rime::Table/installed"); true }, { _, _ -> true })
        val installed = store.installed().single()

        val reopened = DictionaryPackStore(root, rime)
        assertEquals(installed, reopened.installed().single())
        assertNull(reopened.pending())
        reopened.stage(preview("原神\tyuan shen\t850\n"))
        assertEquals(subscription, reopened.pending()!!.single().sourceUrl)
        assertEquals(installed, reopened.installed().single())

        fails { reopened.apply({ false }, { _, _ -> true }) }
        val recovered = DictionaryPackStore(root, rime)
        assertEquals(installed, recovered.installed().single())
        assertNull(recovered.pending())
        assertEquals("Rime::Table/installed", table.readText())
    }

    private fun preview(text: String = "原神\tyuan shen\t100\n") = DictionaryPackFormat.parse(text.toByteArray(), "原神.tsv")
    private fun fails(block: () -> Unit) {
        try { block(); fail("应拒绝不安全输入") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
    }
    private fun zip(name: String, content: String): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { it.putNextEntry(ZipEntry(name)); it.write(content.toByteArray()); it.closeEntry() }
    }.toByteArray()

    @Test fun `保留拼音空格去重并规范化带数字声调`() {
        val result = preview("原神\tyuan2 shen2\t100\n原神\tyuan shen\t200\n")
        assertTrue(result.canInstall)
        assertEquals(1, result.duplicates)
        assertEquals(DictEntry("原神", "yuan shen", 200), result.entries.single())
        assertTrue(result.source().contains("原神\tyuan shen\t200"))
    }
    @Test fun `非法行必须预览报告且禁止安装`() {
        val result = preview("原神\tyuan shen\n错误\tcuo\n")
        assertFalse(result.canInstall)
        assertEquals(1, result.entries.size)
        assertEquals(1, result.errors.size)
    }
    @Test fun `拒绝错误编码和源词典外部依赖`() {
        fails { DictionaryPackFormat.parse(byteArrayOf(0xff.toByte(), 0x81.toByte()), "a.txt") }
        fails { DictionaryPackFormat.parse("---\nname: bad\nimport_tables: [../../other]\n...\n原神\tyuan shen\n".toByteArray(), "bad.dict.yaml") }
        fails { DictionaryPackFormat.parse(("---\nname: " + "[".repeat(100) + "0" + "]".repeat(100) + "\n...\n原神\tyuan shen\n").toByteArray(), "deep.dict.yaml") }
        val deepJson = runCatching { DictionaryPackFormat.jsonObject("{\"name\":" + "[".repeat(100) + "0" + "]".repeat(100) + "}") }.exceptionOrNull()
        assertTrue(deepJson?.message.orEmpty().contains("嵌套"))
        assertEquals("[not nesting]", DictionaryPackFormat.jsonObject("{\"name\":\"[not nesting]\"}").getString("name"))
    }
    @Test fun `ZIP 拒绝路径穿越代码与压缩炸弹`() {
        fails { DictionaryPackFormat.parse(zip("../evil.dict.yaml", "x"), "a.zip") }
        fails { DictionaryPackFormat.parse(zip("evil.lua", "print(1)"), "a.zip") }
        val hiddenCode = runCatching { DictionaryPackFormat.parse(zip("LICENSES/evil.lua", "print(1)"), "a.zip") }.exceptionOrNull()
        assertTrue(hiddenCode?.message.orEmpty().contains("不接受代码文件"))
        fails { DictionaryPackFormat.parse(zip("a.txt", "字".repeat(1_000_000)), "a.zip") }
        val excessiveLines = runCatching { preview("\n".repeat(202_000)) }.exceptionOrNull()
        assertTrue(excessiveLines?.message.orEmpty().contains("行数"))
    }
    @Test fun `UTF16 BOM 可识别并显示编码`() {
        val data = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "原神\tyuan shen\n".toByteArray(Charsets.UTF_16LE)
        val result = DictionaryPackFormat.parse(data, "a.txt")
        assertTrue(result.canInstall)
        assertEquals("UTF-16LE", result.encoding)
    }
    @Test fun `内置原神 ZIP 与云端相同并保留数据许可`() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("dictionary-packs/genshin.zip")!!.use { it.readBytes() }
        val result = DictionaryPackFormat.parse(bytes, "genshin.zip")
        assertTrue(result.canInstall)
        assertEquals("genshin", result.id)
        assertEquals(16, result.entries.size)
        assertEquals("CC0-1.0", result.license)
        assertTrue(result.licenseNotices.isNotEmpty())
        java.io.File("build/dictionary-pack-test/${result.dictName}.dict.yaml").also {
            it.parentFile.mkdirs()
            it.writeText(result.source())
        }
    }
    @Test fun `追加补丁保留用户 packs 和其他设置且停用可逆`() {
        val original = "patch:\n  \"translator/packs\": [custom_existing]\n  \"menu/page_size\": 9\n"
        val updated = ManagedDictionaryPackPatch.merge(original, listOf("xime_pack_genshin", "xime_pack_other"))
        assertTrue(updated.contains("[custom_existing]"))
        assertTrue(updated.contains("\"menu/page_size\": 9"))
        assertEquals(updated, ManagedDictionaryPackPatch.merge(updated, listOf("xime_pack_genshin", "xime_pack_other")))
        assertEquals(original, ManagedDictionaryPackPatch.merge(updated, emptyList()))
    }
    @Test fun `现有追加列表与托管列表共存并保留合法非托管词典`() {
        val original = "patch:\n  'translator/packs/+': [mine, another]\n"
        val updated = ManagedDictionaryPackPatch.merge(original, listOf("xime_pack_genshin"))
        val disabled = ManagedDictionaryPackPatch.merge(updated, emptyList())
        assertTrue(disabled.contains("\"mine\""))
        assertTrue(disabled.contains("\"another\""))
        assertFalse(disabled.contains("xime_pack_genshin"))
        DictionaryPackFormat.yaml.parseToYamlNode(disabled)
        fails { ManagedDictionaryPackPatch.merge("patch:\n  # xime-dictionaries:begin\n", listOf("xime_pack_genshin")) }
    }

    @Test fun `部署失败恢复配置产物与安装状态不修改UserDB`() = withStore { store, root, rime ->
        val original = "patch:\n  \"menu/page_size\": 9\n"
        java.io.File(rime, "rime_frost.custom.yaml").writeText(original)
        java.io.File(rime, "rime_frost.userdb").mkdirs()
        java.io.File(rime, "rime_frost.userdb/sentinel").writeText("personal-data")
        val compiledSchema = java.io.File(rime, "build/rime_frost.schema.yaml").also { it.parentFile.mkdirs(); it.writeText("old compiled schema") }
        val pack = preview()
        store.stage(pack)
        fails { store.apply({ assertFalse(compiledSchema.exists()); false }, { _, _ -> true }) }
        assertEquals("old compiled schema", compiledSchema.readText())
        assertEquals(original, java.io.File(rime, "rime_frost.custom.yaml").readText())
        assertFalse(java.io.File(rime, "${pack.dictName}.dict.yaml").exists())
        assertTrue(store.installed().isEmpty())
        assertEquals("personal-data", java.io.File(rime, "rime_frost.userdb/sentinel").readText())
        assertFalse(java.io.File(root, "transaction/journal.json").exists())
    }

    @Test fun `安装更新保留上一版并能回滚卸载`() = withStore { store, _, rime ->
        val first = preview()
        fun deploy(): Boolean {
            java.io.File(rime, "build").mkdirs()
            java.io.File(rime, "build/${first.dictName}.table.bin").writeText("Rime::Table/test-data")
            return true
        }
        store.stage(first)
        assertTrue(store.apply(::deploy, { _, _ -> true }))
        assertEquals(first.version, store.installed().single().version)
        val second = preview("原神\tyuan shen\t500\n")
        store.stage(second)
        assertTrue(store.apply(::deploy, { _, _ -> true }))
        store.rollback()
        assertTrue(store.apply(::deploy, { _, _ -> true }))
        assertEquals(first.version, store.installed().single().version)
        store.uninstall(first.id)
        assertTrue(store.apply(::deploy, { _, _ -> true }))
        assertTrue(store.installed().isEmpty())
        assertFalse(java.io.File(rime, "${first.dictName}.dict.yaml").exists())
    }

    @Test fun `编译返回成功但未生成新pack时拒绝旧表并回滚`() = withStore { store, _, rime ->
        val pack = preview()
        store.stage(pack)
        val table = java.io.File(rime, "build/${pack.dictName}.table.bin").also { it.parentFile.mkdirs() }
        store.apply({ table.writeText("Rime::Table/old-version"); true }, { _, _ -> true })
        store.stage(preview("原神\tyuan shen\t500\n"))
        fails { store.apply({ assertFalse(table.exists()); true }, { _, _ -> true }) }
        assertEquals("Rime::Table/old-version", table.readText())
        assertEquals(pack.version, store.installed().single().version)
    }

    @Test fun `模拟进程中断后恢复事务且不采纳半成品`() = withStore { store, _, rime ->
        val pack = preview()
        store.stage(pack)
        try { store.apply({ throw AssertionError("simulated process termination") }, { _, _ -> true }) }
        catch (_: AssertionError) { }
        assertTrue(java.io.File(rime, "${pack.dictName}.dict.yaml").exists())
        store.recover()
        assertFalse(java.io.File(rime, "${pack.dictName}.dict.yaml").exists())
        assertTrue(store.installed().isEmpty())
    }

    @Test fun `拒绝不兼容主词典音节和覆盖外部修改`() = withStore { store, _, rime ->
        fails { store.stage(preview("原神\tnotreal shen\n")) }
        val pack = preview()
        java.io.File(rime, "${pack.dictName}.dict.yaml").writeText("external-user-dictionary")
        store.stage(pack)
        fails { store.apply({ true }, { _, _ -> true }) }
        assertEquals("external-user-dictionary", java.io.File(rime, "${pack.dictName}.dict.yaml").readText())
    }

    private fun withStore(block: (DictionaryPackStore, java.io.File, java.io.File) -> Unit) {
        val temp = Files.createTempDirectory("xime-pack-test").toFile()
        try {
            val rime = java.io.File(temp, "rime").also { it.mkdirs() }
            java.io.File(rime, "rime_frost.schema.yaml").writeText("schema:\n  schema_id: rime_frost\ntranslator:\n  dictionary: rime_frost\n")
            java.io.File(rime, "rime_frost.dict.yaml").writeText("---\nname: rime_frost\n...\n原神\tyuan shen\n")
            val root = java.io.File(temp, "packs")
            block(DictionaryPackStore(root, rime), root, rime)
        } finally { temp.deleteRecursively() }
    }
}
