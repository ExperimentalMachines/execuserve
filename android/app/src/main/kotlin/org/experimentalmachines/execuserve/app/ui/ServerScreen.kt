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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import org.experimentalmachines.execuserve.engine.RunningJob
import org.experimentalmachines.execuserve.host.Benchmark
import org.experimentalmachines.execuserve.host.Choices
import org.experimentalmachines.execuserve.host.ConsoleChat
import org.experimentalmachines.execuserve.host.Heat
import org.experimentalmachines.execuserve.host.HostSettings
import org.experimentalmachines.execuserve.host.ModelEndpoints
import org.experimentalmachines.execuserve.host.ModelNames
import org.experimentalmachines.execuserve.host.NetworkKind
import org.experimentalmachines.execuserve.host.Outcome
import org.experimentalmachines.execuserve.host.ServeHost
import org.experimentalmachines.execuserve.host.ServerLook
import org.experimentalmachines.execuserve.server.ApiKey
import org.experimentalmachines.execuserve.server.BindMode

/**
 * Hosting: whether the server runs and what it is doing, how to connect to it, and which
 * installed models are in memory. Each concept has one name here and everywhere: installed
 * (files on the phone), in memory (open, answering without loading), memory limit (the most
 * models in memory at once), hosting (the server).
 */
@Composable
fun ServerScreen(model: MainViewModel, padding: PaddingValues, wide: Boolean, openModels: () -> Unit, openRuns: () -> Unit, openChat: (String) -> Unit) {
    val server by model.server.collectAsState()
    val status by model.status.collectAsState()
    val settings by model.settings.collectAsState()
    val keys by model.keys.collectAsState()
    val installed by model.installed.collectAsState()
    val runs by model.runs.collectAsState()
    val current = settings ?: return
    val key = model.shareableKey(keys)
    val running = server as? ServeHost.State.Running
    val limit = current.memoryLimit
    var editingMemory by rememberSaveable { mutableStateOf(false) }
    val primary: LazyListScope.() -> Unit = {
        item(key = "status") {
            val recovery by model.recovery.collectAsState()
            StatusPanel(
                server,
                status,
                installed,
                current,
                recovery,
                model::start,
                model::stop,
                onEditMemory = { editingMemory = true },
            )
        }
        // On a phone, problems come straight after the status; on a tablet, beside it.
        if (!wide) {
            item(key = "attention") { Attention() }
            if (installed.isNotEmpty()) item(key = "connect") { ConnectPanel(server, current, key, installed, model) }
        }
        item(key = "models-heading") {
            PanelTitle(stringResource(R.string.host_models), trailing = { Action(stringResource(R.string.host_library), openModels) })
        }
        if (installed.isEmpty()) {
            item(key = "empty") { EmptyModels(openModels) }
        } else if (running != null) {
            item(key = "models-note") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Note(stringResource(R.string.host_models_note))
                    // At the limit, what loading another model does, said once for the list.
                    EvictionNote(status, limit, installed)
                }
            }
        }
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
        if (wide) {
            if (installed.isNotEmpty()) item(key = "connect") { ConnectPanel(server, current, key, installed, model) }
            item(key = "attention") { Attention() }
        }
        item(key = "device") { DevicePanel(model, openRuns) }
    }
    PanelColumns(wide, padding, main = primary, side = secondary)
    if (editingMemory) MemorySheet(installed, current, running != null, model, onDismiss = { editingMemory = false })
}

/**
 * The server, line by line: its state and what it is doing, then what it holds. Stopped,
 * it says what that means for clients; either way it says what Start loads.
 */
