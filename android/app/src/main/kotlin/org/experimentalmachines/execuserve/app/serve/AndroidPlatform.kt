@file:OptIn(ExperimentalCoroutinesApi::class)

package org.experimentalmachines.execuserve.app.serve

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.asCoroutineDispatcher
import org.experimentalmachines.execuserve.app.BuildConfig
import org.experimentalmachines.execuserve.engine.Environment
import org.experimentalmachines.execuserve.engine.LlmRuntime
import org.experimentalmachines.execuserve.executorch.ExecuTorchRuntime
import org.experimentalmachines.execuserve.host.Endpoint
import org.experimentalmachines.execuserve.host.HostPlatform
import org.experimentalmachines.execuserve.server.BindMode
import java.util.concurrent.Executors

/** What [org.experimentalmachines.execuserve.host.ServeHost] needs from Android. */
class AndroidPlatform(context: Context) : HostPlatform {

    private val device = DeviceEnvironment(context).apply {
        // A sticky battery broadcast and a thermal listener: negligible, and needed by both
        // the engine's admission policy and the console, serving or not.
        start()
    }

    override val version: String = BuildConfig.VERSION_NAME

    override val cpuCores: Int = Runtime.getRuntime().availableProcessors()

    override val environment: StateFlow<Environment> = device.state

    private val activityManager = context.getSystemService(ActivityManager::class.java)

    override fun runtime(): LlmRuntime = ExecuTorchRuntime(allowMultipleResidents = {
        // Native weights/KV are outside the Java heap. Use the platform's pressure signal,
        // not Runtime.freeMemory(), and do not pretend model file size predicts peak RSS.
        val memory = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memory)
        !memory.lowMemory && memory.availMem > memory.threshold
    })

    override fun lane(): CloseableCoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "execuserve-lane", LANE_STACK_BYTES)
    }.asCoroutineDispatcher()

    override fun endpoints(port: Int, bind: BindMode): List<Endpoint> = Addresses.endpoints(port, bind)

    override fun hosts(): Set<String> = Addresses.hosts()

    private companion object {
        /** The runtime's own threads do the arithmetic; this one only calls in. */
        const val LANE_STACK_BYTES = 4L shl 20
    }
}
