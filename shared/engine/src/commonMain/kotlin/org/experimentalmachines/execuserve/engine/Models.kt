package org.experimentalmachines.execuserve.engine

/**
 * An installed model, as the catalog found it on disk.
 *
 * @property id the name clients send, e.g. `qwen3-1.7b-8da4w-gptq-2k`.
 * @property family the chat-template family (`qwen3`, `lfm2.5`, ...), or null to read it from
 * the file name. Chat requests need one; raw completions do not.
 * @property aliases shorter names that resolve here when no other model claims them.
 */
data class ModelEntry(
    val id: String,
    val files: ModelFiles,
    val family: String? = null,
    val sizeBytes: Long = 0,
    val contextLength: Int? = null,
    val aliases: Set<String> = emptySet(),
    val source: String? = null,
    val installedAtMs: Long = 0,
    /** The organisation that released the weights, for display; see the catalog's `Labs`. */
    val lab: String? = null,
    /**
     * The delegate folder the catalog installed this from (`xnnpack`, `vulkan`), or null for
     * a file copied in by hand: a `.pte` does not say which delegates it holds.
     */
    val backend: String? = null,
)

/** Where the engine finds models. Implemented by the catalog over the models directory. */
interface ModelSource {
    fun all(): List<ModelEntry>

    /**
     * The model a client means by [name]: an exact id, else an alias that exactly one
     * installed model carries. Null when nothing or more than one thing matches.
     */
    fun resolve(name: String): ModelEntry? {
        val models = all()
        models.firstOrNull { it.id.equals(name, ignoreCase = true) }?.let { return it }
        val wanted = name.lowercase().removeSuffix(".pte")
        models.firstOrNull { it.id.equals(wanted, ignoreCase = true) }?.let { return it }
        val claimed = models.filter { model -> model.aliases.any { it.equals(wanted, ignoreCase = true) } }
        return claimed.singleOrNull()
    }
}

/** A fixed list, for tests and the dev server. */
class StaticModelSource(private val models: List<ModelEntry>) : ModelSource {
    override fun all(): List<ModelEntry> = models
}
