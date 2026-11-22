package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Draws the pairing deep link as a QR code.
 *
 * The encoder is ZXing's, which the app already ships for the diagnostic screens: a hand-written
 * encoder is one wrong mask pattern away from producing codes that some phone cameras read and
 * others do not, and the only way to find out is to point a phone at the screen.
 *
 * Error correction is M (about 15 % recoverable). The code is small — one link, about 120 bytes — so
 * M already survives a windscreen reflection; H would grow the modules and make the code harder to
 * read from a phone held at arm's length in a car.
 */
object HiCarQrCode {

    /**
     * A square bitmap of [sizePx], black modules on white.
     *
     * White rather than transparent: a QR code with a transparent background shows whatever is
     * behind it through the quiet zone, and that is what makes a phone refuse to focus on it.
     */
    fun bitmap(text: String, sizePx: Int, quietZoneModules: Int = 2): Bitmap? {
        if (text.isEmpty() || sizePx < 64) return null
        val hints = mapOf(
            EncodeHintType.MARGIN to quietZoneModules,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = runCatching {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        }.getOrNull() ?: return null
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
