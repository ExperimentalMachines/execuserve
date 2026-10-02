package org.experimentalmachines.execuserve.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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

class ContentReportTest {
    @Test
    fun theTextCarriesTheFieldsThenTheReplyAndLeavesBlanksOut() {
        val text = ContentReport.text("Report", listOf("Model" to "qwen3", "Reason" to "Harmful", "Note" to "  "), "Reply:", " the reply ")
        assertEquals("Report\n\nModel: qwen3\nReason: Harmful\n\nReply:\nthe reply", text)
    }

    @Test
    fun aVeryLongReplyIsCutRatherThanOverflowingTheShare() {
        val text = ContentReport.text("Report", emptyList(), "Reply:", "x".repeat(ContentReport.MAX_REPLY_CHARS + 50))
        assertTrue(text.endsWith("x…"))
        assertEquals(ContentReport.MAX_REPLY_CHARS, text.substringAfter("Reply:\n").count { it == 'x' })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReportDialogTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun aReportNeedsAReasonAndSharesExactlyWhatItShows() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var shared: Pair<String, String>? = null
        var dismissed = false
        try {
            activity.get().setContent {
                ExecuServeTheme(ThemeMode.LIGHT) {
                    ReportDialog(
                        model = "qwen3-1.7b",
                        reply = "A reply someone found offensive.",
                        version = "0.1.0 (7)",
                        onDismiss = { dismissed = true },
                        onShare = { subject, text -> shared = subject to text },
                    )
                }
            }
            // No reason yet: nothing can be shared.
            compose.onNodeWithText("Share report").assertIsNotEnabled()
            compose.onNodeWithText("Offensive or hateful").performClick()
            compose.onNodeWithText("Note (optional)").performTextInput("It insults a group.")
            compose.onNodeWithText("Share report").assertIsEnabled().performClick()
            compose.waitForIdle()

            val (subject, text) = shared!!
            assertEquals("ExecuServe content report: qwen3-1.7b", subject)
            for (part in listOf("Model: qwen3-1.7b", "Reason: Offensive or hateful", "Note: It insults a group.", "App version: 0.1.0 (7)")) {
                assertTrue("missing '$part' in:\n$text", part in text)
            }
            assertTrue(text.endsWith("Reply:\nA reply someone found offensive."))
            assertFalse(dismissed)
        } finally {
            activity.close()
        }
    }

    @Test
    fun cancelDismissesWithoutSharing() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var shared = false
        var dismissed = false
        try {
            activity.get().setContent {
                ExecuServeTheme(ThemeMode.LIGHT) {
                    ReportDialog("m", "r", "v", onDismiss = { dismissed = true }, onShare = { _, _ -> shared = true })
                }
            }
            compose.onNodeWithText("Cancel").performClick()
            compose.waitForIdle()
            assertTrue(dismissed)
            assertFalse(shared)
        } finally {
            activity.close()
        }
    }
}
