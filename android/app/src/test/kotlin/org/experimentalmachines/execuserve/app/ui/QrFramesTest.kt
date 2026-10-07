package org.experimentalmachines.execuserve.app.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrFramesTest {
    private val text = "EXECUSERVE-PAIR:0123456789abcdef0123456789abcdef"

    /** A camera-like Y plane: the code on a grey frame, rows padded, the last row cut at the width. */
    private fun frame(width: Int, height: Int, stride: Int, inverted: Boolean = false): ByteArray {
        val code = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, height / 2, height / 2)
        val left = (width - code.width) / 2
        val top = (height - code.height) / 2
        val data = ByteArray(stride * (height - 1) + width) { 0x70 }
        for (y in 0 until code.height) {
            for (x in 0 until code.width) {
                val dark = code[x, y] != inverted
                data[(top + y) * stride + left + x] = if (dark) 0x10 else 0xF0.toByte()
            }
        }
        return data
    }

    @Test
    fun readsACodeFromAPaddedFrameEitherWayRound() {
        val reader = QrFrames()
        assertEquals(text, reader.read(frame(640, 480, 704), 704, 640, 480))
        assertEquals(text, reader.read(frame(640, 480, 704, inverted = true), 704, 640, 480))
    }

    @Test
    fun aFrameWithNoCodeOrTheWrongShapeReadsNothing() {
        val reader = QrFrames()
        assertNull(reader.read(ByteArray(640 * 480) { 0x70 }, 640, 640, 480))
        assertNull(reader.read(ByteArray(10), 640, 640, 480))
        assertNull(reader.read(ByteArray(640 * 480), 600, 640, 480))
    }
}
