package org.experimentalmachines.execuserve.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogTest {

    @Test
    fun familiesAreReadFromNamesLongestFirst() {
        assertEquals("qwen3.5", Families.detect("Qwen3.5-2B-8da4w"))
        assertEquals("qwen3", Families.detect("Qwen3-1.7B-8da4w-gptq-2k.pte"))
        assertEquals("lfm2.5", Families.detect("LFM2.5-1.2B-Instruct-heretic"))
        assertEquals("llama3.2", Families.detect("experimentalmachines/Llama-3.2-1B-Instruct-ExecuTorch"))
        assertNull(Families.detect("mystery.pte"))
    }

    @Test
    fun idsAndAliases() {
        val id = ModelIds.idFor("Qwen3-1.7B-8da4w-gptq-2k")
        assertEquals("qwen3-1.7b-8da4w-gptq-2k", id)
        assertEquals(setOf("qwen3-1.7b"), ModelIds.aliasesFor(id))
        assertEquals(setOf("lfm2.5-1.2b-instruct"), ModelIds.aliasesFor("lfm2.5-1.2b-instruct-8da4w-gptq-32k"))
        assertTrue(ModelIds.aliasesFor("custom-model").isEmpty())
    }

    private val repo = HfRepo(
        id = "experimentalmachines/Qwen3-1.7B-ExecuTorch",
        sha = "abc123",
        siblings = listOf("README.md", "tokenizer.json", "xnnpack/Qwen3-1.7B-8da4w-gptq-2k.pte", "xnnpack/config.json")
            .map(::HfSibling),
    )

    private val config = """
        {"runtime":"executorch","runtime_version":"1.4.0","backend":"xnnpack","target":null,
         "tokenizer":"tokenizer.json","source_model":"Qwen/Qwen3-1.7B",
         "variants":[
          {"file":"Qwen3-1.7B-8da4w-gptq-2k.pte","size_bytes":1284898560,"sha256":"6842","context":2048,
           "quantization":"8da4w-gptq-g32","fits_phone_budget":true,"methods":{"get_max_context_len":2048}},
          {"file":"Qwen3-1.7B-8da4w-gptq-4k.pte","size_bytes":1,"sha256":"x","context":4096}
         ]}
    """.trimIndent()

    @Test
    fun anExportConfigBecomesPinnedVariants() {
        assertEquals(listOf("xnnpack/config.json"), HfCatalog.configPaths(repo))
        val variants = HfCatalog.variants(repo, "xnnpack/config.json", HfCatalog.parseConfig(config))
        // The 4k file is named in config.json but not in the repository: not offered.
        val only = variants.single()
        assertEquals("xnnpack/Qwen3-1.7B-8da4w-gptq-2k.pte", only.path)
        assertEquals("tokenizer.json", only.tokenizerPath)
        assertEquals("qwen3", only.family)
        assertEquals(2048, only.context)
        val plan = HfCatalog.plan(only, nowMs = 1)
        assertEquals("qwen3-1.7b-8da4w-gptq-2k", plan.id)
        assertEquals(
            "https://huggingface.co/experimentalmachines/Qwen3-1.7B-ExecuTorch/resolve/abc123/xnnpack/Qwen3-1.7B-8da4w-gptq-2k.pte",
            plan.files[0].url,
        )
        assertEquals("6842", plan.files[0].sha256)
        assertEquals("abc123", plan.manifest.revision)
    }

    @Test
    fun otherBackendsAreNotOffered() {
        val mtk = HfCatalog.parseConfig("""{"runtime":"executorch","backend":"neuropilot","variants":[]}""")
        assertTrue(HfCatalog.variants(repo, "xnnpack/config.json", mtk).isEmpty())
    }

    private class MemoryFs(val files: Map<String, String>) : FileSystemView {
        private val dirs = files.keys.flatMap { path -> path.split('/').indices.drop(1).map { path.split('/').take(it).joinToString("/") } }.toSet()
        override fun list(directory: String) = (files.keys + dirs)
            .filter { it.startsWith("$directory/") && '/' !in it.removePrefix("$directory/") }
            .map { it.removePrefix("$directory/") }
        override fun isDirectory(path: String) = path in dirs
        override fun isFile(path: String) = path in files
        override fun size(path: String) = files[path]?.length?.toLong() ?: 0
        override fun modifiedMs(path: String) = 7L
        override fun readText(path: String) = files.getValue(path)
    }

    @Test
    fun theScannerFindsEveryLayoutAndSkipsHalfInstalledOnes() {
        val manifest = Manifest(id = "qwen3-1.7b-8da4w-gptq-2k", family = "qwen3", contextLength = 2048).encode()
        val fs = MemoryFs(
            mapOf(
                "/m/qwen3-1.7b-8da4w-gptq-2k/model.pte" to "x",
                "/m/qwen3-1.7b-8da4w-gptq-2k/tokenizer.json" to "{}",
                "/m/qwen3-1.7b-8da4w-gptq-2k/execuserve.json" to manifest,
                "/m/LFM2.5-1.2B-Instruct-8da4w-gptq-2k.pte" to "x",
                "/m/LFM2.5-1.2B-Instruct-8da4w-gptq-2k.tokenizer.json" to "{}",
                "/m/pushed/SmolLM2-135M-Instruct-8da4w-4k.pte" to "x",
                "/m/pushed/tokenizer.json" to "{}",
                "/m/downloading/execuserve.json" to Manifest(id = "half").encode(),
                "/m/downloading/model.pte.part" to "x",
                "/m/orphan.pte" to "x",
            ),
        )
        val scanner = ModelScanner(fs, "/m")
        val found = scanner.rescan().associateBy { it.id }
        assertEquals(setOf("qwen3-1.7b-8da4w-gptq-2k", "lfm2.5-1.2b-instruct-8da4w-gptq-2k", "pushed"), found.keys)
        assertEquals("qwen3", found.getValue("qwen3-1.7b-8da4w-gptq-2k").family)
        assertEquals(2048, found.getValue("lfm2.5-1.2b-instruct-8da4w-gptq-2k").contextLength)
        assertEquals("smollm2", found.getValue("pushed").family)
        assertEquals(4096, found.getValue("pushed").contextLength)
        assertTrue(scanner.problems.keys.any { "downloading" in it })
        assertTrue(scanner.problems.keys.any { "orphan" in it })
        assertEquals("qwen3-1.7b-8da4w-gptq-2k", scanner.resolve("qwen3-1.7b")?.id)
    }
}