@Composable
private fun StatusPanel(
    server: ServeHost.State,
    status: EngineStatus?,
    installed: List<ModelEntry>,
    settings: HostSettings,
    recovery: Recovery?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEditMemory: () -> Unit,
) {
    val look = ServerLook.of(server, status)
    val tone = LocalTones.current.of(look.mood)
    Panel {
        val state: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dot(tone.color, 10.dp)
                    Text(stringResource(look.words), style = MaterialTheme.typography.titleLarge, color = tone.color)
                }
                Text(activityLine(server, look, status, installed), style = MaterialTheme.typography.bodyMedium)
            }
        }
        val action: @Composable () -> Unit = {
            when (server) {
                is ServeHost.State.Running -> OutlineButton(stringResource(R.string.action_stop_hosting), onStop)
                // Nothing to serve yet: the models panel below offers the catalog instead.
                is ServeHost.State.Stopped -> if (installed.isNotEmpty()) Button(onClick = onStart) { Text(stringResource(R.string.action_start_hosting)) }
                else -> Unit
            }
        }
        // Beside the state while it fits; under it with large text, where the side column
        // left the state a word or two a line.
        if (largeText()) {
            state(Modifier.fillMaxWidth())
            action()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                state(Modifier.weight(1f))
                action()
            }
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
        if (installed.isNotEmpty()) {
            val inMemory = status?.resident?.size ?: 0
            CardLine(stringResource(R.string.host_fact_models), pluralStringResource(R.plurals.host_models_value, installed.size, installed.size, inMemory))
            CardLine(
                stringResource(R.string.host_fact_limit),
                pluralStringResource(R.plurals.memory_limit_models, settings.memoryLimit, settings.memoryLimit),
                action = stringResource(R.string.action_change) to onEditMemory,
                actionDescription = stringResource(R.string.change_memory_limit),
            )
            val names = settings.startupModels { id -> installed.firstOrNull { it.id == id }?.let { ModelNames.shown(it, installed) } }
                .take(settings.memoryLimit)
            CardLine(
                stringResource(R.string.host_fact_start),
                if (names.isEmpty()) stringResource(R.string.host_start_none) else stringResource(R.string.host_start_loads, names.joinToString(", ")),
                action = stringResource(R.string.action_change) to onEditMemory,
                actionDescription = stringResource(R.string.change_startup_models),
            )
        }
    }
}

/** A labelled line of the status card, with the action that changes it. */
@Composable
private fun CardLine(label: String, value: String, action: Pair<String, () -> Unit>? = null, actionDescription: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.row)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        action?.let { (text, onClick) ->
            Action(text, onClick, modifier = actionDescription?.let { d -> Modifier.semantics { contentDescription = d } } ?: Modifier)
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The second line of the card: why hosting is not running, or what it is doing right now. */
@Composable
private fun activityLine(server: ServeHost.State, look: ServerLook, status: EngineStatus?, installed: List<ModelEntry>): String {
    val line = when {
        installed.isEmpty() && (server is ServeHost.State.Stopped || server is ServeHost.State.Running) -> stringResource(R.string.host_needs_model)
        server !is ServeHost.State.Running -> lifecycleLine(server)
        look == ServerLook.PAUSED_HOT -> stringResource(R.string.status_paused_hot)
        look == ServerLook.PAUSED_BATTERY -> stringResource(R.string.status_paused_battery)
        look == ServerLook.NOT_RESPONDING -> stringResource(R.string.alert_wedged_text)
        else -> laneLine(status, installed)
    }
    val waiting = status?.queued ?: 0
    return if (waiting > 0 && server is ServeHost.State.Running) pluralStringResource(R.plurals.host_activity_waiting, waiting, line, waiting) else line
}

@Composable
private fun lifecycleLine(server: ServeHost.State): String = when (server) {
    is ServeHost.State.Stopped -> server.error ?: stringResource(R.string.host_stopped)
    ServeHost.State.Starting -> stringResource(R.string.status_starting_hint)
    else -> stringResource(R.string.status_stopping_hint)
}

@Composable
private fun laneLine(status: EngineStatus?, installed: List<ModelEntry>): String = when (status?.lane) {
    LaneState.LOADING -> {
        val id = status.loading ?: status.running?.model
        stringResource(R.string.host_activity_loading, installed.firstOrNull { it.id == id }?.let { ModelNames.shown(it, installed) } ?: id.orEmpty())
    }
    LaneState.PREFILLING -> stringResource(R.string.host_activity_reading, clientName(status.running?.client))
    LaneState.GENERATING -> stringResource(R.string.host_activity_writing, clientName(status.running?.client))
    else -> stringResource(R.string.host_activity_ready)
}

/** One installed model: its build, whether it is in memory, and the one memory action that fits. */
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
    val loading = status?.lane == LaneState.LOADING && status.loading == entry.id
    // A request waiting for its model to load is still a request: it keeps its Cancel.
    val job = status?.running?.takeIf { it.model == entry.id }
    val broken = status?.broken?.get(entry.id)
    var details by rememberSaveable(entry.id) { mutableStateOf(false) }
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
    val (state, tone) = modelState(broken != null, loading, job, status?.lane, loaded, now)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LabMark(model, entry.lab, 32.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(breakable(name), style = MaterialTheme.typography.titleMedium)
                Text(modelFacts(entry), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MemoryNeed(memoryNeed(entry))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(tone.color, 8.dp)
            Text(state, style = MaterialTheme.typography.labelLarge, color = tone.color)
        }
        if (job != null || loading) Progress(job, status.lane, tone.color)
        if (broken != null) Text(broken, style = MaterialTheme.typography.bodySmall, color = LocalTones.current.failed.color)
        ModelActions(entry, running != null, loaded, loading, broken != null, job, status?.lane, ConsoleChat.canChat(entry), model, onChat, details) {
            details =
                !details
        }
        if (details) ModelDetails(entry, last, running)
    }
}

