package com.kingzcheung.xime.settings

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DictionaryPackBackupTest {
    private fun archive(entries: Map<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> entries.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } }
    }.toByteArray()
    @Test fun `专题词库元数据与订阅设置随完整备份恢复且不恢复事务`() {
        val root = Files.createTempDirectory("pack-backup").toFile()
        try {
            val hash = "a".repeat(64)
            val entries = mapOf("_xime_backup/dictionary-packs/installed.json" to "{}",
                "_xime_backup/dictionary-packs/sources/$hash.dict.yaml" to "source",
                "_xime_backup/dictionary-packs/notices/$hash.json" to "{}",
                "_xime_backup/dictionary_updates.json" to "{}")
            var restoredPrefs = ""
            assertTrue(BackupManager.restoreArchive(root, { name, _ -> restoredPrefs = name }, archive(entries)).isSuccess)
            assertEquals("source", File(root, "dictionary-packs/sources/$hash.dict.yaml").readText())
            assertEquals("xime_dictionary_updates", restoredPrefs)
            assertTrue(BackupManager.restoreArchive(root, { _, _ -> }, archive(mapOf("_xime_backup/dictionary-packs/transaction/journal.json" to "{}"))).isFailure)
            assertTrue(BackupManager.restoreArchive(root, { _, _ -> }, archive(mapOf("_xime_backup/dictionary-packs/../evil" to "x"))).isFailure)
        } finally { root.deleteRecursively() }
    }
}