class LabsTest {
    @kotlin.test.Test
    fun theLabIsTheSourceModelsOwner() {
        kotlin.test.assertEquals("Qwen", Labs.of("Qwen/Qwen3-1.7B", "qwen3"))
        kotlin.test.assertEquals("meta-llama", Labs.of("meta-llama/Llama-3.2-1B-Instruct", "llama3.2"))
    }

    @kotlin.test.Test
    fun ourOwnDerivativesAreCreditedToTheirFamilysLab() {
        kotlin.test.assertEquals("LiquidAI", Labs.of("experimentalmachines/LFM2.5-1.2B-Instruct-heretic", "lfm2.5"))
        kotlin.test.assertEquals("LiquidAI", Labs.of(null, "lfm2.5"))
        kotlin.test.assertEquals(null, Labs.of(null, null))
        kotlin.test.assertEquals("Liquid AI", Labs.displayName("LiquidAI"))
    }
}

class HubUrlTest {
    @Test
    fun repositoryIdsAreCheckedBeforeTheyBecomeUrls() {
        assertEquals(
            "https://huggingface.co/api/models/experimentalmachines/Qwen3-1.7B-ExecuTorch",
            HfCatalog.modelUrl("experimentalmachines/Qwen3-1.7B-ExecuTorch"),
        )
        // What a shell-supplied `pull` argument must never become: another path on the Hub,
        // a second query, or a different host.
        for (bad in listOf("../../api/whoami", "org/../x", "org/name?x=1", "org/name/extra", "@evil.example/x", "org", "")) {
            kotlin.test.assertFalse(HfCatalog.isRepoId(bad), bad)
            kotlin.test.assertFailsWith<IllegalArgumentException> { HfCatalog.modelUrl(bad) }
        }
    }

    @Test
    fun avatarsAreAskedOnlyOfTheHub() {
        assertEquals("https://huggingface.co/api/organizations/LiquidAI/avatar", Labs.avatarApi("LiquidAI"))
        kotlin.test.assertFailsWith<IllegalArgumentException> { Labs.avatarApi("x/../../api") }
    }

    @Test
    fun uncensoredDerivativesAreKnownByName() {
        kotlin.test.assertTrue(Uncensored.isUncensored("experimentalmachines/LFM2.5-1.2B-Instruct-heretic-ExecuTorch"))
        kotlin.test.assertTrue(Uncensored.isUncensored("someone/Llama-3.2-3B-Instruct-abliterated"))
        kotlin.test.assertTrue(Uncensored.isUncensored("someone/Qwen3-4B-Uncensored"))
        kotlin.test.assertFalse(Uncensored.isUncensored("experimentalmachines/LFM2.5-1.2B-Instruct-ExecuTorch"))
    }
}
