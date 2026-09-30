package org.experimentalmachines.execuserve.app.ui

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.experimentalmachines.execuserve.app.R
import org.experimentalmachines.execuserve.app.settings.Recovery
import org.experimentalmachines.execuserve.app.text.Format
import org.experimentalmachines.execuserve.engine.EngineStatus
import org.experimentalmachines.execuserve.engine.Environment
import org.experimentalmachines.execuserve.engine.JobRecord
import org.experimentalmachines.execuserve.engine.LaneState
import org.experimentalmachines.execuserve.engine.ModelEntry
import org.experimentalmachines.execuserve.host.Benchmark
import org.experimentalmachines.execuserve.host.ConsoleTest
import org.experimentalmachines.execuserve.host.Heat
import org.experimentalmachines.execuserve.host.HostSettings
import org.experimentalmachines.execuserve.host.Mood
import org.experimentalmachines.execuserve.host.NetworkKind
import org.experimentalmachines.execuserve.host.Outcome
import org.experimentalmachines.execuserve.host.ServeHost
import org.experimentalmachines.execuserve.host.ServerLook
import org.experimentalmachines.execuserve.server.ApiKey
import org.experimentalmachines.execuserve.server.BindMode

/**
 * The console. On a phone one column: the state and what it is doing, anything that needs
 * fixing, how to connect, the figures, a test request, the log. From 600 dp the figures and
 * the log move to a second column.
 */
@Composable
fun ServerScreen(model: MainViewModel, padding: PaddingValues, wide: Boolean, openModels: () -> Unit, openRuns: () -> Unit) {
    val server by model.server.collectAsState()
    val status by model.status.collectAsState()
    val settings by model.settings.collectAsState()
    val keys by model.keys.collectAsState()
    val installed by model.installed.collectAsState()
    val environment by model.environment.collectAsState()
    val memory by model.freeMemory.collectAsState()
    val current = settings ?: return
    val key = model.shareableKey(keys)
    val running = server as? ServeHost.State.Running

    val primary: LazyListScope.() -> Unit = {
        item(key = "status") {
            val recovery by model.recovery.collectAsState()
            StatusPanel(server, status, recovery, model::start, model::stop, model::cancelJob)
        }
        item(key = "attention") { Attention() }
        if (installed.isEmpty()) item(key = "empty") { EmptyModels(openModels) }
        // On a phone the figures come straight after the state: what is running and how fast
        // is what a returning person looks for first. Wide, they head the second column.
        if (!wide) item(key = "figures") { Figures(server, status, installed, environment, memory, model) }
        item(key = "connect") { ConnectPanel(server, current, key, installed, model) }
        if (running != null && installed.isNotEmpty()) {
            item(key = "try") { TryPanel(model, running, current, key, installed, status) }
        }
    }
    val secondary: LazyListScope.() -> Unit = {
        if (wide) item(key = "figures") { Figures(server, status, installed, environment, memory, model) }
        item(key = "log") {
            val runs by model.runs.collectAsState()
            LogPanel(remember(runs) { runs.filter { it.api != Benchmark.API }.take(LATEST_RUNS) }, openRuns)
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Dimens.gutter)) {
            LazyColumn(Modifier.weight(1.1f), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(Dimens.gap), content = primary)
            LazyColumn(Modifier.weight(1f), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(Dimens.gap), content = secondary)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(Dimens.gap)) {
            primary()
            secondary()
        }
    }
}

// ---------------------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------------------

/**
 * The server's state, in its colour, with what it is doing now: the request being answered
 * (and a way to cancel it), the queue behind it, and the totals since it started.
 */
