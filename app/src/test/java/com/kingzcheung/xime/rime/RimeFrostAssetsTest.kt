package com.kingzcheung.xime.rime

import com.kingzcheung.xime.settings.SchemaManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RimeFrostAssetsTest {
    private val bundle = sequenceOf(
        File("app/src/main/assets/rime-frost"),
        File("src/main/assets/rime-frost"),
    ).first { it.isDirectory }

    @Test
    fun updatePolicyPreservesUserEditsAndTracksBundledFiles() {
        assertEquals(FrostAssetSyncAction.COPY, frostAssetSyncAction(null, null, "new"))
        assertEquals(FrostAssetSyncAction.COPY, frostAssetSyncAction("old", "old", "new"))
        assertEquals(FrostAssetSyncAction.TRACK, frostAssetSyncAction(null, "new", "new"))
        assertEquals(FrostAssetSyncAction.PRESERVE, frostAssetSyncAction(null, "user", "new"))
        assertEquals(FrostAssetSyncAction.PRESERVE, frostAssetSyncAction("old", "user", "new"))
    }

    @Test
    fun freshInstallTemplateDoesNotOverrideTheFrostPreferenceDefault() {
        val template = sequenceOf(
            File("app/src/main/assets/default.custom.yaml"),
            File("src/main/assets/default.custom.yaml"),
        ).first { it.isFile }.readText()
        assertFalse(Regex("(?m)^\\s*schema_list:").containsMatchIn(template))
        val frostModes = listOf("rime_frost", "rime_frost_double_pinyin_flypy", "rime_frost_mixed_flypy")
        assertEquals(frostModes, SchemaManager.BUILTIN_SCHEMAS.take(frostModes.size))
        assertTrue(SchemaManager.isInitialSchemaList(frostModes))
    }

    @Test
    fun bundledDictionaryImportsResolveOffline() {
        listOf("rime_frost.dict.yaml", "rime_frost_melt_eng.dict.yaml").forEach { name ->
            val dictionary = File(bundle, name)
            for (reference in importTables(dictionary)) {
                assertTrue("Missing imported table $reference from $name", File(bundle, "$reference.dict.yaml").isFile)
            }
        }
    }

    @Test
    fun optionalCellPackageContainsEveryTableImportedByItsSchema() {
        val projectRoot = sequenceOf(File("app"), File("."))
            .first { File(it, "optional/rime-frost-cells").isDirectory }
        val optional = File(projectRoot, "optional/rime-frost-cells")
        val dictionary = File(optional, "rime_frost_cells.dict.yaml")
        val schema = File(optional, "rime_frost_cells.schema.yaml").readText()
        assertTrue(schema.contains("schema_id: rime_frost_cells"))
        assertTrue(schema.contains("dictionary: rime_frost_cells"))
        val imports = importTables(dictionary)
        assertEquals(23, imports.count { it.startsWith("rime_frost_data/cn_dicts_cell/") })
        imports.forEach { reference ->
            val root = if (reference.startsWith("rime_frost_data/cn_dicts_cell/")) optional else bundle
            assertTrue("Missing optional import $reference", File(root, "$reference.dict.yaml").isFile)
        }
    }

    @Test
    fun mixedSchemaUsesFlypyDerivationsInOneTranslator() {
        val flypy = File(bundle, "rime_frost_double_pinyin_flypy.schema.yaml").readLines()
        val mixed = File(bundle, "rime_frost_mixed_flypy.schema.yaml").readLines()
        val officialMappings = flypy.map { it.trim() }.filter { it.startsWith("- xform/") }
        val mixedMappings = mixed.map { it.trim() }.filter { it.startsWith("- derive/") }
        val flypyKeys = "ⓆⓌⓇⓉⓎⓊⒾⓄⓅⓈⒹⒻⒼⒽⒿⓀⓁⓏⓍⒸⓋⒷⓃⓂ"
        val officialFlypyMappings = officialMappings.filter { rule -> rule.any { it in flypyKeys } }
        assertTrue("Expected the official Flypy mapping rules", officialMappings.isNotEmpty())
        assertTrue(mixed.contains("    - script_translator"))
        assertTrue(officialFlypyMappings.isNotEmpty())
        assertTrue(mixedMappings.containsAll(officialFlypyMappings.map { it.replaceFirst("- xform/", "- derive/") }))
        assertTrue(mixedMappings.contains("- derive/^([aoe])(ng)?$/$1$1$2/"))
        assertTrue(mixed.any { it.trim().startsWith("- xlit/") })
        assertTrue(mixed.any { it.trimStart().startsWith("- uniquifier") })
    }

    private fun importTables(file: File): List<String> {
        var inImports = false
        return file.readLines().mapNotNull { line ->
            if (line.trim() == "import_tables:") {
                inImports = true
                return@mapNotNull null
            }
            if (inImports && line.trim() == "...") inImports = false
            if (inImports && line.trimStart().startsWith("- ")) {
                line.trim().removePrefix("- ").substringBefore('#').trim()
            } else null
        }
    }
}
