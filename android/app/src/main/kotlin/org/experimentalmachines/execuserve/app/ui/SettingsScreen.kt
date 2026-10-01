package org.experimentalmachines.execuserve.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.experimentalmachines.execuserve.app.BuildConfig
import org.experimentalmachines.execuserve.app.R
import org.experimentalmachines.execuserve.executorch.ExecuTorchRuntime
import org.experimentalmachines.execuserve.host.Choices
import org.experimentalmachines.execuserve.host.ServeHost
import org.experimentalmachines.execuserve.host.ThemeMode
import org.experimentalmachines.execuserve.host.ThinkingDefault
import org.experimentalmachines.execuserve.host.WakePolicy
import org.experimentalmachines.execuserve.server.ApiKey
import org.experimentalmachines.execuserve.server.BindMode

@Composable
fun SettingsScreen(model: MainViewModel, padding: PaddingValues) {
    val settings by model.settings.collectAsState()
    val server by model.server.collectAsState()
    val keys by model.keys.collectAsState()
    val installed by model.installed.collectAsState()
    val current = settings ?: return
    val running = server as? ServeHost.State.Running
    val tones = LocalTones.current
    val update = model::update

    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(Dimens.gap)) {
        if (running != null && current.needsRestartFrom(running.settings)) {
            item(key = "restart") {
                Panel(stringResource(R.string.settings_restart_title), tone = tones.attention) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.row)) {
                        Text(stringResource(R.string.settings_restart_needed), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        OutlineButton(stringResource(R.string.action_restart), model::restart)
                    }
                }
            }
        }

        item(key = "connection") {
            Panel(stringResource(R.string.settings_connection)) {
                ChoiceRow(
                    stringResource(R.string.connect_who),
                    options = BindMode.entries.map { it to stringResource(it.words) },
                    selected = current.bind,
                    onSelect = { mode -> model.setBind(mode) },
                )
                if (current.bind == BindMode.NETWORK) {
                    Text(stringResource(R.string.connect_plain_http), style = MaterialTheme.typography.bodySmall, color = tones.attention.color)
                }
                NumberRow(stringResource(R.string.settings_port), value = current.port, range = Choices.PORTS) { v -> update { it.copy(port = v) } }
                SwitchRow(stringResource(R.string.settings_open_loopback), stringResource(R.string.settings_open_loopback_note), current.openLoopback) { on ->
                    update { it.copy(openLoopback = on) }
                }
            }
        }

        item(key = "capacity") {
            Panel(stringResource(R.string.settings_hosting)) {
                MenuRow(
                    stringResource(R.string.settings_resident),
                    stringResource(R.string.settings_resident_note),
                    options = (1..3).map { it to it.toString() },
                    selected = current.maxResidentModels,
                    onSelect = { count -> update { it.copy(maxResidentModels = count, threads = if (count > 1) 0 else it.threads) } },
                )
                Text(stringResource(R.string.settings_resident_threads), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Expandable(stringResource(R.string.settings_startup_models), stringResource(R.string.settings_startup_models_note)) {
                    installed.forEach { entry ->
                        SwitchRow(entry.aliases.firstOrNull() ?: entry.id, null, entry.id in current.preloadModels || entry.id == current.defaultModel) { on ->
                            update { settings -> settings.copy(
                                defaultModel = settings.defaultModel.takeUnless { !on && it == entry.id },
                                preloadModels = if (on) settings.preloadModels + entry.id else settings.preloadModels - entry.id,
                            ) }
                        }
                    }
                }
            }
        }
        item(key = "keys") { Panel { Expandable(stringResource(R.string.settings_keys)) { KeysPanel(keys, model::addKey, model::revokeKey) } } }

        item(key = "model") {
            Panel { Expandable(stringResource(R.string.settings_model)) {
                ChoiceRow(
                    stringResource(R.string.settings_reasoning),
                    stringResource(R.string.settings_when_unset),
                    options = ThinkingDefault.entries.map { it to stringResource(it.words) },
                    selected = current.thinking,
                    onSelect = { v -> update { it.copy(thinking = v) } },
                )
                val greedy = stringResource(R.string.settings_temperature_greedy)
                MenuRow(
                    stringResource(R.string.settings_temperature),
                    stringResource(R.string.settings_when_unset),
                    options = Choices.TEMPERATURES.map { it to (if (it == 0f) greedy else "%.1f".format(it)) },
                    selected = Choices.nearestTemperature(current.temperature),
                    onSelect = { v -> update { it.copy(temperature = v) } },
                )
                SwitchRow(
                    stringResource(R.string.settings_keep_reasoning),
                    stringResource(R.string.settings_keep_reasoning_note),
                    current.keepReasoningInHistory,
                ) { on -> update { it.copy(keepReasoningInHistory = on) } }
                val active by model.activeThreads.collectAsState()
                val automatic = stringResource(R.string.threads_auto)
                MenuRow(
                    stringResource(R.string.settings_threads),
                    active?.let { stringResource(R.string.settings_threads_now, it) } ?: stringResource(R.string.settings_threads_idle),
                    options = Choices.threads(model.cpuCores).map { n -> n to if (n == 0) automatic else n.toString() },
                    selected = current.threads,
                    onSelect = { v -> update { it.copy(threads = v, maxResidentModels = if (v > 0) 1 else it.maxResidentModels) } },
                )
                Text(stringResource(R.string.settings_threads_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Expandable(stringResource(R.string.settings_threads_why)) {
                    Text(stringResource(R.string.settings_threads_why_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } }
        }

        item(key = "background") {
            Panel { Expandable(stringResource(R.string.settings_background)) {
                val context = LocalContext.current
                SwitchRow(stringResource(R.string.settings_boot), null, current.startAtBoot) { on -> update { it.copy(startAtBoot = on) } }
                // HyperOS decides, with a permission no app can read, whether Android may
                // restart the server after its process dies, and whether it may start at boot.
                // Measured on the POCO: without it a crash ended serving for good; with it the
                // server was back in two seconds. So the row is always there on these phones.
                if (isXiaomi()) {
                    Row(Modifier.heightIn(min = Dimens.touch), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_autostart_title), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.settings_autostart), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Action(stringResource(R.string.action_open), onClick = { openAutostart(context) })
                    }
                }
                ChoiceRow(
                    stringResource(R.string.settings_wake),
                    options = WakePolicy.entries.map { it to stringResource(it.words) },
                    selected = current.wake,
                    onSelect = { v -> update { it.copy(wake = v) } },
                )
                MenuRow(
                    stringResource(R.string.settings_unload),
                    options = Choices.IDLE_UNLOAD_MINUTES.map { minutes ->
                        minutes to if (minutes == 0) stringResource(R.string.never) else pluralStringResource(R.plurals.after_minutes, minutes, minutes)
                    },
                    selected = current.idleUnloadMinutes,
                    onSelect = { v -> update { it.copy(idleUnloadMinutes = v) } },
                )
                MenuRow(
                    stringResource(R.string.settings_battery_floor),
                    stringResource(R.string.settings_battery_floor_note),
                    options = Choices.BATTERY_FLOORS.map { percent ->
                        percent to if (percent == 0) stringResource(R.string.never) else stringResource(R.string.battery_below, percent)
                    },
                    selected = current.minBatteryPercent,
                    onSelect = { v -> update { it.copy(minBatteryPercent = v) } },
                )
                BatteryOptimisation()
            } }
        }

        item(key = "limits") {
            Panel {
                Expandable(stringResource(R.string.settings_limits), stringResource(R.string.settings_limits_summary, current.maxQueued, current.maxPerClient)) {
                    val seconds = stringResource(R.string.unit_seconds)
                    NumberRow(stringResource(R.string.settings_max_queued), value = current.maxQueued, range = Choices.QUEUE_SIZES) { v -> update { it.copy(maxQueued = v) } }
                    NumberRow(stringResource(R.string.settings_max_per_client), value = current.maxPerClient, range = Choices.PER_CLIENT) { v -> update { it.copy(maxPerClient = v) } }
                    NumberRow(stringResource(R.string.settings_queue_timeout), value = current.queueTimeoutSeconds, range = Choices.QUEUE_TIMEOUT_SECONDS, suffix = seconds) { v ->
                        update { it.copy(queueTimeoutSeconds = v) }
                    }
                    NumberRow(stringResource(R.string.settings_request_timeout), value = current.requestTimeoutSeconds, range = Choices.REQUEST_TIMEOUT_SECONDS, suffix = seconds) { v ->
                        update { it.copy(requestTimeoutSeconds = v) }
                    }
                }
            }
        }

        item(key = "advanced") {
            Panel {
                Expandable(stringResource(R.string.settings_advanced), stringResource(R.string.settings_advanced_summary)) {
                    TextRow(stringResource(R.string.settings_hosts), stringResource(R.string.settings_hosts_note), current.extraHosts, "phone.tailnet.ts.net") { v ->
                        update { it.copy(extraHosts = v) }
                    }
                    TextRow(stringResource(R.string.settings_cors), stringResource(R.string.settings_cors_note), current.corsOrigins, "http://localhost:3000") { v ->
                        update { it.copy(corsOrigins = v) }
                    }
                    SwitchRow(stringResource(R.string.settings_external), stringResource(R.string.settings_external_note), current.allowExternalStart) { on ->
                        update { it.copy(allowExternalStart = on) }
                    }
                }
            }
        }

        item(key = "appearance") {
            Panel(stringResource(R.string.settings_appearance)) {
                ChoiceRow(
                    stringResource(R.string.settings_theme),
                    options = ThemeMode.entries.map { it to stringResource(it.words) },
                    selected = current.theme,
                    onSelect = { v -> update { it.copy(theme = v) } },
                )
            }
        }

        item(key = "about") {
            Panel(stringResource(R.string.settings_about)) {
                Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                Text(stringResource(R.string.about_runtime, ExecuTorchRuntime.VERSION), style = MaterialTheme.typography.bodySmall, color = muted)
                Text(stringResource(R.string.about_api), style = MaterialTheme.typography.bodySmall, color = muted)
                Text(stringResource(R.string.about_license), style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

@Composable
private fun KeysPanel(keys: List<ApiKey>, onAdd: (String) -> Unit, onRevoke: (String) -> Unit) {
    val context = LocalContext.current
    Panel(stringResource(R.string.settings_keys)) {
        Text(stringResource(R.string.settings_keys_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        keys.forEach { key ->
            CopyRow(
                value = key.secret, label = key.name,
                shown = key.secret.take(KEY_HEAD) + "…" + key.secret.takeLast(KEY_TAIL),
                qr = true, sensitive = true,
                extra = {
                    if (keys.size > 1) Action(stringResource(R.string.action_revoke), onClick = { onRevoke(key.id) }, destructive = true)
                },
            )
        }
        var name by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.row)) {
            OutlinedTextField(name, { name = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.settings_key_new)) }, singleLine = true)
            OutlineButton(stringResource(R.string.action_add), onClick = {
                if (name.isNotBlank()) {
                    onAdd(name)
                    name = ""
                }
            })
        }
    }
}

/**
 * Whether Android may defer the app. Not needed on the phones measured (the foreground
 * service alone kept the server reachable in Doze), so it is a setting here rather than a
 * warning on the console.
 */
@Composable
private fun BatteryOptimisation() {
    val context = LocalContext.current
    val resumes = resumeCount()
    val exempt = remember(resumes) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
    Row(Modifier.heightIn(min = Dimens.touch), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_optimisation), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (exempt) R.string.settings_optimisation_off else R.string.settings_optimisation_on),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!exempt) {
            Action(stringResource(R.string.action_exempt), onClick = {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            })
        }
    }
}

private fun isXiaomi() = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "poco", "redmi")

private fun openAutostart(context: Context) {
    val autostart = Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(autostart) }.onFailure { context.startActivity(details) }
}

private const val KEY_HEAD = 6
private const val KEY_TAIL = 4
