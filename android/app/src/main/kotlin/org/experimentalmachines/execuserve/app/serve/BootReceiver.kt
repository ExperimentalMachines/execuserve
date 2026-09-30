package org.experimentalmachines.execuserve.app.serve

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.experimentalmachines.execuserve.app.graph

/**
 * Starts serving after a reboot or an app update, when the user asked for that. A
 * `specialUse` foreground service may start from BOOT_COMPLETED; Android 15 forbids it only
 * for types such as `dataSync`.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (context.graph.settings.current().startAtBoot) ServeService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