/** How far a request has read its prompt, or an indefinite bar while loading or writing. */
@Composable
private fun Progress(job: RunningJob?, lane: LaneState?, color: androidx.compose.ui.graphics.Color) {
    if (job != null && lane == LaneState.PREFILLING && job.promptChars > 0) {
        LinearProgressIndicator(progress = {
            (job.prefilledChars.toFloat() / job.promptChars).coerceIn(0f, 1f)
        }, modifier = Modifier.fillMaxWidth(), color = color)
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = color)
    }
}

/** A model's state in words and tone: failed, loading, answering, in memory or not. */
@Composable
private fun modelState(failed: Boolean, loading: Boolean, job: RunningJob?, lane: LaneState?, loaded: Boolean, now: Long): Pair<String, Tone> {
    val tones = LocalTones.current
    val elapsed = job?.let { Format.duration((now - it.startedAtMs).coerceAtLeast(0)) }.orEmpty()
    return when {
        failed -> stringResource(R.string.host_model_failed) to tones.failed
        loading -> stringResource(R.string.host_loading) to tones.working
        job != null && lane == LaneState.PREFILLING -> stringResource(R.string.host_job_reading, clientName(job.client), elapsed) to tones.working
        job != null -> stringResource(R.string.host_job_writing, clientName(job.client), elapsed) to tones.working
        loaded -> stringResource(R.string.host_in_memory) to tones.good
        else -> stringResource(R.string.host_not_in_memory) to tones.idle
    }
}

/** At the memory limit, loading a model unloads the one used least recently: said before the tap. */
@Composable
private fun EvictionNote(status: EngineStatus?, limit: Int, installed: List<ModelEntry>) {
    val resident = status?.resident.orEmpty()
    if (resident.size < limit) return
    val evicted = resident.minByOrNull { it.lastUsedMs } ?: return
    val name = installed.firstOrNull { it.id == evicted.id }?.let { ModelNames.shown(it, installed) } ?: evicted.id
    Note(stringResource(R.string.host_evicts, name, pluralStringResource(R.plurals.memory_limit_models, limit, limit)))
}

