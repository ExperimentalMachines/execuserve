package org.experimentalmachines.execuserve.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.experimentalmachines.execuserve.app.R
import org.experimentalmachines.execuserve.app.text.Format
import org.experimentalmachines.execuserve.catalog.ModelIds
import org.experimentalmachines.execuserve.engine.ContextBucket
import org.experimentalmachines.execuserve.engine.Discrepancy
import org.experimentalmachines.execuserve.engine.JobRecord
import org.experimentalmachines.execuserve.engine.Metrics
import org.experimentalmachines.execuserve.engine.ModelSummary
import org.experimentalmachines.execuserve.engine.Spread
import org.experimentalmachines.execuserve.host.Benchmark
import org.experimentalmachines.execuserve.host.Heat
import org.experimentalmachines.execuserve.host.Outcome
import org.experimentalmachines.execuserve.host.ServeHost

/**
 * Every request the server finished, across restarts: recent runs first, because the most
 * common question is "what just happened"; the comparison between models and the benchmark
 * are a tap away, not the first thing on the page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunsScreen(model: MainViewModel, padding: PaddingValues, wide: Boolean) {
    val runs by model.runs.collectAsState()
    val installed by model.installed.collectAsState()
    val server by model.server.collectAsState()
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var comparing by rememberSaveable { mutableStateOf(false) }
    var benchmarking by rememberSaveable { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val ordinary = remember(runs) { runs.filter { it.api != Benchmark.API } }
    val shown = remember(ordinary, filter) { if (filter == null) ordinary else ordinary.filter { it.model == filter } }
    val models = remember(ordinary) { ordinary.map { it.model }.distinct() }

    val side: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
        item(key = "diagnostics") {
            Panel {
                Expandable(stringResource(R.string.host_diagnostics), stringResource(R.string.host_diagnostics_hint)) {
                    Action(stringResource(if (comparing) R.string.runs_compare_close else R.string.runs_compare), { comparing = !comparing })
                    Action(stringResource(if (benchmarking) R.string.runs_benchmark_close else R.string.runs_benchmark), { benchmarking = !benchmarking })
                }
            }
        }
        if (comparing) item(key = "compare") { ComparePanel(model, ordinary) }
        if (benchmarking) item(key = "benchmark") { BenchmarkPanel(model, installed.map { it.id }, server is ServeHost.State.Running, runs) }
    }
    val main: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
        item(key = "runs") {
            Panel(
                pluralStringResource(R.plurals.runs_count, shown.size, Format.count(shown.size)),
                trailing = {
                    if (runs.isNotEmpty()) {
                        Row {
                            Action(stringResource(R.string.runs_export), onClick = { model.exportRuns() })
                            Action(stringResource(R.string.runs_clear), onClick = { clearing = true }, destructive = true)
                        }
                    }
                },
            ) {
                if (models.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.row)) {
                        ModelChip(stringResource(R.string.runs_all), filter == null) { filter = null }
                        models.forEach { id -> ModelChip(id, filter == id) { filter = id } }
                    }
                }
                if (shown.isEmpty()) {
                    Text(stringResource(R.string.runs_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // The newest hundred are drawn; the rest are in the export and the API.
                shown.take(SHOWN_RUNS).forEach { run -> key(run.id) { RunRow(run) } }
                Expandable(stringResource(R.string.runs_kept_title)) {
                    Text(stringResource(R.string.runs_kept), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (wide) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Dimens.gutter)) {
            LazyColumn(Modifier.weight(1f), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(Dimens.gap), content = main)
            LazyColumn(Modifier.weight(1f), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(Dimens.gap), content = side)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(Dimens.gap), content = { main(); side() })
    }

    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text(stringResource(R.string.runs_clear_title)) },
            text = { Text(stringResource(R.string.runs_clear_body)) },
            confirmButton = {
                Action(stringResource(R.string.runs_clear), onClick = {
                    model.clearHistory()
                    clearing = false
                }, destructive = true)
            },
            dismissButton = { Action(stringResource(R.string.action_cancel), onClick = { clearing = false }) },
        )
    }
}

@Composable
private fun ModelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

/** One run: what happened and how long it took at a glance; each phase and every check on a tap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunRow(run: JobRecord) {
    val tones = LocalTones.current
    var open by rememberSaveable(run.id) { mutableStateOf(false) }
    val outcome = Outcome.of(run)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val checks = remember(run) { Metrics.discrepancies(run) }
    val alias = remember(run.model) { ModelIds.aliasesFor(run.model).firstOrNull() ?: run.model }
    Column {
        Divider()
        Column(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = Dimens.row),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.row)) {
                Text(alias, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Chevron(open, 18.dp)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(outcome.words), style = MaterialTheme.typography.labelLarge, color = tones.of(outcome.mood).color)
                Text(Format.duration(run.totalMs), style = MaterialTheme.typography.bodySmall, color = muted)
                Text(remember(run.finishedAtMs) { Format.time(run.finishedAtMs, withSeconds = true) }, style = MaterialTheme.typography.bodySmall, color = muted)
            }
            val prefill = run.prefillTokensPerSecond.takeIf { it > 0 }?.let { stringResource(R.string.fig_rate, Format.rate(it)) } ?: stringResource(R.string.none_yet)
            val decode = run.decodeTokensPerSecond.takeIf { it > 0 }?.let { stringResource(R.string.fig_rate, Format.rate(it)) } ?: stringResource(R.string.none_yet)
            Text(stringResource(R.string.host_phase_rates, prefill, decode), style = MaterialTheme.typography.bodySmall, color = muted)
            Text(run.client, style = MaterialTheme.typography.bodySmall, color = muted)
            if (open) RunDetail(run, checks)
        }
    }
}

@Composable
private fun RunDetail(run: JobRecord, checks: List<Discrepancy>) {
    val tones = LocalTones.current
    Column(Modifier.padding(top = Dimens.row), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Line(stringResource(R.string.detail_model), run.model, mono = true)
        Line(stringResource(R.string.phase_queue), Format.duration(run.queueMs))
        if (run.loadMs > 0) Line(stringResource(R.string.phase_load), Format.duration(run.loadMs))
        Line(
            stringResource(R.string.phase_prompt),
            stringResource(R.string.phase_value, Format.duration(run.prefillMs), Format.count(run.promptTokens - run.cachedTokens), Format.rate(run.prefillTokensPerSecond)),
        )
        if (run.firstTokenMs > 0) Line(stringResource(R.string.phase_first_token), Format.duration(run.firstTokenMs))
        if (run.decodeMs > 0) {
            Line(
                stringResource(R.string.phase_writing),
                stringResource(R.string.phase_value, Format.duration(run.decodeMs), Format.count(run.completionTokens), Format.rate(run.decodeTokensPerSecond)),
            )
        }
        Line(stringResource(R.string.phase_total), Format.duration(run.totalMs))
        Line(stringResource(R.string.fact_cached), pluralStringResource(R.plurals.tokens, run.cachedTokens, Format.count(run.cachedTokens)))
        Line(
            stringResource(R.string.fig_device),
            listOfNotNull(
                run.threads?.let { stringResource(R.string.run_threads, it) },
                stringResource(Heat.of(run.thermal).words),
                run.batteryPercent?.let { stringResource(if (run.charging) R.string.fig_battery_charging else R.string.fig_battery, it) },
            ).joinToString(", "),
        )
        if (run.api.isNotEmpty()) {
            Line("API", stringResource(R.string.run_api, run.api, stringResource(if (run.stream) R.string.run_streamed else R.string.run_whole)))
        }
        Line(stringResource(R.string.detail_id), run.id, mono = true)
        // What the run's own figures say about each other.
        if (run.estimatedPromptTokens > 0) {
            Note(stringResource(R.string.check_estimated, Format.count(run.estimatedPromptTokens), Format.count(run.promptTokens)), MaterialTheme.colorScheme.onSurfaceVariant)
        }
        checks.forEach { check ->
            Note(
                stringResource(
                    when (check) {
                        Discrepancy.DECODE_CLOCKS -> R.string.check_decode_clocks
                        Discrepancy.PHASES_EXCEED_FIRST_TOKEN -> R.string.check_phases
                        Discrepancy.FIRST_TOKEN_AFTER_END -> R.string.check_first_token
                        Discrepancy.CACHE_EXCEEDS_PROMPT -> R.string.check_cache
                    },
                ),
                tones.attention.color,
            )
        }
    }
}

/**
 * The column labels for [RunRow]'s first line, once above the list rather than a unit on
 * every row: the model (with its client below), tokens in and out, total time, writing
 * speed in tokens a second.
 */
