package org.experimentalmachines.execuserve.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.experimentalmachines.execuserve.app.R
import org.experimentalmachines.execuserve.host.ServeHost
import org.experimentalmachines.execuserve.host.ServerLook
import org.experimentalmachines.execuserve.host.ThemeMode
import org.experimentalmachines.execuserve.engine.EngineStatus

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by model.settings.collectAsState()
            ExecuServeTheme(settings?.theme ?: ThemeMode.SYSTEM) { App(model) }
        }
    }
}

internal val TABS = listOf(R.string.tab_server, R.string.tab_models, R.string.tab_runs, R.string.tab_settings)

@Composable
private fun App(model: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val server by model.server.collectAsState()
    val status by model.status.collectAsState()
    ConsoleFrame(
        tab = tab,
        onTabSelect = { tab = it },
        header = { Header(stringResource(TABS[tab]), if (tab == 0) null else server, status) },
    ) { padding, twoColumns ->
        when (tab) {
            0 -> ServerScreen(model, padding, twoColumns, openModels = { tab = 1 }, openRuns = { tab = 2 }, openSettings = { tab = 3 })
            1 -> ModelsScreen(model, padding)
            2 -> RunsScreen(model, padding, twoColumns)
            else -> SettingsScreen(model, padding)
        }
    }
}

/**
 * The tab's name, and the server's state from any other tab. The frame keeps the
 * scrolling content below this header.
 */
@Composable
private fun Header(title: String, server: ServeHost.State?, status: EngineStatus?) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = Dimens.gutter + 4.dp, end = Dimens.gutter, top = 12.dp, bottom = Dimens.row),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Mark(32.dp, Modifier.padding(end = 4.dp))
        Column(Modifier.weight(1f)) {
            Text("ExecuServe", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
        }
        if (server != null) {
            val look = ServerLook.of(server, status)
            val tone = LocalTones.current.of(look.mood)
            Dot(tone.color, 8.dp)
            Text(stringResource(look.words), style = MaterialTheme.typography.labelLarge, color = tone.color)
            Spacer(Modifier.width(4.dp))
        }
    }
}
