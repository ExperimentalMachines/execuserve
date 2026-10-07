package org.experimentalmachines.execuserve.catalog

/**
 * The builds to start with, first on the Library before the whole catalog: each a CPU
 * (XNNPACK) build, which runs on any phone, at the window that suits the model.
 */
object Recommended {
    /** Why each one is on the list, for the line under its name. */
    enum class Reason { QUICK, STRONGER, COMPACT, THINKS }

    data class Pick(val repo: String, val path: String, val name: String, val reason: Reason)

    val picks = listOf(
        Pick("${HfCatalog.ORG}/LFM2.5-1.2B-Instruct-ExecuTorch", "xnnpack/LFM2.5-1.2B-Instruct-8da4w-gptq-32k.pte", "LFM2.5 1.2B Instruct", Reason.QUICK),
        Pick("${HfCatalog.ORG}/LFM2.5-2.6B-ExecuTorch", "xnnpack/LFM2.5-2.6B-8da4w-gptq-32k.pte", "LFM2.5 2.6B", Reason.STRONGER),
        Pick("${HfCatalog.ORG}/Llama-3.2-1B-Instruct-ExecuTorch", "xnnpack/Llama-3.2-1B-Instruct-8da4w-gptq-32k.pte", "Llama 3.2 1B Instruct", Reason.COMPACT),
        Pick("${HfCatalog.ORG}/Qwen3-1.7B-ExecuTorch", "xnnpack/Qwen3-1.7B-8da4w-gptq-4k.pte", "Qwen3 1.7B", Reason.THINKS),
    )

    /** The picks the loaded catalog offers, in list order, each with its variant. */
    fun from(variants: List<CatalogVariant>): List<Pair<Pick, CatalogVariant>> =
        picks.mapNotNull { pick -> variants.firstOrNull { it.repo == pick.repo && it.path == pick.path }?.let { pick to it } }
}
