package org.experimentalmachines.execuserve.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTest {
    @Test
    fun aLongReplyIsReadWholeInPiecesCutBetweenWords() {
        val reply = (1..400).joinToString(" ") { "word$it." }
        val pieces = reply.pieces(100)
        assertTrue(pieces.all { it.length <= 100 })
        // Nothing is dropped, and no word is cut in two.
        assertEquals(reply.split(" "), pieces.flatMap { it.split(" ") })
    }

    @Test
    fun aShortReplyIsOnePieceAndMarkdownIsNotReadOut() {
        assertEquals(listOf("Hello there."), "Hello there.".pieces(4000))
        assertEquals("Use the bold word and code.", "Use the **bold** word and `code`.".forSpeech())
    }
}
