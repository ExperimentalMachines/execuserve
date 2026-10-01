package org.experimentalmachines.execuserve.app.ui

import android.app.Application
import android.content.ClipboardManager
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class TransferTest {
    @Test fun copyTransfersExactValueAndMarksSecrets() {
        val context = RuntimeEnvironment.getApplication()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        assertTrue(copy(context, "es-test-secret", sensitive = true))
        assertEquals("es-test-secret", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(clipboard.primaryClip!!.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        assertTrue(copy(context, "http://192.168.1.20:8080/"))
        assertEquals("http://192.168.1.20:8080/", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertNull(clipboard.primaryClip!!.description.extras)
    }

    @Test fun qrRoundTripsUrlsModelIdsAndUnicodeSecrets() {
        for (value in listOf("http://192.168.1.20:8080/v1", "http://[fd00::1]:8080/", "qwen3-0.6b-8da4w-4k", "es-秘密-test-key")) {
            val matrix = valueQr(value)
            val scale = 8
            val size = matrix.width * scale
            val pixels = IntArray(size * size) { i -> if (matrix[(i % size) / scale, (i / size) / scale]) 0xff000000.toInt() else 0xffffffff.toInt() }
            val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels))))
            assertEquals(value, decoded.text)
            for (y in 0 until 4) for (x in 0 until matrix.width) assertFalse(matrix[x, y])
        }
    }
}
