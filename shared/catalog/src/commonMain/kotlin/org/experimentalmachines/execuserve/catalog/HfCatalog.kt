package org.experimentalmachines.execuserve.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One repository in the Hugging Face listing. Only what the catalog reads. */
@Serializable
data class HfRepo(val id: String, val sha: String? = null, val siblings: List<HfSibling> = emptyList())

@Serializable
data class HfSibling(val rfilename: String)

/** The exporter's `config.json`, which sits beside the `.pte` files it describes. */
@Serializable
data class ExportConfig(
    val runtime: String? = null,
    @SerialName("runtime_version") val runtimeVersion: String? = null,
    val backend: String? = null,
    /** The chip a chip-locked export was compiled for (`sm8850`), or null for CPU and GPU. */
    val target: String? = null,
    val tokenizer: String? = null,
    @SerialName("source_model") val sourceModel: String? = null,
    val variants: List<ExportVariant> = emptyList(),
)

@Serializable
data class ExportVariant(
    /** The one `.pte` of a single-file export. */
    val file: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    /** A digest for [file], or for a multi-file export a digest per file name. */
    val sha256: JsonElement? = null,
    val context: Int? = null,
    val quantization: String? = null,
    @SerialName("fits_phone_budget") val fitsPhoneBudget: Boolean? = null,
    /** A MediaTek export's compiled chunks, in order; empty for single-file exports. */
    val files: List<String> = emptyList(),
    /** A MediaTek export's token embedding table, which the NPU graphs take as input. */
    val embedding: String? = null,
    /** The MediaTek runner's options for these chunks (heads, types, window), verbatim. */
    val runner: JsonObject? = null,
) {
    /** The published digest of [name], or null when the exporter gave none. */
    fun digest(name: String): String? = when (val hashes = sha256) {
        is JsonPrimitive -> hashes.contentOrNull.takeIf { name == file }
        is JsonObject -> (hashes[name] as? JsonPrimitive)?.contentOrNull
        else -> null
    }
}

/**
 * The NPU half of a MediaTek export, which runs only beside a CPU build of the same model and
 * window: the NPU prefills, the CPU decodes ([withCpuHalves] finds that build).
 */
data class NpuParts(
    val chunkPaths: List<String>,
    val embeddingPath: String,
    /** Digests by repository path, for the chunks and the embedding. */
    val digests: Map<String, String?>,
    /** [ExportVariant.runner], as JSON text. */
    val runner: String,
    /** The paired CPU build: its path, size and digest. */
    val cpuPath: String? = null,
    val cpuSizeBytes: Long = 0,
    val cpuSha256: String? = null,
)

/** One downloadable export, pinned to the commit its hash was read from. */
data class CatalogVariant(
    val repo: String,
    val revision: String,
    val path: String,
    val tokenizerPath: String,
    val sizeBytes: Long,
    val sha256: String?,
    val context: Int?,
    val quantization: String?,
    val fitsPhoneBudget: Boolean?,
    val family: String?,
    val runtimeVersion: String?,
    val sourceModel: String? = null,
    val lab: String? = null,
    /** The export's delegate: [HfCatalog.BACKEND] (CPU), [HfCatalog.VULKAN] (GPU), [HfCatalog.QNN] or [HfCatalog.NEUROPILOT] (NPU). */
    val backend: String = HfCatalog.BACKEND,
    /** A MediaTek export's NPU half; null for every other backend. */
    val npu: NpuParts? = null,
) {
    val installId: String get() = ModelIds.idFor(installStem)

    /** What an install takes on the phone: a MediaTek install is its NPU files and its CPU build. */
    val installBytes: Long get() = sizeBytes + (npu?.cpuSizeBytes ?: 0)

    /**
     * The file's own name, plus `-vulkan` for a GPU build whose name does not already say so:
     * the folder is not part of the id, and two backends' files can share a name (codex QA).
     * CPU builds keep the id every installed copy already has. Every GPU id says `vulkan` and
     * no CPU id does ([HfCatalog.variants] does not list a CPU file named for Vulkan), so a
     * CPU and a GPU install can never share a folder.
     */
    private val installStem: String
        get() {
            // A MediaTek export is named by its first chunk, less the chunk count.
            val stem = path.substringAfterLast('/').substringBeforeLast('.').replace(HfCatalog.CHUNK_SUFFIX, "")
            // Every non-CPU install id names its backend, so no two backends' builds of one model
            // can share a folder; the exporter's names already do, and CPU ids stay as they were.
            val marker = when (backend) {
                HfCatalog.VULKAN -> HfCatalog.VULKAN
                HfCatalog.QNN -> HfCatalog.QNN
                HfCatalog.NEUROPILOT -> "neuropilot"
                else -> null
            }
            return if (marker != null && !stem.contains(marker, ignoreCase = true)) "$stem-$marker" else stem
        }
}

/** A file to fetch, and what it must hash to when the publisher said. */
data class RemoteFile(val url: String, val name: String, val sizeBytes: Long?, val sha256: String?)

/** Everything needed to install one variant into its own folder. */
data class InstallPlan(val id: String, val files: List<RemoteFile>, val manifest: Manifest)

/**
 * The `experimentalmachines` exports on Hugging Face, read the way the exporter publishes
 * them: one repository per checkpoint, a folder per backend, a `config.json` per folder
 * naming each window's file, size and hash.
 */
object HfCatalog {
    const val ORG = "experimentalmachines"
    const val BACKEND = "xnnpack"

    /** The GPU folder: listed only where the runtime says the phone can run it. */
    const val VULKAN = "vulkan"

