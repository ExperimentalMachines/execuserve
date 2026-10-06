@file:OptIn(ExperimentalCoroutinesApi::class)

package org.experimentalmachines.execuserve.app.serve

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import org.experimentalmachines.execuserve.app.BuildConfig
import org.experimentalmachines.execuserve.engine.Environment
import org.experimentalmachines.execuserve.engine.LlmRuntime
import org.experimentalmachines.execuserve.executorch.ExecuTorchRuntime
import org.experimentalmachines.execuserve.executorch.NativeCrashGuard
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

    // The runtime records every native call itself: see NativeCrashGuard.
    override fun takeInterrupted(): String? = NativeCrashGuard.take()

    override fun quarantined(): Set<String> = NativeCrashGuard.refused()

    override fun setQuarantined(id: String, quarantined: Boolean) = NativeCrashGuard.setRefused(id, quarantined)

    override val cpuCores: Int = Runtime.getRuntime().availableProcessors()

    // About two thirds of the phone's memory, the most a foreground app takes before Android
    // starts closing others (ModelMemory.usableBytes).
    override val memoryBudgetBytes: Long? = context.getSystemService(android.app.ActivityManager::class.java)
        ?.let { manager -> android.app.ActivityManager.MemoryInfo().also(manager::getMemoryInfo).totalMem }
        ?.takeIf { it > 0 }
        ?.let(org.experimentalmachines.execuserve.engine.ModelMemory::usableBytes)

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
