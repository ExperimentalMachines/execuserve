package org.experimentalmachines.execuserve.catalog

/**
 * The chat-template family a file or repository name belongs to, spelled the way
 * `PromptTemplates.forModel` reads it back. Longer names first: `qwen3.5` contains `qwen3`.
 */
object Families {
    private val ORDER = listOf(
        "qwen35" to "qwen3.5",
        "qwen3" to "qwen3",
        "qwen25" to "qwen2.5",
        "smollm2" to "smollm2",
        "smollm3" to "smollm3",
        "llama32" to "llama3.2",
        "phi4mini" to "phi4-mini",
        "gemma3" to "gemma3",
        "lfm25" to "lfm2.5",
    )

    fun detect(name: String): String? {
        val normalized = name.lowercase().filter { it.isLetterOrDigit() }
        return ORDER.firstOrNull { (token, _) -> token in normalized }?.second
    }
}

/**
 * Model ids, and the short aliases a client may use instead.
 *
 * `Qwen3-1.7B-8da4w-gptq-2k.pte` is installed as `qwen3-1.7b-8da4w-gptq-2k` and answers to
 * `qwen3-1.7b` as long as no other installed model claims that name too.
 */
object ModelIds {
    private val QUANT_START = Regex("-(8da4w|4w|8w|a16w\\d+|fp32|fp16|bf16|q4|int4|int8)(-|$)")

    fun idFor(fileStem: String): String =
        fileStem.lowercase()
            .map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '-' }
            .joinToString("")
            .trim('-', '.')
            .ifEmpty { "model" }

    fun aliasesFor(id: String): Set<String> {
        val base = QUANT_START.find(id)?.let { id.substring(0, it.range.first) }
        return setOfNotNull(base?.takeIf { it.isNotEmpty() && it != id })
    }
}

/**
 * The lab behind a model: the Hugging Face organisation that released the weights, whose
 * picture the catalog shows beside it. Read from the export's source model; an export made
 * from one of our own derivatives (an abliterated copy, say) is credited to the lab of the
 * family it descends from, which is the lineage a person is asking about.
 */
object Labs {
    private val BY_FAMILY = mapOf(
        "qwen3.5" to "Qwen",
        "qwen3" to "Qwen",
        "qwen2.5" to "Qwen",
        "smollm2" to "HuggingFaceTB",
        "smollm3" to "HuggingFaceTB",
        "llama3.2" to "meta-llama",
        "phi4-mini" to "microsoft",
        "gemma3" to "google",
        "lfm2.5" to "LiquidAI",
    )

    /** How each lab is known, where its Hub name is not how people say it. */
    private val NAMES = mapOf(
        "HuggingFaceTB" to "Hugging Face",
        "meta-llama" to "Meta",
        "LiquidAI" to "Liquid AI",
        "microsoft" to "Microsoft",
        "google" to "Google",
    )

    fun of(sourceModel: String?, family: String?): String? {
        val owner = sourceModel?.substringBefore('/', "")
            ?.takeIf { it.isNotEmpty() && !it.equals(HfCatalog.ORG, ignoreCase = true) }
        return owner ?: family?.let(BY_FAMILY::get)
    }

    fun displayName(lab: String): String = NAMES[lab] ?: lab

    /** The Hub's answer to "what is this organisation's picture": `{"avatarUrl": ...}`. */
    fun avatarApi(lab: String) = "https://huggingface.co/api/organizations/$lab/avatar"
}