@Composable
fun RunHeader() {
    if (largeText()) return
    val style = MaterialTheme.typography.labelMedium
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.row)) {
        Text(stringResource(R.string.runs_col_identity), Modifier.weight(IDENTITY_WEIGHT), style = style, color = muted)
        Text(stringResource(R.string.runs_col_tokens), Modifier.weight(TOKENS_WEIGHT), style = style, color = muted, textAlign = TextAlign.End)
        Text(stringResource(R.string.runs_col_total), Modifier.weight(TIME_WEIGHT), style = style, color = muted, textAlign = TextAlign.End)
        Text(stringResource(R.string.runs_col_rate), Modifier.weight(RATE_WEIGHT), style = style, color = muted, textAlign = TextAlign.End)
    }
}

/** One of a run's figures, right-aligned in its column; a long one wraps rather than lose its unit. */
@Composable
private fun RowScope.Cell(value: String, weight: Float) {
    Text(value, Modifier.weight(weight), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
}

/** A label and a value in one aligned column; ids and model names, which are copied, in the mono. */
@Composable
private fun Line(label: String, value: String, mono: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
        Text(label, Modifier.width(LABEL_WIDTH), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = if (mono) Mono else MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Note(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** Each model beside the others, over ordinary traffic, with the number of runs behind every figure. */
@Composable
private fun ComparePanel(model: MainViewModel, ordinary: List<JobRecord>) {
    val summaries = remember(ordinary) { Metrics.summarize(ordinary) }
    val entries by model.installed.collectAsState()
    Panel(stringResource(R.string.compare_title)) {
        if (summaries.isEmpty()) Text(stringResource(R.string.runs_none), style = MaterialTheme.typography.bodyMedium)
        summaries.forEach { summary ->
            Divider()
            SummaryBlock(model, summary, entries.firstOrNull { it.id == summary.model }?.lab)
        }
        Expandable(stringResource(R.string.compare_how)) {
            Text(stringResource(R.string.compare_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SummaryBlock(model: MainViewModel, summary: ModelSummary, lab: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.row)) {
            LabMark(model, lab, 28.dp)
            Column(Modifier.weight(1f)) {
                Text(summary.model, style = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.compare_runs, Format.count(summary.runs), Format.count(summary.failed)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        summary.firstTokenMs?.let { spread ->
            Line(stringResource(R.string.compare_first_token), stringResource(R.string.spread, Format.duration(spread.median.toLong()), spread.count))
        }
        if (summary.prefill.isNotEmpty()) Line(stringResource(R.string.compare_prompt), buckets(summary.prefill))
        if (summary.decode.isNotEmpty()) Line(stringResource(R.string.compare_writing), buckets(summary.decode))
        if (summary.promptTokens > 0) {
            Line(
                stringResource(R.string.compare_cache),
                pluralStringResource(R.plurals.compare_cache_value, summary.cacheHits, Format.percent(summary.cachedTokens, summary.promptTokens), Format.count(summary.cacheHits)),
            )
        }
        if (summary.threads.isNotEmpty()) {
            val threads = summary.threads.sorted().joinToString(", ")
            Line(stringResource(R.string.compare_threads), if (summary.threads.size > 1) stringResource(R.string.compare_threads_mixed, threads) else threads)
        }
    }
}

@Composable
private fun buckets(rates: Map<ContextBucket, Spread>): String = ContextBucket.entries.mapNotNull { bucket ->
    rates[bucket]?.let { spread ->
        stringResource(R.string.bucket_rate, stringResource(bucket.words), Format.rate(spread.median), spread.count)
    }
}.joinToString("\n")

private val ContextBucket.words: Int get() = when (this) {
    ContextBucket.SHORT -> R.string.bucket_short
    ContextBucket.MEDIUM -> R.string.bucket_medium
    ContextBucket.LONG -> R.string.bucket_long
}

/** Pick a model, measure it the same way every time, see the latest result for each. */
@Composable
private fun BenchmarkPanel(model: MainViewModel, installed: List<String>, serving: Boolean, runs: List<JobRecord>) {
    val state by model.benchmark.collectAsState()
    var chosen by rememberSaveable { mutableStateOf(installed.firstOrNull()) }
    Panel(stringResource(R.string.bench_title)) {
        Text(stringResource(R.string.bench_note, Benchmark.DECODE_TOKENS), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (installed.size > 1) {
            MenuRow(stringResource(R.string.try_model), options = installed.map { it to it }, selected = chosen ?: installed.first(), onSelect = { chosen = it })
        }
        when (val current = state) {
            is BenchmarkState.Running -> {
                Text(stringResource(R.string.bench_progress, current.finished + 1, Benchmark.REPEATS), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { current.finished / Benchmark.REPEATS.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = LocalTones.current.working.color,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            else -> if (serving) {
                InkButton(stringResource(R.string.bench_run), onClick = { chosen?.let(model::runBenchmark) }, enabled = chosen != null)
            } else {
                Text(stringResource(R.string.bench_needs_server), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // The latest benchmark of each model, from the history: survives restarts like any run.
        runs.filter { it.api == Benchmark.API && it.finish != null }.groupBy { it.model }.forEach { (id, own) ->
            val latest = own.take(Benchmark.REPEATS)
            val prefill = Spread.of(latest.map { it.prefillTokensPerSecond }) ?: return@forEach
            val decode = Spread.of(latest.map { it.decodeTokensPerSecond }) ?: return@forEach
            val last = latest.first()
            Divider()
            Text(id, style = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(
                    R.string.bench_result,
                    Format.rate(prefill.median), Format.count(last.promptTokens),
                    Format.rate(decode.median), Format.count(last.completionTokens),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                listOfNotNull(
                    pluralStringResource(R.plurals.runs_count, latest.size, Format.count(latest.size)),
                    last.threads?.let { stringResource(R.string.run_threads, it) },
                    stringResource(Heat.of(last.thermal).words),
                    Format.time(last.finishedAtMs),
                ).joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val SHOWN_RUNS = 100
private const val IDENTITY_WEIGHT = 1.4f
private const val TOKENS_WEIGHT = 1.1f
private const val TIME_WEIGHT = 0.9f
private const val RATE_WEIGHT = 0.7f
private val LABEL_WIDTH = 112.dp
