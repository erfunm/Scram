package com.example.screencaptureapp

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class NotificationPollingService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1002
        private const val RESPONSE_NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "ResponseChannel"
        private const val TAG = "NotificationPolling"
        private const val POLLING_INTERVAL = 30000L // 30 seconds

        fun start(context: Context) {
            val intent = Intent(context, NotificationPollingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, NotificationPollingService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var pollingRunnable: Runnable? = null
    private var lastNotificationTimestamp = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createServiceNotification())
        startPolling()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "API Response Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows responses from API"
                setShowBadge(true)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createServiceNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Listening for API Responses")
            .setContentText("Checking for new notifications...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startPolling() {
        pollingRunnable = object : Runnable {
            override fun run() {
                checkForNewNotifications()
                handler.postDelayed(this, POLLING_INTERVAL)
            }
        }
        handler.post(pollingRunnable!!)
        Log.d(TAG, "Started polling for notifications")
    }

    private fun checkForNewNotifications() {
        serviceScope.launch {
            try {
                val notification = ApiClient.getLatestNotification()
                if (notification != null && notification.timestamp > lastNotificationTimestamp) {
                    showResponseNotification(notification)
                    lastNotificationTimestamp = notification.timestamp
                    Log.d(TAG, "New notification received: ${notification.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for notifications", e)
            }
        }
    }

    private fun showResponseNotification(response: NotificationResponse) {
        val priority = when (response.priority.lowercase()) {
            "high" -> NotificationCompat.PRIORITY_HIGH
            "max" -> NotificationCompat.PRIORITY_MAX
            "low" -> NotificationCompat.PRIORITY_LOW
            "min" -> NotificationCompat.PRIORITY_MIN
            else -> NotificationCompat.PRIORITY_DEFAULT
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("API Response")
            .setContentText(response.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(response.message))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(priority)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(RESPONSE_NOTIFICATION_ID, notification)
    }

    private fun stopPolling() {
        pollingRunnable?.let { handler.removeCallbacks(it) }
        Log.d(TAG, "Stopped polling for notifications")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}