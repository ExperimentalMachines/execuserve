package org.experimentalmachines.execuserve.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Insets belong to the whole frame, once: either landscape rotation can put navigation
 * controls or a cutout on a side. Navigation and content both stay inside that safe area.
 * The rail and the two-column layout have independent breakpoints; columns are measured
 * after the rail and page gutters, and leave more room when text is enlarged.
 */
@Composable
internal fun ConsoleFrame(
    tab: Int,
    onTabSelect: (Int) -> Unit,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    insets: WindowInsets = WindowInsets.safeDrawing,
    content: @Composable (PaddingValues, Boolean) -> Unit,
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(insets),
    ) {
        val rail = maxWidth >= Dimens.wide
        Row(Modifier.fillMaxSize()) {
            if (rail) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                ) {
                    // A short landscape window or an open keyboard must not hide tabs.
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        TABS.forEachIndexed { index, label ->
                            NavigationRailItem(
                                selected = tab == index,
                                onClick = { onTabSelect(index) },
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
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = header,
                bottomBar = {
                    if (!rail) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            windowInsets = WindowInsets(0, 0, 0, 0),
                        ) {
                            TABS.forEachIndexed { index, label ->
                                NavigationBarItem(
                                    selected = tab == index,
                                    onClick = { onTabSelect(index) },
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
                // Apply frame padding to the viewport, not independently to each list.
                BoxWithConstraints(
                    Modifier.fillMaxSize()
                        .padding(padding)
                        .consumeWindowInsets(padding)
                        .padding(horizontal = Dimens.gutter),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                    val twoColumns = (tab == Tabs.HOSTING || tab == Tabs.ACTIVITY) &&
                        minOf(maxWidth, Dimens.columns) >= Dimens.minColumn * fontScale * 2 + Dimens.gutter
                    Box(
                        Modifier.widthIn(max = if (twoColumns) Dimens.columns else Dimens.column).fillMaxSize(),
                    ) {
                        content(PaddingValues(top = 4.dp, bottom = Dimens.gutter), twoColumns)
                    }
                }
            }
        }
    }
}
