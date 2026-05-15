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
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.bluewave_mobile.utils.BlueWaveLogger
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
        // Re-verify permissions on every start (system restart after
        // user revocation must not crash with SecurityException).
        val hasBtPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasBtPermission) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val notification = buildForegroundNotification()
        startForeground(FOREGROUND_NOTIFICATION_ID, notification)

        val app = application as BlueWaveApplication
        app.container.messageRepository.onMessageReceived = { senderMac, senderName, text ->
            postMessageNotification(senderMac, senderName, text)
        }

        // If the system restarted us (START_STICKY) we may have lost
        // sessions while the process was dead. Re-connect immediately
        // so the user does not have to open the app to become online.
        app.triggerAutoConnect()

        return START_STICKY
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
        val notificationId = stableNotificationId(senderMac)
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
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
        try {
            manager.notify(notificationId, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS was revoked while the service was running.
            BlueWaveLogger.w(TAG, "Cannot post message notification — permission revoked", e)
        }
    }

    /**
     * Derives a stable, positive notification ID from a MAC address.
     * Uses the low 31 bits of the MAC hex value so the same peer
     * always maps to the same ID (updates instead of duplicates).
     */
    private fun stableNotificationId(mac: String): Int {
        val hex = mac.uppercase().replace(":", "").replace("-", "")
        return (hex.toLongOrNull(16) ?: 0L).toInt() and 0x7FFFFFFF
    }

    companion object {
        private const val TAG = "BluetoothFgService"
        const val CHANNEL_SERVICE = "bluewave_service"
        const val CHANNEL_MESSAGES = "bluewave_messages"
        const val FOREGROUND_NOTIFICATION_ID = 1
        const val EXTRA_OPEN_CHAT_MAC = "open_chat_mac"

        fun start(context: Context) {
            val hasBtPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasBtPermission) return
            // Android 13+ requires POST_NOTIFICATIONS for foreground service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasNotifPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasNotifPermission) return
            }
            val intent = Intent(context, BluetoothForegroundService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: IllegalStateException) {
                // Android 12+: app is not in a valid foreground state.
                BlueWaveLogger.w(TAG, "Cannot start foreground service from background", e)
            }
        }
    }
}
