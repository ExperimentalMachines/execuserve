package org.experimentalmachines.execuserve.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A browser signing in with the phone's camera, for pages that cannot use their own: a laptop
 * opens the chat over plain HTTP, where browsers refuse the camera. The page asks for a
 * pairing and shows it as a QR code; the phone scans it, its owner checks the browser and the
 * code, and the phone hands over a key. Only the page that asked can collect the key: it holds
 * the poll token, which never appears on screen or in the QR code.
 *
 * In memory only: a restart forgets every pairing, and each lasts [ttlMs].
 */
@OptIn(ExperimentalUuidApi::class)
class Pairings(private val clock: () -> Long, private val ttlMs: Long = TTL_MS) {

    /** What the phone shows its owner before approving: never the poll token. */
    data class Request(
        val id: String,
        /** Six characters the page shows beside its QR code, to compare or to type. */
        val code: String,
        /** The browser and system, from the request's User-Agent: claimed, not proven. */
        val client: String,
        /** The address the request came from. */
        val address: String,
        val expiresAtMs: Long,
    )

    /** A new pairing as the page receives it. */
    class Started(val request: Request, val pollToken: String)

    sealed interface Outcome {
        data class Approved(val key: String) : Outcome
        data object Pending : Outcome
        data object Declined : Outcome
        data object Gone : Outcome
        data object WrongToken : Outcome

        /** Another wait on this pairing is open: one at a time, so waits cannot pile up. */
        data object Busy : Outcome
    }

    private class Entry(val request: Request, val pollToken: String) {
        /** The key, or null when the owner declined. */
        val answer = CompletableDeferred<String?>()

        /** Whether a wait is open; guarded by the mutex. */
        var waiting = false
    }

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>()

    /** A pairing for the browser at [address], or null when too many are waiting already. */
    suspend fun start(address: String, userAgent: String?): Started? = mutex.withLock {
        prune()
        if (entries.size >= MAX_PENDING || entries.values.count { it.request.address == address } >= MAX_PER_ADDRESS) return null
        var code: String
        do code = newCode() while (entries.values.any { it.request.code == code })
        val request = Request(Uuid.random().toHexString(), code, describe(userAgent), address.take(MAX_ADDRESS), clock() + ttlMs)
        val entry = Entry(request, Uuid.random().toHexString() + Uuid.random().toHexString())
        entries[request.id] = entry
        Started(request, entry.pollToken)
    }

    /**
     * Waits up to [waitMs] for the phone's answer. An approved key is handed out once: the
     * pairing ends as it is collected.
     */
    suspend fun await(id: String, pollToken: String, waitMs: Long): Outcome {
        val (entry, refused) = mutex.withLock {
            prune()
            val entry = entries[id]
            val refused = when {
                entry == null -> Outcome.Gone
                !constantTimeEquals(entry.pollToken, pollToken) -> Outcome.WrongToken
                entry.waiting -> Outcome.Busy
                else -> null
            }
            if (refused == null) entry?.waiting = true
            entry to refused
        }
        if (refused != null || entry == null) return refused ?: Outcome.Gone
        return try {
            // join, not await: a pairing cancelled or expired meanwhile ends the wait without
            // throwing into the request.
            withTimeoutOrNull(waitMs) { entry.answer.join() }
            collect(id, entry)
        } finally {
            // Even for a wait cancelled by its page leaving: otherwise the next wait is refused.
            withContext(NonCancellable) { mutex.withLock { entry.waiting = false } }
        }
    }

    private suspend fun collect(id: String, entry: Entry): Outcome = when {
        !entry.answer.isCompleted -> Outcome.Pending
        entry.answer.isCancelled -> Outcome.Gone
        else -> {
            val answered = entry.answer.await()
            mutex.withLock {
                // Collected once: a second page with the same token finds nothing, and an
                // answer outlives its pairing by not a moment.
                val live = entries[id] === entry && entry.request.expiresAtMs > clock()
                if (entries[id] === entry) entries.remove(id)
                when {
                    !live -> Outcome.Gone
                    answered != null -> Outcome.Approved(answered)
                    else -> Outcome.Declined
                }
            }
        }
    }

