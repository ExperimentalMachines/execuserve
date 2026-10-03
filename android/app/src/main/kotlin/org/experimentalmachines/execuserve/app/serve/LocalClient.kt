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
    /**
     * One request, which [cancel] stops from any thread: before its socket opens (it then never
     * sends) or while it streams (closing the socket, so the server sees the client leave and
     * stops within a token). Each request has its own, so stopping an old one cannot touch
     * the next.
     */
    class Call {
        @Volatile internal var cancelled = false
            private set

        @Volatile internal var connection: HttpURLConnection? = null

        fun cancel() {
            cancelled = true
            connection?.disconnect()
        }
    }

    /**
     * @throws TestFailure when the server refuses, the stream fails, or it stops before its end.
     * @throws StoppedByUser after [Call.cancel].
     */
    suspend fun chat(call: Call, baseUrl: String, key: String, body: String, onText: (content: String, reasoning: String) -> Unit): TestResult =
        withContext(Dispatchers.IO) {
            val started = SystemClock.elapsedRealtime()
            var firstToken = 0L
            val reader = ReplyReader { content, reasoning ->
                if (firstToken == 0L) firstToken = SystemClock.elapsedRealtime()
                onText(content, reasoning)
            }
            val http = open("$baseUrl/chat/completions", key)
            // Published before the check: a cancel either sees this connection or is seen here.
            call.connection = http
            try {
                if (call.cancelled) throw StoppedByUser()
                http.outputStream.use { it.write(body.toByteArray()) }
                refuseUnlessOk(http)
                readStream(http, reader)
                if (!reader.finished) throw IOException("The reply stopped before its end.")
                val total = SystemClock.elapsedRealtime() - started
                reader.result(firstTokenMs = if (firstToken > 0) firstToken - started else total, totalMs = total)
            } catch (closed: IOException) {
                if (call.cancelled) throw StoppedByUser()
                throw TestFailure(null, closed.message ?: closed::class.java.simpleName)
            } catch (malformed: IllegalArgumentException) {
                // A line that is not the JSON object a chunk should be (SerializationException is
                // one): a failed reply, not a crash (agy review).
                throw TestFailure(null, "Unreadable reply: ${malformed.message ?: malformed::class.java.simpleName}")
            } finally {
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
