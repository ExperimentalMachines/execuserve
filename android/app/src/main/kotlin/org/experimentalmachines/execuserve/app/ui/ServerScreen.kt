package org.experimentalmachines.execuserve.app.ui

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.experimentalmachines.execuserve.app.R
import org.experimentalmachines.execuserve.app.settings.Recovery
import org.experimentalmachines.execuserve.app.text.Format
import org.experimentalmachines.execuserve.engine.EngineStatus
import org.experimentalmachines.execuserve.engine.JobRecord
import org.experimentalmachines.execuserve.engine.LaneState
import org.experimentalmachines.execuserve.engine.ModelEntry
import org.experimentalmachines.execuserve.host.Benchmark
import org.experimentalmachines.execuserve.host.Choices
import org.experimentalmachines.execuserve.host.Heat
import org.experimentalmachines.execuserve.host.HostSettings
import org.experimentalmachines.execuserve.host.ModelNames
import org.experimentalmachines.execuserve.host.NetworkKind
import org.experimentalmachines.execuserve.host.ServeHost
import org.experimentalmachines.execuserve.host.ServerLook
import org.experimentalmachines.execuserve.server.ApiKey
import org.experimentalmachines.execuserve.server.BindMode

/** The listener is shared; every installed model has its own address and memory state. */
@Composable
fun ServerScreen(
    model: MainViewModel,
    padding: PaddingValues,
    wide: Boolean,
    openModels: () -> Unit,
    openRuns: () -> Unit,
    openSettings: () -> Unit,
    openChat: (String) -> Unit,
) {
    val server by model.server.collectAsState()
    val status by model.status.collectAsState()
    val settings by model.settings.collectAsState()
    val keys by model.keys.collectAsState()
    val installed by model.installed.collectAsState()
    val runs by model.runs.collectAsState()
    val current = settings ?: return
    val key = model.shareableKey(keys)
    val running = server as? ServeHost.State.Running
    val primary: LazyListScope.() -> Unit = {
        item(key = "status") {
            val recovery by model.recovery.collectAsState()
            var choosingStart by rememberSaveable { mutableStateOf(false) }
            StatusPanel(
                server,
                status,
                installed.size,
                if (current.threads == 0) current.maxResidentModels else 1,
                recovery,
                model::start,
                model::stop,
                openSettings,
                startsWith = current.startupModels { id -> installed.firstOrNull { it.id == id }?.let { ModelNames.shown(it, installed) } },
                onChooseStart = { choosingStart = true },
            )
            if (choosingStart) StartupSheet(installed, current, model, onDismiss = { choosingStart = false })
        }
        item(key = "models-heading") {
            PanelTitle(stringResource(R.string.host_models), trailing = { Action(stringResource(R.string.host_library), openModels) })
        }
        if (installed.isEmpty()) item(key = "empty") { EmptyModels(openModels) }
        val resident = status?.resident.orEmpty().map { it.id }.toSet()
        ModelNames.hostedOrder(installed, resident, current.defaultModel).forEach { entry ->
            item(key = "host-" + entry.id) {
                HostedModel(
                    entry,
                    ModelNames.shown(entry, installed),
                    status,
                    running,
                    runs.firstOrNull { it.model == entry.id && it.api != Benchmark.API },
                    model,
                    onChat = { openChat(entry.id) },
                )
            }
        }
    }
    val secondary: LazyListScope.() -> Unit = {
        item(key = "connect") { ConnectPanel(server, current, key, model) }
        item(key = "attention") { Attention() }
        item(key = "device") { DevicePanel(model) }
        item(key = "log") { LogPanel(runs.filter { it.api != Benchmark.API }.take(LATEST_RUNS), openRuns) }
    }
    PanelColumns(wide, padding, main = primary, side = secondary)
}

