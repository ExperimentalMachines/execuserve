package org.experimentalmachines.execuserve.app.models

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.experimentalmachines.execuserve.catalog.InstallPlan
import org.experimentalmachines.execuserve.catalog.Manifest
import org.experimentalmachines.execuserve.catalog.RemoteFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** One install's progress, for the Models screen and the notification. */
data class DownloadState(val id: String, val bytes: Long, val total: Long, val phase: Phase, val error: String? = null) {
    enum class Phase { QUEUED, DOWNLOADING, VERIFYING, DONE, FAILED, CANCELLED }

    val active: Boolean get() = phase == Phase.QUEUED || phase == Phase.DOWNLOADING || phase == Phase.VERIFYING
}

/**
 * Installs models from the catalog, one at a time, inside the foreground service.
 *
 * Each file goes to `<name>.part`, resumed with an HTTP range after an interruption, hashed
 * as it is written, and renamed into place only when the hash matches. The manifest is
 * written last, so a folder the scanner can see is a finished install; a half-downloaded one
 * holds only `.part` files and is ignored. Written by hand rather than handed to the system
 * `DownloadManager`, which Android 16 subjects to job quotas even while a foreground service
 * runs.
 */
class Downloader(private val modelsDir: File, private val scope: CoroutineScope, private val onInstalled: suspend (String) -> Unit) {
    private val _state = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val state: StateFlow<Map<String, DownloadState>> = _state.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    /**
     * The connection in flight and whose it is, closed on that download's cancel: blocking
     * I/O ignores coroutine cancellation. Cancelling a queued download must not close another's
     * (codex review).
     */
    @Volatile private var active: Pair<String, HttpURLConnection>? = null

    /**
     * One install at a time, in the order asked. Mutex waiters queue fairly, and one that is
     * cancelled leaves the queue without letting the next one past a download still running;
     * joining only the previous download did (codex review).
     */
    private val oneAtATime = Mutex()

    val busy: Boolean get() = _state.value.values.any { it.active }

    @Synchronized
    fun enqueue(plan: InstallPlan) {
        if (_state.value[plan.id]?.active == true) return
        val total = plan.files.sumOf { it.sizeBytes ?: 0 }
        set(DownloadState(plan.id, 0, total, DownloadState.Phase.QUEUED))
        jobs[plan.id] = scope.launch(Dispatchers.IO) {
            oneAtATime.withLock { install(plan, total) }
        }
    }

    @Synchronized
    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        active?.takeIf { it.first == id }?.second?.disconnect()
        _state.value[id]?.let { set(it.copy(phase = DownloadState.Phase.CANCELLED)) }
    }

    /**
     * Bytes done and expected across a plan's files. A file published without a size (the
     * tokenizer) adds its length once the server states it, so the percentage never passes
     * 100 (codex review).
     */
    private class Progress(var done: Long, var total: Long)

    private suspend fun install(plan: InstallPlan, total: Long) {
        val folder = modelsDir.resolve(plan.id).apply { mkdirs() }
        val progress = Progress(0, total)
        try {
            for (file in plan.files) {
                fetch(file, folder.resolve(file.name), plan.id, progress)
                progress.done += folder.resolve(file.name).length()
            }
            val model = folder.resolve("model.pte")
            folder.resolve(Manifest.FILE_NAME).writeText(plan.manifest.copy(sizeBytes = model.length()).encode())
            set(DownloadState(plan.id, progress.done, progress.done, DownloadState.Phase.DONE))
            onInstalled(plan.id)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            set(DownloadState(plan.id, progress.done, progress.total, DownloadState.Phase.CANCELLED))
            throw cancelled
        } catch (failure: Exception) {
            // A cancel closes the socket, which surfaces here as an I/O error: still a cancel.
            val phase = if (coroutineContext.isActive) DownloadState.Phase.FAILED else DownloadState.Phase.CANCELLED
            set(DownloadState(plan.id, progress.done, progress.total, phase, failure.message ?: failure::class.java.simpleName))
        }
    }

    private suspend fun fetch(remote: RemoteFile, destination: File, id: String, progress: Progress) {
        val before = progress.done
        if (destination.isFile && remote.sha256 != null && sha256(destination) == remote.sha256) {
            if (remote.sizeBytes == null) progress.total += destination.length()
            return
        }
        val part = File(destination.path + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        // Resuming: the bytes already on disk are part of the hash.
        if (part.isFile) part.inputStream().use { input -> pump(input.buffered(), null, digest) }
        var offset = part.length()

        val connection = (URL(remote.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "ExecuServe")
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        active = id to connection
        try {
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> Unit
                HttpURLConnection.HTTP_OK -> if (offset > 0) {
                    // The server ignored the range: start over.
                    part.delete()
                    digest.reset()
                    offset = 0
                }
                RANGE_NOT_SATISFIABLE -> Unit // already complete; verified below
                else -> throw IOException("HTTP $code for ${remote.url.substringAfterLast('/')}")
            }
            if (remote.sizeBytes == null) progress.total += offset + connection.contentLengthLong.coerceAtLeast(0)
            val total = progress.total
            if (connection.responseCode != RANGE_NOT_SATISFIABLE) {
                set(DownloadState(id, before + offset, total, DownloadState.Phase.DOWNLOADING))
                connection.inputStream.buffered(BUFFER).use { input ->
                    FileOutputStream(part, offset > 0).use { output ->
                        var written = offset
                        var reported = 0L
                        pump(input, output, digest) { chunk ->
                            written += chunk
                            if (written - reported >= REPORT_BYTES) {
                                reported = written
                                set(DownloadState(id, before + written, total, DownloadState.Phase.DOWNLOADING))
                            }
                        }
                    }
                }
            }
        } finally {
            active = null
            connection.disconnect()
        }
        set(DownloadState(id, before + part.length(), progress.total, DownloadState.Phase.VERIFYING))
        val hash = digest.digest().toHex()
        if (remote.sha256 != null && !hash.equals(remote.sha256, ignoreCase = true)) {
            part.delete()
            throw IOException("${destination.name} did not match its published checksum; it was deleted.")
        }
        if (remote.sizeBytes != null && remote.sizeBytes!! > 0 && part.length() != remote.sizeBytes) {
            part.delete()
            throw IOException("${destination.name} is ${part.length()} bytes, expected ${remote.sizeBytes}.")
        }
        if (!part.renameTo(destination)) throw IOException("Could not move ${destination.name} into place.")
    }

    private suspend fun pump(input: java.io.InputStream, output: java.io.OutputStream?, digest: MessageDigest, onChunk: (Int) -> Unit = {}) {
        val buffer = ByteArray(BUFFER)
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            output?.write(buffer, 0, read)
            onChunk(read)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun set(state: DownloadState) = _state.update { it + (state.id to state) }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val BUFFER = 1 shl 20
        const val REPORT_BYTES = 4L shl 20
        const val TIMEOUT_MS = 30_000
        const val RANGE_NOT_SATISFIABLE = 416
    }
}
