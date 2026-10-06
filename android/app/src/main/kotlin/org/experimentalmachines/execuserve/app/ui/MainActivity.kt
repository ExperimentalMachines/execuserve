package org.experimentalmachines.execuserve.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.experimentalmachines.execuserve.app.R
import org.experimentalmachines.execuserve.engine.EngineStatus
import org.experimentalmachines.execuserve.host.ServeHost
import org.experimentalmachines.execuserve.host.ServerLook
import org.experimentalmachines.execuserve.host.ThemeMode

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

/** The tabs, in order; Chat sits in the middle, between finding a model and reading what it did. */
internal object Tabs {
    const val HOSTING = 0
    const val LIBRARY = 1
    const val CHAT = 2
    const val ACTIVITY = 3
    const val SETTINGS = 4
}

internal val TABS = listOf(R.string.tab_server, R.string.tab_models, R.string.tab_chat, R.string.tab_runs, R.string.tab_settings)

@Composable
private fun App(model: MainViewModel) {
    // Nothing installed yet: start in the Library, where the catalog is.
    var tab by rememberSaveable { mutableIntStateOf(if (model.installed.value.isEmpty()) Tabs.LIBRARY else Tabs.HOSTING) }
    val server by model.server.collectAsState()
    val status by model.status.collectAsState()
    ConsoleFrame(
        tab = tab,
        onTabSelect = { tab = it },
        header = { Header(stringResource(TABS[tab]), if (tab == Tabs.HOSTING) null else server, status) },
    ) { padding, twoColumns ->
        when (tab) {
            Tabs.HOSTING -> ServerScreen(
                model,
                padding,
                twoColumns,
                openModels = { tab = Tabs.LIBRARY },
                openRuns = { tab = Tabs.ACTIVITY },
                openSettings = { tab = Tabs.SETTINGS },
                openChat = { id ->
                    model.chooseChatModel(id)
                    tab = Tabs.CHAT
                },
            )
            Tabs.LIBRARY -> ModelsScreen(model, padding, twoColumns)
            Tabs.CHAT -> ChatScreen(model, padding, openModels = { tab = Tabs.LIBRARY })
            Tabs.ACTIVITY -> RunsScreen(model, padding, twoColumns)
            else -> SettingsScreen(model, padding, twoColumns)
        }
    }
}

/**
 * The mark and the name, and the server's state from any tab but Hosting. The tab's own name
 * is in the navigation, so the header does not repeat it on screen; a screen reader still
 * hears it with the name. The frame keeps the scrolling content below this header.
 */
@Composable
private fun Header(tab: String, server: ServeHost.State?, status: EngineStatus?) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = Dimens.gutter + 4.dp, end = Dimens.gutter, top = 12.dp, bottom = Dimens.row),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Mark(36.dp, Modifier.padding(end = 4.dp))
        val name = stringResource(R.string.app_name)
        Text(
            name,
            Modifier.weight(1f).semantics {
                heading()
                contentDescription = "$name, $tab"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (server != null) {
            val look = ServerLook.of(server, status)
            val tone = LocalTones.current.of(look.mood)
            Dot(tone.color, 8.dp)
            Text(stringResource(look.words), style = MaterialTheme.typography.labelLarge, color = tone.color)
            Spacer(Modifier.width(4.dp))
        }
    }
}