@Composable
private fun StatusPanel(
    server: ServeHost.State,
    status: EngineStatus?,
    installedCount: Int,
    capacity: Int,
    recovery: Recovery?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    openSettings: () -> Unit,
    startsWith: List<String>,
    onChooseStart: () -> Unit,
) {
    val look = ServerLook.of(server, status)
    val tone = LocalTones.current.of(look.mood)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dot(tone.color, 10.dp)
                    Text(stringResource(look.words), style = MaterialTheme.typography.titleLarge, color = tone.color)
                }
                Text(statusLine(server, look, status, installedCount, capacity), style = MaterialTheme.typography.bodyMedium)
            }
            when (server) {
                is ServeHost.State.Running -> OutlineButton(stringResource(R.string.action_stop), onStop)
                // Nothing to serve yet: the models panel below offers the catalog instead.
                is ServeHost.State.Stopped -> if (installedCount > 0) Button(onClick = onStart) { Text(stringResource(R.string.action_start)) }
                else -> Unit
            }
        }
        if ((status?.queued ?: 0) >
            0
        ) {
            Text(pluralStringResource(R.plurals.status_waiting, status!!.queued, status.queued), style = MaterialTheme.typography.bodySmall)
        }
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
        Text(stringResource(R.string.host_compute_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (installedCount > 0) StartupLine(installedCount, startsWith, onChooseStart, openSettings)
    }
}

/** The status card's second line: why it is not serving, or what it serves. */
@Composable
private fun statusLine(server: ServeHost.State, look: ServerLook, status: EngineStatus?, installedCount: Int, capacity: Int): String = when {
    installedCount == 0 && (server is ServeHost.State.Stopped || server is ServeHost.State.Running) -> stringResource(R.string.host_needs_model)
    server is ServeHost.State.Stopped -> server.error ?: stringResource(R.string.host_stopped)
    server == ServeHost.State.Starting -> stringResource(R.string.status_starting_hint)
    server == ServeHost.State.Stopping -> stringResource(R.string.status_stopping_hint)
    look == ServerLook.PAUSED_HOT -> stringResource(R.string.status_paused_hot)
    look == ServerLook.PAUSED_BATTERY -> stringResource(R.string.status_paused_battery)
    look == ServerLook.NOT_RESPONDING -> stringResource(R.string.alert_wedged_text)
    else -> pluralStringResource(R.plurals.host_summary, installedCount, installedCount, status?.resident?.size ?: 0, capacity)
}