/**
 * The one memory action that fits the model's state, Chat, and Details. Unload waits its turn
 * behind a reply in progress; a finished command (done or failed) ends the wait.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelActions(
    entry: ModelEntry,
    hosting: Boolean,
    loaded: Boolean,
    loading: Boolean,
    failed: Boolean,
    job: RunningJob?,
    lane: LaneState?,
    canChat: Boolean,
    model: MainViewModel,
    onChat: () -> Unit,
    details: Boolean,
    onDetails: () -> Unit,
) {
    var unloading by remember(entry.id) { mutableStateOf(false) }
    if (!loaded) unloading = false
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hosting) {
            if (job != null) Action(stringResource(R.string.action_cancel), { model.cancelJob(job.id) })
            when {
                failed -> Action(stringResource(R.string.host_retry), { model.retry(entry.id) })
                loaded -> Action(
                    stringResource(if (job != null) R.string.host_unload_after else R.string.host_unload),
                    {
                        unloading = true
                        model.unload(entry.id) { unloading = false }
                    },
                    enabled = !unloading,
                )
                !loading && job == null -> Action(stringResource(R.string.host_load), { model.load(entry.id) }, enabled = lane != LaneState.LOADING)
            }
            // A model without a chat template answers raw prompts only: no Chat for it.
            if (canChat) Action(stringResource(R.string.host_chat), onChat)
        }
        Action(stringResource(if (details) R.string.host_hide_details else R.string.host_details), onDetails)
    }
}

/** Build, window and size in one line: "NPU (Qualcomm) · 4k-token context · 1.6 GB on disk". */
@Composable
internal fun modelFacts(entry: ModelEntry): String = listOfNotNull(
    // Installs from before the backend was recorded: a Vulkan build says so in its name.
    processorLabel(entry.backend ?: if ("vulkan" in entry.id) org.experimentalmachines.execuserve.catalog.HfCatalog.VULKAN else null),
    entry.contextLength?.let { stringResource(R.string.host_model_context, Format.window(it)) },
    stringResource(R.string.host_model_disk, Format.bytes(entry.sizeBytes)),
).joinToString(" · ")