@Composable
private fun StatusPanel(
    server: ServeHost.State,
    status: EngineStatus?,
    recovery: Recovery?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: (String) -> Unit,
) {
    val look = ServerLook.of(server, status)
    val tone = LocalTones.current.of(look.mood)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Dot(tone.color, 12.dp)
            Column(Modifier.weight(1f)) {
                Text(stringResource(look.words), style = MaterialTheme.typography.titleLarge, color = tone.color)
                Text(statusDetail(server, status, look), style = MaterialTheme.typography.bodyMedium)
            }
            when (server) {
                is ServeHost.State.Running -> OutlineButton(stringResource(R.string.action_stop), onStop)
                is ServeHost.State.Stopped -> Button(onClick = onStart) { Text(stringResource(R.string.action_start)) }
                else -> Unit
            }
        }
        val job = status?.running
        if (job != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(R.plurals.tokens, job.generatedTokens, Format.count(job.generatedTokens)),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Action(stringResource(R.string.action_cancel), onClick = { onCancel(job.id) })
            }
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = tone.color, trackColor = MaterialTheme.colorScheme.surfaceVariant)
        }
        // Android brought the server back after its process died: say so, so an uptime that
        // restarted does not read as one that never stopped.
        if (server is ServeHost.State.Running && recovery != null) {
            Text(
                stringResource(
                    if (recovery.afterWedge) R.string.status_recovered_wedge else R.string.status_recovered,
                    remember(recovery.atMs) { Format.time(recovery.atMs) },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = LocalTones.current.attention.color,
            )
        }
        if ((status?.queued ?: 0) > 0) {
            Text(pluralStringResource(R.plurals.status_waiting, status!!.queued, status.queued), style = MaterialTheme.typography.bodyMedium)
        }
        if (server is ServeHost.State.Running && status != null) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(Dimens.gutter)) {
                Fact(stringResource(R.string.total_since), remember(server.startedAtMs) { Format.time(server.startedAtMs) }, labelColor = muted)
                Fact(stringResource(R.string.total_served), Format.count(status.totals.completed), labelColor = muted)
                Fact(stringResource(R.string.total_failed), Format.count(status.totals.failed), labelColor = muted)
                Fact(stringResource(R.string.total_refused), Format.count(status.totals.refused), labelColor = muted)
            }
        }
    }
}

@Composable
private fun statusDetail(server: ServeHost.State, status: EngineStatus?, look: ServerLook): String = when (server) {
    is ServeHost.State.Running -> when {
        status == null -> stringResource(R.string.status_engine_starting)
        look == ServerLook.PAUSED_HOT -> stringResource(R.string.status_paused_hot)
        look == ServerLook.PAUSED_BATTERY -> stringResource(R.string.status_paused_battery)
        look == ServerLook.NOT_RESPONDING -> stringResource(R.string.alert_wedged_text)
        status.lane == LaneState.LOADING -> stringResource(R.string.status_loading, status.running?.model ?: stringResource(R.string.status_a_model))
        status.lane == LaneState.PREFILLING -> stringResource(R.string.status_reading, status.running?.client.orEmpty())
        status.lane == LaneState.GENERATING -> stringResource(R.string.status_writing, status.running?.client.orEmpty())
        else -> stringResource(R.string.status_ready)
    }
    is ServeHost.State.Stopped -> server.error ?: stringResource(R.string.status_stopped_hint)
    ServeHost.State.Starting -> stringResource(R.string.status_starting_hint)
    ServeHost.State.Stopping -> stringResource(R.string.status_stopping_hint)
}

/**
 * What stands between this server and running with the screen off, shown only when
 * something needs doing. On the POCO the foreground service alone kept it reachable in Doze
 * with no exemptions; the one setting that breaks it is Restricted battery usage.
 */
@Composable
private fun Attention() {
    val context = LocalContext.current
    val tones = LocalTones.current
    // The permission sheet pauses the app, so its answer arrives as a resume.
    val resumes = resumeCount()
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val restricted = remember(resumes) { context.getSystemService(ActivityManager::class.java).isBackgroundRestricted }
    val needsNotifications = remember(resumes) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }
    if (!restricted && !needsNotifications) return
    Panel(stringResource(R.string.attention_title), tone = if (restricted) tones.failed else tones.attention) {
        if (restricted) {
            Check(stringResource(R.string.attention_restricted), stringResource(R.string.action_fix)) {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }
        }
        if (needsNotifications) {
            Check(stringResource(R.string.attention_notifications), stringResource(R.string.action_allow)) {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

/** A thing to fix and the button that fixes it; stacked when large text would crowd them. */
@Composable
private fun Check(text: String, action: String, onClick: () -> Unit) {
    if (largeText()) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.row)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            OutlineButton(action, onClick)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.row)) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            OutlineButton(action, onClick)
        }
    }
}

