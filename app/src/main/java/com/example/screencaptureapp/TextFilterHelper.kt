package com.example.screencaptureapp

import android.util.Log

object TextFilterHelper {
    private const val TAG = "TextFilterHelper"

    /**
     * Clean extracted text by removing system UI elements - NO WHITESPACE FILTERING
     */
    fun cleanExtractedText(rawText: String): String {
        if (rawText.isBlank()) return rawText

        Log.d(TAG, "🧹 Starting text cleaning (${rawText.length} chars)")
        Log.d(TAG, "📝 Raw text: $rawText")

        var cleanedText = rawText

        // Step 1: Remove time patterns
        cleanedText = removeTimePatterns(cleanedText)

        // Step 2: Remove date patterns
        cleanedText = removeDatePatterns(cleanedText)

        // Step 3: Remove system UI patterns
        cleanedText = removeSystemUIPatterns(cleanedText)

        // Step 4: Basic cleanup only (NO aggressive whitespace removal)
        cleanedText = basicCleanup(cleanedText)

        Log.d(TAG, "✨ Text cleaned: ${rawText.length} → ${cleanedText.length} chars")

        if (cleanedText.isNotEmpty()) {
            Log.d(TAG, "🧹 FULL CLEANED TEXT:")
            Log.d(TAG, "--- START CLEANED TEXT ---")
            Log.d(TAG, cleanedText)
            Log.d(TAG, "--- END CLEANED TEXT ---")
        } else {
            Log.d(TAG, "🗑️ All text was filtered out")
        }

        return cleanedText
    }

    /**
     * Remove time patterns like 00:26, 17:55, 10:41, 1:4O
     */
    private fun removeTimePatterns(text: String): String {
        var cleaned = text

        // Time patterns to remove
        val timePatterns = listOf(
            "\\b\\d{1,2}:\\d{2}\\b",      // 00:26, 17:55, 10:41
            "\\b\\d{1,2}:\\d{1}[oO]\\b",  // 1:4O, 1:4o (OCR mistakes)
            "\\b\\d{1,2}:\\d{2}[oO]\\b"   // 10:4O, 17:5O
        )

        for (pattern in timePatterns) {
            val regex = pattern.toRegex()
            val matches = regex.findAll(cleaned).toList()

            for (match in matches) {
                Log.d(TAG, "🕐 Removing time: '${match.value}'")
                cleaned = cleaned.replace(match.value, " ")
            }
        }

        return cleaned
    }

    /**
     * Remove date patterns like Tuesday, Jan 11, 2022, Yesterday
     */
    private fun removeDatePatterns(text: String): String {
        var cleaned = text

        // Date patterns to remove
        val datePatterns = listOf(
            // Full day names
            "\\b(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\\b",
            // Short day names
            "\\b(Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\b",
            // Month day year patterns
            "\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+\\d{1,2},?\\s+\\d{4}\\b",
            // Relative dates
            "\\b(Yesterday|Today|Tomorrow)\\b",
            // Date separators that are left hanging
            "\\s*[,\\-–—]\\s*\\d{4}\\s*[,\\-–—]?\\s*"
        )

        for (pattern in datePatterns) {
            val regex = pattern.toRegex()
            val matches = regex.findAll(cleaned).toList()

            for (match in matches) {
                Log.d(TAG, "📅 Removing date: '${match.value}'")
                cleaned = cleaned.replace(match.value, " ")
            }
        }

        return cleaned
    }

