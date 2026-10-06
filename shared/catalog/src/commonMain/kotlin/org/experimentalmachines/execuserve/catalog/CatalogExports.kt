package org.experimentalmachines.execuserve.catalog

/** The folder holding [backend]'s exports compiled for [soc], e.g. `qnn/sm8850`. */
fun chipFolder(backend: String, soc: String) = "$backend/${soc.lowercase()}"

/**
 * Whether the export described by [config] in [folder] is offered on a phone that runs
 * [backends]. A chip-locked export (QNN, MediaTek) is offered only through its own chip's
 * folder, and only when the config agrees about the chip: a file compiled for another chip
 * downloads in full and then refuses to load. Every other export goes by its backend.
 */
internal fun offeredHere(folder: String, backend: String, config: ExportConfig, backends: Set<String>): Boolean {
    val chipLocked = folder.count { it == '/' } > 0 || config.target != null
    if (!chipLocked) return backend in backends
    val target = config.target ?: return false
    return folder in backends && folder == chipFolder(backend, target)
}

/** What [HfCatalog.variants] knows about one config, to turn each of its variants into a [CatalogVariant]. */
internal class Listing(
    private val repo: HfRepo,
    private val revision: String,
    private val folder: String,
    private val backend: String,
    private val files: Set<String>,
    private val tokenizer: String,
    private val family: String?,
    private val config: ExportConfig,
) {
    private fun inFolder(name: String) = if (folder.isEmpty()) name else "$folder/$name"

    fun variant(variant: ExportVariant): CatalogVariant? {
        val name = variant.file ?: variant.files.firstOrNull() ?: return null
        val path = inFolder(name)
        // A CPU file whose name says Vulkan would take a GPU build's install id and be
        // reported as one; the exporter never writes one, so it is not listed (codex QA).
        val misnamed = backend != HfCatalog.VULKAN && name.contains(HfCatalog.VULKAN, ignoreCase = true)
        val npu = npuParts(variant, files, ::inFolder)
        // A multi-file NPU export is offered whole or not at all.
        val incomplete = variant.files.isNotEmpty() && npu == null
        if (path !in files || misnamed || incomplete) return null
        return CatalogVariant(
            repo = repo.id,
            revision = revision,
            path = path,
            tokenizerPath = tokenizer,
            sizeBytes = variant.sizeBytes,
            sha256 = variant.digest(name),
            context = variant.context,
            quantization = variant.quantization,
            fitsPhoneBudget = variant.fitsPhoneBudget,
            family = family,
            runtimeVersion = config.runtimeVersion,
            sourceModel = config.sourceModel,
            lab = Labs.of(config.sourceModel, family),
            backend = backend,
            npu = npu,
        )
    }
}

/**
 * Pairs each MediaTek variant with the CPU build that decodes for it: same repository, same
 * window. One without such a build cannot answer and is dropped.
 */
fun withCpuHalves(variants: List<CatalogVariant>): List<CatalogVariant> = variants.mapNotNull { variant ->
    val npu = variant.npu ?: return@mapNotNull variant
    val cpu = variants.firstOrNull { it.repo == variant.repo && it.backend == HfCatalog.BACKEND && it.context == variant.context }
        ?: return@mapNotNull null
    variant.copy(npu = npu.copy(cpuPath = cpu.path, cpuSizeBytes = cpu.sizeBytes, cpuSha256 = cpu.sha256))
}

/**
 * A MediaTek variant's NPU half, or null for a single-file export. A multi-file export is
 * offered only whole and verifiable: every chunk and the embedding table, each with its
 * published digest, and the runner's options must be there, so an incomplete one also comes
 * back null and the caller drops it.
 */
internal fun npuParts(variant: ExportVariant, files: Set<String>, inFolder: (String) -> String): NpuParts? {
    val chunks = variant.files.map(inFolder)
    val embedding = variant.embedding?.let(inFolder)
    val runner = variant.runner
    val missing = embedding == null || (chunks + embedding).any { it !in files }
    if (chunks.isEmpty() || runner == null || missing) return null
    val digests = (variant.files + listOfNotNull(variant.embedding)).associate { inFolder(it) to variant.digest(it) }
    if (digests.values.any { it.isNullOrBlank() }) return null
    return NpuParts(
        chunkPaths = chunks,
        embeddingPath = embedding,
        digests = digests,
        runner = runner.toString(),
    )
}

/** The NPU chunks and embedding under their own names, the CPU build as `model.pte`. */
internal fun npuInstallPlan(variant: CatalogVariant, npu: NpuParts, nowMs: Long): InstallPlan {
    val cpuPath = requireNotNull(npu.cpuPath) { "${variant.installId} has no CPU build to decode with" }
    fun remote(path: String, name: String, size: Long?, sha256: String?) =
        RemoteFile(HfCatalog.fileUrl(variant.repo, variant.revision, path), name, size, sha256)
    val files = (npu.chunkPaths + npu.embeddingPath).map { remote(it, it.substringAfterLast('/'), null, npu.digests[it]) } +
        remote(cpuPath, "model.pte", npu.cpuSizeBytes, npu.cpuSha256) +
        remote(variant.tokenizerPath, "tokenizer.json", null, null)
    val parts = ManifestNpu(
        chunks = npu.chunkPaths.map { it.substringAfterLast('/') },
        embedding = npu.embeddingPath.substringAfterLast('/'),
        runner = npu.runner,
    )
    return InstallPlan(variant.installId, files, installManifest(variant, nowMs, variant.sizeBytes + npu.cpuSizeBytes, npu.cpuSha256, parts))
}

/** The `execuserve.json` an install of [variant] gets. */
internal fun installManifest(variant: CatalogVariant, nowMs: Long, sizeBytes: Long, sha256: String?, npu: ManifestNpu?) = Manifest(
    id = variant.installId,
    family = variant.family,
    contextLength = variant.context,
    sizeBytes = sizeBytes,
    sha256 = sha256,
    source = variant.repo,
    sourceModel = variant.sourceModel,
    lab = variant.lab,
    revision = variant.revision,
    quantization = variant.quantization,
    installedAtMs = nowMs,
    backend = variant.backend,
    npu = npu,
)
