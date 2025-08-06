package com.example.screencaptureapp

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.*
import kotlin.coroutines.resume

object ScamDetectionModelHelper {
    private const val TAG = "ScamDetectionModel"
    private const val MAX_TOKENS = 4096  // INCREASED: For longer prompts and responses
    private const val TEMPERATURE = 0.2f
    private const val TOP_K = 20
    private const val TOP_P = 0.9f
    private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes

    // PERSISTENT COMPONENTS - Created once, reused many times
    private var llmInference: LlmInference? = null
    private var session: LlmInferenceSession? = null
    private var isInitialized = false
    private var initializationContext: Context? = null // Store context for re-initialization

    // Idle management
    private var lastUsedTime = 0L
    private var idleCleanupJob: Job? = null
    private val lifecycleScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // MUTEX FIX: Add cleanup protection flags
    @Volatile private var isCleaningUp = false
    @Volatile private var isInitializing = false

    /**
     * CRITICAL FIX: Auto re-initialize if model was cleaned up
     */
    private suspend fun ensureModelInitialized(context: Context): Boolean {
        // If already initialized and working, just update usage time
        if (isInitialized && llmInference != null && session != null) {
            Log.d(TAG, "⚡ Model already initialized - reusing existing instance")
            updateLastUsedTime()
            scheduleIdleCleanup()
            return true
        }

        // If model was cleaned up, auto re-initialize
        if (!isInitialized || llmInference == null || session == null) {
            Log.d(TAG, "🔄 Model was cleaned up or not initialized - auto re-initializing...")
            return initialize(context)
        }

        return false
    }

    suspend fun initialize(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Prevent concurrent initialization
                if (isInitializing) {
                    Log.d(TAG, "⏳ Model initialization already in progress - waiting...")
                    while (isInitializing) {
                        delay(100)
                    }
                    return@withContext isInitialized
                }

                isInitializing = true

                // Store context for future re-initialization
                initializationContext = context.applicationContext

                if (isInitialized && llmInference != null && session != null) {
                    Log.d(TAG, "✅ Model already initialized - reusing existing instance")
                    updateLastUsedTime()
                    scheduleIdleCleanup()
                    isInitializing = false
                    return@withContext true
                }

                Log.d(TAG, "🔄 Initializing Gemma-3n model for scam detection...")
                val startTime = System.currentTimeMillis()

                // Clean up any existing instance first
                cleanUpInternal()

                // Check if model is downloaded
                val modelFile = if (ModelDownloader.isModelDownloaded(context)) {
                    ModelDownloader.getModelFile(context)
                } else {
                    Log.e(TAG, "❌ Model file not found - need to download first")
                    isInitializing = false
                    return@withContext false
                }

                if (!modelFile.exists() || modelFile.length() < 1000000000) {
                    Log.e(TAG, "❌ Invalid model file: exists=${modelFile.exists()}, size=${modelFile.length()}")
                    isInitializing = false
                    return@withContext false
                }

                Log.d(TAG, "📁 Model file validated: ${modelFile.length() / 1024 / 1024} MB")

                // Create PERSISTENT LLM inference engine WITH GPU BACKEND
                llmInference = LlmInference.createFromOptions(
                    context,
                    LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelFile.absolutePath)
                        .setMaxTokens(MAX_TOKENS)  // Now using 4096 tokens
                        .setPreferredBackend(LlmInference.Backend.GPU) // Use GPU acceleration
                        .build()
                )

                if (llmInference == null) {
                    Log.e(TAG, "❌ Failed to create LlmInference")
                    isInitializing = false
                    return@withContext false
                }

                // Create PERSISTENT session - Will be reset before each query
                session = LlmInferenceSession.createFromOptions(
                    llmInference!!,
                    LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTopK(TOP_K)
                        .setTopP(TOP_P)
                        .setTemperature(TEMPERATURE)
                        .build()
                )

                if (session == null) {
                    Log.e(TAG, "❌ Failed to create LlmInferenceSession")
                    llmInference?.close()
                    llmInference = null
                    isInitializing = false
                    return@withContext false
                }

                isInitialized = true
                updateLastUsedTime()

