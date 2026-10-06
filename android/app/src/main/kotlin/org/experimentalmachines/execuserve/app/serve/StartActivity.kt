package org.experimentalmachines.execuserve.app.serve

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import org.experimentalmachines.execuserve.app.R
import org.experimentalmachines.execuserve.app.graph
import org.experimentalmachines.execuserve.app.ui.ExecuServeTheme
import org.experimentalmachines.execuserve.host.HostSettings
import org.experimentalmachines.execuserve.host.ServeHost
import org.experimentalmachines.execuserve.host.ThemeMode
import org.experimentalmachines.execuserve.server.BindMode

/**
 * `execuserve://start`: another app asking for the server. Always confirmed by the person
 * holding the phone; an exported component that started a model server on its own would
 * be a button every installed app could press.
 */
class StartActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Another app's overlay could cover this dialog and steer a tap onto Start; while it
        // is showing, Android hides every non-system overlay (API 31+, this app's minimum).
        window.setHideOverlayWindows(true)
        if (graph.host.state.value is ServeHost.State.Running) {
            setResult(Activity.RESULT_OK)
            finish()
            return
        }
        val caller = referrer?.host
        // A server with nothing to serve answers every request with an error.
        val nothingInstalled = graph.models.installed.value.isEmpty()
        setContent {
            val settings by graph.settings.settings.collectAsState(initial = null)
            ExecuServeTheme(settings?.theme ?: ThemeMode.SYSTEM) {
                val current = settings ?: return@ExecuServeTheme
                val refusal = when {
                    !current.allowExternalStart -> R.string.start_disabled
                    nothingInstalled -> R.string.start_no_model
                    else -> null
                }
                if (refusal != null) {
                    AlertDialog(
                        onDismissRequest = ::cancel,
                        title = { Text(stringResource(R.string.app_name)) },
                        text = { Text(stringResource(refusal)) },
                        confirmButton = { TextButton(onClick = ::cancel) { Text(stringResource(R.string.action_ok)) } },
                    )
                } else {
                    AlertDialog(
                        onDismissRequest = ::cancel,
                        title = { Text(stringResource(R.string.start_title)) },
                        text = { Text(describe(caller, current)) },
                        confirmButton = {
                            TextButton(onClick = {
                                ServeService.start(this)
                                setResult(Activity.RESULT_OK)
                                finish()
                            }) { Text(stringResource(R.string.action_start)) }
                        },
                        dismissButton = { TextButton(onClick = ::cancel) { Text(stringResource(R.string.action_cancel)) } },
                    )
                }
            }
        }
    }

    private fun describe(caller: String?, settings: HostSettings): String {
        val who = caller?.let { getString(R.string.start_by_app, it) } ?: getString(R.string.start_by_unknown)
        val model = settings.defaultModel ?: getString(R.string.start_first_model)
        val what = if (settings.bind == BindMode.NETWORK) R.string.start_what_network else R.string.start_what_loopback
        return who + " " + getString(what, model, settings.port)
    }

    private fun cancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }
}
