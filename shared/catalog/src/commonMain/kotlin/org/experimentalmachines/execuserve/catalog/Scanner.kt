package org.experimentalmachines.execuserve.catalog

import org.experimentalmachines.execuserve.engine.ModelEntry
import org.experimentalmachines.execuserve.engine.ModelFiles
import org.experimentalmachines.execuserve.engine.ModelSource

/** The little of a file system the scanner needs. */
interface FileSystemView {
    fun list(directory: String): List<String>
    fun isDirectory(path: String): Boolean
    fun isFile(path: String): Boolean
    fun size(path: String): Long
    fun modifiedMs(path: String): Long
    fun readText(path: String): String
}

/**
 * Finds models under one directory. Three layouts are recognised, so that both what
 * ExecuServe installs and what someone `adb push`es by hand are found:
 *
 * 1. a folder with `execuserve.json`, which says everything;
 * 2. a folder with one `.pte` and a `tokenizer.json`;
 * 3. `Name.pte` beside `Name.tokenizer.json` (OpenWeights' convention) or `tokenizer.json`.
 *
 * A `.pte` with no tokenizer anywhere beside it is skipped: a compiled graph with the wrong
 * tokenizer answers in fluent nonsense, so no guess is made.
 */
class ModelScanner(private val fs: FileSystemView, private val root: String) : ModelSource {

    @kotlin.concurrent.Volatile
    private var cached: List<ModelEntry> = emptyList()

    /** Problems found on the last scan, by path, for the app to show. */
    @kotlin.concurrent.Volatile
    var problems: Map<String, String> = emptyMap()
        private set

    override fun all(): List<ModelEntry> = cached

    /** Reads the directory again. Call after installing, deleting or pushing a model. */
    fun rescan(): List<ModelEntry> {
        val found = mutableListOf<ModelEntry>()
        val issues = mutableMapOf<String, String>()
        if (fs.isDirectory(root)) {
            fs.list(root).sorted().forEach { name ->
                val path = join(root, name)
                when {
                    fs.isDirectory(path) -> folder(path, name, issues)?.let(found::add)
                    name.endsWith(PTE, ignoreCase = true) -> loose(root, name, issues)?.let(found::add)
                }
            }
        }
        // Two layouts can produce one id; the first (sorted) wins and the other is reported.
        val unique = found.groupBy { it.id }.map { (id, same) ->
            same.drop(1).forEach { issues[it.files.model] = "Another model is already installed as '$id'." }
            same.first()
        }
        cached = unique
        problems = issues
        return unique
    }

    private fun folder(path: String, name: String, issues: MutableMap<String, String>): ModelEntry? {
        val manifestPath = join(path, Manifest.FILE_NAME)
        if (fs.isFile(manifestPath)) {
            val manifest = runCatching { Manifest.decode(fs.readText(manifestPath)) }.getOrElse {
                issues[manifestPath] = "Unreadable manifest: ${it.message}"
                return null
            }
            val model = join(path, manifest.model)
            val tokenizer = join(path, manifest.tokenizer)
            if (!fs.isFile(model) || !fs.isFile(tokenizer)) {
                issues[path] = "Incomplete: ${manifest.model} or ${manifest.tokenizer} is missing (a download may still be running)."
                return null
            }
            return ModelEntry(
                id = manifest.id,
                files = ModelFiles(model, tokenizer),
                family = manifest.family ?: Families.detect(manifest.id),
                sizeBytes = fs.size(model),
                contextLength = manifest.contextLength,
                aliases = ModelIds.aliasesFor(manifest.id),
                source = manifest.source,
                installedAtMs = manifest.installedAtMs.takeIf { it > 0 } ?: fs.modifiedMs(model),
                lab = manifest.lab ?: Labs.of(manifest.sourceModel, manifest.family ?: Families.detect(manifest.id)),
            )
        }
        val ptes = fs.list(path).filter { it.endsWith(PTE, ignoreCase = true) }
        if (ptes.isEmpty()) return null
        if (ptes.size > 1) {
            issues[path] = "More than one .pte in one folder; give each model its own folder."
            return null
        }
        val tokenizer = tokenizerBeside(path, ptes.single()) ?: run {
            issues[path] = "No tokenizer.json beside ${ptes.single()}."
            return null
        }
        val model = join(path, ptes.single())
        val id = ModelIds.idFor(name)
        val family = Families.detect(name) ?: Families.detect(ptes.single())
        return ModelEntry(
            id = id,
            files = ModelFiles(model, tokenizer),
            family = family,
            sizeBytes = fs.size(model),
            contextLength = windowFromName(ptes.single()),
            aliases = ModelIds.aliasesFor(id),
            installedAtMs = fs.modifiedMs(model),
            lab = Labs.of(null, family),
        )
    }

    private fun loose(directory: String, fileName: String, issues: MutableMap<String, String>): ModelEntry? {
        val tokenizer = tokenizerBeside(directory, fileName) ?: run {
            issues[join(directory, fileName)] = "No tokenizer beside $fileName (expected ${stem(fileName)}$TOKENIZER_SUFFIX or tokenizer.json)."
            return null
        }
        val model = join(directory, fileName)
        val id = ModelIds.idFor(stem(fileName))
        val family = Families.detect(fileName)
        return ModelEntry(
            id = id,
            files = ModelFiles(model, tokenizer),
            family = family,
            sizeBytes = fs.size(model),
            contextLength = windowFromName(fileName),
            aliases = ModelIds.aliasesFor(id),
            installedAtMs = fs.modifiedMs(model),
            lab = Labs.of(null, family),
        )
    }

    private fun tokenizerBeside(directory: String, pte: String): String? =
        listOf(stem(pte) + TOKENIZER_SUFFIX, "tokenizer.json")
            .map { join(directory, it) }
            .firstOrNull { fs.isFile(it) }

    private companion object {
        const val PTE = ".pte"
        const val TOKENIZER_SUFFIX = ".tokenizer.json"
        val WINDOW = Regex("-(\\d+)k(?:-|\\.|$)", RegexOption.IGNORE_CASE)

        fun stem(file: String) = file.substringBeforeLast('.')

        fun join(dir: String, name: String) = if (dir.endsWith('/')) dir + name else "$dir/$name"

        /**
         * `...-2k.pte` is a 2048-token export in the exporter's naming. Only a hint for the
         * admission check before the file is opened; the file's own metadata wins after.
         */
        fun windowFromName(file: String): Int? = WINDOW.find(file)?.groupValues?.get(1)?.toIntOrNull()?.let { it * 1024 }
    }
}
