package com.example.screencaptureapp

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi

object AppDetectorHelper {
    private const val TAG = "AppDetector"

    /**
     * Check if we have usage stats permission
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val currentTime = System.currentTimeMillis()
                val usageEvents = usageStatsManager.queryEvents(currentTime - 10000, currentTime)
                usageEvents.hasNextEvent()
            } catch (e: Exception) {
                Log.e(TAG, "Error checking usage stats permission", e)
                false
            }
        } else {
            false
        }
    }

    /**
     * Request usage stats permission from user
     */
    fun requestUsageStatsPermission(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Get the currently running foreground app package name
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun getCurrentApp(context: Context): String? {
        return try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val currentTime = System.currentTimeMillis()

            // Query events from last 10 seconds
            val usageEvents = usageStatsManager.queryEvents(currentTime - 10000, currentTime)

            var lastResumedPackage: String? = null
            var lastEventTime = 0L
            val event = UsageEvents.Event()

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                    event.timeStamp > lastEventTime) {
                    lastResumedPackage = event.packageName
                    lastEventTime = event.timeStamp
                }
            }

            lastResumedPackage
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current app", e)
            null
        }
    }

    /**
     * Get user-friendly app name from package name
     */
    fun getAppName(context: Context, packageName: String): String {
        return try {
            val packageManager = context.packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName // Return package name if we can't get the friendly name
        }
    }

    /**
     * Get app category for customizing prompts (focused on messaging and email)
     */
    fun getAppCategory(packageName: String): AppCategory {
        return when {
            // Messaging and SMS Apps
            packageName.equals("com.google.android.apps.messaging", ignoreCase = true) ||
                    packageName.contains("whatsapp", true) ||
                    packageName.contains("telegram", true) ||
                    packageName.contains("signal", true) ||
                    packageName.contains("messenger", true) ||
                    packageName.contains("sms", true) ||
                    packageName.contains("messages", true) ||
                    packageName.contains("mms", true) ||
                    packageName.contains("textra", true) ||
                    packageName.contains("chomp", true) ||
                    packageName.contains("handcent", true) ||
                    packageName.contains("gosms", true) ||
                    packageName.contains("viber", true) ||
                    packageName.contains("line", true) ||
                    packageName.contains("wechat", true) ||
                    packageName.contains("kik", true) ||
                    packageName.contains("discord", true) ||
                    packageName.contains("slack", true) -> AppCategory.MESSAGING

            // Email Apps
            packageName.equals("com.google.android.gm", ignoreCase = true) ||
                    packageName.contains("gmail", true) ||
                    packageName.contains("email", true) ||
                    packageName.contains("outlook", true) ||
                    packageName.contains("mail", true) ||
                    packageName.contains("yahoo", true) ||
                    packageName.contains("protonmail", true) ||
                    packageName.contains("thunderbird", true) ||
                    packageName.contains("bluemail", true) ||
                    packageName.contains("spark", true) ||
                    packageName.contains("newton", true) -> AppCategory.EMAIL

            // All other apps use general detection
            else -> AppCategory.OTHER
        }
    }

    /**
     * Get customized prompt based on app category - Let Gemma detect language automatically
     */
    fun getCustomizedPrompt(category: AppCategory, extractedText: String): String {
        return when (category) {
            AppCategory.MESSAGING -> buildMessagingPrompt(extractedText)
            AppCategory.EMAIL -> buildEmailPrompt(extractedText)
            AppCategory.OTHER -> buildGeneralPrompt(extractedText)
        }
    }

    /**
     * SMS/Messaging app specific prompt - Gemma detects language automatically
     */
    private fun buildMessagingPrompt(text: String): String {
        return """You are a multilingual scam detection assistant. Answer in the same language as the text. Work strictly from the text content; do not assume anything. If evidence is insufficient, choose "not_scam".
            
IMPORTANT: The text is a an OCR extracted message. Don't count grammar issues or suspicious formatting as scam.          

RUBRIC (score positives AND negatives):

+2 asks for personal/financial info (passwords, card, OTP sharing)
+2 asks to click a LINK or CALL a number to fix/pay/verify
+1 threats/penalties ("account suspended", "legal action") or urgency ("act now")
+1 domain/URL looks unrelated to the claimed brand (or a shortener)
+1 payment/gift-card/crypto request

-2 OTP/verification message that says "do not share" and DOES NOT ask to click/call or provide info
-1 informational notification (missed call, delivery/appointment reminder) with no request to act
-1 link to a clearly matching official domain of the brand (e.g., brand.tld or well-known subdomain)

DECISION:
- If total score ≥ 3 → label = "scam"
- Else → label = "not_scam"

IMPORTANT:
- Do NOT mark OTP messages as "scam" just for containing a code or "do not share".
- If unsure, default to "not_scam".
Texts like: "Nest Your passcode to log in is 150952. This will expire in minutes. 80% Can't reply to this short code. Learn more" that are only giving information or codes are not Scams.


---
$text
---

Respond in the same language as the input, exactly as JSON:
{"label": "scam" or "not_scam", "reason": "brief explanation"}"""
    }

    /**
     * Email app specific prompt - Gemma detects language automatically
     */
    private fun buildEmailPrompt(text: String): String {
        return """You are a multilingual assistant trained to detect phishing and scam emails. Answer in the same language as the text. Work strictly from the text content; do not assume anything. If evidence is insufficient, choose "not_scam".

IMPORTANT: The text is a an OCR extracted email. Don't count grammar issues or suspicious formatting as scam.

Step 1: Detect the language of the email below.

Step 2: Assess the email for scam using these 5 criteria:

1. Does it request sensitive personal/financial information?
2. Does it create urgency, fear, or legal threat?
3. Does it offer financial rewards, job offers, or fake invoices?
4. Does it contain mismatched sender info, shady links, or formatting that mimics a known brand?

Classification:
- If 2 or more are YES → classify as scam
- Else → not scam

Email: "$text"

Respond in the same language as the input email, exactly as JSON:
{"label": "scam" or "not_scam", "reason": "brief explanation"}"""
    }

    /**
     * General prompt for other apps - Gemma detects language automatically
     */
    private fun buildGeneralPrompt(text: String): String {
        return """Analyze this text for scams or fraud.

Text: "$text"

Look for:
- Personal/financial info requests
- Urgent pressure tactics
- Too-good-to-be-true offers
- Threats or fear tactics
- Suspicious links/contacts
- Fake company impersonation

Respond in the same language as the input text, exactly as JSON:
{"label": "scam" or "not_scam", "reason": "brief explanation"}"""
    }

    // REMOVED: formatGemmaChatTemplate function - system prompts are already included in each prompt
}

enum class AppCategory {
    MESSAGING,  // SMS, WhatsApp, Telegram, etc.
    EMAIL,      // Gmail, Outlook, etc.
    OTHER       // All other apps (uses general prompt)
}