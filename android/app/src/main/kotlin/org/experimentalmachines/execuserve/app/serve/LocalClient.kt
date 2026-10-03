package org.experimentalmachines.execuserve.app.serve

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.experimentalmachines.execuserve.host.ConsoleTest
import org.experimentalmachines.execuserve.host.ReplyReader
import org.experimentalmachines.execuserve.host.TestFailure
import org.experimentalmachines.execuserve.host.TestResult
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The socket under the console's chat: HTTP to this app's own server, the way any client
 * calls it. What is sent ([ConsoleChat]) and how the answer is read ([ReplyReader]) are
 * shared code.
 */
class LocalClient {
    @Volatile private var connection: HttpURLConnection? = null

    @Volatile private var cancelled = false

    /** Closes the socket; the server sees the client leave and stops within a token. */
    fun cancel() {
        cancelled = true
        connection?.disconnect()
    }

    /**
     * @throws TestFailure when the server refuses or the stream fails.
     * @throws StoppedByUser after [cancel].
     */
    suspend fun chat(baseUrl: String, key: String, body: String, onText: (content: String, reasoning: String) -> Unit): TestResult =
        withContext(Dispatchers.IO) {
            cancelled = false
            val started = SystemClock.elapsedRealtime()
            var firstToken = 0L
            val reader = ReplyReader { content, reasoning ->
                if (firstToken == 0L) firstToken = SystemClock.elapsedRealtime()
                onText(content, reasoning)
            }
            val http = open("$baseUrl/chat/completions", key)
            connection = http
            try {
                http.outputStream.use { it.write(body.toByteArray()) }
                refuseUnlessOk(http)
                readStream(http, reader)
                val total = SystemClock.elapsedRealtime() - started
                reader.result(firstTokenMs = if (firstToken > 0) firstToken - started else total, totalMs = total)
            } catch (closed: IOException) {
                if (cancelled) throw StoppedByUser()
                throw TestFailure(null, closed.message ?: closed::class.java.simpleName)
            } finally {
                connection = null
                http.disconnect()
            }
        }

    private fun refuseUnlessOk(http: HttpURLConnection) {
        val code = http.responseCode
        if (code !in HTTP_OK..HTTP_LAST_OK) {
            throw TestFailure(code, ConsoleTest.errorMessage(http.errorStream?.bufferedReader()?.use { it.readText() }))
        }
    }

    private fun readStream(http: HttpURLConnection, reader: ReplyReader) = http.inputStream.bufferedReader().use { input ->
        while (true) {
            val line = input.readLine() ?: break
            if (!reader.line(line)) break
        }
    }

    private fun open(url: String, key: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = CONNECT_TIMEOUT_MS
        // A long prompt can take a while to read before anything arrives.
        readTimeout = READ_TIMEOUT_MS
        setRequestProperty("Authorization", "Bearer $key")
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept", "text/event-stream")
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 600_000
        const val HTTP_OK = 200
        const val HTTP_LAST_OK = 299
    }
}

/** The person pressed Stop: an outcome, not a failure. */
class StoppedByUser : Exception()