    /**
     * Remove system UI patterns like 94, 80%, bullet points, system messages
     */
    private fun removeSystemUIPatterns(text: String): String {
        var cleaned = text

        // System UI patterns to remove
        val systemPatterns = listOf(
            // Battery percentages and standalone numbers
            "\\b\\d{1,3}%\\b",                    // 80%, 94%
            "\\b\\d{1,2}\\s+\\d{1,3}%\\b",       // 94 80%
            // Bullet points and separators
            "[•●◦]+",                             // Bullet points
            // System messages
            "Can't reply to this short code\\. Learn more",
            "Message not delivered",
            "Delivered",
            "Read \\d{1,2}:\\d{2}",
            "Sending\\.\\.\\.",
            // Network indicators
            "Sending with \\w+",
            // Standalone short numbers that aren't verification codes
            "(?<!code\\s)(?<!verification\\s)(?<!authentication\\s)\\b\\d{1,2}(?!\\d)\\b(?!\\s*for)(?!\\s*Microsoft)(?!\\s*authentication)"
        )

        for (pattern in systemPatterns) {
            val regex = pattern.toRegex()
            val matches = regex.findAll(cleaned).toList()

            for (match in matches) {
                // Double-check we're not removing verification codes
                val matchText = match.value
                val surroundingText = getSurroundingText(cleaned, match.range, 30)

                if (isVerificationCode(matchText, surroundingText)) {
                    Log.d(TAG, "🔐 Preserving verification code: '$matchText'")
                    continue
                }

                Log.d(TAG, "🗑️ Removing system UI: '$matchText'")
                cleaned = cleaned.replace(matchText, " ")
            }
        }

        return cleaned
    }

    /**
     * Check if a number might be a verification code
     */
    private fun isVerificationCode(matchText: String, context: String): Boolean {
        val lowerContext = context.lowercase()

        // Check if it's near verification-related keywords
        val verificationKeywords = listOf(
            "verification", "code", "authenticate", "authentication",
            "verify", "otp", "pin", "passcode"
        )

        return verificationKeywords.any { keyword ->
            lowerContext.contains(keyword)
        }
    }

    /**
     * Get surrounding text for context checking
     */
    private fun getSurroundingText(text: String, range: IntRange, padding: Int): String {
        val start = maxOf(0, range.first - padding)
        val end = minOf(text.length, range.last + padding + 1)
        return text.substring(start, end)
    }

    /**
     * MINIMAL cleanup - preserve original spacing and structure
     */
    private fun basicCleanup(text: String): String {
        return text
            .replace(Regex("\\s*[•●◦]+\\s*"), " ")  // Remove bullet points only
            .replace(Regex("\\s*[\\-–—]{3,}\\s*"), " ") // Remove only long dashes (3+ chars)
            .replace(Regex("\\s{3,}"), " ")         // Only compress 3+ spaces to single space
            .trim()                                 // Just trim edges
    }

    /**
     * Enhanced cleaning with context awareness - NO WHITESPACE FILTERING
     */
    fun intelligentTextCleaning(rawText: String, appPackageName: String?): String {
        // Apply basic cleaning first
        val basicCleaned = cleanExtractedText(rawText)

        // Apply app-specific cleaning if package name provided
        return if (appPackageName != null) {
            applyAppSpecificCleaning(basicCleaned, appPackageName)
        } else {
            basicCleaned
        }
    }

    /**
     * App-specific cleaning - separate method that takes already cleaned text
     */
    fun applyAppSpecificCleaning(cleanedText: String, appPackageName: String): String {
        if (cleanedText.isBlank()) return cleanedText

        Log.d(TAG, "📱 Applying app-specific cleaning for: $appPackageName")

        // Only apply additional app-specific filtering if text doesn't have critical scam content
        if (hasImportantScamContent(cleanedText)) {
            Log.d(TAG, "🔍 Important scam content detected - skipping app-specific filtering")
            return cleanedText
        }

        return when {
            isMessagingApp(appPackageName) -> {
                Log.d(TAG, "📱 Applying messaging app filtering")
                removeMessagingUI(cleanedText)
            }
            isEmailApp(appPackageName) -> {
                Log.d(TAG, "📧 Applying email app filtering")
                removeEmailUI(cleanedText)
            }
            isBrowserApp(appPackageName) -> {
                Log.d(TAG, "🌐 Applying browser filtering")
                removeBrowserUI(cleanedText)
            }
            else -> {
                Log.d(TAG, "📱 No specific app filtering needed")
                cleanedText
            }
        }
    }

    /**
     * Check if text contains important scam-related content
     */
    private fun hasImportantScamContent(text: String): Boolean {
        val lowerText = text.lowercase()

        val criticalPatterns = listOf(
            "verification code", "authentication code", "security code",
            "passcode", "password", "login", "account suspended",
            "expires", "urgent", "winner", "free gift", "click here",
            "verify now", "microsoft", "google", "apple", "amazon",
            "bank account", "credit card", "social security"
        )

        return criticalPatterns.any { pattern ->
            lowerText.contains(pattern)
        }
    }

