package org.experimentalmachines.execuserve.executorch

import java.io.File

/**
 * Which model a native call was running when the process died.
 *
 * A model too large for the phone's memory does not fail inside ExecuTorch, where an error
 * could be caught: the system kills the whole process while the model loads or answers
 * (measured with MediaTek NPU builds on a 12 GB phone). Nothing in the process survives to say
 * so, and a service restart that loads the same model dies the same way. So each open, prefill
 * and generate leaves the model's id in a file for its duration, written before the native call
 * starts; a file still there at the next start names the model that was running.
 */
object NativeCrashGuard {
    @Volatile private var marker: File? = null

    /** Called once at startup, before any model opens. */
    fun init(directory: File) {
        marker = directory.resolve("native-running")
    }

    /** Records [id] as running, or clears the record when null. */
    fun mark(id: String?) {
        val file = marker ?: return
        runCatching { if (id == null) file.delete() else file.writeText(id) }
    }

    /** The model a native call was running when the previous process died, or null. */
    fun interrupted(): String? = marker?.takeIf { it.isFile }?.let { runCatching { it.readText().trim() }.getOrNull() }?.ifEmpty { null }

    /** Runs [block] with [id] recorded as running. */
    inline fun <T> around(id: String, block: () -> T): T {
        mark(id)
        try {
            return block()
        } finally {
            mark(null)
        }
    }

    /** The install id a model's files belong to: the folder an install created for it. */
    fun idOf(modelPath: String): String = File(modelPath).parentFile?.name ?: File(modelPath).nameWithoutExtension
}
