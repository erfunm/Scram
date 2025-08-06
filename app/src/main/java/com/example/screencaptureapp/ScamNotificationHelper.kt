package com.example.screencaptureapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

object ScamNotificationHelper {
    private const val TAG = "ScamNotificationHelper"
    private const val SCAM_CHANNEL_ID = "scam_alerts"
    private const val SAFE_CHANNEL_ID = "safe_alerts"
    private const val SCAM_NOTIFICATION_ID = 9999
    private const val SAFE_NOTIFICATION_ID = 9998

    fun createScamAlertChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)

            // High priority channel for scam alerts
            val scamChannel = NotificationChannel(
                SCAM_CHANNEL_ID,
                "Scam Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority alerts for detected scams"
                enableVibration(true)
                setShowBadge(true)
                enableLights(true)
            }

            // Default importance for safe content notifications
            val safeChannel = NotificationChannel(
                SAFE_CHANNEL_ID,
                "Safe Content Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for safe content analysis"
                enableVibration(false)
                setShowBadge(true)
                enableLights(false)
            }

            notificationManager.createNotificationChannel(scamChannel)
            notificationManager.createNotificationChannel(safeChannel)

            Log.d(TAG, "✅ Notification channels created")
        }
    }

    fun showScamAlert(context: Context, scamResult: ScamResult) {
        try {
            if (scamResult.isScam) {
                showScamDetectedNotification(context, scamResult)
            } else {
                showSafeContentNotification(context, scamResult)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to show notification", e)
        }
    }

    private fun showScamDetectedNotification(context: Context, scamResult: ScamResult) {
        try {
            Log.d(TAG, "🚨 Showing scam alert notification")

            // FIXED: Use user-friendly app name instead of package name
            val appDisplayName = getAppDisplayName(scamResult.appContext)

            val title = if (appDisplayName != null) {
                "🚨 SCAM DETECTED in $appDisplayName!"
            } else {
                "🚨 SCAM DETECTED!"
            }

            val content = when (scamResult.appCategory) {
                AppCategory.MESSAGING -> buildMessagingScamContent(appDisplayName, scamResult.explanation)
                AppCategory.EMAIL -> buildEmailScamContent(appDisplayName, scamResult.explanation)
                AppCategory.OTHER -> buildGeneralScamContent(appDisplayName, scamResult.explanation)
            }

            val notification = NotificationCompat.Builder(context, SCAM_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setColor(0xFFFF4444.toInt())
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.notify(SCAM_NOTIFICATION_ID, notification)

            // FIXED: Show toast with proper app name
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val toastMessage = if (appDisplayName != null) {
                    "🚨 SCAM DETECTED in $appDisplayName!"
                } else {
                    "🚨 SCAM DETECTED!"
                }
                android.widget.Toast.makeText(context, toastMessage, android.widget.Toast.LENGTH_LONG).show()
            }

            Log.d(TAG, "✅ Scam alert notification sent successfully!")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to show scam alert notification", e)
        }
    }

    private fun showSafeContentNotification(context: Context, scamResult: ScamResult) {
        try {
            Log.d(TAG, "✅ Showing safe content notification")

            // FIXED: Use user-friendly app name instead of package name
            val appDisplayName = getAppDisplayName(scamResult.appContext)

            val title = if (appDisplayName != null) {
                "✅ Safe Content in $appDisplayName"
            } else {
                "✅ Safe Content Detected"
            }

            val content = when (scamResult.appCategory) {
                AppCategory.MESSAGING -> buildMessagingSafeContent(appDisplayName, scamResult.explanation)
                AppCategory.EMAIL -> buildEmailSafeContent(appDisplayName, scamResult.explanation)
                AppCategory.OTHER -> buildGeneralSafeContent(appDisplayName, scamResult.explanation)
            }

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.cancel(SAFE_NOTIFICATION_ID)

            val notification = NotificationCompat.Builder(context, SAFE_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setColor(0xFF4CAF50.toInt())
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(false)
                .setTimeoutAfter(10000)
                .build()

            notificationManager.notify(SAFE_NOTIFICATION_ID, notification)

            // FIXED: Show toast with proper app name
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val toastMessage = if (appDisplayName != null) {
                    "✅ $appDisplayName analyzed - Safe"
                } else {
                    "✅ Content analyzed - Safe"
                }
                android.widget.Toast.makeText(context, toastMessage, android.widget.Toast.LENGTH_SHORT).show()
            }

            Log.d(TAG, "✅ Safe content notification sent successfully!")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to show safe content notification", e)
        }
    }

    /**
     * FIXED: Convert app context (which might be package name) to user-friendly display name
     */
    private fun getAppDisplayName(appContext: String?): String? {
        if (appContext == null) return null

        // If appContext looks like a package name, try to convert it
        return if (appContext.contains(".")) {
            // This looks like a package name, convert to friendly name
            convertPackageNameToDisplayName(appContext)
        } else {
            // This is already a friendly name, use as-is
            appContext
        }
    }

    /**
     * Convert common package names to user-friendly display names
     */
    private fun convertPackageNameToDisplayName(packageName: String): String {
        return when (packageName.lowercase()) {
            // Google Apps
            "com.google.android.apps.messaging" -> "Messages"
            "com.google.android.gm" -> "Gmail"
            "com.google.android.apps.docs" -> "Google Docs"
            "com.google.android.apps.photos" -> "Google Photos"
            "com.google.android.youtube" -> "YouTube"
            "com.google.android.apps.maps" -> "Google Maps"
            "com.google.android.calendar" -> "Google Calendar"
            "com.google.android.contacts" -> "Contacts"
            "com.google.android.dialer" -> "Phone"

            // Popular Messaging Apps
            "com.whatsapp" -> "WhatsApp"
            "org.telegram.messenger" -> "Telegram"
            "com.facebook.orca" -> "Messenger"
            "com.discord" -> "Discord"
            "com.viber.voip" -> "Viber"
            "jp.naver.line.android" -> "LINE"
            "org.thoughtcrime.securesms" -> "Signal"
            "com.slack" -> "Slack"
            "us.zoom.videomeetings" -> "Zoom"
            "com.microsoft.teams" -> "Microsoft Teams"

            // Email Apps
            "com.microsoft.office.outlook" -> "Outlook"
            "com.yahoo.mobile.client.android.mail" -> "Yahoo Mail"
            "ch.protonmail.android" -> "ProtonMail"
            "com.edison.mail" -> "Edison Mail"
            "com.syntomo.email" -> "Blue Mail"
            "com.airwatch.androidagent" -> "Workspace ONE"

            // Social Media
            "com.facebook.katana" -> "Facebook"
            "com.instagram.android" -> "Instagram"
            "com.twitter.android" -> "Twitter"
            "com.snapchat.android" -> "Snapchat"
            "com.linkedin.android" -> "LinkedIn"
            "com.reddit.frontpage" -> "Reddit"
            "com.pinterest" -> "Pinterest"
            "com.tiktok.musically" -> "TikTok"

            // Banking Apps (common ones)
            "com.chase.sig.android" -> "Chase Mobile"
            "com.bankofamerica.mobile" -> "Bank of America"
            "com.wellsfargo.mobile.android" -> "Wells Fargo Mobile"
            "com.usaa.mobile.android.usaa" -> "USAA Mobile"
            "com.citi.citimobile" -> "Citi Mobile"
            "com.paypal.android.p2pmobile" -> "PayPal"
            "com.venmo" -> "Venmo"
            "com.coinbase.android" -> "Coinbase"

            // Browsers
            "com.android.chrome" -> "Chrome"
            "org.mozilla.firefox" -> "Firefox"
            "com.microsoft.emmx" -> "Microsoft Edge"
            "com.opera.browser" -> "Opera"
            "com.brave.browser" -> "Brave"

            // Default: Extract app name from package name
            else -> {
                try {
                    // Try to extract a reasonable name from package structure
                    val parts = packageName.split(".")
                    when {
                        parts.size >= 3 -> {
                            // com.company.appname -> AppName
                            parts.last().replaceFirstChar {
                                if (it.isLowerCase()) it.titlecase() else it.toString()
                            }
                        }
                        parts.size == 2 -> {
                            // company.app -> App
                            parts.last().replaceFirstChar {
                                if (it.isLowerCase()) it.titlecase() else it.toString()
                            }
                        }
                        else -> packageName.replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase() else it.toString()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse package name: $packageName", e)
                    packageName
                }
            }
        }
    }

    /**
     * Build messaging-specific scam content
     */
    private fun buildMessagingScamContent(appName: String?, explanation: String): String {
        return if (appName != null) {
            "⚠️ Suspicious message detected in $appName! $explanation"
        } else {
            "⚠️ Suspicious message detected! $explanation"
        }
    }

    /**
     * Build email-specific scam content
     */
    private fun buildEmailScamContent(appName: String?, explanation: String): String {
        return if (appName != null) {
            "⚠️ Potential phishing email detected in $appName! $explanation"
        } else {
            "⚠️ Potential phishing email detected! $explanation"
        }
    }

    /**
     * Build general scam content
     */
    private fun buildGeneralScamContent(appName: String?, explanation: String): String {
        return if (appName != null) {
            "⚠️ Suspicious content detected in $appName! $explanation"
        } else {
            "⚠️ Suspicious content detected! $explanation"
        }
    }

    /**
     * Build messaging-specific safe content
     */
    private fun buildMessagingSafeContent(appName: String?, explanation: String): String {
        return if (appName != null) {
            "Message in $appName appears legitimate. $explanation"
        } else {
            "Message appears legitimate. $explanation"
        }
    }

    /**
     * Build email-specific safe content
     */
    private fun buildEmailSafeContent(appName: String?, explanation: String): String {
        return if (appName != null) {
            "Email in $appName appears safe. $explanation"
        } else {
            "Email appears safe. $explanation"
        }
    }

    /**
     * Build general safe content
     */
    private fun buildGeneralSafeContent(appName: String?, explanation: String): String {
        return if (appName != null) {
            "Content in $appName appears safe. $explanation"
        } else {
            "Content appears safe. $explanation"
        }
    }
}