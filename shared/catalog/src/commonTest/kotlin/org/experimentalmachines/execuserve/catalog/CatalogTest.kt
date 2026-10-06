package org.experimentalmachines.execuserve.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun vulkanExportsAreOfferedOnlyWhereTheRuntimeCanRunThem() {
        val gpuRepo = HfRepo(
            id = "experimentalmachines/Qwen3-0.6B-ExecuTorch",
            sha = "def456",
            siblings = listOf("tokenizer.json", "vulkan/config.json", "vulkan/Qwen3-0.6B-vulkan-8da4w-2k.pte", "xnnpack/config.json")
                .map(::HfSibling),
        )
        val gpuConfig = HfCatalog.parseConfig(
            """{"runtime":"executorch","runtime_version":"1.4.0","backend":"vulkan",
               "variants":[{"file":"Qwen3-0.6B-vulkan-8da4w-2k.pte","size_bytes":616404352,"context":2048}]}""",
        )
        // A CPU-only phone sees only the XNNPACK folder, and is never handed the GPU file.
        assertEquals(listOf("xnnpack/config.json"), HfCatalog.configPaths(gpuRepo))
        assertTrue(HfCatalog.variants(gpuRepo, "vulkan/config.json", gpuConfig).isEmpty())
        // A phone that can run Vulkan sees both, and the GPU variant says which it is.
        val both = setOf(HfCatalog.BACKEND, HfCatalog.VULKAN)
        assertEquals(listOf("vulkan/config.json", "xnnpack/config.json"), HfCatalog.configPaths(gpuRepo, both))
        val gpu = HfCatalog.variants(gpuRepo, "vulkan/config.json", gpuConfig, both).single()
        assertEquals("vulkan", gpu.backend)
        assertEquals("vulkan/Qwen3-0.6B-vulkan-8da4w-2k.pte", gpu.path)
        // Its install never collides with the CPU build of the same window.
        assertEquals("qwen3-0.6b-vulkan-8da4w-2k", gpu.installId)
        // Nor does a GPU file that shares its CPU twin's name exactly, while CPU ids stay as they were.
        val twin = gpu.copy(path = "vulkan/Qwen3-0.6B-8da4w-2k.pte")
        val cpu = twin.copy(path = "xnnpack/Qwen3-0.6B-8da4w-2k.pte", backend = HfCatalog.BACKEND)
        assertEquals("qwen3-0.6b-8da4w-2k", cpu.installId)
        assertTrue(twin.installId != cpu.installId)
        // The install records which it is, so the server need not read it off the name.
        assertEquals("vulkan", HfCatalog.plan(gpu, 1).manifest.backend)
        assertEquals("xnnpack", HfCatalog.plan(cpu, 1).manifest.backend)
    }

    @Test
    fun aCpuFileNamedForVulkanIsNotListed() {
        // It would take the GPU build's install id, so the two could overwrite each other.
        val repo = HfRepo(
            id = "experimentalmachines/Qwen3-0.6B-ExecuTorch",
            sha = "def456",
            siblings = listOf("tokenizer.json", "xnnpack/config.json", "xnnpack/Qwen3-0.6B-vulkan-8da4w-2k.pte", "xnnpack/Qwen3-0.6B-8da4w-2k.pte")
                .map(::HfSibling),
        )
        val config = HfCatalog.parseConfig(
            """{"runtime":"executorch","backend":"xnnpack","variants":[
               {"file":"Qwen3-0.6B-vulkan-8da4w-2k.pte","size_bytes":1,"context":2048},
               {"file":"Qwen3-0.6B-8da4w-2k.pte","size_bytes":1,"context":2048}]}""",
        )
        assertEquals(listOf("xnnpack/Qwen3-0.6B-8da4w-2k.pte"), HfCatalog.variants(repo, "xnnpack/config.json", config).map { it.path })
    }

    @Test
    fun npuExportsAreOfferedOnlyOnTheirOwnChip() {
        val npuRepo = HfRepo(
            id = "experimentalmachines/Qwen3-1.7B-ExecuTorch",
            sha = "fed789",
            siblings = listOf(
                "tokenizer.json",
                "xnnpack/config.json",
                "qnn/sm8850/config.json",
                "qnn/sm8850/Qwen3-1.7B-qnn-hybrid-4k.pte",
                "qnn/sm8750/config.json",
                "qnn/sm8750/Qwen3-1.7B-qnn-hybrid-4k.pte",
            ).map(::HfSibling),
        )
        fun npuConfig(target: String?) = HfCatalog.parseConfig(
            """{"runtime":"executorch","runtime_version":"1.5.1","backend":"qnn","target":${target?.let { "\"$it\"" } ?: "null"},
               "variants":[{"file":"Qwen3-1.7B-qnn-hybrid-4k.pte","size_bytes":1759848704,"sha256":"13db","context":4096,
               "quantization":"QNN HTP, ExecuTorch qwen3-1_7b recipe"}]}""",
        )
        val sm8850 = setOf(HfCatalog.BACKEND, chipFolder(HfCatalog.QNN, "SM8850"))
        assertEquals("qnn/sm8850", chipFolder(HfCatalog.QNN, "SM8850"))
        // This chip's folder is listed and the other chip's is not, whatever its config says.
        assertEquals(listOf("xnnpack/config.json", "qnn/sm8850/config.json"), HfCatalog.configPaths(npuRepo, sm8850))
        assertTrue(HfCatalog.variants(npuRepo, "qnn/sm8750/config.json", npuConfig("sm8750"), sm8850).isEmpty())
        val npu = HfCatalog.variants(npuRepo, "qnn/sm8850/config.json", npuConfig("sm8850"), sm8850).single()
        assertEquals("qnn", npu.backend)
        assertEquals("qnn/sm8850/Qwen3-1.7B-qnn-hybrid-4k.pte", npu.path)
        assertEquals("qwen3-1.7b-qnn-hybrid-4k", npu.installId)
        assertEquals("qnn", HfCatalog.plan(npu, 1).manifest.backend)
        // A config that names another chip, or none, is not trusted from inside this chip's folder.
        assertTrue(HfCatalog.variants(npuRepo, "qnn/sm8850/config.json", npuConfig("sm8750"), sm8850).isEmpty())
        assertTrue(HfCatalog.variants(npuRepo, "qnn/sm8850/config.json", npuConfig(null), sm8850).isEmpty())
        // A phone without this NPU (no chip folder in its set) is offered nothing from it, even
        // though "qnn" alone would match the config's backend.
        assertTrue(HfCatalog.variants(npuRepo, "qnn/sm8850/config.json", npuConfig("sm8850"), setOf(HfCatalog.BACKEND, HfCatalog.QNN)).isEmpty())
    }

    @Test
    fun aMediaTekExportIsInstalledWholeBesideItsCpuBuild() {
        val chunks = (1..4).map { "Qwen3-1.7B-neuropilot-a16w8-4k-chunk${it}of4.pte" }
        val embedding = "Qwen3-1.7B-neuropilot-embedding-fp32.bin"
        val repo = HfRepo(
            id = "experimentalmachines/Qwen3-1.7B-ExecuTorch",
            sha = "aaa111",
            siblings = (
                listOf("tokenizer.json", "xnnpack/config.json", "xnnpack/Qwen3-1.7B-8da4w-gptq-4k.pte", "mtk/mt6991/config.json") +
                    (chunks + embedding).map { "mtk/mt6991/$it" }
                ).map(::HfSibling),
        )
        // The shape execupack publishes: per-file digests, and the runner's options.
        val mtk = HfCatalog.parseConfig(
            """{"runtime":"executorch","runtime_version":"1.4.0","backend":"mtk","target":"mt6991","tokenizer":"tokenizer.json",
               "source_model":"Qwen/Qwen3-1.7B","variants":[{"files":${chunks.joinToString(",", "[", "]") { "\"$it\"" }},
               "embedding":"$embedding","size_bytes":3207914112,
               "sha256":{${(chunks + embedding).joinToString(",") { "\"$it\":\"h-$it\"" }}},
               "context":4096,"quantization":"NeuroPilot A16W8, 4 chunks","methods":{},
               "runner":{"cache_size":4096,"num_head":16,"num_layer":28}}]}""",
        )
        val cpu = HfCatalog.parseConfig(
            """{"runtime":"executorch","backend":"xnnpack","variants":[
               {"file":"Qwen3-1.7B-8da4w-gptq-4k.pte","size_bytes":1289092864,"sha256":"cpu-hash","context":4096}]}""",
        )
        val phone = setOf(HfCatalog.BACKEND, chipFolder(HfCatalog.NEUROPILOT, "MT6991"))
        val listed = HfCatalog.configPaths(repo, phone).flatMap { path ->
            HfCatalog.variants(repo, path, if (path.startsWith("mtk")) mtk else cpu, phone)
        }
        val npu = withCpuHalves(listed).single { it.backend == HfCatalog.NEUROPILOT }
        assertEquals("qwen3-1.7b-neuropilot-a16w8-4k", npu.installId)
        assertEquals("xnnpack/Qwen3-1.7B-8da4w-gptq-4k.pte", npu.npu?.cpuPath)
        val plan = HfCatalog.plan(npu, 1)
        // Every chunk and the embedding under their own names with their own digests, the CPU
        // build as model.pte, and the tokenizer.
        assertEquals(chunks + embedding + "model.pte" + "tokenizer.json", plan.files.map { it.name })
        assertEquals("h-${chunks[2]}", plan.files[2].sha256)
        assertEquals("cpu-hash", plan.files.single { it.name == "model.pte" }.sha256)
        assertEquals(chunks, plan.manifest.npu?.chunks)
        assertTrue(plan.manifest.npu!!.runner.contains("\"cache_size\":4096"))
        assertEquals("mtk", plan.manifest.backend)
        // Without a CPU build of the same window there is nothing to decode with: not offered.
        assertTrue(withCpuHalves(listed.filter { it.backend == HfCatalog.NEUROPILOT }).isEmpty())
        // A chunk missing from the repository withholds the whole export.
        val partial = repo.copy(siblings = repo.siblings.filterNot { it.rfilename.endsWith("chunk3of4.pte") })
        assertTrue(HfCatalog.variants(partial, "mtk/mt6991/config.json", mtk, phone).isEmpty())
    }

    @Test
    fun npuInstallIdsNameTheirBackendAndQnnBuildsGetAShortAlias() {
        val base = CatalogVariant("r/x", "abc", "qnn/sm8850/Qwen3-1.7B-hybrid-4k.pte", "tokenizer.json", 1, null, 4096, null, null, "qwen3", null)
        // A QNN file whose name does not say so still gets an id no CPU build can take.
        assertEquals("qwen3-1.7b-hybrid-4k-qnn", base.copy(backend = HfCatalog.QNN).installId)
        assertEquals("qwen3-1.7b-qnn-hybrid-4k", base.copy(path = "qnn/sm8850/Qwen3-1.7B-qnn-hybrid-4k.pte", backend = HfCatalog.QNN).installId)
        assertEquals("qwen3-1.7b-hybrid-4k", base.copy(backend = HfCatalog.BACKEND).installId)
        assertEquals(setOf("qwen3-1.7b-qnn"), ModelIds.aliasesFor("qwen3-1.7b-qnn-hybrid-4k"))
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
        val manifest = Manifest(id = "qwen3-1.7b-8da4w-gptq-2k", family = "qwen3", contextLength = 2048, backend = "xnnpack").encode()
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
                "/m/copied/Qwen3-1.7B-qnn-hybrid-4k.pte" to "x",
                "/m/copied/tokenizer.json" to "{}",
            ),
        )
        val scanner = ModelScanner(fs, "/m")
        val found = scanner.rescan().associateBy { it.id }
        assertEquals(setOf("qwen3-1.7b-8da4w-gptq-2k", "lfm2.5-1.2b-instruct-8da4w-gptq-2k", "pushed"), found.keys)
        assertEquals("qwen3", found.getValue("qwen3-1.7b-8da4w-gptq-2k").family)
        assertEquals("xnnpack", found.getValue("qwen3-1.7b-8da4w-gptq-2k").backend)
        // A file copied in by hand carries no record of its delegate.
        assertEquals(null, found.getValue("pushed").backend)
        assertEquals(2048, found.getValue("lfm2.5-1.2b-instruct-8da4w-gptq-2k").contextLength)
        assertEquals("smollm2", found.getValue("pushed").family)
        assertEquals(4096, found.getValue("pushed").contextLength)
        assertTrue(scanner.problems.keys.any { "downloading" in it })
        assertTrue(scanner.problems.keys.any { "orphan" in it })
        // An NPU build copied in by hand would open on the CPU runner: refused, saying how to install it.
        assertTrue(scanner.problems.getValue("/m/copied").contains("NPU build"))
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

    @Test
    fun runtimeWarningsFollowExecuTorchsCompatibilityPolicy() {
        // Same release, and the previous minor one: promised to load, no warning.
        assertFalse(runtimeMismatch("1.5.1", "1.5.1"))
        assertFalse(runtimeMismatch("1.5.0", "1.5.1"))
        assertFalse(runtimeMismatch("1.4.0", "1.5.1"))
        // Newer than the runtime, two minors older, or another major: nothing is promised.
        assertTrue(runtimeMismatch("1.6.0", "1.5.1"))
        assertTrue(runtimeMismatch("1.5.2", "1.5.1"))
        assertTrue(runtimeMismatch("1.3.1", "1.5.1"))
        assertTrue(runtimeMismatch("2.0.0", "1.5.1"))
        // Missing or unreadable versions say nothing either way.
        assertFalse(runtimeMismatch(null, "1.5.1"))
        assertFalse(runtimeMismatch("main", "1.5.1"))
    }
}