@Composable
private fun EmptyModels(openModels: () -> Unit) {
    Panel(stringResource(R.string.empty_title)) {
        Text(stringResource(R.string.empty_body), style = MaterialTheme.typography.bodyMedium)
        InkButton(stringResource(R.string.empty_action), openModels)
    }
}

// ---------------------------------------------------------------------------------------
// Connect
// ---------------------------------------------------------------------------------------

/**
 * Everything another app needs, and the one choice that decides who that can be. The
 * switch is live: it saves the setting and, while serving, restarts on the new address.
 */
@Composable
private fun ConnectPanel(
    server: ServeHost.State,
    settings: HostSettings,
    key: ApiKey?,
    installed: List<ModelEntry>,
    model: MainViewModel,
) {
    val context = LocalContext.current
    val tones = LocalTones.current
    val running = server as? ServeHost.State.Running
    val loaded = installed.firstOrNull { it.id == settings.defaultModel } ?: installed.firstOrNull()
    Panel(stringResource(R.string.connect_title)) {
        ChoiceRow(
            stringResource(R.string.connect_who),
            options = BindMode.entries.map { it to stringResource(it.words) },
            selected = settings.bind,
            onSelect = { model.setBind(it) },
        )
        if (settings.bind == BindMode.NETWORK) {
            Text(stringResource(R.string.connect_plain_http), style = MaterialTheme.typography.bodySmall, color = tones.attention.color)
        }
        if (running == null) {
            val starts = if (settings.bind == BindMode.NETWORK) R.string.connect_starts_network else R.string.connect_starts_loopback
            Text(stringResource(starts, settings.port), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val reachable = running.endpoints.any { it.network != NetworkKind.THIS_DEVICE }
            if (running.settings.bind == BindMode.NETWORK && !reachable) {
                Text(stringResource(R.string.connect_no_network), style = MaterialTheme.typography.bodyMedium, color = tones.attention.color)
            }
            running.endpoints.forEachIndexed { index, endpoint ->
                // Which network an address is on matters only when there is more than one.
                val label = if (running.settings.bind == BindMode.NETWORK) stringResource(endpoint.network.words) else null
                CopyRow(endpoint.url, label = label, prominent = index == 0)
            }
        }
        if (key != null) {
            var shown by rememberSaveable { mutableStateOf(false) }
            CopyRow(
                value = key.secret,
                label = stringResource(R.string.connect_key, key.name),
                shown = if (shown) key.secret else key.secret.take(KEY_HEAD) + "…" + key.secret.takeLast(KEY_TAIL),
                extra = { Action(stringResource(if (shown) R.string.action_hide else R.string.action_show), onClick = { shown = !shown }) },
            )
        }
        if (running != null && key != null) {
            val base = running.endpoints.first().url
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.row), verticalArrangement = Arrangement.spacedBy(Dimens.row)) {
                InkButton(stringResource(R.string.action_share), onClick = {
                    model.shareConnection(base, key.secret, loaded?.let { it.aliases.firstOrNull() ?: it.id })
                })
                OutlineButton(stringResource(R.string.connect_copy_terminal), onClick = { copy(context, model.terminalExports(base, key.secret)) })
            }
        }
        Expandable(stringResource(R.string.connect_local_title)) {
            Text(stringResource(R.string.connect_local_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CopyRow(CLEARTEXT_CONFIG)
        }
    }
}

// ---------------------------------------------------------------------------------------
// Figures
// ---------------------------------------------------------------------------------------

/** The four numbers a server is watched by, as one panel: two by two, whatever the width. */
@Composable
private fun Figures(
    server: ServeHost.State,
    status: EngineStatus?,
    installed: List<ModelEntry>,
    environment: Environment,
    freeMemory: Long,
    model: MainViewModel,
) {
    val tones = LocalTones.current
    // The latest reply from the history, so the figures survive a restart like the runs do.
    val runs by model.runs.collectAsState()
    val last = remember(runs) { runs.firstOrNull { it.api != Benchmark.API && it.completionTokens > 0 } }
    val resident = status?.resident?.firstOrNull()
    val job = status?.running
    // A clock only while a reply is being written, for the live rate.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (job != null && job.firstTokenAtMs > 0) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(job.id) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(TICK_MS)
                }
            }
        }
    }
    val entry = installed.firstOrNull { it.id == resident?.id }
    Panel {
        FigurePair({
            Figure(
                stringResource(R.string.fig_model),
                resident?.let { entry?.aliases?.firstOrNull() ?: it.id } ?: stringResource(R.string.none),
                when {
                    resident != null -> listOfNotNull(
                        resident.contextLength?.let { stringResource(R.string.fig_window, Format.window(it)) },
                        entry?.let { Format.bytes(it.sizeBytes) },
                    )
                    server is ServeHost.State.Running -> listOf(stringResource(R.string.fig_model_first_request))
                    else -> listOf(stringResource(R.string.fig_model_not_loaded))
                },
                Modifier.weight(1f),
            )
        }, {
            val live = job?.decodeRate(now)
            Figure(
                stringResource(R.string.fig_speed),
                (live ?: last?.decodeTokensPerSecond?.takeIf { it > 0 })?.let { stringResource(R.string.fig_rate, Format.rate(it)) }
                    ?: stringResource(R.string.none_yet),
                when {
                    live != null -> listOf(stringResource(R.string.fig_speed_now))
                    last != null -> listOfNotNull(
                        stringResource(R.string.fig_speed_last),
                        last.prefillTokensPerSecond.takeIf { it > 0 }?.let { stringResource(R.string.fig_speed_prompt, Format.rate(it)) },
                    )
                    else -> listOf(stringResource(R.string.fig_speed_none))
                },
                Modifier.weight(1f),
            )
        })
        Divider()
        FigurePair({
            Figure(
                stringResource(R.string.fig_cache),
                last?.takeIf { it.promptTokens > 0 }?.let { Format.percent(it.cachedTokens.toLong(), it.promptTokens.toLong()) }
                    ?: stringResource(R.string.none_yet),
                listOf(
                    last?.let { stringResource(R.string.fig_cache_detail, Format.count(it.cachedTokens), Format.count(it.promptTokens)) }
                        ?: stringResource(R.string.fig_cache_none),
                ),
                Modifier.weight(1f),
            )
        }, {
            val heat = Heat.of(environment.thermal)
            Figure(
                stringResource(R.string.fig_device),
                stringResource(heat.words),
                listOfNotNull(
                    environment.batteryPercent?.let {
                        stringResource(if (environment.charging) R.string.fig_battery_charging else R.string.fig_battery, it)
                    },
                    freeMemory.takeIf { it > 0 }?.let { stringResource(R.string.fig_memory, Format.bytes(it)) },
                ),
                Modifier.weight(1f),
                valueColor = if (heat.mood == Mood.GOOD) Color.Unspecified else tones.of(heat.mood).color,
            )
        })
    }
}

