package org.experimentalmachines.execuserve.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTextTest {
    @Test
    fun whileStreamingAnOpenSpanIsClosedAndANewMarkerHeldBack() {
        // Bold that has begun reads as bold now, not as asterisks until it ends.
        assertEquals("The text is **repeated and**", ChatText.closeOpen("The text is **repeated and"))
        // A marker with nothing after it yet is not shown at all.
        assertEquals("The text is", ChatText.closeOpen("The text is **"))
        assertEquals("run `ls`", ChatText.closeOpen("run `ls"))
        // Closed spans, earlier lines and open code fences are left as they are.
        assertEquals("**done** and more", ChatText.closeOpen("**done** and more"))
        assertEquals("**a\nb", ChatText.closeOpen("**a\nb"))
        assertEquals("```\ncode **x", ChatText.closeOpen("```\ncode **x"))
    }

    @Test
    fun anOpenFenceIsClosedAndAnEvenOneLeftAlone() {
        assertEquals("```kotlin\nval a = 1\n```", "```kotlin\nval a = 1".withClosedFence())
        assertEquals("```\nx\n```", "```\nx\n```".withClosedFence())
    }

    @Test
    fun imagesBecomeLinksAndTasksGetBoxesOutsideCodeOnly() {
        assertEquals("[cat](https://x/c.png)", "![cat](https://x/c.png)".withLinkedImages())
        assertEquals("- ☑ done", "- [x] done".withCheckboxes())
        assertEquals("```\n- [x] kept\n```", "```\n- [x] kept\n```".withCheckboxes())
    }
}
