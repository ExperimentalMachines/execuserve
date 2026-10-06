package org.experimentalmachines.execuserve.app.ui

import android.app.ActivityManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.experimentalmachines.execuserve.app.R
import org.experimentalmachines.execuserve.app.text.Format
import org.experimentalmachines.execuserve.catalog.CatalogVariant
import org.experimentalmachines.execuserve.catalog.HfCatalog
import org.experimentalmachines.execuserve.engine.ModelEntry
import org.experimentalmachines.execuserve.engine.ModelMemory
import org.experimentalmachines.execuserve.engine.NpuMemory

/**
 * The memory a model takes once loaded, the figure that decides whether it runs, shown
 * wherever a model is chosen: in the catalog before the download, and on every installed
 * model. Its file size is what it costs to download, not to run (ModelMemory, NpuMemory).
 * Qualcomm NPU builds get none: their cache lives in the NPU's own memory, unmeasured here.
 */
fun memoryNeed(entry: ModelEntry): Long? = when {
    entry.files.npu != null -> entry.files.npu?.let { NpuMemory.needBytes(it.runnerOptions, entry.sizeBytes) }
    entry.backend == HfCatalog.QNN -> null
    else -> ModelMemory.needBytes(entry.source ?: entry.id, entry.contextLength, entry.sizeBytes)
        ?: ModelMemory.needBytes(entry.id, entry.contextLength, entry.sizeBytes)
}

fun memoryNeed(variant: CatalogVariant): Long? = when {
    variant.npu != null -> variant.npu?.let { NpuMemory.needBytes(it.runner, variant.installBytes) }
    variant.backend == HfCatalog.QNN -> null
    else -> ModelMemory.needBytes(variant.repo, variant.context, variant.sizeBytes)
}

/** This phone's memory in all and free now, from Android. */
data class PhoneMemory(val totalBytes: Long, val availableBytes: Long)

fun phoneMemory(context: Context): PhoneMemory? = context.getSystemService(ActivityManager::class.java)?.let { manager ->
    ActivityManager.MemoryInfo().also(manager::getMemoryInfo).let { PhoneMemory(it.totalMem, it.availMem) }
}

/** "Needs 1.1 GB of memory" and, when it does not sit comfortably, what that means here. */
@Composable
fun MemoryNeed(need: Long?) {
    need ?: return
    val context = LocalContext.current
    val phone = remember { phoneMemory(context) }
    val tones = LocalTones.current
    val fit = phone?.let { ModelMemory.fit(need, it.totalBytes, it.availableBytes) } ?: ModelMemory.Fit.COMFORTABLE
    Text(
        stringResource(R.string.memory_needs, Format.bytes(need)),
        style = MaterialTheme.typography.bodySmall,
        color = if (fit == ModelMemory.Fit.COMFORTABLE) MaterialTheme.colorScheme.onSurfaceVariant else tones.attention.color,
    )
    when (fit) {
        ModelMemory.Fit.TIGHT -> Text(stringResource(R.string.memory_tight), style = MaterialTheme.typography.bodySmall, color = tones.attention.color)
        ModelMemory.Fit.WONT_FIT -> Text(
            stringResource(R.string.memory_wont_fit, Format.bytes(ModelMemory.usableBytes(phone?.totalBytes ?: 0))),
            style = MaterialTheme.typography.bodySmall,
            color = tones.failed.color,
        )
        ModelMemory.Fit.COMFORTABLE -> Unit
    }
}
