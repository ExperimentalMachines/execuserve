package org.experimentalmachines.execuserve.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.experimentalmachines.execuserve.app.R
import org.experimentalmachines.execuserve.app.text.Format
import org.experimentalmachines.execuserve.catalog.ModelIds
import org.experimentalmachines.execuserve.engine.Discrepancy
import org.experimentalmachines.execuserve.engine.JobRecord
import org.experimentalmachines.execuserve.engine.Metrics
import org.experimentalmachines.execuserve.host.Benchmark
import org.experimentalmachines.execuserve.host.Heat
import org.experimentalmachines.execuserve.host.ModelNames
import org.experimentalmachines.execuserve.host.Outcome

/**
 * Every request the server finished, across restarts: the one running now, then the most
 * recent, a page at a time.
 */
@Composable
fun RunsScreen(model: MainViewModel, padding: PaddingValues, wide: Boolean) {
    val runs by model.runs.collectAsState()
    val installed by model.installed.collectAsState()
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    // A page at a time: the history keeps hundreds, and a look at the latest draws only those.
    var limit by rememberSaveable(filter) { mutableIntStateOf(PAGE) }
    var clearing by remember { mutableStateOf(false) }
    val ordinary = remember(runs) { runs.filter { it.api != Benchmark.API } }
    val shown = remember(ordinary, filter) { if (filter == null) ordinary else ordinary.filter { it.model == filter } }
    val models = remember(ordinary) { ordinary.map { it.model }.distinct() }
    val names = remember(models) { historyNames(models) }
    val page = remember(shown, limit) { shown.take(limit) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.fillMaxSize().then(if (wide) Modifier.widthIn(max = WIDE_LIST) else Modifier), contentPadding = padding) {
            // What is happening now, above what has finished: a long request is not missing.
            item(key = "now") { NowPanel(model, installed, Modifier.padding(bottom = Dimens.gap)) }
            item(key = "head") {
                RunsHead(
                    count = shown.size,
                    any = runs.isNotEmpty(),
                    onExport = { model.exportRuns() },
                    onClear = { clearing = true },
                ) {
                    if (models.size > 1) ModelFilter(models, names, filter) { filter = it }
                }
            }
            // Each request is its own item, drawn as it scrolls in, inside one continuous card.
            items(page, key = { it.id }) { run ->
                PanelPart {
                    RunRow(run, names.getValue(run.model))
                }
            }
            item(key = "foot") {
                RunsFoot(more = shown.size - page.size) { limit += PAGE }
            }
        }
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

/** The list's title, its count and actions, the model filter, or that there is nothing yet. */
@Composable
private fun RunsHead(count: Int, any: Boolean, onExport: () -> Unit, onClear: () -> Unit, filters: @Composable () -> Unit) {
    PanelPart(top = true) {
        Column(Modifier.padding(top = Dimens.gutter), verticalArrangement = Arrangement.spacedBy(Dimens.row)) {
            PanelTitle(
                pluralStringResource(R.plurals.runs_count, count, Format.count(count)),
                trailing = {
                    if (any) {
                        Row {
                            Action(stringResource(R.string.runs_export), onClick = onExport)
                            Action(stringResource(R.string.runs_clear), onClick = onClear, destructive = true)
                        }
                    }
                },
            )
            filters()
            if (count == 0) {
                Text(
                    stringResource(R.string.runs_none),
                    Modifier.padding(bottom = Dimens.row),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Show more, while there is more, and where the history is kept. */
@Composable
private fun RunsFoot(more: Int, onMore: () -> Unit) {
    PanelPart(bottom = true) {
        Column(Modifier.padding(bottom = Dimens.gutter), verticalArrangement = Arrangement.spacedBy(Dimens.row)) {
            if (more > 0) {
                Divider()
                Action(stringResource(R.string.runs_show_more, Format.count(minOf(more, PAGE)), Format.count(more)), onClick = onMore)
            }
            Expandable(stringResource(R.string.runs_kept_title)) {
                Text(stringResource(R.string.runs_kept), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelFilter(models: List<String>, names: Map<String, String>, filter: String?, onFilter: (String?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.row)) {
        ModelChip(stringResource(R.string.runs_all), filter == null) { onFilter(null) }
        models.forEach { id -> ModelChip(names.getValue(id), filter == id) { onFilter(id) } }
    }
}

/**
 * A slice of one [Panel] split across lazy items: the same surface and side padding, rounded
 * only at the [top] of the first slice and the [bottom] of the last.
 */
@Composable
private fun PanelPart(top: Boolean = false, bottom: Boolean = false, content: @Composable () -> Unit) {
    val corner = Dimens.corner
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(
            topStart = if (top) corner else 0.dp,
            topEnd = if (top) corner else 0.dp,
            bottomStart = if (bottom) corner else 0.dp,
            bottomEnd = if (bottom) corner else 0.dp,
        ),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(Modifier.padding(horizontal = Dimens.gutter)) { content() }
    }
}

/** History names: the alias, or the full id where two exports share it (see [ModelNames.shown]). */
internal fun historyNames(ids: Collection<String>): Map<String, String> = ModelNames.shown(ids) { ModelIds.aliasesFor(it).firstOrNull() }

/** A model filter; a full id is cut in the middle, so the window at its end still shows. */
@Composable
private fun ModelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.widthIn(max = 200.dp)) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

/** One run: what happened and how long it took at a glance; each phase and every check on a tap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunRow(run: JobRecord, name: String) {
    val tones = LocalTones.current
    var open by rememberSaveable(run.id) { mutableStateOf(false) }
    val outcome = Outcome.of(run)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val checks = remember(run) { Metrics.discrepancies(run) }
    Column {
        Divider()
        Column(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = Dimens.row),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.row)) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Chevron(open, 18.dp)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(outcome.words), style = MaterialTheme.typography.labelLarge, color = tones.of(outcome.mood).color)
                Text(Format.duration(run.totalMs), style = MaterialTheme.typography.bodySmall, color = muted)
                Text(
                    remember(run.finishedAtMs) {
                        Format.dateTime(run.finishedAtMs)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
            // Only the rates that were measured: a request that failed or wrote one token has fewer.
            val rates = listOfNotNull(
                run.prefillTokensPerSecond.takeIf { it > 0 }?.let { stringResource(R.string.chat_prefill, Format.rate(it)) },
                run.decodeTokensPerSecond.takeIf { it > 0 }?.let { stringResource(R.string.chat_decode, Format.rate(it)) },
            )
            if (rates.isNotEmpty()) Text(rates.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = muted)
            Text(clientName(run.client), style = MaterialTheme.typography.bodySmall, color = muted)
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
            stringResource(
                R.string.phase_value,
                Format.duration(run.prefillMs),
                Format.count(run.promptTokens - run.cachedTokens),
                Format.rate(run.prefillTokensPerSecond),
            ),
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
                run.threads?.let { pluralStringResource(R.plurals.run_threads, it, it) },
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
            Note(
                stringResource(R.string.check_estimated, Format.count(run.estimatedPromptTokens), Format.count(run.promptTokens)),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

private const val PAGE = 25
private val WIDE_LIST = 760.dp
private val LABEL_WIDTH = 112.dp

/** The request running now, how many wait, and Cancel; only while there is one. One runs at a time. */
@Composable
private fun NowPanel(model: MainViewModel, installed: List<org.experimentalmachines.execuserve.engine.ModelEntry>, modifier: Modifier = Modifier) {
    val status by model.status.collectAsState()
    val job = status?.running ?: return
    val name = installed.firstOrNull { it.id == job.model }?.let { org.experimentalmachines.execuserve.host.ModelNames.shown(it, installed) } ?: job.model
    val phase = when (status?.lane) {
        org.experimentalmachines.execuserve.engine.LaneState.LOADING -> stringResource(R.string.now_loading)
        org.experimentalmachines.execuserve.engine.LaneState.PREFILLING -> stringResource(R.string.now_reading)
        else -> stringResource(R.string.now_writing)
    }
    Panel(stringResource(R.string.now_title), modifier, trailing = { Action(stringResource(R.string.action_cancel), { model.cancelJob(job.id) }) }) {
        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            listOf(phase, clientName(job.client)).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val waiting = status?.queued ?: 0
        Text(
            pluralStringResource(R.plurals.now_waiting, waiting, waiting),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
