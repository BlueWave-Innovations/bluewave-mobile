package com.example.bluewave_mobile.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Compresses images before they are shipped over Bluetooth.
 *
 * The wire format currently does not support streaming/chunking, so
 * keeping photos below a reasonable ceiling is the pragmatic way to
 * guarantee delivery without redesigning the whole protocol.
 *
 * Strategy:
 *  * Decode bounds first to avoid loading a 12 MP image into RAM.
 *  * Down-scale so the long edge is at most [MAX_EDGE_PX].
 *  * Re-encode as JPEG at [JPEG_QUALITY].
 *
 * Non-image files are returned unchanged.
 */
object ImageCompressor {

    /** Long-edge limit in pixels. 1280 covers most phone screens. */
    private const val MAX_EDGE_PX = 1280

    /** JPEG quality — a good balance between visual fidelity and size. */
    private const val JPEG_QUALITY = 85

    /** Files larger than this are candidates for compression. */
    private const val COMPRESS_THRESHOLD_BYTES = 80_000L

    /**
     * Reads the image at [uri], compresses it if necessary, and writes
     * the result to [destFile]. Returns `true` if compression was
     * applied, `false` if the file was copied as-is.
     */
    fun compressIfNeeded(context: Context, uri: Uri, destFile: File): Boolean {
        val mimeType = context.contentResolver.getType(uri) ?: return false
        if (!mimeType.startsWith("image/")) return false

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) return false

        // If both dimensions are already small and the file is small,
        // skip re-encoding to avoid generational quality loss.
        val srcSize = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.statSize ?: 0L
        }.getOrDefault(0L)
        if (options.outWidth <= MAX_EDGE_PX && options.outHeight <= MAX_EDGE_PX
            && srcSize < COMPRESS_THRESHOLD_BYTES
        ) {
            return false
        }

        val scale = min(
            MAX_EDGE_PX.toFloat() / max(options.outWidth, options.outHeight),
            1.0f,
        )
        val dstW = (options.outWidth * scale).toInt()
        val dstH = (options.outHeight * scale).toInt()

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, dstW, dstH)
        }
        val bitmap: Bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: return false

        val scaled = if (bitmap.width != dstW || bitmap.height != dstH) {
            Bitmap.createScaledBitmap(bitmap, dstW, dstH, true).also { bitmap.recycle() }
        } else {
            bitmap
        }

        FileOutputStream(destFile).use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        scaled.recycle()
        BlueWaveLogger.i("ImageCompressor", "Compressed $mimeType to ${dstW}x${dstH}, size=${destFile.length()}")
        return true
    }

    private fun calculateInSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        while (srcW / (inSampleSize * 2) >= reqW && srcH / (inSampleSize * 2) >= reqH) {
            inSampleSize *= 2
        }
        return inSampleSize
    }
}
