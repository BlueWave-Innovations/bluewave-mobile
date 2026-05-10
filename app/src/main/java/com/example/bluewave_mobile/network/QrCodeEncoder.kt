package com.example.bluewave_mobile.network

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Pure-Kotlin QR code encoder backed by `com.google.zxing:core`.
 *
 * Renders a [QrContactPayload] as an ARGB [Bitmap] sized [edgeSize]
 * × [edgeSize] pixels. The bitmap is opaque (white background, black
 * modules) and uses [Bitmap.Config.ARGB_8888] so the calling
 * composable can hand it straight to `BitmapPainter` without any
 * additional pixel-format conversion.
 *
 * Error correction level is fixed to `H` (≈30% of the modules can be
 * erased and the QR is still recoverable). The expected reading
 * surface is a phone screen photographed from another phone's lens
 * — partial occlusion, glare and oblique angles are likely so the
 * higher level is worth the extra modules.
 */
object QrCodeEncoder {

    /**
     * Encode [payload] into a square [Bitmap].
     *
     * @param edgeSize Edge size of the rendered bitmap in pixels. The
     *                 ZXing matrix is upscaled or downsampled to fit
     *                 — pass at least 256 for legibility on high-DPI
     *                 phones.
     * @return A square ARGB_8888 bitmap, or `null` when the payload
     *         is malformed and ZXing refuses to produce a matrix.
     */
    fun encode(payload: QrContactPayload, edgeSize: Int = 512): Bitmap? {
        require(edgeSize > 0) { "edgeSize must be positive, was $edgeSize" }
        val text = payload.toUri()
        val hints: Map<EncodeHintType, Any> = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = runCatching {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, edgeSize, edgeSize, hints)
        }.getOrNull() ?: return null
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                pixels[rowOffset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
