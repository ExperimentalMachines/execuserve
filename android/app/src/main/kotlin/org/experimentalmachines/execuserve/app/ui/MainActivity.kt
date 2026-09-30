package org.experimentalmachines.execuserve.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
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

private val TABS = listOf(R.string.tab_server, R.string.tab_models, R.string.tab_runs, R.string.tab_settings)

/**
 * The frame. Below 600 dp a bottom bar; from 600 dp (a phone on its side, a foldable, a
 * tablet) a navigation rail, and the screens lay out in two columns where they have them.
 */
@Composable
private fun App(model: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val server by model.server.collectAsState()
    val status by model.status.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= Dimens.wide
        // The rail sits beside the scaffold, not inside it, so it owns the full height and
        // its own insets; inside, the header covered its first item (seen in landscape).
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    Spacer(Modifier.height(8.dp))
                    TABS.forEachIndexed { index, label ->
                        NavigationRailItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { NavGlyph(index, tab == index) },
                            label = { Text(stringResource(label)) },
                            colors = NavigationRailItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.background,
                // The Server tab's own card shows the state; the other tabs carry it here.
                topBar = { Header(stringResource(TABS[tab]), if (tab == 0) null else server, status) },
                bottomBar = {
                    if (!wide) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            TABS.forEachIndexed { index, label ->
                                NavigationBarItem(
                                    selected = tab == index,
                                    onClick = { tab = index },
                                    icon = { NavGlyph(index, tab == index) },
                                    label = { Text(stringResource(label)) },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                val content = PaddingValues(
                    start = Dimens.gutter,
                    end = Dimens.gutter,
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + Dimens.gutter,
                )
                // Cards read best at a phone's width or a little more; a tablet centres them.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    // The two-column tabs grow wider; a single column stays at reading width.
                    val limit = Modifier.widthIn(max = if ((tab == 0 || tab == 2) && wide) Dimens.columns else Dimens.column)
                    Box(limit) {
                        when (tab) {
                            0 -> ServerScreen(model, content, wide, openModels = { tab = 1 }, openRuns = { tab = 2 })
                            1 -> ModelsScreen(model, content)
                            2 -> RunsScreen(model, content, wide)
                            else -> SettingsScreen(model, content)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The tab's name, and the server's state from any other tab. Opaque, status bar included:
 * content scrolls under it, and a transparent header let the two overlap (seen on the
 * emulator).
 */
@Composable
private fun Header(title: String, server: ServeHost.State?, status: EngineStatus?) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = Dimens.gutter + 4.dp, end = Dimens.gutter, top = 12.dp, bottom = Dimens.row),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
        if (server != null) {
            val look = ServerLook.of(server, status)
            val tone = LocalTones.current.of(look.mood)
            Dot(tone.color, 8.dp)
            Text(stringResource(look.words), style = MaterialTheme.typography.labelLarge, color = tone.color)
            Spacer(Modifier.width(4.dp))
        }
    }
}
