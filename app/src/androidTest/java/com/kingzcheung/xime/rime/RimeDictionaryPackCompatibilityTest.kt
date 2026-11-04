package com.kingzcheung.xime.rime

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.DictionaryPackFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RimeDictionaryPackCompatibilityTest {
    @Test
    fun frostDictionaryPacksCompileAndQueryInAllThreeSchemas() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rimeDir = File(context.filesDir, "rime")
        val engine = RimeEngine.getInstance()
        awaitIdleEngine(engine, context)
        assertTrue("当前没有未提交输入可被测试切换丢弃", engine.getInput().isEmpty())

        val testUserDir = File(context.cacheDir, "rime-pack-compatibility")
        testUserDir.deleteRecursively()
        check(testUserDir.mkdirs())
        copyFrostBuildCache(File(rimeDir, "build"), File(testUserDir, "build"))
        File(testUserDir, "default.custom.yaml").writeText(
            """patch:
  schema_list:
    - schema: rime_frost
    - schema: rime_frost_double_pinyin_flypy
    - schema: rime_frost_mixed_flypy
""",
            Charsets.UTF_8,
        )
        val preview = InstrumentationRegistry.getInstrumentation().context.assets.open("dictionary-packs/genshin.zip").use {
            DictionaryPackFormat.parse(it.readBytes(), "genshin.zip")
        }
        assertTrue("测试夹具词库解析失败", preview.canInstall)
        val packs = listOf(preview.dictName, "xime_genshin_pack_b")
        val schemaIds = listOf(
            "rime_frost",
            "rime_frost_double_pinyin_flypy",
            "rime_frost_mixed_flypy",
        )
        schemaIds.forEach { schemaId ->
            File(testUserDir, "$schemaId.custom.yaml").writeText(
                """patch:
  "translator/packs/+":
    - ${packs[0]}
    - ${packs[1]}
  "translator/enable_sentence": false
  "translator/enable_user_dict": false
""",
                Charsets.UTF_8,
            )
        }
        File(testUserDir, "${packs[0]}.dict.yaml").writeText(preview.source().trimEnd() + "\n芙宁娜\tfu ning na\t1000\n")
        writePack(testUserDir, packs[1], "珐露珊邀约任务", "fa lu shan yao yue ren wu")

        var swappedToTestData = false
        try {
            engine.destroy()
            swappedToTestData = true
            engine.initialize(testUserDir.absolutePath, rimeDir.absolutePath)

            val deployed = engine.deployIncremental() || engine.deploy()
            assertTrue("librime 未能编译 Frost 字典扩展包", deployed)
            packs.forEach { pack ->
                val table = File(testUserDir, "build/$pack.table.bin")
                assertTrue("librime 未生成 $pack.table.bin", table.isFile && table.length() > 0)
            }
            assertTrue("Rime session 未能载入三个 Frost 方案", engine.ensureSession())
            assertTrue("严格部署必须返回缺失方案的真实失败", !engine.deploySchema("xime_missing_pack_test", allowFullMaintenance = false))

            val queries = mapOf(
            "rime_frost" to listOf(
                Triple("chengehudongtianmoben", "尘歌壶洞天摹本", true),
                Triple("falushanyaoyuerenwu", "珐露珊邀约任务", false),
                Triple("funingna", "芙宁娜", true),
            ),
            "rime_frost_double_pinyin_flypy" to listOf(
                Triple("ifgehudstmmobf", "尘歌壶洞天摹本", true),
                Triple("faluujycytrfwu", "珐露珊邀约任务", false),
                Triple("funkna", "芙宁娜", true),
            ),
                "rime_frost_mixed_flypy" to listOf(
                Triple("chengehudongtianmoben", "尘歌壶洞天摹本", true),
                Triple("ifgehudstmmobf", "尘歌壶洞天摹本", true),
                Triple("falushanyaoyuerenwu", "珐露珊邀约任务", false),
                Triple("faluujycytrfwu", "珐露珊邀约任务", false),
                Triple("funingna", "芙宁娜", true),
                Triple("funkna", "芙宁娜", true),
                ),
            )
            for (enabled in listOf(true, false, true)) {
                (if (enabled) packs else packs.takeLast(1)).forEach { pack ->
                    listOf("table.bin", "reverse.bin").forEach { suffix -> File(testUserDir, "build/$pack.$suffix").delete() }
                }
                schemaIds.forEach { id ->
                    File(testUserDir, "$id.custom.yaml").writeText("patch:\n  \"translator/packs/+\":\n" +
                        (if (enabled) "    - ${packs[0]}\n" else "") + "    - ${packs[1]}\n" +
                        "  \"translator/enable_sentence\": false\n  \"translator/enable_user_dict\": false\n")
                    File(testUserDir, "build/$id.schema.yaml").delete()
                    assertTrue("方案 $id 重新部署失败", engine.deploySchema(id, allowFullMaintenance = false))
                }
                queries.forEach { (schemaId, entries) ->
                    assertTrue("不能切换到 $schemaId", engine.switchSchema(schemaId))
                    entries.forEach { (input, expected, managed) ->
                        engine.clearComposition()
                        assertTrue("$schemaId 无法输入 $input", engine.setInput(input))
                        assertTrue(
                            "$schemaId 输入 $input 候选状态不符：$expected；候选=${engine.getCandidates().toList()}",
                            engine.getCandidates().contains(expected) == (enabled || !managed),
                        )
                    }
                }
            }
        } finally {
            engine.clearComposition()
            if (swappedToTestData) {
                engine.destroy()
                engine.initialize(rimeDir.absolutePath, rimeDir.absolutePath)
                assertTrue("测试结束后未能恢复应用 Rime 数据目录", RimeConfigHelper.ensureDeployment(context))
            }
            testUserDir.deleteRecursively()
        }
    }

    private fun awaitIdleEngine(engine: RimeEngine, context: android.content.Context) {
        val deadline = SystemClock.elapsedRealtime() + 300_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (RimeEngine.isInitialized() &&
                !engine.isEngineBusy() &&
                SettingsPreferences.isDeploymentDone(context)
            ) return
            SystemClock.sleep(200L)
        }
        assertTrue("Xime 的 Rime 初始化/部署未在 5 分钟内结束", false)
    }

    private fun writePack(userDir: File, name: String, word: String, code: String) {
        val header = """---
name: $name
version: "1.0"
sort: by_weight
use_preset_vocabulary: false
...
"""
        val row = listOf(word, code, "1000").joinToString("\t")
        File(userDir, "$name.dict.yaml").writeText(header + row + "\n", Charsets.UTF_8)
    }

    private fun copyFrostBuildCache(source: File, target: File) {
        if (!source.isDirectory) return
        source.walkTopDown()
            .filter { file ->
                file.isFile && (
                    file.name == "last_build_time" ||
                        (file.name.startsWith("rime_frost") &&
                            (file.name.endsWith(".table.bin") ||
                                file.name.endsWith(".prism.bin") ||
                                file.name.endsWith(".schema.yaml")))
                    )
            }
            .forEach { file ->
                val destination = File(target, file.relativeTo(source).path)
                destination.parentFile?.mkdirs()
                file.copyTo(destination, overwrite = true)
            }
    }
}
