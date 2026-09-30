package org.experimentalmachines.execuserve.engine

/**
 * What the run history says, computed the same way for the console, the API and the tests.
 *
 * Two rules keep the figures honest. Rates are compared only between like runs: a cache hit
 * reads a fraction of its prompt, so its prefill rate measures less work, and decoding slows
 * as the context grows (25 to 10 tokens a second on Qwen3-1.7B between a short prompt and
 * 2 300 tokens on the POCO), so rates are kept per context size. And every figure carries the
 * number of runs behind it.
 */
enum class ContextBucket(val upTo: Int) {
    SHORT(SHORT_TOKENS),
    MEDIUM(MEDIUM_TOKENS),
    LONG(Int.MAX_VALUE),
    ;

    companion object {
        fun of(contextTokens: Int): ContextBucket = entries.first { contextTokens < it.upTo }
    }
}

private const val SHORT_TOKENS = 512
private const val MEDIUM_TOKENS = 2048

/** The median and 90th percentile of a sample, and how many values it holds. */
data class Spread(val median: Double, val p90: Double, val count: Int) {
    companion object {
        fun of(values: List<Double>): Spread? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            return Spread(percentile(sorted, HALF), percentile(sorted, NINETY), sorted.size)
        }

        /** Nearest rank: always a value that was measured, never an interpolation. */
        private fun percentile(sorted: List<Double>, fraction: Double): Double {
            val rank = kotlin.math.ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }

        private const val HALF = 0.5
        private const val NINETY = 0.9
    }
}

/** One model's history, as figures that can be set beside another model's. */
data class ModelSummary(
    val model: String,
    val runs: Int,
    val failed: Int,
    /** Time to the first token, over runs that produced one. */
    val firstTokenMs: Spread?,
    /** Prompt reading rate, over runs that reused nothing, by context size. */
    val prefill: Map<ContextBucket, Spread>,
    /** Decoding rate, by the context size decoding started from. */
    val decode: Map<ContextBucket, Spread>,
    /** Runs that continued a cached conversation, and the share of prompt tokens reused. */
    val cacheHits: Int,
    val cachedTokens: Long,
    val promptTokens: Long,
    /** Thread counts the runs used; more than one means the rates mix settings. */
    val threads: Set<Int>,
    val lastRunAtMs: Long,
)

/** Where a run's own figures disagree with each other. Neither clock is taken as the truth. */
enum class Discrepancy {
    /** The runtime's decode time and this side's clock differ by more than [CLOCK_TOLERANCE]. */
    DECODE_CLOCKS,

    /** Queue, load and prefill take longer than the time to the first token they precede. */
    PHASES_EXCEED_FIRST_TOKEN,

    /** The first token came after the request had ended. */
    FIRST_TOKEN_AFTER_END,

    /** More tokens reused than the prompt held. */
    CACHE_EXCEEDS_PROMPT,
}

object Metrics {
    /** Relative disagreement between two clocks worth showing. */
    const val CLOCK_TOLERANCE = 0.10

    /** Absolute slack for millisecond clocks read at slightly different moments. */
    private const val SLACK_MS = 50L

    fun discrepancies(run: JobRecord): List<Discrepancy> = buildList {
        if (run.decodeMs > 0 && run.decodeWallMs > 0) {
            val larger = maxOf(run.decodeMs, run.decodeWallMs)
            val gap = kotlin.math.abs(run.decodeMs - run.decodeWallMs)
            if (gap > SLACK_MS && gap.toDouble() / larger > CLOCK_TOLERANCE) add(Discrepancy.DECODE_CLOCKS)
        }
        if (run.firstTokenMs > 0 && run.queueMs + run.loadMs + run.prefillMs > run.firstTokenMs + SLACK_MS) {
            add(Discrepancy.PHASES_EXCEED_FIRST_TOKEN)
        }
        if (run.firstTokenMs > run.totalMs + SLACK_MS) add(Discrepancy.FIRST_TOKEN_AFTER_END)
        if (run.cachedTokens > run.promptTokens) add(Discrepancy.CACHE_EXCEEDS_PROMPT)
    }

    /** Per model, newest activity first. Only successful runs contribute rates. */
    fun summarize(runs: List<JobRecord>): List<ModelSummary> = runs.groupBy { it.model }.map { (model, own) ->
        val ok = own.filter { it.finish != null }
        ModelSummary(
            model = model,
            runs = own.size,
            failed = own.size - ok.size,
            firstTokenMs = Spread.of(ok.filter { it.firstTokenMs > 0 }.map { it.firstTokenMs.toDouble() }),
            prefill = ok.filter { it.cachedTokens == 0 && it.prefillTokensPerSecond > 0 }
                .groupBy { ContextBucket.of(it.promptTokens) }
                .mapValues { (_, bucket) -> Spread.of(bucket.map { it.prefillTokensPerSecond })!! },
            decode = ok.filter { it.decodeTokensPerSecond > 0 }
                .groupBy { ContextBucket.of(it.promptTokens) }
                .mapValues { (_, bucket) -> Spread.of(bucket.map { it.decodeTokensPerSecond })!! },
            cacheHits = ok.count { it.cachedTokens > 0 },
            cachedTokens = ok.sumOf { it.cachedTokens.toLong() },
            promptTokens = ok.sumOf { it.promptTokens.toLong() },
            threads = own.mapNotNull { it.threads }.toSet(),
            lastRunAtMs = own.maxOf { it.finishedAtMs },
        )
    }.sortedByDescending { it.lastRunAtMs }

    /** One row per run, for a spreadsheet. */
    fun csv(runs: List<JobRecord>): String = buildString {
        appendLine(COLUMNS.joinToString(","))
        runs.forEach { run ->
            val row = listOf(
                run.id, run.finishedAtMs, run.model, run.api, run.stream, run.outcome,
                run.promptTokens, run.cachedTokens, run.estimatedPromptTokens, run.completionTokens,
                run.queueMs, run.loadMs, run.prefillMs, run.firstTokenMs, run.decodeMs, run.decodeWallMs, run.totalMs,
                twoDecimals(run.prefillTokensPerSecond), twoDecimals(run.decodeTokensPerSecond),
                run.threads ?: "", run.thermal.name.lowercase(), run.batteryPercent ?: "", run.charging,
            )
            appendLine(row.joinToString(",") { quote(it.toString()) })
        }
    }

    private val COLUMNS = listOf(
        "id", "finished_at_ms", "model", "api", "stream", "outcome",
        "prompt_tokens", "cached_tokens", "estimated_prompt_tokens", "completion_tokens",
        "queue_ms", "load_ms", "prefill_ms", "first_token_ms", "decode_ms", "decode_wall_ms", "total_ms",
        "prefill_tokens_per_second", "decode_tokens_per_second",
        "threads", "thermal", "battery_percent", "charging",
    )

    private fun quote(value: String) = if (value.any { it == ',' || it == '"' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    /** Two decimals without a locale: a spreadsheet reads a dot. */
    private fun twoDecimals(value: Double): String {
        val hundredths = kotlin.math.round(value * HUNDRED).toLong()
        return "${hundredths / HUNDRED.toLong()}.${(hundredths % HUNDRED.toLong()).toString().padStart(2, '0')}"
    }

    private const val HUNDRED = 100.0
}
