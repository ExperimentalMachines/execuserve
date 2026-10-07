package org.experimentalmachines.execuserve.app.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * The text of a QR code in a camera frame's brightness (Y) plane, or null. Rows may be padded
 * past the frame's width ([rowStride]), and the last row may stop at the width. A screen seen
 * in a dark room can read inverted, so a frame is tried both ways.
 */
internal class QrFrames {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

    fun read(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        val fits = width > 0 && height > 0 && rowStride >= width
        if (!fits || luminance.size < rowStride * (height - 1) + width) return null
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return decode(source) ?: decode(source.invert())
    }

    private fun decode(source: com.google.zxing.LuminanceSource): String? = try {
        reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (_: com.google.zxing.ReaderException) {
        null
    } finally {
        reader.reset()
    }
}
