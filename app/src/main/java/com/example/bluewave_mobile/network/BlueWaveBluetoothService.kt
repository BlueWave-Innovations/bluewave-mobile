package com.example.bluewave_mobile.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.bluewave_mobile.BlueWaveApplication
import com.example.bluewave_mobile.MainActivity
import com.example.bluewave_mobile.R

/**
 * Foreground service that keeps the RFCOMM accept-loop alive even
 * when the user backgrounds the app.
 *
 * Android aggressively kills background processes — without a
 * foreground service the accept-loop in [BluetoothSessionManager]
 * dies the moment the OS reclaims the process. Once dead, inbound
 * RFCOMM connections from peers go unanswered and the chat stops
 * delivering messages. This service is the standard pattern that
 * every Android BT messenger / torrent client uses to opt out of
 * that lifecycle reclamation: the OS guarantees foreground
 * services stay alive as long as the persistent notification is
 * visible.
 *
 * Foreground-service type is `connectedDevice` because BlueWave is
 * a peer-to-peer connected-device app. Android 14+ enforces that
 * the manifest, the runtime `startForeground` call and the
 * declared `<uses-permission>` set all agree on the type;
 * `connectedDevice` only requires `BLUETOOTH_CONNECT` (already
 * granted to BlueWave on the device-list flow) plus the new
 * [android.Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE]
 * permission, which is normal-protection so it auto-grants at
 * install time.
 *
 * Lifecycle:
 *  * [BlueWaveApplication.onCreate] starts this service via
 *    [ContextCompat.startForegroundService]; the service then
 *    calls [ServiceCompat.startForeground] with a typed
 *    notification, satisfying the 5-second `startForeground` SLA.
 *  * [MainActivity] also re-starts the service from `onStart` so
 *    that an Activity-only restart after a process kill (e.g.
 *    swipe-from-recents on a low-RAM device) brings the listener
 *    back online without a full app re-launch.
 *  * The service itself does not own the
 *    [BluetoothSessionManager] / [BlueWaveSdpProber] singletons —
 *    they live on the process-wide
 *    [BlueWaveApplication.applicationScope]. The service exists
 *    purely to keep that process alive.
 */
class BlueWaveBluetoothService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                },
            )
        } catch (e: SecurityException) {
            // Thrown when FOREGROUND_SERVICE_CONNECTED_DEVICE is held
            // but the BLUETOOTH_CONNECT runtime permission was not yet
            // granted at the moment the OS validated the FGS type
            // (Android 14+ does this check at startForeground time).
            // Falling back to stopSelf is the documented recovery —
            // the user-facing flow re-tries the service start the
            // moment BLUETOOTH_CONNECT lands through
            // PermissionGateView's launcher result callback.
            Log.w(TAG, "startForeground rejected (likely missing BLUETOOTH_CONNECT)", e)
            stopSelf()
            return START_NOT_STICKY
        }
        // START_STICKY: the OS re-creates the service after a
        // memory-pressure kill, which re-enters this onStartCommand
        // with a null intent — that's still enough to call
        // startForeground again and bring the accept loop back.
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.bt_service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.bt_service_channel_description)
            setShowBadge(false)
            // The persistent listener notification is informational
            // and should never make a sound / vibrate — the channel
            // is locked to LOW importance so a "Found new BlueWave
            // peer" alert can later live on its own MEDIUM channel.
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            this,
            PENDING_INTENT_REQUEST_CODE,
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bluewave_notification)
            .setContentTitle(getString(R.string.bt_service_notification_title))
            .setContentText(getString(R.string.bt_service_notification_text))
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(tapPendingIntent)
            .build()
    }

    companion object {
        private const val TAG = "BlueWaveBtService"

        /** Notification channel ID, exposed for testing / debugging. */
        const val CHANNEL_ID = "bluewave_bt_listener"

        /** Foreground notification ID. Stable across restarts. */
        private const val NOTIFICATION_ID = 0xB1E
        private const val PENDING_INTENT_REQUEST_CODE = 0xBE

        /**
         * Idempotent helper: starts the foreground service if it is
         * not already running. Safe to call from
         * [android.app.Application.onCreate] (treated as "in-use"
         * by the OS on cold launch) and from
         * [android.app.Activity.onStart].
         */
        fun start(context: Context) {
            val intent = Intent(context, BlueWaveBluetoothService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {
                // Documented retry path: API 31+ enforces stricter
                // background-start rules. Failing to start does not
                // crash the app — the accept-loop just runs without
                // the FGS guarantee until the user comes back into
                // foreground and we re-try via MainActivity.onStart.
                Log.w(TAG, "startForegroundService rejected", e)
            }
        }

        /**
         * Idempotent helper for symmetry with [start]. Not currently
         * wired into the app — the FGS is intentionally kept alive
         * for the lifetime of the process — but exposed so a future
         * "disable background BT" setting can stop the listener
         * without ripping the singleton out of the application
         * class.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, BlueWaveBluetoothService::class.java))
        }
    }
}