// ---------------------------------------------------------------------------------------
// Log
// ---------------------------------------------------------------------------------------

/** The latest few requests, from the history; the rest are on the Runs tab. */
@Composable
private fun LogPanel(latest: List<JobRecord>, openRuns: () -> Unit) {
    Panel(stringResource(R.string.log_title), trailing = { Action(stringResource(R.string.runs_see_all), onClick = openRuns) }) {
        if (latest.isEmpty()) {
            Text(stringResource(R.string.none_yet), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (latest.isNotEmpty()) RunHeader()
        latest.forEach { run -> key(run.id) { RunRow(run) } }
    }
}

// ---------------------------------------------------------------------------------------
// Try it
// ---------------------------------------------------------------------------------------

/**
 * One request to this server from the console, streamed or whole. Not a chat: a single
 * prompt, to see the endpoint answer the way a client would and what it cost.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TryPanel(
    model: MainViewModel,
    running: ServeHost.State.Running,
    settings: HostSettings,
    key: ApiKey?,
    installed: List<ModelEntry>,
    status: EngineStatus?,
) {
    val tones = LocalTones.current
    val state by model.tryState.collectAsState()
    val defaultPrompt = stringResource(R.string.try_prompt_default)
    var prompt by rememberSaveable { mutableStateOf(defaultPrompt) }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val target = chosen?.takeIf { id -> installed.any { it.id == id } }
        ?: status?.resident?.firstOrNull()?.id ?: settings.defaultModel ?: installed.first().id
    // Collapsed until wanted: a test is an occasional act, and open it would push the figures and the log down.
    Panel {
        Expandable(stringResource(R.string.try_title), stringResource(R.string.try_subtitle)) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5,
                label = { Text(stringResource(R.string.try_prompt)) },
            )
            if (installed.size > 1) {
                MenuRow(
                    stringResource(R.string.try_model),
                    options = installed.map { it.id to (it.aliases.firstOrNull() ?: it.id) },
                    selected = target,
                    onSelect = { chosen = it },
                )
            }
            SwitchRow(
                stringResource(R.string.try_stream),
                stringResource(if (state.stream) R.string.try_stream_on else R.string.try_stream_off),
                state.stream,
            ) { model.setTryStream(it) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.running) {
                    OutlineButton(stringResource(R.string.action_stop), model::stopTry)
                    Text(
                        stringResource(if (state.stream || state.content.isNotEmpty()) R.string.try_writing else R.string.try_waiting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    InkButton(stringResource(R.string.action_send), onClick = { model.runTry(prompt, target) }, enabled = prompt.isNotBlank())
                    Text(
                        stringResource(R.string.try_limit, ConsoleTest.MAX_TOKENS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.running && state.content.isEmpty() && state.reasoning.isEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = tones.working.color, trackColor = tones.working.container)
            }
            if (state.reasoning.isNotEmpty()) {
                Expandable(
                    stringResource(R.string.try_reasoning),
                    pluralStringResource(R.plurals.characters, state.reasoning.length, Format.count(state.reasoning.length)),
                ) {
                    SelectionContainer {
                        Text(state.reasoning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.content.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer { Text(state.content, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
                }
            }
            if (state.stopped) {
                Text(stringResource(R.string.try_stopped), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.failure?.let { failure ->
                Text(
                    failure.status?.let { stringResource(R.string.try_http_error, it, failure.message.orEmpty()) } ?: failure.message.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = tones.failed.color,
                )
            }
            state.result?.let { result ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.gutter), verticalArrangement = Arrangement.spacedBy(Dimens.row)) {
                    // A whole reply arrives at once, so its first-token time would repeat the total.
                    if (state.stream) Fact(stringResource(R.string.fact_first_token), Format.duration(result.firstTokenMs))
                    Fact(stringResource(R.string.fact_total), Format.duration(result.totalMs))
                    Fact(stringResource(R.string.fact_tokens), stringResource(R.string.fact_tokens_value, Format.count(result.promptTokens), Format.count(result.completionTokens)))
                    if (result.decodeTokensPerSecond > 0) Fact(stringResource(R.string.fact_speed), stringResource(R.string.fig_rate, Format.rate(result.decodeTokensPerSecond)))
                    if (result.cachedTokens > 0) Fact(stringResource(R.string.fact_cached), Format.count(result.cachedTokens))
                    result.finish?.let { finish ->
                        val outcome = Outcome.of(finish)
                        Fact(stringResource(R.string.fact_finish), stringResource(outcome.words), valueColor = tones.of(outcome.mood).color)
                    }
                }
            }
            val curl = ConsoleTest.curl(running.endpoints.first().url, key?.secret ?: "KEY", target, prompt, state.stream)
            Expandable(stringResource(R.string.try_terminal), stringResource(if (state.stream) R.string.try_terminal_stream else R.string.try_terminal_whole)) {
                CopyRow(curl)
            }
        }
    }
}

/**
 * What another app on this phone puts in `res/xml/network_security_config.xml` to call
 * plain HTTP on loopback. Android blocks cleartext for apps targeting API 28 and up.
 */
private const val CLEARTEXT_CONFIG = """<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
</network-security-config>"""

private const val TICK_MS = 500L
// A key shows its ends, so a person can tell keys apart without revealing one.
private const val KEY_HEAD = 6
private const val KEY_TAIL = 4
private const val LATEST_RUNS = 5
