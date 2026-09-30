package org.experimentalmachines.execuserve.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `execuserve.json`, written beside a model when ExecuServe installs it. Everything the
 * server needs to know without opening the file, and where the file came from.
 */
@Serializable
data class Manifest(
    val id: String,
    val family: String? = null,
    val model: String = "model.pte",
    val tokenizer: String = "tokenizer.json",
    @SerialName("context_length") val contextLength: Int? = null,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    val sha256: String? = null,
    val source: String? = null,
    /** The lab's release this export was made from, e.g. `Qwen/Qwen3-1.7B`. */
    @SerialName("source_model") val sourceModel: String? = null,
    /** The Hugging Face organisation credited for the weights; see [Labs]. */
    val lab: String? = null,
    val revision: String? = null,
    val quantization: String? = null,
    @SerialName("installed_at_ms") val installedAtMs: Long = 0,
) {
    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val FILE_NAME = "execuserve.json"

        internal val JSON = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

        fun decode(text: String): Manifest = JSON.decodeFromString(serializer(), text)
    }
}