/** What a model last did, and where a client reaches this model alone. */
@Composable
private fun ModelDetails(entry: ModelEntry, last: JobRecord?, running: ServeHost.State.Running?) {
    if (last == null) {
        Text(stringResource(R.string.host_no_requests), style = MaterialTheme.typography.bodySmall)
    } else {
        // How it ended first: a failed or cancelled request is the last request too.
        Text(
            stringResource(
                R.string.host_last_request,
                stringResource(Outcome.of(last).words),
                remember(last.finishedAtMs) { Format.dateTime(last.finishedAtMs) },
                Format.duration(last.totalMs),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        if (last.prefillTokensPerSecond > 0 || last.decodeTokensPerSecond > 0) {
            Text(
                stringResource(R.string.host_last_rates, Format.rate(last.prefillTokensPerSecond), Format.rate(last.decodeTokensPerSecond)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    CopyRow(entry.id, label = stringResource(R.string.host_model_id), qr = true)
    running?.endpoints?.forEach { endpoint ->
        val where = stringResource(endpoint.network.words)
        CopyRow(ModelEndpoints.api(endpoint.url, entry.id), label = stringResource(R.string.host_model_api_on, where), qr = true)
        CopyRow(ModelEndpoints.browser(endpoint.url, entry.id), label = stringResource(R.string.host_model_browser_on, where), qr = true)
    }
}

/** Who a request came from, as a person would say it: this app's own chat is "Chat". */
@Composable
internal fun clientName(client: String?): String = when (client) {
    null -> ""
    org.experimentalmachines.execuserve.host.CONSOLE_KEY -> stringResource(R.string.client_chat)
    else -> client
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
 * Everything another app needs: who may connect, the address, and the key. Changing who may
 * connect while hosting restarts it on the new address, so it asks first.
 */
@Composable
private fun ConnectPanel(server: ServeHost.State, settings: HostSettings, key: ApiKey?, installed: List<ModelEntry>, model: MainViewModel) {
    val running = server as? ServeHost.State.Running
    var confirming by remember { mutableStateOf<BindMode?>(null) }
    Panel(stringResource(R.string.host_connection)) {
        ChoiceRow(
            stringResource(R.string.connect_who),
            options = BindMode.entries.map { it to stringResource(it.words) },
            selected = settings.bind,
            onSelect = { mode -> if (running != null && mode != settings.bind) confirming = mode else model.setBind(mode) },
        )
        if (settings.bind == BindMode.NETWORK) {
            Text(stringResource(R.string.connect_plain_http), style = MaterialTheme.typography.bodySmall, color = LocalTones.current.attention.color)
        }
        if (running != null && running.settings.bind == BindMode.NETWORK && running.endpoints.none { it.network != NetworkKind.THIS_DEVICE }) {
            Text(stringResource(R.string.connect_no_network), style = MaterialTheme.typography.bodySmall, color = LocalTones.current.attention.color)
        }
        if (running != null) {
            // What a client needs, together: the base URL, a model ID and (below) the key.
            val first = running.endpoints.first()
            CopyRow(first.url, label = stringResource(R.string.host_api_base, stringResource(first.network.words)), qr = true)
            KeyRow(key)
            ApiModels(installed, model, settings.memoryLimit, first.url)
            CopyRow(first.url.removeSuffix("/v1").trimEnd('/') + "/", label = stringResource(R.string.connect_browser_chat), qr = true)
        } else {
            Text(
                stringResource(R.string.host_address_when_running),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KeyRow(key)
        }
        if (running != null) {
            Expandable(stringResource(R.string.host_all_models_endpoint), stringResource(R.string.host_all_models_hint)) {
                running.endpoints.drop(1).forEach { endpoint ->
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
    confirming?.let { mode ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.connect_restart_title)) },
            text = { Text(stringResource(R.string.connect_restart_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    model.setBind(mode)
                }) { Text(stringResource(R.string.connect_restart_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** The API key, beside the address it goes with: shown by its ends until opened. */
@Composable
private fun KeyRow(key: ApiKey?) {
    Expandable(stringResource(R.string.host_access_key), stringResource(R.string.host_access_key_hint)) {
        if (key != null) {
            CopyRow(key.secret, label = key.name, shown = key.secret.take(KEY_HEAD) + "…" + key.secret.takeLast(KEY_TAIL), qr = true, sensitive = true)
        } else {
            Text(stringResource(R.string.host_no_key), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * How another app reaches each model: every model shares the one address and is chosen by
 * the request's "model" field. Each row is the ID to send, whether it is in memory, and what
 * asking for it does now; an app that cannot set the field gets a model's own address.
 */
@Composable
private fun ApiModels(installed: List<ModelEntry>, model: MainViewModel, limit: Int, base: String) {
    val status by model.status.collectAsState()
    val resident = status?.resident.orEmpty()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.row)) {
        Text(stringResource(R.string.connect_models_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.connect_models_note), style = MaterialTheme.typography.bodySmall, color = muted)
        installed.forEach { entry ->
            val id = requestId(entry, installed)
            val state = when {
                resident.any { it.id == entry.id } -> stringResource(R.string.connect_model_in_memory)
                resident.size >= limit -> resident.minByOrNull { it.lastUsedMs }?.let { evicted ->
                    stringResource(R.string.connect_model_swaps, installed.firstOrNull { it.id == evicted.id }?.let { requestId(it, installed) } ?: evicted.id)
                } ?: stringResource(R.string.connect_model_loads)
                else -> stringResource(R.string.connect_model_loads)
            }
            CopyRow(id, label = state)
        }
        Text(stringResource(R.string.connect_models_queue), style = MaterialTheme.typography.bodySmall, color = muted)
        Expandable(stringResource(R.string.connect_model_address_title)) {
            Text(stringResource(R.string.connect_model_address_note), style = MaterialTheme.typography.bodySmall, color = muted)
            installed.forEach { entry ->
                CopyRow(base.removeSuffix("/v1").trimEnd('/') + "/models/" + requestId(entry, installed) + "/v1", label = ModelNames.shown(entry, installed))
            }
        }
    }
}

/** The shortest name that picks [entry]: its alias when no other installed model shares it. */
internal fun requestId(entry: ModelEntry, installed: List<ModelEntry>): String =
    entry.aliases.firstOrNull { alias -> installed.none { it.id != entry.id && alias in it.aliases } } ?: entry.id

/** The phone's state, and what hosting has done; the requests themselves are on their own tab. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DevicePanel(model: MainViewModel, openRuns: () -> Unit) {
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
            Action(stringResource(R.string.runs_see_all), onClick = openRuns)
        }
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

/**
 * Memory and startup, in one place: the memory limit, unloading idle models, and which models
 * hosting loads when it starts. Startup loads at most the memory limit, in the order shown;
 * a selection past it says it will not load.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemorySheet(installed: List<ModelEntry>, settings: HostSettings, running: Boolean, model: MainViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter).padding(bottom = Dimens.gutter),
            verticalArrangement = Arrangement.spacedBy(Dimens.row),
        ) {
            Text(stringResource(R.string.memory_title), style = MaterialTheme.typography.titleLarge)
            MenuRow(
                stringResource(R.string.host_fact_limit),
                stringResource(R.string.memory_limit_note),
                options = Choices.RESIDENT_MODELS.map { it to pluralStringResource(R.plurals.memory_limit_models, it, it) },
                selected = settings.maxResidentModels,
                onSelect = { count -> model.update { it.copy(maxResidentModels = count, threads = if (count > 1) 0 else it.threads) } },
            )
            // Said before the choice, not after it: more than one model means Automatic threads.
            Note(stringResource(R.string.memory_threads_note))
            MenuRow(
                stringResource(R.string.settings_unload),
                options = Choices.IDLE_UNLOAD_MINUTES.map { minutes ->
                    minutes to if (minutes == 0) stringResource(R.string.never) else pluralStringResource(R.plurals.after_minutes, minutes, minutes)
                },
                selected = settings.idleUnloadMinutes,
                onSelect = { v -> model.update { it.copy(idleUnloadMinutes = v) } },
            )
            Text(stringResource(R.string.host_fact_start), Modifier.padding(top = Dimens.row), style = MaterialTheme.typography.titleMedium)
            Note(stringResource(if (running) R.string.memory_start_note_running else R.string.memory_start_note))
            // Every saved choice, in the order startup takes them, then the rest; the limit only
            // decides which of the chosen load, and says so beside the ones that will not.
            val order = settings.startupOrder { id -> installed.firstOrNull { it.id == id }?.id }
            val shown = order.mapNotNull { id -> installed.firstOrNull { it.id == id } } + installed.filter { it.id !in order }
            shown.forEach { entry ->
                val position = order.indexOf(entry.id)
                SwitchRow(
                    ModelNames.shown(entry, installed),
                    if (position >= settings.memoryLimit) {
                        stringResource(
                            R.string.memory_start_over,
                            pluralStringResource(R.plurals.memory_limit_models, settings.memoryLimit, settings.memoryLimit),
                        )
                    } else {
                        processorLabel(entry.backend)
                    },
                    position >= 0,
                ) { on ->
                    model.update {
                        it.copy(
                            defaultModel = it.defaultModel.takeUnless { id -> !on && id == entry.id },
                            preloadModels = if (on) it.preloadModels + entry.id else it.preloadModels - entry.id,
                        )
                    }
                }
            }
        }
    }
}
