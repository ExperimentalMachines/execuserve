package org.experimentalmachines.execuserve.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTextTest {
    private val code = Color.LightGray

    @Test
    fun boldAndCodeLoseTheirMarkersAndKeepTheirStyle() {
        val text = ChatText.styled("1. **Low latency**: use `adb` here", code)
        assertEquals("1. Low latency: use adb here", text.text)
        val bold = text.spanStyles.single { it.item.fontWeight == FontWeight.SemiBold }
        assertEquals("Low latency", text.text.substring(bold.start, bold.end))
        val mono = text.spanStyles.single { it.item.fontFamily == FontFamily.Monospace }
        assertEquals("adb", text.text.substring(mono.start, mono.end))
    }

    @Test
    fun headingsLoseTheirHashesAndLinesStayLines() {
        val text = ChatText.styled("## Plan\n- one\n- two", code)
        assertEquals("Plan\n- one\n- two", text.text)
        assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.SemiBold && text.text.substring(it.start, it.end) == "Plan" })
    }

    @Test
    fun anUnfinishedMarkerMidStreamShowsAsTyped() {
        assertEquals("partial **bold so far", ChatText.styled("partial **bold so far", code).text)
    }

    @Test
    fun whileStreamingAnOpenSpanIsClosedAndANewMarkerHeldBack() {
        // Bold that has begun reads as bold now, not as asterisks until it ends.
        val streaming = ChatText.styled(ChatText.closeOpen("The text is **repeated and"), code)
        assertEquals("The text is repeated and", streaming.text)
        assertTrue(streaming.spanStyles.any { it.item.fontWeight == FontWeight.SemiBold && streaming.text.substring(it.start, it.end) == "repeated and" })
        // A marker with nothing after it yet is not shown at all.
        assertEquals("The text is", ChatText.closeOpen("The text is **"))
        assertEquals("run `ls", ChatText.closeOpen("run `ls").removeSuffix("`"))
        assertEquals("run `ls`", ChatText.closeOpen("run `ls"))
        // Closed spans, earlier lines and open code fences are left as they are.
        assertEquals("**done** and more", ChatText.closeOpen("**done** and more"))
        assertEquals("**a\nb", ChatText.closeOpen("**a\nb"))
        assertEquals("```\ncode **x", ChatText.closeOpen("```\ncode **x"))
    }

    @Test
    fun horizontalRulesAreDroppedNotPrintedAsDashes() {
        assertEquals("One\n\nTwo", ChatText.styled("One\n\n---\n\nTwo", code).text)
    }
}