                val initTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "✅ Gemma-3n model initialized successfully with GPU backend in ${initTime}ms")
                Log.d(TAG, "🚀 Session created and ready for multilingual scam detection (${MAX_TOKENS} token limit)")

                // Schedule idle cleanup
                scheduleIdleCleanup()

                isInitializing = false
                true

            } catch (e: Exception) {
                Log.e(TAG, "❌ Model initialization failed", e)
                cleanUpInternal()
                isInitializing = false
                false
            }
        }
    }

    /**
     * FIXED: Actually reset the session to clear token accumulation
     */
    private fun resetSessionForNewQuery() {
        try {
            Log.d(TAG, "🔄 Resetting session to clear token history...")

            // CRITICAL: Close the old session to clear tokens
            session?.close()
            session = null

            // Create a completely fresh session with zero tokens
            session = LlmInferenceSession.createFromOptions(
                llmInference!!,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(TOP_K)
                    .setTopP(TOP_P)
                    .setTemperature(TEMPERATURE)
                    .build()
            )

            Log.d(TAG, "✅ Fresh session created with zero token history")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Session reset failed", e)
            throw e // Re-throw to prevent using broken session
        }
    }

    // UPDATED: Context-aware analysis with improved language detection
    suspend fun analyzeTextForScamWithContext(context: Context, extractedText: String): ScamResult = withContext(Dispatchers.IO) {
        try {
            // CRITICAL FIX: Auto re-initialize if needed
            if (!ensureModelInitialized(context)) {
                Log.e(TAG, "❌ Failed to initialize/re-initialize model")
                return@withContext ScamResult(
                    isScam = false,
                    confidence = 0f,
                    explanation = "Model initialization failed"
                )
            }

            updateLastUsedTime()

            // Use your existing AppDetectorHelper for app context detection
            val currentApp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                AppDetectorHelper.hasUsageStatsPermission(context)) {
                AppDetectorHelper.getCurrentApp(context)
            } else {
                null
            }

            // Get app category and custom prompt using your existing logic
            val appCategory = currentApp?.let { AppDetectorHelper.getAppCategory(it) } ?: AppCategory.OTHER

            // FIXED: Use the improved prompt generation with better language detection
            val customPrompt = AppDetectorHelper.getCustomizedPrompt(appCategory, extractedText)

            // Log app context for debugging
            currentApp?.let { packageName ->
                val appName = AppDetectorHelper.getAppName(context, packageName)
                Log.d(TAG, "🔍 Analyzing text from: $appName ($packageName) - Category: $appCategory")
            }

            Log.d(TAG, "🚀 Starting multilingual analysis with fresh session...")
            val analysisStart = System.currentTimeMillis()

            // CRITICAL: Reset session to avoid token overflow
            resetSessionForNewQuery()

            // Now add query to fresh session
            session!!.addQueryChunk(customPrompt)

            val response = suspendCancellableCoroutine<String> { continuation ->
                var fullResponse = ""
                session!!.generateResponseAsync { partialResult, done ->
                    fullResponse += partialResult
                    Log.d(TAG, "📝 Partial: $partialResult (done: $done)")
                    if (done) {
                        continuation.resume(fullResponse)
                    }
                }
            }

            val analysisTime = System.currentTimeMillis() - analysisStart
            Log.d(TAG, "⚡ Multilingual context-aware analysis complete in ${analysisTime}ms")
            Log.d(TAG, "🤖 Response: ${response.take(200)}...")

            // Session is automatically fresh for next use
            scheduleIdleCleanup()

            // Parse the response and add context info
            val parsedResult = parseMultilingualResponse(response)
            ScamResult(
                isScam = parsedResult.isScam,
                confidence = parsedResult.confidence,
                explanation = parsedResult.explanation,
                appContext = currentApp?.let { AppDetectorHelper.getAppName(context, it) },
                appCategory = appCategory
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ Context-aware analysis error", e)
            // On error, reset session for next use
            try {
                resetSessionForNewQuery()
            } catch (resetError: Exception) {
                Log.e(TAG, "❌ Failed to reset session after error", resetError)
            }
            ScamResult(
                isScam = false,
                confidence = 0f,
                explanation = "Analysis failed: ${e.message}"
            )
        }
    }

    // Keep the original method for backward compatibility - now returns ScamResult
    suspend fun analyzeTextForScam(text: String): ScamResult {
        // CRITICAL FIX: Need context for re-initialization
        val context = initializationContext
        if (context == null) {
            Log.e(TAG, "❌ No context available for model initialization")
            return ScamResult(
                isScam = false,
                confidence = 0f,
                explanation = "No context available for model initialization"
            )
        }

        return withContext(Dispatchers.IO) {
            val analysisStart = System.currentTimeMillis()

            try {
                // CRITICAL FIX: Auto re-initialize if needed
                if (!ensureModelInitialized(context)) {
                    Log.e(TAG, "❌ Failed to initialize/re-initialize model")
                    return@withContext ScamResult(
                        isScam = false,
                        confidence = 0f,
                        explanation = "Model initialization failed"
                    )
                }

                updateLastUsedTime()

                val prompt = """<start_of_turn>user
You are an expert scam detector. Analyze text and respond in JSON format.

Analyze the following text for potential scam indicators.
Consider these red flags:
- Urgency or time pressure
- Requests for personal information (SSN, passwords, bank details)
- Suspicious links or email addresses
- Too good to be true offers
- Poor grammar or spelling
- Impersonation of legitimate companies
- Requests for money or gift cards

Text to analyze:
"$text"

Respond exactly as JSON:
{"label": "scam" or "not_scam", "reason": "brief explanation"}
<end_of_turn>
<start_of_turn>model
""".trimIndent()

                Log.d(TAG, "🚀 Starting basic analysis with fresh session...")

                // CRITICAL: Reset session before each query
                resetSessionForNewQuery()

                // Now add query to fresh session
                session!!.addQueryChunk(prompt)

                val result = suspendCancellableCoroutine<String> { continuation ->
                    var fullResponse = ""

                    session!!.generateResponseAsync { partialResult, done ->
                        fullResponse += partialResult
                        Log.d(TAG, "📝 Partial: $partialResult (done: $done)")

                        if (done) {
                            continuation.resume(fullResponse)
                        }
                    }
                }

                val analysisTime = System.currentTimeMillis() - analysisStart
                Log.d(TAG, "⚡ Basic analysis complete in ${analysisTime}ms (using fresh session)")

                // Update idle timer
                scheduleIdleCleanup()

                // Convert response to ScamResult for consistency
                val analysisResult = parseMultilingualResponse(result)
                return@withContext ScamResult(
                    isScam = analysisResult.isScam,
                    confidence = analysisResult.confidence,
                    explanation = analysisResult.explanation,
                    appContext = null, // No app context in basic analysis
                    appCategory = AppCategory.OTHER
                )

            } catch (e: Exception) {
                Log.e(TAG, "❌ Analysis error", e)
                // On error, reset session for next use
                try {
                    resetSessionForNewQuery()
                } catch (resetError: Exception) {
                    Log.e(TAG, "❌ Failed to reset session after error", resetError)
                }
                return@withContext ScamResult(
                    isScam = false,
                    confidence = 0f,
                    explanation = "Analysis failed: ${e.message}"
                )
            }
        }
    }

    /**
     * UPDATED: Enhanced multilingual response parsing
     */
    private fun parseMultilingualResponse(response: String): ScamResult {
        return try {
            Log.d(TAG, "📝 Parsing multilingual response: ${response.take(300)}...")

            // Try to parse JSON response first
            val jsonStart = response.indexOf('{')
            val jsonEnd = response.lastIndexOf('}') + 1

            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonStr = response.substring(jsonStart, jsonEnd)
                Log.d(TAG, "🔍 Extracted JSON: $jsonStr")

                // FIXED: Enhanced multilingual scam detection
                val isScam = detectScamInMultipleLanguages(jsonStr)

                // FIXED: Better regex pattern that handles escaped quotes and long text
                val explanation = extractReasonFromJson(jsonStr)

                val confidence = if (isScam) 0.85f else 0.15f

                Log.d(TAG, "✅ Parsed multilingual result: isScam=$isScam, explanation=${explanation.take(100)}...")

                ScamResult(
                    isScam = isScam,
                    confidence = confidence,
                    explanation = explanation
                )
            } else {
                // Fallback: structured format parsing
                val lines = response.lines()
                val riskLine = lines.find { it.contains("scam", ignoreCase = true) } ?: ""
                val reasonLine = lines.find { it.contains("reason", ignoreCase = true) } ?: ""

                val risk = detectScamInResponse(response)
                val reason = reasonLine.ifEmpty { response.take(200) }

                ScamResult(
                    isScam = risk,
                    confidence = if (risk) 0.7f else 0.3f,
                    explanation = reason
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to parse multilingual response: $response", e)
            // Fallback parsing with multilingual detection
            val isScam = detectScamInResponse(response)

            ScamResult(
                isScam = isScam,
                confidence = if (isScam) 0.7f else 0.3f,
                explanation = "Analysis: ${response.take(100)}..."
            )
        }
    }

    /**
     * FIXED: Better JSON reason extraction that handles complex text with quotes
     */
    private fun extractReasonFromJson(jsonStr: String): String {
        return try {
            // Method 1: Find "reason": and extract until the closing quote, handling escaped quotes
            val reasonStart = jsonStr.indexOf("\"reason\":")
            if (reasonStart == -1) {
                return "Analysis completed"
            }

            // Find the opening quote after "reason":
            val contentStart = jsonStr.indexOf("\"", reasonStart + 9) // 9 = length of "reason":
            if (contentStart == -1) {
                return "Analysis completed"
            }

            // Find the closing quote, but handle escaped quotes
            var contentEnd = contentStart + 1
            var foundEnd = false

            while (contentEnd < jsonStr.length && !foundEnd) {
                val char = jsonStr[contentEnd]
                if (char == '"') {
                    // Check if this quote is escaped
                    var escapeCount = 0
                    var checkPos = contentEnd - 1
                    while (checkPos >= 0 && jsonStr[checkPos] == '\\') {
                        escapeCount++
                        checkPos--
                    }
                    // If even number of backslashes (including 0), quote is not escaped
                    if (escapeCount % 2 == 0) {
                        foundEnd = true
                    } else {
                        contentEnd++
                    }
                } else {
                    contentEnd++
                }
            }

            if (foundEnd) {
                val reason = jsonStr.substring(contentStart + 1, contentEnd)
                // Clean up escaped quotes
                return reason.replace("\\\"", "\"").replace("\\\\", "\\")
            }

            // Method 2: Fallback - try simple regex but with better pattern
            val reasonMatch = "\"reason\"\\s*:\\s*\"([^\"]*(?:\\\\.[^\"]*)*)\""
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(jsonStr)

            if (reasonMatch != null) {
                return reasonMatch.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
            }

            // Method 3: Last resort - extract everything between reason": " and next "
            val reasonPattern = "\"reason\"\\s*:\\s*\"([^}]+)\""
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(jsonStr)

            reasonPattern?.groupValues?.get(1)?.trim() ?: "Analysis completed"

        } catch (e: Exception) {
            Log.w(TAG, "❌ Failed to extract reason from JSON: ${e.message}")
            "Analysis completed"
        }
    }

    /**
     * ENHANCED MULTILINGUAL SCAM DETECTION - Detects "scam" in multiple languages within JSON
     */
    private fun detectScamInMultipleLanguages(jsonString: String): Boolean {
        val lowerJson = jsonString.lowercase()

        // FIRST: Check for explicit "not scam" patterns to avoid false positives
        val notScamPatterns = listOf(
            // English variations
            "\"label\":\\s*\"not[_\\s-]*scam\"",  // Matches "not_scam", "not scam", "not-scam"
            "\"label\":\\s*\"no[_\\s-]*scam\"",   // Matches "no_scam", "no scam"
            "\"label\":\\s*\"safe\"",
            "\"label\":\\s*\"legitimate\"",
            "\"label\":\\s*\"clean\"",
            "\"label\":\\s*\"valid\"",
            "\"label\":\\s*\"normal\"",

            // Spanish
            "\"label\":\\s*\"no[_\\s-]*estafa\"",
            "\"label\":\\s*\"seguro\"",
            "\"label\":\\s*\"legítimo\"",
            "\"label\":\\s*\"válido\"",

            // French
            "\"label\":\\s*\"pas[_\\s-]*arnaque\"",
            "\"label\":\\s*\"sûr\"",
            "\"label\":\\s*\"légitime\"",
            "\"label\":\\s*\"valide\"",

            // German
            "\"label\":\\s*\"kein[_\\s-]*betrug\"",
            "\"label\":\\s*\"sicher\"",
            "\"label\":\\s*\"legitim\"",
            "\"label\":\\s*\"gültig\"",

            // Italian
            "\"label\":\\s*\"non[_\\s-]*truffa\"",
            "\"label\":\\s*\"sicuro\"",
            "\"label\":\\s*\"legittimo\"",
            "\"label\":\\s*\"valido\"",

            // Portuguese
            "\"label\":\\s*\"não[_\\s-]*golpe\"",
            "\"label\":\\s*\"seguro\"",
            "\"label\":\\s*\"legítimo\"",
            "\"label\":\\s*\"válido\"",

            // Other languages
            "\"label\":\\s*\"安全\"", // Chinese: safe
            "\"label\":\\s*\"正当\"", // Chinese: legitimate
            "\"label\":\\s*\"безопасно\"", // Russian: safe
            "\"label\":\\s*\"легитимно\"" // Russian: legitimate
        )

        // Check for "not scam" patterns FIRST
        val foundNotScamPattern = notScamPatterns.any { pattern ->
            pattern.toRegex(RegexOption.IGNORE_CASE).containsMatchIn(lowerJson)
        }

        if (foundNotScamPattern) {
            Log.d(TAG, "✅ Found 'not scam' pattern - marking as safe")
            return false
        }

        // ONLY if no "not scam" found, check for scam patterns
        val scamPatterns = listOf(
            // English variations
            "\"label\":\\s*\"scam\"",
            "\"label\":\\s*\"spam\"",
            "\"label\":\\s*\"fraud\"",
            "\"label\":\\s*\"phishing\"",
            "\"label\":\\s*\"suspicious\"",
            "\"label\":\\s*\"dangerous\"",
            "\"label\":\\s*\"malicious\"",

            // Spanish
            "\"label\":\\s*\"estafa\"",
            "\"label\":\\s*\"fraude\"",
            "\"label\":\\s*\"engaño\"",
            "\"label\":\\s*\"timo\"",
            "\"label\":\\s*\"sospechoso\"",

            // French
            "\"label\":\\s*\"arnaque\"",
            "\"label\":\\s*\"escroquerie\"",
            "\"label\":\\s*\"fraude\"",
            "\"label\":\\s*\"suspect\"",

            // German
            "\"label\":\\s*\"betrug\"",
            "\"label\":\\s*\"schwindel\"",
            "\"label\":\\s*\"verdächtig\"",

            // Italian
            "\"label\":\\s*\"truffa\"",
            "\"label\":\\s*\"frode\"",
            "\"label\":\\s*\"sospetto\"",

            // Portuguese
            "\"label\":\\s*\"golpe\"",
            "\"label\":\\s*\"fraude\"",
            "\"label\":\\s*\"suspeito\"",

            // Other languages
            "\"label\":\\s*\"詐欺\"", // Japanese
            "\"label\":\\s*\"사기\"", // Korean
            "\"label\":\\s*\"诈骗\"", // Chinese
            "\"label\":\\s*\"обман\"" // Russian
        )

        // Check if any scam pattern matches
        val foundScamPattern = scamPatterns.any { pattern ->
            pattern.toRegex(RegexOption.IGNORE_CASE).containsMatchIn(lowerJson)
        }

        if (foundScamPattern) {
            Log.d(TAG, "🚨 Found scam pattern - marking as scam")
            return true
        }

        // DEFAULT: If neither pattern found clearly, default to safe
        Log.d(TAG, "❓ No clear pattern found - defaulting to safe")
        return false
    }

    /**
     * FALLBACK: Detect scam in raw response text (for non-JSON responses)
     */
    private fun detectScamInResponse(response: String): Boolean {
        val lowerResponse = response.lowercase()

        // Scam keywords in multiple languages
        val scamKeywords = listOf(
            // English
            "scam", "fraud", "phishing", "suspicious", "dangerous", "malicious", "threat",

            // Spanish
            "estafa", "fraude", "engaño", "sospechoso", "peligroso", "amenaza",

            // French
            "arnaque", "escroquerie", "fraude", "suspect", "dangereux", "menace",

            // German
            "betrug", "schwindel", "abzocke", "verdächtig", "gefährlich", "bedrohung",

            // Italian
            "truffa", "frode", "sospetto", "pericoloso", "minaccia",

            // Portuguese
            "golpe", "fraude", "suspeito", "perigoso", "ameaça",

            // More languages can be added...
        )

        // Safe keywords to avoid false positives
        val safeKeywords = listOf(
            // English
            "not scam", "no scam", "safe", "legitimate", "clean", "trusted", "secure",

            // Spanish
            "no estafa", "seguro", "legítimo", "confiable",

            // French
            "pas arnaque", "sûr", "légitime", "fiable",

            // German
            "kein betrug", "sicher", "legitim", "vertrauenswürdig",

            // Other languages...
        )

        // First check for explicit "not scam" phrases
        val hasSafeKeywords = safeKeywords.any { keyword ->
            lowerResponse.contains(keyword)
        }

        if (hasSafeKeywords) {
            Log.d(TAG, "✅ Found 'safe' keywords in multilingual response")
            return false
        }

        // Then check for scam keywords
        val hasScamKeywords = scamKeywords.any { keyword ->
            lowerResponse.contains(keyword)
        }

        if (hasScamKeywords) {
            Log.d(TAG, "🚨 Found 'scam' keywords in multilingual response")
            return true
        }

        // Default to safe if unclear
        Log.d(TAG, "❓ Unclear response, defaulting to safe")
        return false
    }

    private fun updateLastUsedTime() {
        lastUsedTime = System.currentTimeMillis()
    }

    private fun scheduleIdleCleanup() {
        // Cancel existing cleanup job
        idleCleanupJob?.cancel()

        // Schedule new cleanup
        idleCleanupJob = lifecycleScope.launch {
            delay(IDLE_TIMEOUT_MS)

            // Check if still idle
            if (System.currentTimeMillis() - lastUsedTime >= IDLE_TIMEOUT_MS) {
                Log.d(TAG, "💤 5-minute idle timeout reached - cleaning up to save memory")
                cleanUp()
            }
        }
    }

    fun onTrimMemory(level: Int) {
        // MUTEX FIX: Prevent concurrent cleanup calls
        if (isCleaningUp) {
            Log.d(TAG, "🔄 Cleanup already in progress - skipping")
            return
        }

        Log.d(TAG, "🗑️ Memory trim level: $level")

        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.d(TAG, "💾 Critical memory pressure - force cleanup")
                isCleaningUp = true
                try {
                    cleanUp()
                } finally {
                    isCleaningUp = false
                }
            }
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                Log.d(TAG, "⚠️ Low memory - scheduling cleanup soon")
                // Reduce idle timeout when memory is low
                idleCleanupJob?.cancel()
                idleCleanupJob = lifecycleScope.launch {
                    delay(30_000) // 30 seconds instead of 5 minutes
                    isCleaningUp = true
                    try {
                        cleanUp()
                    } finally {
                        isCleaningUp = false
                    }
                }
            }
        }
    }

    /**
     * Internal cleanup method - used internally without mutex
     */
    private fun cleanUpInternal() {
        try {
            idleCleanupJob?.cancel()
            idleCleanupJob = null
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error cancelling cleanup job: ${e.message}")
        }

        // CRITICAL: Close session BEFORE closing inference to prevent mutex error
        try {
            session?.close()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Session cleanup error: ${e.message}")
        } finally {
            session = null
        }

        // CRITICAL: Close inference after session is closed
        try {
            llmInference?.close()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Inference cleanup error: ${e.message}")
        } finally {
            llmInference = null
        }

        isInitialized = false
        Log.d(TAG, "🧹 Model cleanup completed internally")
    }

    /**
     * Public cleanup method with mutex protection
     */
    fun cleanUp() {
        // MUTEX FIX: Add synchronization to prevent race conditions
        synchronized(this) {
            cleanUpInternal()
            Log.d(TAG, "🧹 Public model cleanup completed - will auto re-initialize when needed")
        }
    }
}

// Keep both data classes for compatibility
data class ScamAnalysisResult(
    val isScam: Boolean,
    val confidence: Float,
    val explanation: String
)

data class ScamResult(
    val isScam: Boolean,
    val confidence: Float,
    val explanation: String,
    val appContext: String? = null,      // Name of the app where text was found
    val appCategory: AppCategory = AppCategory.OTHER  // Category of the app
)