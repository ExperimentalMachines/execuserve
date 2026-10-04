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
import org.experimentalmachines.execuserve.executorch.VulkanSupport
import org.experimentalmachines.execuserve.host.RunHistory
import org.experimentalmachines.execuserve.host.ServeHost

class ExecuServeApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // Before the catalog asks which export folders this phone can run.
        VulkanSupport.init(this)
    }
}

/** The process's singletons, wired by hand: a dozen objects need no framework. */
class AppGraph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(context)
    val models = ModelStore(context)
    val catalog = CatalogRepository(includeUncensored = BuildConfig.CATALOG_UNCENSORED) {
        if (VulkanSupport.usable) setOf(HfCatalog.BACKEND, HfCatalog.VULKAN) else setOf(HfCatalog.BACKEND)
    }
    val history = RunHistory(FileRunStore(context.filesDir.resolve("runs.jsonl")), scope, System::currentTimeMillis)
    val host = ServeHost(AndroidPlatform(context), settings, models, history, scope)
    val downloader = Downloader(models.directory, scope) { models.rescan() }
    val labs = LabImages(context.cacheDir, scope)
}

val Context.graph: AppGraph get() = (applicationContext as ExecuServeApp).graph
