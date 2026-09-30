package org.experimentalmachines.execuserve.app.models

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.experimentalmachines.execuserve.catalog.JavaFileSystem
import org.experimentalmachines.execuserve.catalog.ModelScanner
import org.experimentalmachines.execuserve.engine.ModelEntry
import org.experimentalmachines.execuserve.host.ModelLibrary
import java.io.File

/**
 * The installed models, in the app's external files directory so that
 * `adb push model.pte /sdcard/Android/data/<package>/files/models/<name>/` works without
 * root, and uninstalling removes them.
 */
class ModelStore(context: Context) : ModelLibrary {

    val directory: File = ownedModelsDirectory(context)

    private val scanner = ModelScanner(JavaFileSystem, directory.absolutePath)

    private val _installed = MutableStateFlow<List<ModelEntry>>(emptyList())
    val installed: StateFlow<List<ModelEntry>> = _installed.asStateFlow()

    private val _problems = MutableStateFlow<Map<String, String>>(emptyMap())
    val problems: StateFlow<Map<String, String>> = _problems.asStateFlow()

    init {
        rescanNow()
    }

    override fun all(): List<ModelEntry> = scanner.all()

    override suspend fun rescan() = withContext(Dispatchers.IO) { rescanNow() }

    private fun rescanNow() {
        _installed.value = scanner.rescan()
        _problems.value = scanner.problems
    }

    /** What [delete] would free: the whole folder, or the loose model and a paired tokenizer. */
    fun bytesFreedBy(entry: ModelEntry): Long = deletionSet(entry).sumOf { file ->
        if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else file.length()
    }

    /** Deletes a model's folder, or its loose files. The engine must have unloaded it. */
    suspend fun delete(entry: ModelEntry) = withContext(Dispatchers.IO) {
        deletionSet(entry).forEach { it.deleteRecursively() }
        rescanNow()
    }

    private fun deletionSet(entry: ModelEntry): List<File> {
        val model = File(entry.files.model)
        val folder = model.parentFile
        if (folder != null && folder != directory && folder.parentFile == directory) return listOf(folder)
        val tokenizer = File(entry.files.tokenizer)
        // A shared tokenizer.json may serve other loose models; only a paired one goes.
        return if (tokenizer.name != SHARED_TOKENIZER) listOf(model, tokenizer) else listOf(model)
    }

    private companion object {
        const val SHARED_TOKENIZER = "tokenizer.json"
    }
}

/**
 * The models folder, created by this app so that this app can read it.
 *
 * Under Android 11's FUSE storage a folder that `adb shell mkdir` or `adb push dir/` made
 * inside the app's own external directory belongs to the shell, and the app cannot list it
 * (seen on the emulator: the server listed no models). Files pushed into a folder the app
 * made are readable. So a folder the app cannot read is set aside and a new one made.
 */
private fun ownedModelsDirectory(context: Context): File {
    val parent = context.getExternalFilesDir(null) ?: context.filesDir
    val directory = parent.resolve("models")
    fun usable(dir: File) = dir.isDirectory && dir.canRead() && dir.list() != null
    if (directory.exists() && !usable(directory)) {
        directory.renameTo(parent.resolve("models-unreadable-${System.currentTimeMillis()}"))
    }
    directory.mkdirs()
    if (usable(directory)) return directory
    // The folder could be neither read nor moved aside: fall back to app-private storage,
    // which adb cannot reach but the catalog can still fill (codex review).
    return context.filesDir.resolve("models").apply { mkdirs() }
}
