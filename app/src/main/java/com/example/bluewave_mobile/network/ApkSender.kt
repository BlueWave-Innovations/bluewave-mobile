package com.example.bluewave_mobile.network

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands the locally-installed APK to the system Bluetooth share UI so
 * the user can offer it to a peer that does not yet have BlueWave.
 *
 * **Why we delegate to the system instead of pushing the APK
 * ourselves:** Android's classic Bluetooth Object Push Profile (OPP)
 * is implemented by the system Bluetooth app (`com.android.bluetooth`)
 * and is reachable by any process via [Intent.ACTION_SEND] with the
 * APK MIME type. Implementing OPP ourselves would require either
 * Device Owner / MDM privileges or a bespoke RFCOMM transfer protocol
 * that the receiving end (which by definition does NOT have BlueWave
 * installed yet) cannot understand. Routing through OPP is the
 * Android-blessed, friction-minimising path.
 *
 * The flow is:
 *
 *  1. [stageApk] copies the live APK into the app's cache so a
 *     [FileProvider] URI is available — the system bluetooth-share UI
 *     can only consume content URIs, not raw `file://` paths.
 *  2. [suggestInstall] fires [Intent.ACTION_SEND] addressed to
 *     `com.android.bluetooth`. The system shows its standard "share
 *     to Bluetooth" picker; the user selects the target MAC, the peer
 *     receives a "Accept incoming file" dialog, after which Android
 *     stores the APK in `/sdcard/Bluetooth/`.
 *  3. The receiving user opens the APK and gets the standard
 *     "install from unknown sources" prompt — this is platform UX
 *     that we cannot (and intentionally do not) bypass.
 */
class ApkSender(private val context: Context) {

    /**
     * Authority for the FileProvider that serves the staged APK URI.
     * Must match the `<provider>` declaration in `AndroidManifest.xml`.
     */
    private val authority: String = "${context.packageName}.fileprovider"

    /**
     * Path inside [Context.getCacheDir] where the live APK is staged.
     * Re-used across calls — the file is written once at app start
     * and refreshed only when [Context.getApplicationInfo].sourceDir
     * changes (i.e. after a self-update).
     */
    private val stagedFile: File = File(context.cacheDir, "BlueWave-current.apk")

    /**
     * Copies the running APK into the cache so [suggestInstall] has
     * something to share. Safe to call from
     * [android.app.Application.onCreate]; runs synchronously and is
     * cheap because the source file is already in the app sandbox.
     *
     * Returns `true` when the staged file is up to date, `false` when
     * the running APK could not be located (rare; only happens on
     * malformed installs).
     */
    fun stageApk(): Boolean {
        val sourcePath: String = try {
            context.packageManager
                .getApplicationInfo(context.packageName, 0)
                .sourceDir
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Cannot locate own package for APK staging", e)
            return false
        }
        val source = File(sourcePath)
        if (!source.exists()) {
            Log.w(TAG, "Source APK does not exist at $sourcePath")
            return false
        }
        // Skip the copy if the cached file is already an exact mirror.
        if (stagedFile.exists() && stagedFile.length() == source.length()) {
            return true
        }
        return try {
            source.copyTo(stagedFile, overwrite = true)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stage APK to cache", e)
            false
        }
    }

    /**
     * Launches the system Bluetooth share dialog with the staged APK
     * as the payload.
     *
     * Returns:
     *  * `Result.success` when an activity was started — either the
     *    direct path through `com.android.bluetooth`, or the fallback
     *    chooser when that explicit package is not resolvable
     *    (forks of AOSP rename it on rare devices).
     *  * `Result.failure` when the APK is not staged (call
     *    [stageApk] first) or when no activity could handle the
     *    send intent at all.
     */
    fun suggestInstall(chooserTitle: String = DEFAULT_CHOOSER_TITLE): Result<Unit> {
        if (!stagedFile.exists()) {
            Log.w(TAG, "Cannot suggest install — APK has not been staged yet")
            return Result.failure(IllegalStateException("APK not staged"))
        }
        val uri: Uri = try {
            FileProvider.getUriForFile(context, authority, stagedFile)
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = APK_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Try to address the system Bluetooth share activity
            // directly. If the device renames the package the chooser
            // fallback below kicks in.
            setPackage(SYSTEM_BLUETOOTH_PACKAGE)
        }

        return try {
            context.startActivity(sendIntent)
            Result.success(Unit)
        } catch (e: Exception) {
            // No direct route — fall back to the share-sheet chooser.
            try {
                val chooser = Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = APK_MIME_TYPE
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    chooserTitle,
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
                Result.success(Unit)
            } catch (chooserError: Exception) {
                Log.w(TAG, "No activity available to handle APK share", chooserError)
                Result.failure(chooserError)
            }
        }
    }

    private companion object {
        const val TAG = "ApkSender"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val SYSTEM_BLUETOOTH_PACKAGE = "com.android.bluetooth"
        // Plain-English fallback for the chooser sheet when the caller
        // does not pass a localized title. The Compose layer at the
        // call site normally provides a localized [AppStrings] value.
        const val DEFAULT_CHOOSER_TITLE = "Send BlueWave via Bluetooth"
    }
}
