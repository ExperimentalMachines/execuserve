package org.experimentalmachines.execuserve.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.experimentalmachines.execuserve.host.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Compose measurements: asymmetric insets, constrained windows and enlarged text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w1400dp-h1000dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConsoleFrameTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private var scenario by mutableStateOf(Scenario())
    private var twoColumns = false
    private var selectedTab = -1

    private data class Scenario(
        val width: Int = 920,
        val height: Int = 400,
        val left: Int = 24,
        val top: Int = 24,
        val right: Int = 48,
        val bottom: Int = 0,
        val fontScale: Float = 1f,
        val tab: Int = 0,
        val direction: LayoutDirection = LayoutDirection.Ltr,
        val theme: ThemeMode = ThemeMode.LIGHT,
    )

    @Before fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        activity.get().setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, scenario.fontScale),
                LocalLayoutDirection provides scenario.direction,
            ) {
                ExecuServeTheme(scenario.theme) {
                    Box(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
                        ConsoleFrame(
                            tab = scenario.tab,
                            onTabSelect = { selectedTab = it },
                            header = { Text("Header", Modifier.testTag("header").height(48.dp)) },
                            modifier = Modifier.requiredSize(scenario.width.dp, scenario.height.dp),
                            insets = WindowInsets(scenario.left, scenario.top, scenario.right, scenario.bottom),
                        ) { padding, wide ->
                            twoColumns = wide
                            Row(Modifier.fillMaxSize().testTag("content")) {
                                LazyColumn(Modifier.weight(1f).testTag("primary"), contentPadding = padding) {
                                    item { Panel("Connect") { CopyRow("http://192.168.100.123:8080/v1") } }
                                }
                                if (wide) {
                                    LazyColumn(Modifier.weight(1f).testTag("secondary"), contentPadding = padding) {
                                        item { Panel("Runs") { Text("No requests yet") } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @After fun tearDown() {
        if (::activity.isInitialized) activity.pause().stop().destroy()
    }

    private fun show(value: Scenario) {
        compose.runOnIdle { scenario = value }
        compose.waitForIdle()
    }

    private fun assertSafeBounds() {
        val header = compose.onNodeWithTag("header").getUnclippedBoundsInRoot()
        val body = compose.onNodeWithTag("content").getUnclippedBoundsInRoot()
        assertTrue("Header crosses left inset: $header", header.left.value >= scenario.left)
        assertTrue("Header crosses right inset: $header", header.right.value <= scenario.width - scenario.right)
        assertEquals(scenario.top.toFloat(), header.top.value, 0.5f)
        // Chat sets its own margins inside the content; every other tab gets the frame's gutter.
        val gutter = if (scenario.tab == Tabs.CHAT) 0 else 16
        assertTrue("Content crosses left inset: $body", body.left.value >= scenario.left + gutter)
        assertTrue("Content crosses right inset: $body", body.right.value <= scenario.width - scenario.right - gutter)
        assertTrue("Content overlaps header: $body", body.top.value >= header.bottom.value)
        assertTrue("Content crosses bottom inset: $body", body.bottom.value <= scenario.height - scenario.bottom)
        compose.onNodeWithText("Copy").assertIsDisplayed()
        val copy = compose.onNodeWithText("Copy").getUnclippedBoundsInRoot()
        assertTrue("Copy action crosses right edge: $copy", copy.right.value <= body.right.value)
    }

    @Test fun bothLandscapeRotationsProtectEveryTab() {
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            for (tab in TABS.indices) {
                for ((left, right) in listOf(24 to 48, 48 to 24)) {
                    show(Scenario(left = left, right = right, tab = tab, theme = theme))
                    assertSafeBounds()
                    assertEquals(tab != Tabs.CHAT, twoColumns)
                }
            }
        }
    }

    @Test fun gestureInsetsAreAppliedOnce() {
        show(Scenario(left = 32, right = 0, bottom = 24))
        assertSafeBounds()
        val body = compose.onNodeWithTag("content").getUnclippedBoundsInRoot()
        assertEquals(920f - 16, body.right.value, 0.5f)
        assertEquals(400f - 24, body.bottom.value, 0.5f)
    }

    @Test fun narrowLandscapeKeepsRailButUsesOneColumn() {
        show(Scenario(width = 720))
        assertSafeBounds()
        assertTrue(!twoColumns)
        compose.onNodeWithText("Settings").performScrollTo().assertIsDisplayed()
    }

    @Test fun largeTextGetsReadableColumns() {
        show(Scenario(fontScale = 1.5f))
        assertSafeBounds()
        assertTrue(!twoColumns)
        show(Scenario(width = 1280, height = 800, fontScale = 1.5f))
        assertSafeBounds()
        assertTrue(twoColumns)
    }

    @Test fun portraitAndSplitScreenUseBottomNavigation() {
        for (width in listOf(400, 560)) {
            show(Scenario(width = width, height = 850, left = 0, right = 0, bottom = 24))
            assertSafeBounds()
            assertTrue(!twoColumns)
            compose.onNodeWithText("Settings").assertIsDisplayed().performClick()
            assertEquals(Tabs.SETTINGS, selectedTab)
        }
    }

    @Test fun shortLandscapeAndKeyboardKeepLastTabReachable() {
        show(Scenario(height = 240, bottom = 40))
        compose.onNodeWithText("Settings").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(Tabs.SETTINGS, selectedTab)
    }

    @Test fun rtlKeepsPhysicalInsetsOnTheirReportedSides() {
        show(Scenario(direction = LayoutDirection.Rtl))
        assertSafeBounds()
    }
}