    /** The page gave up (its dialog closed): the pairing ends. */
    suspend fun cancel(id: String, pollToken: String): Boolean = mutex.withLock {
        val entry = entries[id] ?: return@withLock false
        if (!constantTimeEquals(entry.pollToken, pollToken)) return@withLock false
        entries.remove(id)
        entry.answer.cancel()
        true
    }

    /** The waiting pairing a scanned QR code or a typed code names, or null. */
    suspend fun find(scannedOrTyped: String): Request? = mutex.withLock {
        prune()
        val text = scannedOrTyped.trim()
        val id = text.removePrefix(SCHEME).takeIf { text.startsWith(SCHEME, ignoreCase = false) }
        if (id != null) return@withLock entries[id]?.takeUnless { it.answer.isCompleted }?.request
        val code = normalCode(text) ?: return@withLock null
        entries.values.firstOrNull { it.request.code == code && !it.answer.isCompleted }?.request
    }

    /** Hands [key] to the page waiting on [id]; false when it is no longer waiting. */
    suspend fun approve(id: String, key: String): Boolean = answer(id, key)

    suspend fun decline(id: String): Boolean = answer(id, null)

    private suspend fun answer(id: String, key: String?): Boolean = mutex.withLock {
        prune()
        val entry = entries[id] ?: return@withLock false
        entry.answer.complete(key)
    }

    /** Drops expired pairings; a page still waiting on one hears it is gone. */
    private fun prune() {
        val now = clock()
        entries.values.filter { it.request.expiresAtMs <= now }.forEach {
            entries.remove(it.request.id)
            it.answer.cancel()
        }
    }

    private fun newCode(): String {
        val bytes = Uuid.random().toByteArray()
        return (0 until CODE_LENGTH).map { CODE_ALPHABET[(bytes[it].toInt() and BYTE_MASK) % CODE_ALPHABET.length] }.joinToString("")
    }

    companion object {
        /** What the page's QR code holds: no `http`, so a phone's camera app does not open it. */
        const val SCHEME = "EXECUSERVE-PAIR:"
        const val TTL_MS = 180_000L
        const val MAX_PENDING = 32
        const val MAX_PER_ADDRESS = 3
        const val CODE_LENGTH = 6

        /** No 0/O, 1/I/L: read off one screen and typed on another. */
        private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        private const val BYTE_MASK = 0xff
        private const val MAX_ADDRESS = 64
        private const val MAX_CLIENT = 60

        /** A typed code, upper-cased and without spaces or dashes; null when it cannot be one. */
        fun normalCode(text: String): String? = text.uppercase().filterNot { it == '-' || it.isWhitespace() }
            .takeIf { it.length == CODE_LENGTH && it.all { c -> c in CODE_ALPHABET } }

        /** "K7P-4QX": how the code is shown. */
        fun shown(code: String): String = code.take(CODE_LENGTH / 2) + "-" + code.drop(CODE_LENGTH / 2)

        /** "Chrome on macOS", from a User-Agent, in words the phone's owner recognises. */
        fun describe(userAgent: String?): String {
            val ua = userAgent.orEmpty()
            val browser = BROWSERS.firstOrNull { (marks, _) -> marks.any { it in ua } }?.second
            val system = SYSTEMS.firstOrNull { (marks, _) -> marks.any { it in ua } }?.second
            return when {
                browser != null && system != null -> "$browser on $system"
                browser != null -> browser
                system != null -> "A browser on $system"
                else -> "A browser"
            }.take(MAX_CLIENT)
        }

        // In order: Edge and Opera also say Chrome, Chrome also says Safari, Android says Linux.
        private val BROWSERS = listOf(
            listOf("Edg/") to "Edge",
            listOf("OPR/") to "Opera",
            listOf("Firefox/", "FxiOS/") to "Firefox",
            listOf("Chrome/", "CriOS/") to "Chrome",
            listOf("Safari/") to "Safari",
        )
        private val SYSTEMS = listOf(
            listOf("Android") to "Android",
            listOf("iPhone", "iPad") to "iOS",
            listOf("CrOS") to "ChromeOS",
            listOf("Mac OS X", "Macintosh") to "macOS",
            listOf("Windows") to "Windows",
            listOf("Linux") to "Linux",
        )
    }
}
