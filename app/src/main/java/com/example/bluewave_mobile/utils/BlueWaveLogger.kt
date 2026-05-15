package com.example.bluewave_mobile.utils

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide file-backed logger. Every log line is written both to
 * `android.util.Log` (so `adb logcat` continues to work) and to a
 * plaintext file on disk so users can export a full history for
 * bug reports.
 *
 * The file lives in `context.filesDir/logs/bluewave.log`. When it
 * grows beyond [MAX_FILE_BYTES] the existing file is renamed to
 * `bluewave.log.1` and a fresh file is started — a poor-man's
 * single-file rotation that keeps total disk usage below ~4 MB.
 *
 * Thread-safety is handled with `@Synchronized` on every public
 * method. Log volume is expected to be low (chat messages, BT
 * connect/disconnect, DB writes) so coarse locking is acceptable.
 */
object BlueWaveLogger {

    /** Hard ceiling for the active log file (2 MiB). */
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024L

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var logFile: File? = null

    /** Must be called once from `Application.onCreate`. */
    @Synchronized
    fun init(context: Context) {
        if (writer != null) return
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        logFile = File(dir, "bluewave.log")
        rotateIfNeeded()
        writer = BufferedWriter(FileWriter(logFile, true))
        i("BlueWaveLogger", "Logger initialised. File: ${logFile?.absolutePath}")
    }

    @Synchronized
    fun v(tag: String, msg: String) {
        Log.v(tag, msg)
        append('V', tag, msg)
    }

    @Synchronized
    fun v(tag: String, msg: String, tr: Throwable?) {
        Log.v(tag, msg, tr)
        append('V', tag, "$msg\n${Log.getStackTraceString(tr)}")
    }

    @Synchronized
    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        append('D', tag, msg)
    }

    @Synchronized
    fun d(tag: String, msg: String, tr: Throwable?) {
        Log.d(tag, msg, tr)
        append('D', tag, "$msg\n${Log.getStackTraceString(tr)}")
    }

    @Synchronized
    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        append('I', tag, msg)
    }

    @Synchronized
    fun i(tag: String, msg: String, tr: Throwable?) {
        Log.i(tag, msg, tr)
        append('I', tag, "$msg\n${Log.getStackTraceString(tr)}")
    }

    @Synchronized
    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        append('W', tag, msg)
    }

    @Synchronized
    fun w(tag: String, msg: String, tr: Throwable?) {
        Log.w(tag, msg, tr)
        append('W', tag, "$msg\n${Log.getStackTraceString(tr)}")
    }

    @Synchronized
    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
        append('E', tag, msg)
    }

    @Synchronized
    fun e(tag: String, msg: String, tr: Throwable?) {
        Log.e(tag, msg, tr)
        append('E', tag, "$msg\n${Log.getStackTraceString(tr)}")
    }

    /** Returns the current log file or `null` before [init]. */
    fun getLogFile(): File? = logFile

    @Synchronized
    private fun append(level: Char, tag: String, msg: String) {
        val w = writer ?: return
        val timestamp = dateFormat.format(Date())
        try {
            w.write("$timestamp $level/$tag $msg")
            w.newLine()
            w.flush()
            rotateIfNeeded()
        } catch (_: IOException) {
            // If the disk is full we silently drop the line rather
            // than crash the app because of a log write.
        }
    }

    private fun rotateIfNeeded() {
        val file = logFile ?: return
        if (file.length() > MAX_FILE_BYTES) {
            val backup = File(file.parent, "${file.name}.1")
            backup.delete()
            file.renameTo(backup)
        }
    }
}
