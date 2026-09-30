package org.experimentalmachines.execuserve.app.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.experimentalmachines.execuserve.host.RunStore
import java.io.File

/**
 * The run history as a file of JSON lines in the app's private storage: appended to on
 * every request, rewritten whole only when the history compacts or is cleared.
 */
class FileRunStore(private val file: File) : RunStore {
    override suspend fun readLines(): List<String> = withContext(Dispatchers.IO) {
        if (file.isFile) file.readLines().filter { it.isNotBlank() } else emptyList()
    }

    override suspend fun appendLine(line: String) = withContext(Dispatchers.IO) {
        file.appendText(line + "\n")
    }

    override suspend fun rewrite(lines: List<String>) = withContext(Dispatchers.IO) {
        // Written aside and moved into place, so a crash mid-write cannot empty the history.
        val next = File(file.path + ".next")
        next.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n"))
        if (!next.renameTo(file)) {
            file.delete()
            next.renameTo(file)
        }
        Unit
    }
}
