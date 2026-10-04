package org.experimentalmachines.execuserve.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

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
    val tokenizer: String? = null,
    @SerialName("source_model") val sourceModel: String? = null,
    val variants: List<ExportVariant> = emptyList(),
)

@Serializable
data class ExportVariant(
    val file: String,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    val sha256: String? = null,
    val context: Int? = null,
    val quantization: String? = null,
    @SerialName("fits_phone_budget") val fitsPhoneBudget: Boolean? = null,
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
    /** The export's delegate folder: [HfCatalog.BACKEND] (CPU) or [HfCatalog.VULKAN] (GPU). */
    val backend: String = HfCatalog.BACKEND,
) {
    val installId: String get() = ModelIds.idFor(installStem)

    /**
     * The file's own name, plus `-vulkan` for a GPU build whose name does not already say so:
     * the folder is not part of the id, and two backends' files can share a name (codex QA).
     * CPU builds keep the id every installed copy already has. Every GPU id says `vulkan` and
     * no CPU id does ([HfCatalog.variants] does not list a CPU file named for Vulkan), so a
     * CPU and a GPU install can never share a folder.
     */
    private val installStem: String
        get() {
            val stem = path.substringAfterLast('/').substringBeforeLast('.')
            val unmarkedGpu = backend == HfCatalog.VULKAN && !stem.contains(HfCatalog.VULKAN, ignoreCase = true)
            return if (unmarkedGpu) "$stem-${HfCatalog.VULKAN}" else stem
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
        val backend = config.backend ?: configPath.substringBefore('/', BACKEND)
        val runnable = (config.runtime == null || config.runtime == "executorch") && backend in backends
        val revision = repo.sha?.takeIf { runnable } ?: return emptyList()
        val folder = configPath.substringBeforeLast('/', "")
        val files = repo.siblings.map { it.rfilename }.toSet()
        val tokenizerName = config.tokenizer ?: "tokenizer.json"
        val tokenizer = listOf(if (folder.isEmpty()) tokenizerName else "$folder/$tokenizerName", tokenizerName)
            .firstOrNull { it in files } ?: return emptyList()
        val family = Families.detect(repo.id) ?: config.sourceModel?.let(Families::detect)
        return config.variants.mapNotNull { variant ->
            val path = if (folder.isEmpty()) variant.file else "$folder/${variant.file}"
            if (path !in files) return@mapNotNull null
            // A CPU file whose name says Vulkan would take a GPU build's install id and be
            // reported as one; the exporter never writes one, so it is not listed (codex QA).
            if (backend != VULKAN && variant.file.contains(VULKAN, ignoreCase = true)) return@mapNotNull null
            CatalogVariant(
                repo = repo.id,
                revision = revision,
                path = path,
                tokenizerPath = tokenizer,
                sizeBytes = variant.sizeBytes,
                sha256 = variant.sha256,
                context = variant.context,
                quantization = variant.quantization,
                fitsPhoneBudget = variant.fitsPhoneBudget,
                family = family,
                runtimeVersion = config.runtimeVersion,
                sourceModel = config.sourceModel,
                lab = Labs.of(config.sourceModel, family),
                backend = backend,
            )
        }
    }

    fun plan(variant: CatalogVariant, nowMs: Long): InstallPlan = InstallPlan(
        id = variant.installId,
        files = listOf(
            RemoteFile(fileUrl(variant.repo, variant.revision, variant.path), "model.pte", variant.sizeBytes, variant.sha256),
            RemoteFile(fileUrl(variant.repo, variant.revision, variant.tokenizerPath), "tokenizer.json", null, null),
        ),
        manifest = Manifest(
            id = variant.installId,
            family = variant.family,
            contextLength = variant.context,
            sizeBytes = variant.sizeBytes,
            sha256 = variant.sha256,
            source = variant.repo,
            sourceModel = variant.sourceModel,
            lab = variant.lab,
            revision = variant.revision,
            quantization = variant.quantization,
            installedAtMs = nowMs,
            backend = variant.backend,
        ),
    )
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
