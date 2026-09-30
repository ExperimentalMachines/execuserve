package org.experimentalmachines.execuserve.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.experimentalmachines.execuserve.engine.EngineStatus

/** `GET /v1/execuserve/status`: what the lane is doing, for dashboards and scripts. */
internal object StatusJson {
    fun of(status: EngineStatus, version: String, threads: Int? = null): JsonObject = buildJsonObject {
        put("version", version)
        put("lane", status.lane.name.lowercase())
        threads?.let { put("threads", it) }
        put("admission", status.admission.name.lowercase())
        put("queued", status.queued)
        status.running?.let { running ->
            putJsonObject("running") {
                put("id", running.id)
                put("model", running.model)
                put("client", running.client)
                put("started_at_ms", running.startedAtMs)
                put("generated_tokens", running.generatedTokens)
                put("cached_tokens", running.cachedTokens)
                if (running.firstTokenAtMs > 0) put("first_token_at_ms", running.firstTokenAtMs)
            }
        }
        putJsonArray("resident") {
            status.resident.forEach { model ->
                addJsonObject {
                    put("id", model.id)
                    put("context_length", model.contextLength)
                    put("held_tokens", model.heldTokens)
                    put("loaded_at_ms", model.loadedAtMs)
                    put("last_used_ms", model.lastUsedMs)
                }
            }
        }
        putJsonObject("broken") { status.broken.forEach { (id, reason) -> put(id, reason) } }
        putJsonObject("totals") {
            put("completed", status.totals.completed)
            put("failed", status.totals.failed)
            put("refused", status.totals.refused)
            put("prompt_tokens", status.totals.promptTokens)
            put("completion_tokens", status.totals.completionTokens)
        }
        putJsonArray("recent") {
            status.recent.forEach { job ->
                addJsonObject {
                    put("id", job.id)
                    put("model", job.model)
                    put("client", job.client)
                    put("outcome", job.outcome)
                    put("finished_at_ms", job.finishedAtMs)
                    put("prompt_tokens", job.promptTokens)
                    put("completion_tokens", job.completionTokens)
                    put("cached_tokens", job.cachedTokens)
                    put("queue_ms", job.queueMs)
                    put("total_ms", job.totalMs)
                    put("decode_tokens_per_second", job.decodeTokensPerSecond)
                    put("prefill_tokens_per_second", job.prefillTokensPerSecond)
                    put("first_token_ms", job.firstTokenMs)
                }
            }
        }
    }
}
