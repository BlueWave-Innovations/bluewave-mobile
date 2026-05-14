package com.example.bluewave_mobile.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.bluewave_mobile.BlueWaveApplication
import com.example.bluewave_mobile.MainActivity
import com.example.bluewave_mobile.R

/**
 * Foreground service that keeps the Bluetooth RFCOMM accept loop
 * and session manager alive when the app is in the background.
 *
 * Android kills background processes aggressively; without a
 * foreground service the accept loop (and therefore incoming
 * messages) would stop within minutes of the user leaving the app.
 *
 * The service also posts per-message notifications for incoming
 * text messages via the [CHANNEL_MESSAGES] notification channel.
 */
class BluetoothForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-verify the BLUETOOTH_CONNECT runtime permission inside
        // onStartCommand, not only in the static `start(context)`
        // companion. Two paths can bring us here without the
        // permission held:
        //   * Android can resurrect a `START_STICKY` service whose
        //     process was killed by the OS — by the time it does so,
        //     the user may have revoked Nearby devices.
        //   * On Android 16 (targetSdk 36) the OS validates the
        //     `connectedDevice` foreground-service type at the
        //     moment `startForeground` is invoked, not when
        //     `startForegroundService` was queued. If permission
        //     was revoked between those two calls, startForeground
        //     throws SecurityException and crashes the process.
        // In either case, stop quietly with START_NOT_STICKY so the
        // OS doesn't retry the loop; the next user-driven permission
        // grant in DeviceListScreen will start the service cleanly.
        if (!hasConnectedDevicePermission()) {
            Log.w(
                TAG,
                "BLUETOOTH_CONNECT not granted; aborting foreground service start",
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val notification = buildForegroundNotification()
        try {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Race window: permission was revoked between the
            // permission check above and the actual `startForeground`
            // call. The system has already rejected us, so the only
            // safe move is to stop quietly.
            Log.w(TAG, "startForeground rejected by system", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val app = application as BlueWaveApplication
        app.container.messageRepository.onMessageReceived = { senderMac, senderName, text ->
            postMessageNotification(senderMac, senderName, text)
        }

        return START_STICKY
    }

    private fun hasConnectedDevicePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val app = application as? BlueWaveApplication
        app?.container?.messageRepository?.onMessageReceived = null
        super.onDestroy()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            getString(R.string.notification_channel_service),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_service_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(serviceChannel)

        val messagesChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            getString(R.string.notification_channel_messages),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.notification_channel_messages_desc)
            enableVibration(true)
        }
        manager.createNotificationChannel(messagesChannel)
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.notification_service_title))
            .setContentText(getString(R.string.notification_service_text))
            .setSmallIcon(R.drawable.ic_bluetooth_connected)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun postMessageNotification(senderMac: String, senderName: String, text: String) {
        val openChatIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_CHAT_MAC, senderMac)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            senderMac.hashCode(),
            openChatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val displayName = senderName.ifBlank { senderMac }
        val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setContentTitle(displayName)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_message)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(senderMac.hashCode(), notification)
    }

    companion object {
        private const val TAG = "BluetoothFgService"
        const val CHANNEL_SERVICE = "bluewave_service"
        const val CHANNEL_MESSAGES = "bluewave_messages"
        const val FOREGROUND_NOTIFICATION_ID = 1
        const val EXTRA_OPEN_CHAT_MAC = "open_chat_mac"

        fun start(context: Context) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) return
            val intent = Intent(context, BluetoothForegroundService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: SecurityException) {
                // Background-start restrictions or a freshly revoked
                // permission can still trip this on Android 12+. The
                // foreground-service launch path is best-effort — if
                // the OS rejects us here, the in-process RFCOMM accept
                // loop launched by BlueWaveApplication.onCreate keeps
                // running while the activity is alive, so messaging
                // works inside the app even without the service.
                Log.w(TAG, "startForegroundService rejected by system", e)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "startForegroundService rejected by system", e)
            }
        }
    }
}