/** What Start loads, said where Start is, with the way to change it beside it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartupLine(installedCount: Int, startsWith: List<String>, onChooseStart: () -> Unit, openSettings: () -> Unit) {
    Text(
        if (startsWith.isEmpty()) stringResource(R.string.host_starts_with_none) else stringResource(R.string.host_starts_with, startsWith.joinToString(", ")),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Action(stringResource(R.string.host_models_at_start), onChooseStart)
        if (installedCount > 1) Action(stringResource(R.string.host_memory_settings), openSettings)
    }
}

/** Metrics belong to a model, never to whichever model happened to finish most recently. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HostedModel(
    entry: ModelEntry,
    name: String,
    status: EngineStatus?,
    running: ServeHost.State.Running?,
    last: JobRecord?,
    model: MainViewModel,
    onChat: () -> Unit,
) {
    val loaded = status?.resident?.any { it.id == entry.id } == true
    val job = status?.running?.takeIf { it.model == entry.id }
    val broken = status?.broken?.get(entry.id)
    var connecting by rememberSaveable(entry.id) { mutableStateOf(false) }
    val tones = LocalTones.current
    val state = when {
        broken != null -> R.string.host_model_failed
        running == null -> R.string.host_offline
        job != null && status.lane == LaneState.LOADING -> R.string.host_loading
        job != null && status.lane == LaneState.PREFILLING -> R.string.host_prefilling
        job != null && status.lane == LaneState.GENERATING -> R.string.host_generating
        loaded -> R.string.host_ready
        else -> R.string.host_on_demand
    }
    val tone = when {
        broken != null -> tones.failed
        job != null -> tones.working
        loaded -> tones.good
        else -> tones.idle
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (job != null) {
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
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LabMark(model, entry.lab, 32.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(state), style = MaterialTheme.typography.labelLarge, color = tone.color)
            }
        }
        Text(
            listOfNotNull(
                stringResource(R.string.host_model_file_size, Format.bytes(entry.sizeBytes)),
                entry.contextLength?.let {
                    stringResource(R.string.host_model_context, Format.window(it))
                },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (broken != null) Text(broken, style = MaterialTheme.typography.bodySmall, color = tones.failed.color)
        if (job != null) {
            Text(
                stringResource(R.string.host_request, job.client, Format.duration((now - job.startedAtMs).coerceAtLeast(0))),
                style = MaterialTheme.typography.bodySmall,
            )
            if (status.lane == LaneState.PREFILLING && job.promptChars > 0) {
                LinearProgressIndicator(progress = {
                    (job.prefilledChars.toFloat() / job.promptChars).coerceIn(0f, 1f)
                }, modifier = Modifier.fillMaxWidth(), color = tone.color)
                Text(
                    stringResource(R.string.host_prompt_progress, Format.count(job.prefilledChars), Format.count(job.promptChars)),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = tone.color)
            }
        }
        FigurePair({
            Figure(
                stringResource(R.string.host_prefill),
                if (job != null && status.lane == LaneState.PREFILLING) {
                    Format.duration(job.prefillElapsedMs(now))
                } else {
                    last?.prefillTokensPerSecond?.takeIf { it > 0 }?.let { stringResource(R.string.fig_rate, Format.rate(it)) }
                        ?: stringResource(R.string.none_yet)
                },
                listOf(
                    if (job != null && status.lane == LaneState.PREFILLING) {
                        stringResource(R.string.host_reading_now)
                    } else {
                        last?.takeIf { it.prefillTokensPerSecond > 0 }?.let { stringResource(R.string.host_prefill_detail, Format.duration(it.prefillMs)) }
                            ?: stringResource(R.string.host_prefill_hint)
                    },
                ),
                Modifier.weight(1f),
            )
        }, {
            val live = job?.decodeRate(now)
            Figure(
                stringResource(R.string.host_decode),
                (live ?: last?.decodeTokensPerSecond?.takeIf { it > 0 })?.let { stringResource(R.string.fig_rate, Format.rate(it)) }
                    ?: stringResource(R.string.none_yet),
                listOf(
                    if (live !=
                        null
                    ) {
                        stringResource(R.string.fig_speed_now)
                    } else {
                        last?.takeIf { it.decodeTokensPerSecond > 0 }?.let { stringResource(R.string.host_decode_detail, Format.duration(it.decodeMs)) }
                            ?: stringResource(R.string.host_decode_hint)
                    },
                ),
                Modifier.weight(1f),
            )
        })
        // Unload is offered whenever the model is in memory, a reply running or not: it waits
        // its turn in the queue, so a reply in progress finishes first.
        var unloading by remember(entry.id) { mutableStateOf(false) }
        if (!loaded) unloading = false
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running != null) {
                if (job != null) Action(stringResource(R.string.action_cancel), { model.cancelJob(job.id) })
                when {
                    loaded -> Action(
                        stringResource(if (job != null) R.string.host_unload_after else R.string.host_unload),
                        {
                            unloading = true
                            model.unload(entry.id)
                        },
                        enabled = !unloading,
                    )
                    job == null -> Action(stringResource(R.string.host_load), { model.load(entry.id) }, enabled = status?.lane != LaneState.LOADING)
                }
                Action(stringResource(R.string.host_chat), onChat)
                Action(stringResource(if (connecting) R.string.host_close_connection else R.string.host_connect), { connecting = !connecting })
            }
        }
        if (running != null) {
            Text(
                stringResource(if (loaded) R.string.host_unload_note else R.string.host_unloaded_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (running != null && connecting) {
            running.endpoints.forEach { endpoint ->
                val api = org.experimentalmachines.execuserve.host.ModelEndpoints.api(endpoint.url, entry.id)
                val browser = org.experimentalmachines.execuserve.host.ModelEndpoints.browser(endpoint.url, entry.id)
                Text(stringResource(endpoint.network.words), style = MaterialTheme.typography.labelLarge)
                CopyRow(api, label = stringResource(R.string.host_model_api), qr = true)
                CopyRow(browser, label = stringResource(R.string.connect_browser_chat), qr = true)
            }
            CopyRow(entry.id, label = stringResource(R.string.host_model_id), qr = true)
            Text(stringResource(R.string.host_shared_key), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
            }
        }
        if (needsNotifications) {
            Check(stringResource(R.string.attention_notifications), stringResource(R.string.action_allow)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
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
private fun ConnectPanel(server: ServeHost.State, settings: HostSettings, key: ApiKey?, model: MainViewModel) {
    val running = server as? ServeHost.State.Running
    Panel(stringResource(R.string.host_connection)) {
        ChoiceRow(
            stringResource(R.string.connect_who),
            options = BindMode.entries.map {
                it to stringResource(it.words)
            },
            selected = settings.bind,
            onSelect = model::setBind,
        )
        if (settings.bind ==
            BindMode.NETWORK
        ) {
            Text(stringResource(R.string.connect_plain_http), style = MaterialTheme.typography.bodySmall, color = LocalTones.current.attention.color)
        }
        if (running != null && running.settings.bind == BindMode.NETWORK && running.endpoints.none { it.network != NetworkKind.THIS_DEVICE }) {
            Text(stringResource(R.string.connect_no_network), style = MaterialTheme.typography.bodySmall, color = LocalTones.current.attention.color)
        }
        Expandable(stringResource(R.string.host_access_key), stringResource(R.string.host_access_key_hint)) {
            if (key != null) {
                CopyRow(key.secret, label = key.name, shown = key.secret.take(KEY_HEAD) + "…" + key.secret.takeLast(KEY_TAIL), qr = true, sensitive = true)
            } else {
                Text(stringResource(R.string.host_no_key), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (running != null) {
            Expandable(stringResource(R.string.host_all_models_endpoint), stringResource(R.string.host_all_models_hint)) {
                running.endpoints.forEach { endpoint ->
                    CopyRow(endpoint.url, label = stringResource(endpoint.network.words), qr = true)
                    CopyRow(endpoint.url.removeSuffix("/v1").trimEnd('/') + "/", label = stringResource(R.string.connect_browser_chat), qr = true)
                }
                if (key != null) {
                    val base = running.endpoints.first().url
                    Action(stringResource(R.string.action_share), { model.shareConnection(base, key.secret, null) })
                }
            }
        }
        Expandable(stringResource(R.string.connect_local_title)) {
            Text(stringResource(R.string.connect_local_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CopyRow(CLEARTEXT_CONFIG)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DevicePanel(model: MainViewModel) {
    val environment by model.environment.collectAsState()
    val memory by model.freeMemory.collectAsState()
    val status by model.status.collectAsState()
    val heat = Heat.of(environment.thermal)
    Panel {
        Expandable(stringResource(R.string.host_device), stringResource(heat.words)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Fact(stringResource(R.string.host_memory_free), Format.bytes(memory))
                environment.batteryPercent?.let { Fact(stringResource(R.string.host_battery), "$it%") }
                Fact(stringResource(R.string.total_served), Format.count(status?.totals?.completed ?: 0))
                Fact(stringResource(R.string.total_failed), Format.count(status?.totals?.failed ?: 0))
            }
        }
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
        val names = remember(latest) { historyNames(latest.map { it.model }.distinct()) }
        latest.forEach { run -> key(run.id) { RunRow(run, names.getValue(run.model)) } }
    }
}

// ---------------------------------------------------------------------------------------
// Try it
// ---------------------------------------------------------------------------------------

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

/**
 * Which models Start loads, and how many may be in memory at once, from Hosting where Start is.
 * The same settings as Settings' hosting panel and the Library's "Load at start".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartupSheet(installed: List<ModelEntry>, settings: HostSettings, model: MainViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.navigationBarsPadding().padding(horizontal = Dimens.gutter).padding(bottom = Dimens.gutter),
            verticalArrangement = Arrangement.spacedBy(Dimens.row),
        ) {
            Text(stringResource(R.string.settings_startup_models), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_startup_models_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            installed.forEach { entry ->
                SwitchRow(
                    ModelNames.shown(entry, installed),
                    processorLabel(entry.backend),
                    entry.id in settings.preloadModels || entry.id == settings.defaultModel,
                ) { on ->
                    model.update {
                        it.copy(
                            defaultModel = it.defaultModel.takeUnless { id -> !on && id == entry.id },
                            preloadModels = if (on) it.preloadModels + entry.id else it.preloadModels - entry.id,
                        )
                    }
                }
            }
            MenuRow(
                stringResource(R.string.settings_resident),
                stringResource(R.string.settings_resident_note),
                options = Choices.RESIDENT_MODELS.map { it to it.toString() },
                selected = settings.maxResidentModels,
                onSelect = { count -> model.update { it.copy(maxResidentModels = count, threads = if (count > 1) 0 else it.threads) } },
            )
        }
    }
}
