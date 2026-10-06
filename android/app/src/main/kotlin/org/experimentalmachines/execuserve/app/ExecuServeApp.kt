package org.experimentalmachines.execuserve.app

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.experimentalmachines.execuserve.app.models.CatalogRepository
import org.experimentalmachines.execuserve.app.models.Downloader
import org.experimentalmachines.execuserve.app.models.FileRunStore
import org.experimentalmachines.execuserve.app.models.LabImages
import org.experimentalmachines.execuserve.app.models.ModelStore
import org.experimentalmachines.execuserve.app.serve.AndroidPlatform
import org.experimentalmachines.execuserve.app.settings.SettingsStore
import org.experimentalmachines.execuserve.catalog.HfCatalog
import org.experimentalmachines.execuserve.catalog.chipFolder
import org.experimentalmachines.execuserve.executorch.NativeCrashGuard
import org.experimentalmachines.execuserve.executorch.NeuroPilotSupport
import org.experimentalmachines.execuserve.executorch.QnnSupport
import org.experimentalmachines.execuserve.executorch.VulkanSupport
import org.experimentalmachines.execuserve.host.RunHistory
import org.experimentalmachines.execuserve.host.ServeHost

class ExecuServeApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // Before the catalog asks which export folders this phone can run.
        VulkanSupport.init(this)
        QnnSupport.init(this)
        NativeCrashGuard.init(filesDir)
    }
}

/** The process's singletons, wired by hand: a dozen objects need no framework. */
class AppGraph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(context)
    val models = ModelStore(context)
    val catalog = CatalogRepository(includeUncensored = BuildConfig.CATALOG_UNCENSORED) {
        buildSet {
            add(HfCatalog.BACKEND)
            if (VulkanSupport.usable) add(HfCatalog.VULKAN)
            // Exactly this chip's NPU folder: a file compiled for another chip will not load.
            QnnSupport.soc?.takeIf { QnnSupport.usable }?.let { add(chipFolder(HfCatalog.QNN, it)) }
            NeuroPilotSupport.soc?.takeIf { NeuroPilotSupport.usable }?.let { add(chipFolder(HfCatalog.NEUROPILOT, it)) }
        }
    }
    val history = RunHistory(FileRunStore(context.filesDir.resolve("runs.jsonl")), scope, System::currentTimeMillis)
    val host = ServeHost(AndroidPlatform(context), settings, models, history, scope)
    val downloader = Downloader(
        models.directory,
        scope,
        refuses = { plan ->
            when (plan.manifest.backend) {
                HfCatalog.VULKAN -> context.getString(R.string.gpu_refused).takeIf { !VulkanSupport.usable }
                HfCatalog.QNN -> context.getString(R.string.npu_refused).takeIf { !QnnSupport.usable }
                HfCatalog.NEUROPILOT -> context.getString(R.string.npu_mediatek_refused).takeIf { !NeuroPilotSupport.usable }
                else -> null
            }
        },
    ) { models.rescan() }
    val labs = LabImages(context.cacheDir, scope)
}

val Context.graph: AppGraph get() = (applicationContext as ExecuServeApp).graph
