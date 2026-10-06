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
    /**
     * The export's delegate folder ([HfCatalog.BACKEND] or [HfCatalog.VULKAN]), kept so the
     * server reports what the file was exported for without reading it off the name. Null in
     * manifests written before it existed.
     */
    val backend: String? = null,
    /** A MediaTek install's NPU half, beside [model] (the CPU build that decodes for it). */
    val npu: ManifestNpu? = null,
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

/** The NPU files of a MediaTek install, by name within its folder, and the runner's options. */
@Serializable
data class ManifestNpu(
    val chunks: List<String>,
    val embedding: String,
    /** The exporter's `runner` block, as JSON text. */
    val runner: String,
)
