package org.experimentalmachines.execuserve.app.models

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.experimentalmachines.execuserve.catalog.Labs
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Each lab's picture, as the Hub publishes it: looked up once, kept on disk, decoded small.
 *
 * A handful of organisations cover the whole catalog, so this is a map and a folder, not an
 * image library. A picture that cannot be had is left out quietly: nobody can act on it, and
 * the row reads fine without one. Only https images on huggingface.co are fetched, and only
 * up to [MAX_BYTES].
 */
class LabImages(cacheDir: File, private val scope: CoroutineScope) {
    private val folder = cacheDir.resolve("labs").apply { mkdirs() }
    private val asked = ConcurrentHashMap.newKeySet<String>()
    private val _images = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val images: StateFlow<Map<String, Bitmap>> = _images.asStateFlow()

    /** Starts fetching [lab]'s picture unless it is here or on its way. */
    fun request(lab: String) {
        if (!asked.add(lab)) return
        scope.launch(Dispatchers.IO) {
            val file = folder.resolve(lab.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + ".img")
            val bytes = file.takeIf { it.isFile && it.length() > 0 }?.readBytes()
                ?: runCatching { fetch(lab) }.getOrNull()?.also { file.writeBytes(it) }
                ?: return@launch asked.remove(lab).let { }
            decode(bytes)?.let { bitmap -> _images.update { it + (lab to bitmap) } }
        }
    }

    private fun fetch(lab: String): ByteArray? {
        val answer = get(Labs.avatarApi(lab)) ?: return null
        val url = (Json.parseToJsonElement(answer.decodeToString()).jsonObject["avatarUrl"] as? JsonPrimitive)?.content ?: return null
        val uri = URI(url)
        if (uri.scheme != "https" || !(uri.host == HUB || uri.host.endsWith(".$HUB"))) return null
        return get(url)
    }

    private fun get(url: String): ByteArray? {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "ExecuServe")
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.use { input ->
                val bytes = input.readNBytes(MAX_BYTES + 1)
                bytes.takeIf { it.size <= MAX_BYTES }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Decoded at roughly the size it is drawn, so a 1 000 px logo costs kilobytes, not megabytes. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= DRAWN_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private companion object {
        const val HUB = "huggingface.co"
        const val TIMEOUT_MS = 15_000
        const val MAX_BYTES = 512 * 1024
        const val DRAWN_PX = 128
    }
}
