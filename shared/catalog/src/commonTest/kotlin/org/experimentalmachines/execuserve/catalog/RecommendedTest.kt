package org.experimentalmachines.execuserve.catalog

import kotlin.test.Test
import kotlin.test.assertEquals

class RecommendedTest {
    private fun variant(repo: String, path: String) = CatalogVariant(
        repo = repo, revision = "abc", path = path, tokenizerPath = "tokenizer.json", sizeBytes = 1, sha256 = null,
        context = null, quantization = null, fitsPhoneBudget = null, family = null, runtimeVersion = null,
    )

    @Test
    fun picksAreTheNamedCpuBuildsInListOrderAndMissingOnesAreLeftOut() {
        // Offered out of order, without the Qwen 4k pick, and with a Qwen build of another window.
        val offered = Recommended.picks.dropLast(1).reversed().map { variant(it.repo, it.path) } +
            variant("${HfCatalog.ORG}/Qwen3-1.7B-ExecuTorch", "xnnpack/Qwen3-1.7B-8da4w-gptq-32k.pte")
        val found = Recommended.from(offered)
        assertEquals(Recommended.picks.dropLast(1).map { it.path }, found.map { it.second.path })
        assertEquals(listOf("LFM2.5 1.2B Instruct", "LFM2.5 2.6B", "Llama 3.2 1B Instruct"), found.map { it.first.name })
        assertEquals(true, Recommended.picks.all { it.path.startsWith("${HfCatalog.BACKEND}/") })
    }
}
