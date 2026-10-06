package org.experimentalmachines.execuserve.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.experimentalmachines.execuserve.app.R
import org.experimentalmachines.execuserve.app.models.CatalogRepo
import org.experimentalmachines.execuserve.app.models.DownloadState
import org.experimentalmachines.execuserve.app.text.Format
import org.experimentalmachines.execuserve.catalog.CatalogVariant
import org.experimentalmachines.execuserve.catalog.HfCatalog
import org.experimentalmachines.execuserve.catalog.Labs
import org.experimentalmachines.execuserve.catalog.runtimeMismatch
import org.experimentalmachines.execuserve.engine.EngineStatus
import org.experimentalmachines.execuserve.engine.ModelEntry
import org.experimentalmachines.execuserve.executorch.ExecuTorchRuntime
import org.experimentalmachines.execuserve.host.ModelNames

@Composable
fun ModelsScreen(model: MainViewModel, padding: PaddingValues, wide: Boolean) {
    val installed by model.installed.collectAsState()
    val problems by model.problems.collectAsState()
    val downloads by model.downloads.collectAsState()
    val settings by model.settings.collectAsState()
    val status by model.status.collectAsState()
    val catalog by model.catalog.collectAsState()
    val gpu by model.gpuUsable.collectAsState()
    val npu by model.npuUsable.collectAsState()
    var deleting by remember { mutableStateOf<ModelEntry?>(null) }

    // The catalog is what this screen is for until something is installed: it loads on its own.
    LaunchedEffect(Unit) { if (catalog == CatalogState.Idle) model.loadCatalog() }

    // Files pushed with adb while the app was away appear on return, with no button to press.
    val resumes = resumeCount()
    LaunchedEffect(resumes) { model.rescan() }

    val active = downloads.values.filter { it.active || it.phase == DownloadState.Phase.FAILED }

    // What is on the device, then what could be: the catalog beside it when there is room.
    val main: LazyListScope.() -> Unit = {
        item(key = "installed") {
            Panel(
                stringResource(R.string.models_on_phone),
                trailing = {
                    Text(
                        pluralStringResource(R.plurals.models_installed, installed.size, installed.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            ) {
                if (installed.isEmpty()) {
                    Text(stringResource(R.string.models_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                installed.forEach { entry ->
                    key(entry.id) {
                        InstalledRow(
                            entry = entry,
                            name = ModelNames.shown(entry, installed),
                            isDefault = settings?.let { entry.id == it.defaultModel || entry.id in it.preloadModels } == true,
                            status = status,
                            onDefault = { on ->
                                model.update { current ->
                                    current.copy(
                                        defaultModel = current.defaultModel.takeUnless { !on && it == entry.id },
                                        preloadModels = if (on) current.preloadModels + entry.id else current.preloadModels - entry.id,
                                    )
                                }
                            },
                            onLoad = { if (status?.broken?.containsKey(entry.id) == true) model.retry(entry.id) else model.load(entry.id) },
                            onUnload = { model.unload(entry.id) },
                            onDelete = { deleting = entry },
                            model = model,
                        )
                    }
                }
            }
        }
        if (problems.isNotEmpty()) {
            item(key = "problems") {
                Panel(stringResource(R.string.models_not_usable), tone = LocalTones.current.attention) {
                    problems.forEach { (path, problem) ->
                        Text(path.substringAfterLast("/models/"), style = Mono)
                        Text(problem, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (active.isNotEmpty()) {
            item(key = "downloads") {
                Panel(stringResource(R.string.models_downloading)) {
                    active.forEachIndexed { index, download ->
                        if (index > 0) Divider()
                        DownloadRow(download, onCancel = { model.cancelDownload(download.id) })
                    }
                }
            }
        }
        item(key = "computer") {
            Panel {
                Expandable(stringResource(R.string.models_computer), stringResource(R.string.models_computer_note)) {
                    Text(stringResource(R.string.models_computer_cli), style = MaterialTheme.typography.bodySmall)
                    CopyRow("tools/execuserve --model path/to/Model.pte")
                    Text(stringResource(R.string.models_computer_push), style = MaterialTheme.typography.bodySmall)
                    CopyRow(model.modelsFolder)
                }
            }
        }
    }
    val side: LazyListScope.() -> Unit = {
        when (val state = catalog) {
            CatalogState.Idle -> item(key = "browse") {
                Panel(stringResource(R.string.catalog_title)) {
                    Text(
                        stringResource(R.string.catalog_source),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    InkButton(stringResource(R.string.catalog_browse), onClick = { model.loadCatalog() })
                }
            }
            CatalogState.Loading -> item(key = "loading") { Panel { LinearProgressIndicator(Modifier.fillMaxWidth()) } }
            is CatalogState.Failed -> item(key = "failed") {
                Panel(stringResource(R.string.catalog_unreachable_title), tone = LocalTones.current.failed) {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    OutlineButton(stringResource(R.string.action_retry), onClick = { model.loadCatalog() })
                }
            }
            is CatalogState.Loaded -> {
                item(key = "catalog-title") {
                    Column(Modifier.padding(top = Dimens.row, start = 4.dp, end = 4.dp)) {
                        PanelTitle(stringResource(R.string.catalog_title))
                        Text(
                            stringResource(R.string.catalog_source),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Collected, so a GPU refusal recorded since the catalog loaded takes this
                // phone's GPU builds off the screen without a reload (codex QA).
                val runnable = state.repos.map { repo -> repo.copy(variants = repo.variants.filter { model.runnableHere(it, gpu, npu) }) }
                    .filter { it.variants.isNotEmpty() }
                items(runnable, key = { "r-" + it.repo }) { repo ->
                    RepoPanel(model, repo, installed, downloads, model::download)
                }
            }
        }
    }
    PanelColumns(wide, padding, main = main, side = side)

    deleting?.let { entry ->
        val freed = remember(entry) { model.bytesFreedBy(entry) }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_title, ModelNames.shown(entry, installed))) },
            text = { Text(stringResource(R.string.delete_body, Format.bytes(freed))) },
            confirmButton = {
                Action(stringResource(R.string.action_delete), onClick = {
                    model.delete(entry)
                    deleting = null
                }, destructive = true)
            },
            dismissButton = { Action(stringResource(R.string.action_cancel), onClick = { deleting = null }) },
        )
    }
}

/** One installed model: its names, what it is, where it came from, and what can be done with it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstalledRow(
    entry: ModelEntry,
    name: String,
    isDefault: Boolean,
    status: EngineStatus?,
    onDefault: (Boolean) -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit,
    model: MainViewModel,
) {
    val tones = LocalTones.current
    val loaded = status?.resident?.any { it.id == entry.id } == true
    val broken = status?.broken?.get(entry.id)
    Column {
        Divider()
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.row)) {
                LabMark(model, entry.lab, 32.dp)
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                // A state is a light and a word, as everywhere else.
                if (loaded) {
                    Dot(tones.good.color, 8.dp)
                    Text(stringResource(R.string.model_loaded), style = MaterialTheme.typography.labelLarge, color = tones.good.color)
                }
            }
            Text(
                Format.bytes(entry.sizeBytes) + " · " + processorLabel(entry.backend ?: if ("vulkan" in entry.id) HfCatalog.VULKAN else null),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Expandable(stringResource(R.string.host_model_details)) {
                Text(entry.id, style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gutter), modifier = Modifier.padding(top = 4.dp)) {
                    entry.contextLength?.let { Fact(stringResource(R.string.fact_window), Format.window(it)) }
                    Fact(stringResource(R.string.fact_size), Format.bytes(entry.sizeBytes))
                    // The template is named only when there is none: that is a limit a client meets.
                    if (entry.family ==
                        null
                    ) {
                        Fact(stringResource(R.string.fact_template), stringResource(R.string.model_raw), valueColor = tones.attention.color)
                    }
                }
                Text(
                    entry.source?.let { stringResource(R.string.model_from, it) } ?: stringResource(R.string.model_from_computer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (broken != null) {
                Text(stringResource(R.string.model_did_not_load, broken), color = tones.failed.color, style = MaterialTheme.typography.bodySmall)
            }
            SwitchRow(stringResource(R.string.model_load_at_start), stringResource(R.string.host_startup_capacity_note), isDefault) { on -> onDefault(on) }
            // Pulled left by a text button's own padding, so the labels line up with the text above.
            FlowRow(Modifier.offset(x = -ACTION_INSET)) {
                // Loading needs the engine, which exists only while serving.
                if (status != null) {
                    if (loaded) {
                        Action(stringResource(R.string.model_unload), onClick = onUnload)
                    } else {
                        Action(stringResource(if (broken != null) R.string.model_try_again else R.string.model_load_now), onClick = onLoad)
                    }
                }
                Action(stringResource(R.string.action_delete), onClick = onDelete, destructive = true)
            }
        }
    }
}

@Composable
private fun DownloadRow(download: DownloadState, onCancel: () -> Unit) {
    val tones = LocalTones.current
    val failed = download.phase == DownloadState.Phase.FAILED
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(download.id, style = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (failed) {
                        download.error ?: stringResource(R.string.phase_failed)
                    } else {
                        stringResource(download.phase.words) + ", " +
                            stringResource(R.string.download_progress, Format.bytes(download.bytes), Format.bytes(download.total))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) tones.failed.color else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!failed) Action(stringResource(R.string.action_cancel), onClick = onCancel)
        }
        if (!failed) {
            val fraction = if (download.total > 0) (download.bytes.toFloat() / download.total).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = {
                fraction
            }, modifier = Modifier.fillMaxWidth(), color = tones.working.color, trackColor = tones.working.container)
        }
    }
}

/** A repository, one line until opened. */
@Composable
private fun RepoPanel(
    model: MainViewModel,
    repo: CatalogRepo,
    installed: List<ModelEntry>,
    downloads: Map<String, DownloadState>,
    onGet: (CatalogVariant) -> Unit,
) {
    val name = repo.repo.substringAfter('/').removeSuffix("-ExecuTorch")
    val have = repo.variants.count { v -> installed.any { it.id == v.installId } }
    val smallest = repo.variants.minOfOrNull { it.installBytes } ?: 0
    val lab = repo.variants.firstNotNullOfOrNull { it.lab }
    val variants = pluralStringResource(R.plurals.catalog_variants, repo.variants.size, repo.variants.size, Format.bytes(smallest))
    val line = listOfNotNull(
        lab?.let(Labs::displayName),
        variants,
        if (have > 0) pluralStringResource(R.plurals.models_installed, have, have) else null,
    ).joinToString(", ")
    Panel {
        Expandable(name, line, leading = { LabMark(model, lab) }) {
            repo.variants.forEachIndexed { index, variant ->
                if (index > 0) Divider()
                VariantRow(variant, installed.any { it.id == variant.installId }, downloads[variant.installId], onGet)
            }
        }
    }
}

@Composable
private fun VariantRow(variant: CatalogVariant, installed: Boolean, download: DownloadState?, onGet: (CatalogVariant) -> Unit) {
    val tones = LocalTones.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.row)) {
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(
                    variant.context?.let {
                        stringResource(R.string.fig_window, Format.window(it))
                    },
                    Format.bytes(variant.installBytes),
                ).joinToString(", "),
                style = MaterialTheme.typography.bodyLarge,
            )
            // Every build says which processor runs it, whether or not its export named a
            // quantization: each backend is a separate download.
            Text(
                listOfNotNull(processorLabel(variant.backend), variant.quantization?.substringBefore(',')).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (variant.backend == HfCatalog.NEUROPILOT) {
                // Measured: Qwen3-1.7B's 4k build needs more than 6.6 GB while loading on a 12 GB phone.
                Text(stringResource(R.string.catalog_npu_memory), style = MaterialTheme.typography.bodySmall, color = tones.attention.color)
            }
            if (variant.fitsPhoneBudget == false) {
                Text(stringResource(R.string.catalog_over_budget), style = MaterialTheme.typography.bodySmall, color = tones.attention.color)
            }
            // An export outside what this runtime is promised to load may load and misbehave;
            // say so before the download. Files from the previous minor release are covered.
            variant.runtimeVersion?.takeIf { runtimeMismatch(it, ExecuTorchRuntime.VERSION) }?.let {
                Text(
                    stringResource(R.string.catalog_runtime, it, ExecuTorchRuntime.VERSION),
                    style = MaterialTheme.typography.bodySmall,
                    color = tones.attention.color,
                )
            }
        }
        when {
            download?.active == true -> Text(Format.percent(download.bytes, download.total), style = MaterialTheme.typography.labelLarge)
            installed -> Pill(stringResource(R.string.catalog_installed), tones.good)
            else -> OutlineButton(stringResource(R.string.catalog_get), onClick = { onGet(variant) })
        }
    }
}

/** Which processor runs a build of [backend]; installs that predate the record are CPU builds. */
@Composable
internal fun processorLabel(backend: String?): String = stringResource(
    when (backend) {
        HfCatalog.VULKAN -> R.string.processor_gpu
        HfCatalog.QNN -> R.string.processor_npu_qualcomm
        HfCatalog.NEUROPILOT -> R.string.processor_npu_mediatek
        else -> R.string.processor_cpu
    },
)