    /**
     * Qualcomm's NPU. Its files are compiled for one chip and load only on it, so they sit
     * one folder deeper, `qnn/<soc>/`, and a phone lists exactly its own chip's folder
     * ([chipFolder]).
     */
    const val QNN = "qnn"

    /**
     * MediaTek's NPU, in folders per chip like [QNN] (`mtk/mt6991/`). Its exports are split
     * into compiled chunks plus an embedding table, and decode on the CPU build of the same
     * window, so an install is several files ([NpuParts]).
     */
    const val NEUROPILOT = "mtk"

    /** The Hub's host and base URL: the only place either is written. */
    const val HUB_HOST = "huggingface.co"
    const val HUB = "https://$HUB_HOST"

    /** More repositories than the organisation publishes, in one page. */
    private const val LIST_LIMIT = 200

    /** `owner/name`, in the characters the Hub allows; anything else never reaches a URL. */
    private val REPO_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,95}/[A-Za-z0-9][A-Za-z0-9._-]{0,95}$")

    fun isRepoId(repo: String) = REPO_ID.matches(repo) && ".." !in repo

    fun listUrl(org: String = ORG) = "$HUB/api/models?author=$org&full=true&limit=$LIST_LIMIT"

    fun modelUrl(repo: String): String {
        require(isRepoId(repo)) { "Not a Hugging Face repository id: $repo" }
        return "$HUB/api/models/$repo"
    }

    /** The Hub's answer to "what is this organisation's picture": `{"avatarUrl": ...}`. */
    fun avatarUrl(org: String): String {
        require(REPO_ID.matches("$org/x")) { "Not a Hugging Face organisation: $org" }
        return "$HUB/api/organizations/$org/avatar"
    }

    fun fileUrl(repo: String, revision: String, path: String) = "$HUB/$repo/resolve/$revision/$path"

    fun parseRepos(json: String): List<HfRepo> = Manifest.JSON.decodeFromString(ListSerializer(HfRepo.serializer()), json)

    fun parseConfig(json: String): ExportConfig = Manifest.JSON.decodeFromString(ExportConfig.serializer(), json)

    /** The `config.json` files in [repo] that describe exports for any of [backends]. */
    fun configPaths(repo: HfRepo, backends: Set<String> = setOf(BACKEND)): List<String> =
        repo.siblings.map { it.rfilename }.filter { path -> backends.any { path == "$it/config.json" } }

    /**
     * The variants [config] (found at [configPath] in [repo]) offers to this runtime.
     *
     * The tokenizer path is relative to the repository root in every export so far; a
     * tokenizer inside the backend folder wins when one is there.
     */
    fun variants(repo: HfRepo, configPath: String, config: ExportConfig, backends: Set<String> = setOf(BACKEND)): List<CatalogVariant> {
        val folder = configPath.substringBeforeLast('/', "")
        val backend = config.backend ?: configPath.substringBefore('/', BACKEND)
        val runnable = (config.runtime == null || config.runtime == "executorch") && offeredHere(folder, backend, config, backends)
        val revision = repo.sha?.takeIf { runnable } ?: return emptyList()
        val files = repo.siblings.map { it.rfilename }.toSet()
        val tokenizerName = config.tokenizer ?: "tokenizer.json"
        val tokenizer = listOf(if (folder.isEmpty()) tokenizerName else "$folder/$tokenizerName", tokenizerName)
            .firstOrNull { it in files } ?: return emptyList()
        val family = Families.detect(repo.id) ?: config.sourceModel?.let(Families::detect)
        val listing = Listing(repo, revision, folder, backend, files, tokenizer, family, config)
        return config.variants.mapNotNull(listing::variant)
    }

    internal val CHUNK_SUFFIX = Regex("-chunk\\d+of\\d+$")

    fun plan(variant: CatalogVariant, nowMs: Long): InstallPlan {
        val npu = variant.npu
        if (npu != null) return npuInstallPlan(variant, npu, nowMs)
        return InstallPlan(
            id = variant.installId,
            files = listOf(
                RemoteFile(fileUrl(variant.repo, variant.revision, variant.path), "model.pte", variant.sizeBytes, variant.sha256),
                RemoteFile(fileUrl(variant.repo, variant.revision, variant.tokenizerPath), "tokenizer.json", null, null),
            ),
            manifest = installManifest(variant, nowMs, variant.sizeBytes, variant.sha256, null),
        )
    }
}

/**
 * Whether a file exported with ExecuTorch [exported] is outside what a runtime at [runtime]
 * is promised to load. ExecuTorch's runtime compatibility policy (runtime/COMPATIBILITY.md):
 * a file loads on its own release and at least the next minor one, and nothing is promised
 * for a file newer than the runtime, down to the patch. So 1.4.x files on a 1.5.x runtime
 * need no warning; a 1.5.2 or 1.6 file on 1.5.1, or a 1.3 file on 1.5, does. Unparseable
 * versions say nothing.
 */
fun runtimeMismatch(exported: String?, runtime: String): Boolean {
    val file = exported?.let(::parseVersion)
    val app = parseVersion(runtime)
    if (file == null || app == null) return false
    val newer = compareValuesBy(file, app, { it[0] }, { it[1] }, { it[2] }) > 0
    return newer || file[0] != app[0] || app[1] - file[1] > 1
}

private fun parseVersion(version: String): List<Int>? {
    val parts = version.trim().split('.').map { it.toIntOrNull() }
    val major = parts.getOrNull(0) ?: return null
    val minor = parts.getOrNull(1) ?: return null
    return listOf(major, minor, parts.getOrNull(2) ?: 0)
}