    /**
     * Check if app is a messaging app
     */
    private fun isMessagingApp(packageName: String): Boolean {
        val messagingApps = listOf(
            "messaging", "whatsapp", "telegram", "signal", "messenger",
            "sms", "mms", "textra", "chomp", "viber", "line", "wechat",
            "discord", "slack"
        )

        return messagingApps.any { app ->
            packageName.lowercase().contains(app)
        }
    }

    /**
     * Check if app is an email app
     */
    private fun isEmailApp(packageName: String): Boolean {
        val emailApps = listOf(
            "gmail", "email", "outlook", "mail", "yahoo", "protonmail",
            "thunderbird", "bluemail", "spark", "newton"
        )

        return emailApps.any { app ->
            packageName.lowercase().contains(app)
        }
    }

    /**
     * Check if app is a browser
     */
    private fun isBrowserApp(packageName: String): Boolean {
        val browserApps = listOf(
            "chrome", "firefox", "browser", "opera", "brave", "edge",
            "safari", "webkit"
        )

        return browserApps.any { app ->
            packageName.lowercase().contains(app)
        }
    }

    /**
     * Remove messaging app UI elements - MINIMAL WHITESPACE CHANGES
     */
    private fun removeMessagingUI(text: String): String {
        var cleaned = text

        val messagingPatterns = listOf(
            "Type a message",
            "Delivered",
            "Read \\d{1,2}:\\d{2}",
            "Online",
            "Typing\\.\\.\\.",
            "Voice message",
            "Photo",
            "Video"
        )

        for (pattern in messagingPatterns) {
            val regex = pattern.toRegex()
            val matches = regex.findAll(cleaned).toList()

            for (match in matches) {
                Log.d(TAG, "💬 Removing messaging UI: '${match.value}'")
                cleaned = cleaned.replace(match.value, " ")
            }
        }

        return basicCleanup(cleaned)
    }

    /**
     * Remove email app UI elements - MINIMAL WHITESPACE CHANGES
     */
    private fun removeEmailUI(text: String): String {
        var cleaned = text

        val emailPatterns = listOf(
            "Inbox \\(\\d+\\)",
            "Compose",
            "Reply",
            "Forward",
            "Archive",
            "Delete",
            "Mark as read"
        )

        for (pattern in emailPatterns) {
            val regex = pattern.toRegex()
            val matches = regex.findAll(cleaned).toList()

            for (match in matches) {
                Log.d(TAG, "📧 Removing email UI: '${match.value}'")
                cleaned = cleaned.replace(match.value, " ")
            }
        }

        return basicCleanup(cleaned)
    }

    /**
     * Remove browser UI elements - MINIMAL WHITESPACE CHANGES
     */
    private fun removeBrowserUI(text: String): String {
        var cleaned = text

        val browserPatterns = listOf(
            "Address bar",
            "Refresh",
            "Back",
            "Forward",
            "Bookmark",
            "History",
            "New tab",
            "Downloads"
        )

        for (pattern in browserPatterns) {
            val regex = pattern.toRegex()
            val matches = regex.findAll(cleaned).toList()

            for (match in matches) {
                Log.d(TAG, "🌐 Removing browser UI: '${match.value}'")
                cleaned = cleaned.replace(match.value, " ")
            }
        }

        return basicCleanup(cleaned)
    }

    /**
     * Check if the cleaned text is worth analyzing
     */
    fun isTextWorthAnalyzing(cleanedText: String): Boolean {
        if (cleanedText.isBlank()) {
            Log.d(TAG, "⚠️ Text is blank")
            return false
        }

        if (cleanedText.length < 10) {
            Log.d(TAG, "⚠️ Text too short: ${cleanedText.length} chars")
            return false
        }

        // Count meaningful words
        val words = cleanedText.split(Regex("\\s+"))
            .filter { it.length > 2 && it.any { char -> char.isLetter() } }

        if (words.size < 3) {
            Log.d(TAG, "⚠️ Too few meaningful words: ${words.size}")
            return false
        }

        Log.d(TAG, "✅ Text is worth analyzing: ${words.size} meaningful words")
        return true
    }
}