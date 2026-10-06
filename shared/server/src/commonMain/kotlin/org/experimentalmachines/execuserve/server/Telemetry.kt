package org.experimentalmachines.execuserve.server

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.experimentalmachines.execuserve.engine.JobRecord
import org.experimentalmachines.execuserve.engine.Metrics
import org.experimentalmachines.execuserve.engine.ModelSummary
import org.experimentalmachines.execuserve.engine.Spread

/**
 * The run history and the counters, for scripts and scrapers.
 *
 * `GET /v1/execuserve/runs` answers with the caller's own runs only: a key can see what it
 * asked and how long that took, not what other clients of the same phone asked (codex
 * review, 2026-09-30). `GET /metrics` is Prometheus text: counters since the server started,
 * gauges for now, and quantiles over recent runs, with no per-client labels.
 */
internal fun Route.telemetry(ctx: ServerContext) {
    get("/v1/execuserve/runs") {
        call.handle {
            val caller = client(ctx)
            val model = request.queryParameters["model"]
            val limit = request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, MAX_RUNS) ?: DEFAULT_RUNS
            val own = ctx.runs().filter { it.clientId == caller.id && (model == null || it.model == model) }
            if (request.queryParameters["format"] == "csv") {
                respondText(Metrics.csv(own.take(limit)), ContentType.Text.CSV)
            } else {
                respondJson(runsJson(own, limit))
            }
        }
    }
    get("/metrics") {
        call.handle {
            client(ctx)
            respondText(prometheus(ctx), ContentType.parse("text/plain; version=0.0.4; charset=utf-8"))
        }
    }
}

private fun runsJson(runs: List<JobRecord>, limit: Int): JsonObject = buildJsonObject {
    put("object", "list")
    put("total", runs.size)
    putJsonArray("data") { runs.take(limit).forEach { addJsonObject { runFields(it) } } }
    putJsonArray("models") { Metrics.summarize(runs).forEach { add(summaryJson(it)) } }
}

internal fun kotlinx.serialization.json.JsonObjectBuilder.runFields(run: JobRecord) {
    put("id", run.id)
    put("model", run.model)
    put("api", run.api)
    put("stream", run.stream)
    put("outcome", run.outcome)
    put("finished_at_ms", run.finishedAtMs)
    put("prompt_tokens", run.promptTokens)
    put("cached_tokens", run.cachedTokens)
    put("estimated_prompt_tokens", run.estimatedPromptTokens)
    put("completion_tokens", run.completionTokens)
    put("queue_ms", run.queueMs)
    put("load_ms", run.loadMs)
    put("prefill_ms", run.prefillMs)
    put("first_token_ms", run.firstTokenMs)
    put("decode_ms", run.decodeMs)
    put("decode_wall_ms", run.decodeWallMs)
    put("total_ms", run.totalMs)
    put("prefill_tokens_per_second", run.prefillTokensPerSecond)
    put("decode_tokens_per_second", run.decodeTokensPerSecond)
    run.threads?.let { put("threads", it) }
    put("thermal", run.thermal.name.lowercase())
    run.batteryPercent?.let { put("battery_percent", it) }
    put("charging", run.charging)
    putJsonArray("discrepancies") { Metrics.discrepancies(run).forEach { add(kotlinx.serialization.json.JsonPrimitive(it.name.lowercase())) } }
}

private fun summaryJson(summary: ModelSummary): JsonObject = buildJsonObject {
    put("model", summary.model)
    put("runs", summary.runs)
    put("failed", summary.failed)
    put("cache_hits", summary.cacheHits)
    put("cached_tokens", summary.cachedTokens)
    put("prompt_tokens", summary.promptTokens)
    putJsonArray("threads") { summary.threads.sorted().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
    summary.firstTokenMs?.let { putJsonObject("first_token_ms") { spread(it) } }
    putJsonObject("prefill_tokens_per_second") { summary.prefill.forEach { (bucket, s) -> putJsonObject(bucket.name.lowercase()) { spread(s) } } }
    putJsonObject("decode_tokens_per_second") { summary.decode.forEach { (bucket, s) -> putJsonObject(bucket.name.lowercase()) { spread(s) } } }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.spread(spread: Spread) {
    put("median", spread.median)
    put("p90", spread.p90)
    put("count", spread.count)
}

/** Prometheus exposition text. Counters restart with the server, as counters do. */
private fun prometheus(ctx: ServerContext): String = buildString {
    val status = ctx.engine.status.value
    val totals = status.totals
    fun metric(name: String, type: String, help: String, samples: List<Pair<String, Number>>) {
        appendLine("# HELP execuserve_$name $help")
        appendLine("# TYPE execuserve_$name $type")
        samples.forEach { (labels, value) -> appendLine("execuserve_$name$labels $value") }
    }
    metric(
        "requests_total",
        "counter",
        "Requests finished, by outcome.",
        listOf(
            "{outcome=\"completed\"}" to totals.completed,
            "{outcome=\"failed\"}" to totals.failed,
            "{outcome=\"refused\"}" to totals.refused,
        ),
    )
    metric("prompt_tokens_total", "counter", "Prompt tokens, cached ones included.", listOf("" to totals.promptTokens))
    metric("prompt_tokens_cached_total", "counter", "Prompt tokens reused from the KV cache.", listOf("" to totals.cachedTokens))
    metric("prompt_seconds_total", "counter", "Time spent reading prompts.", listOf("" to totals.prefillMs / MS))
    metric("tokens_predicted_total", "counter", "Tokens generated.", listOf("" to totals.completionTokens))
    metric("tokens_predicted_seconds_total", "counter", "Time spent generating after the first token.", listOf("" to totals.decodeMs / MS))
    metric("model_loads_total", "counter", "Models opened for a request.", listOf("" to totals.loads))
    metric("model_load_seconds_total", "counter", "Time spent opening models.", listOf("" to totals.loadMs / MS))
    metric("requests_processing", "gauge", "Requests on the compute lane now.", listOf("" to if (status.running != null) 1 else 0))
    metric("requests_deferred", "gauge", "Requests waiting for the lane.", listOf("" to status.queued))
    metric("models_loaded", "gauge", "Models open now.", listOf("" to status.resident.size))
    ctx.threads()?.let { metric("threads", "gauge", "CPU threads the runtime computes with.", listOf("" to it)) }
    val recent = ctx.runs().take(QUANTILE_RUNS).filter { it.finish != null }
    fun quantiles(name: String, help: String, values: List<Double>) {
        val spread = Spread.of(values) ?: return
        // Gauges over a rolling window, not a summary: a summary's _count must only grow,
        // and this one's, the window's size, would stall at its cap (codex review).
        metric(
            name,
            "gauge",
            "$help Over the last $QUANTILE_RUNS runs.",
            listOf("{quantile=\"0.5\"}" to spread.median, "{quantile=\"0.9\"}" to spread.p90),
        )
    }
    quantiles("time_to_first_token_seconds", "Submission to first token.", recent.filter { it.firstTokenMs > 0 }.map { it.firstTokenMs / MS })
    quantiles("request_seconds", "Submission to last token.", recent.map { it.totalMs / MS })
}

private const val MS = 1_000.0
private const val DEFAULT_RUNS = 100
private const val MAX_RUNS = 10_000
private const val QUANTILE_RUNS = 200
