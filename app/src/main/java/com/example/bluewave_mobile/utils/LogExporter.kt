package com.example.bluewave_mobile.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Helper that copies the internal log file into a user-visible location
 * and optionally fires a share intent.
 */
object LogExporter {

    /**
     * Copies the current log file into the public Downloads collection.
     *
     * On Android 10+ (API 29+) the file is inserted via [MediaStore.Downloads]
     * so no runtime permission is required. On older releases it falls back
     * to the legacy public Downloads directory.
     *
     * @return the public [Uri] on success, or `null` if the log file
     *         does not exist or the copy failed.
     */
    fun exportToDownloads(context: Context): Uri? {
        val source = BlueWaveLogger.getLogFile() ?: return null
        if (!source.exists()) return null
        val timeStamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
            .format(Date())
        val fileName = "bluewave_$timeStamp.log"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insertViaMediaStore(context, source, fileName)
        } else {
            copyToLegacyDownloads(source, fileName)
        }
    }

    private fun insertViaMediaStore(context: Context, source: File, fileName: String): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/BlueWave")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, contentValues) ?: return null
        return try {
            resolver.openOutputStream(itemUri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            }
            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, contentValues, null, null)
            BlueWaveLogger.i("LogExporter", "Exported logs to MediaStore: $itemUri")
            itemUri
        } catch (e: IOException) {
            BlueWaveLogger.e("LogExporter", "MediaStore export failed", e)
            resolver.delete(itemUri, null, null)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun copyToLegacyDownloads(source: File, fileName: String): Uri? {
        val destDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "BlueWave",
        ).apply { mkdirs() }
        val destFile = File(destDir, fileName)
        return try {
            source.copyTo(destFile, overwrite = true)
            BlueWaveLogger.i("LogExporter", "Exported logs to: ${destFile.absolutePath}")
            Uri.fromFile(destFile)
        } catch (e: IOException) {
            BlueWaveLogger.e("LogExporter", "Legacy export failed", e)
            null
        }
    }

    /**
     * Creates a share intent for the current log file via [FileProvider].
     * The host activity should wrap this in a chooser.
     */
    fun shareIntent(context: Context): Intent? {
        val file = BlueWaveLogger.getLogFile() ?: return null
        if (!file.exists()) return null
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
