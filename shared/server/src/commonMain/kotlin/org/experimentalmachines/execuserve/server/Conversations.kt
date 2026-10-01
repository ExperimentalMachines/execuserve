package org.experimentalmachines.execuserve.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import org.experimentalmachines.execuserve.engine.Units

/**
 * Stored responses for `previous_response_id`: each response's whole conversation as input
 * items, so the next request can continue it by id instead of sending it again.
 *
 * In memory only, and bounded three ways: by count, by bytes, and by age. A response
 * belongs to the key and canonical model that made it; a different key or model, an evicted one, and every id
 * from before a restart are all `previous_response_not_found`, never a silent fresh start.
 * Continuing a conversation this way reads the same prompt the client would have sent, so
 * it hits the KV cache exactly as often; storing it guarantees nothing about the cache.
 */
class Conversations(
    private val clock: () -> Long,
    private val maxEntries: Int = 64,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) {
    private class Stored(val owner: String, val items: List<JsonElement>, val bytes: Long, val atMs: Long, val model: String?)

    private val lock = Mutex()
    private val entries = LinkedHashMap<String, Stored>()
    private var bytes = 0L

    suspend fun get(id: String, owner: String, model: String? = null): List<JsonElement>? = lock.withLock {
        expire()
        entries[id]?.takeIf { it.owner == owner && it.model == model }?.items
    }

    suspend fun put(id: String, owner: String, items: List<JsonElement>, model: String? = null) = lock.withLock {
        val size = JsonArray(items).toString().length.toLong()
        if (size > maxBytes) return@withLock
        entries.remove(id)?.let { bytes -= it.bytes }
        entries[id] = Stored(owner, items, size, clock(), model)
        bytes += size
        while (entries.size > maxEntries || bytes > maxBytes) drop(entries.keys.first())
    }

    /** Forgets everything a key stored, for when the key is revoked. */
    suspend fun forget(owner: String) = lock.withLock {
        entries.filterValues { it.owner == owner }.keys.toList().forEach(::drop)
    }

    private fun expire() {
        val cutoff = clock() - maxAgeMs
        entries.filterValues { it.atMs < cutoff }.keys.toList().forEach(::drop)
    }

    private fun drop(id: String) {
        entries.remove(id)?.let { bytes -= it.bytes }
    }
}

/** Responses kept for `previous_response_id`: a few MiB for up to an hour. */
private const val DEFAULT_MAX_BYTES = 4 * Units.BYTES_PER_MIB
private const val DEFAULT_MAX_AGE_MS = Units.MS_PER_HOUR
