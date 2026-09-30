@file:OptIn(ExperimentalCoroutinesApi::class)

package org.experimentalmachines.execuserve.app.serve

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

    override fun runtime(): LlmRuntime = ExecuTorchRuntime()

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
