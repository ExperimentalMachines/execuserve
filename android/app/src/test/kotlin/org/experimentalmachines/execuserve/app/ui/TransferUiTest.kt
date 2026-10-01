package org.experimentalmachines.execuserve.app.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.experimentalmachines.execuserve.host.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransferUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    @Config(shadows = [DeniedClipboard::class])
    fun deniedClipboardOpensUsableQrInsteadOfClaimingCopied() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            activity.get().setContent {
                ExecuServeTheme(ThemeMode.LIGHT) {
                    CopyRow("es-test-secret", shown = "es-…ret", qr = true, sensitive = true)
                }
            }
            compose.onNodeWithText("Copy").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Copied").assertDoesNotExist()
            compose.onNodeWithText("Reveal QR code").performClick()
            compose.waitForIdle()
            val dialog = ShadowDialog.getLatestDialog()
            assertTrue(dialog.isShowing)
            assertEquals(0, dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            compose.onNodeWithText("Close").performClick()
            compose.waitForIdle()
            assertFalse(dialog.isShowing)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Implements(ClipboardManager::class)
    class DeniedClipboard {
        @Implementation
        // The parameter is the signature Robolectric matches the shadow on.
        @Suppress("UnusedParameter", "UNUSED_PARAMETER")
        fun setPrimaryClip(clip: ClipData): Unit = throw SecurityException("Clipboard unavailable")
    }

    @Test fun buttonsCopyUnderlyingSecretAndRevealWithoutBlankingRemoteViewer() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            activity.get().setContent {
                ExecuServeTheme(ThemeMode.LIGHT) {
                    CopyRow("es-test-secret", shown = "es-…ret", qr = true, sensitive = true)
                }
            }
            compose.onNodeWithText("Copy").performClick()
            compose.waitForIdle()
            assertEquals("es-test-secret", activity.get().getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text)
            compose.onNodeWithText("Copied").assertExists()
            compose.onNodeWithText("Show QR").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Reveal QR code").performClick()
            compose.waitForIdle()
            val dialog = ShadowDialog.getLatestDialog()
            assertTrue(dialog.isShowing)
            assertEquals(0, dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            compose.onNodeWithText("Close").performClick()
            compose.waitForIdle()
            assertFalse(dialog.isShowing)
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
