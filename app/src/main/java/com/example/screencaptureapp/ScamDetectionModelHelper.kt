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
    private const val MAX_TOKENS = 4096
    private const val TEMPERATURE = 0.2f
    private const val TOP_K = 20
    private const val TOP_P = 0.9f

    // PERSISTENT COMPONENTS - Loaded during activation, cleaned during stop
    private var llmInference: LlmInference? = null
    private var session: LlmInferenceSession? = null
    private var isInitialized = false
    private var initializationContext: Context? = null

    // Protection flags
    @Volatile private var isCleaningUp = false
    @Volatile private var isInitializing = false

    /**
     * Check if model is ready without initializing
     */
    fun isModelReady(): Boolean {
        val ready = isInitialized && llmInference != null && session != null && !isCleaningUp
        Log.d(TAG, "🔍 Model ready check: $ready")
        return ready
    }

    suspend fun initialize(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Early return if already initialized
                if (isInitialized && llmInference != null && session != null) {
                    Log.d(TAG, "⚡ Model already loaded in GPU - reusing")
                    return@withContext true
                }

                // Prevent concurrent initialization
                if (isInitializing) {
                    Log.d(TAG, "⏳ Model initialization in progress - waiting...")
                    var waitTime = 0
                    while (isInitializing && waitTime < 30000) {
                        delay(100)
                        waitTime += 100
                    }
                    return@withContext isInitialized && llmInference != null && session != null
                }

                isInitializing = true
                val startTime = System.currentTimeMillis()
                Log.d(TAG, "🔄 Loading Gemma-3n model into GPU memory...")

                // Store context
                initializationContext = context.applicationContext

                // Clean up any existing instance
                cleanUpInternal()

                // Validate model file
                val modelFile = if (ModelDownloader.isModelDownloaded(context)) {
                    ModelDownloader.getModelFile(context)
                } else {
                    Log.e(TAG, "❌ Model file not found")
                    isInitializing = false
                    return@withContext false
                }

                if (!modelFile.exists() || modelFile.length() < 1000000000) {
                    Log.e(TAG, "❌ Invalid model file: exists=${modelFile.exists()}, size=${modelFile.length()}")
                    isInitializing = false
                    return@withContext false
                }

                Log.d(TAG, "📁 Model file validated: ${modelFile.length() / 1024 / 1024} MB")

                // Create LLM inference engine with GPU backend
                llmInference = LlmInference.createFromOptions(
                    context,
                    LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelFile.absolutePath)
                        .setMaxTokens(MAX_TOKENS)
                        .setPreferredBackend(LlmInference.Backend.GPU)
                        .build()
                )

                if (llmInference == null) {
                    Log.e(TAG, "❌ Failed to create LlmInference")
                    isInitializing = false
                    return@withContext false
                }

                // Create session
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
                val initTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "✅ Model loaded into GPU memory in ${initTime}ms - READY FOR INSTANT ANALYSIS")

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
     * Reset session to clear token history
     */
    private fun resetSessionForNewQuery() {
        try {
            Log.d(TAG, "🔄 Resetting session for fresh analysis...")

            session?.close()
            session = null

            session = LlmInferenceSession.createFromOptions(
                llmInference!!,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(TOP_K)
                    .setTopP(TOP_P)
                    .setTemperature(TEMPERATURE)
                    .build()
            )

            Log.d(TAG, "✅ Fresh session ready")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Session reset failed", e)
            throw e
        }
    }

    /**
     * Main analysis function - assumes model is already loaded
     */
    suspend fun analyzeTextForScamWithContext(context: Context, extractedText: String): ScamResult = withContext(Dispatchers.IO) {
        try {
            // Check if model is ready (should be loaded already)
            if (!isModelReady()) {
                Log.e(TAG, "❌ Model not loaded - user should activate service first")
                return@withContext ScamResult(
                    isScam = false,
                    confidence = 0f,
                    explanation = "Model not loaded - restart service"
                )
            }

            Log.d(TAG, "⚡ Using GPU-loaded model for instant analysis")

            // Get app context if available
            val currentApp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                AppDetectorHelper.hasUsageStatsPermission(context)) {
                AppDetectorHelper.getCurrentApp(context)
            } else {
                null
            }

            val appCategory = currentApp?.let { AppDetectorHelper.getAppCategory(it) } ?: AppCategory.OTHER
            val customPrompt = AppDetectorHelper.getCustomizedPrompt(appCategory, extractedText)

            currentApp?.let { packageName ->
                val appName = AppDetectorHelper.getAppName(context, packageName)
                Log.d(TAG, "🔍 Analyzing text from: $appName ($packageName)")
            }

            Log.d(TAG, "🚀 Starting instant GPU analysis...")

            // Reset session for clean analysis
            resetSessionForNewQuery()
            session!!.addQueryChunk(customPrompt)

            val response = suspendCancellableCoroutine<String> { continuation ->
                var fullResponse = ""
                session!!.generateResponseAsync { partialResult, done ->
                    fullResponse += partialResult
                    if (done) {
                        continuation.resume(fullResponse)
                    }
                }
            }

            Log.d(TAG, "⚡ GPU analysis complete")

            // Parse response
            val parsedResult = parseMultilingualResponse(response)
            ScamResult(
                isScam = parsedResult.isScam,
                confidence = parsedResult.confidence,
                explanation = parsedResult.explanation,
                appContext = currentApp?.let { AppDetectorHelper.getAppName(context, it) },
                appCategory = appCategory
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ Analysis error", e)
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

    /**
     * Backward compatibility method
     */
    suspend fun analyzeTextForScam(text: String): ScamResult {
        val context = initializationContext
        if (context == null) {
            Log.e(TAG, "❌ No context available")
            return ScamResult(
                isScam = false,
                confidence = 0f,
                explanation = "No context available"
            )
        }

        return withContext(Dispatchers.IO) {
            try {
                if (!isModelReady()) {
                    Log.e(TAG, "❌ Model not loaded")
                    return@withContext ScamResult(
                        isScam = false,
                        confidence = 0f,
                        explanation = "Model not loaded"
                    )
                }

                val prompt = """<start_of_turn>user
You are an expert scam detector. Analyze text and respond in JSON format.

Analyze the following text for potential scam indicators:
- Urgency or time pressure
- Requests for personal information
- Suspicious links or email addresses
- Too good to be true offers
- Impersonation of legitimate companies

Text: "$text"

Respond exactly as JSON:
{"label": "scam" or "not_scam", "reason": "brief explanation"}
<end_of_turn>
<start_of_turn>model
""".trimIndent()

                resetSessionForNewQuery()
                session!!.addQueryChunk(prompt)

                val result = suspendCancellableCoroutine<String> { continuation ->
                    var fullResponse = ""
                    session!!.generateResponseAsync { partialResult, done ->
                        fullResponse += partialResult
                        if (done) {
                            continuation.resume(fullResponse)
                        }
                    }
                }

                val analysisResult = parseMultilingualResponse(result)
                return@withContext ScamResult(
                    isScam = analysisResult.isScam,
                    confidence = analysisResult.confidence,
                    explanation = analysisResult.explanation,
                    appContext = null,
                    appCategory = AppCategory.OTHER
                )

            } catch (e: Exception) {
                Log.e(TAG, "❌ Analysis error", e)
                return@withContext ScamResult(
                    isScam = false,
                    confidence = 0f,
                    explanation = "Analysis failed: ${e.message}"
                )
            }
        }
    }

    /**
     * Parse response
     */
    private fun parseMultilingualResponse(response: String): ScamResult {
        return try {
            val jsonStart = response.indexOf('{')
            val jsonEnd = response.lastIndexOf('}') + 1

            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonStr = response.substring(jsonStart, jsonEnd)
                val isScam = detectScamInMultipleLanguages(jsonStr)
                val explanation = extractReasonFromJson(jsonStr)
                val confidence = if (isScam) 0.85f else 0.15f

                ScamResult(
                    isScam = isScam,
                    confidence = confidence,
                    explanation = explanation
                )
            } else {
                val risk = detectScamInResponse(response)
                ScamResult(
                    isScam = risk,
                    confidence = if (risk) 0.7f else 0.3f,
                    explanation = response.take(200)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to parse response", e)
            val isScam = detectScamInResponse(response)
            ScamResult(
                isScam = isScam,
                confidence = if (isScam) 0.7f else 0.3f,
                explanation = "Analysis: ${response.take(100)}..."
            )
        }
    }

    private fun extractReasonFromJson(jsonStr: String): String {
        return try {
            val reasonStart = jsonStr.indexOf("\"reason\":")
            if (reasonStart == -1) return "Analysis completed"

            val contentStart = jsonStr.indexOf("\"", reasonStart + 9)
            if (contentStart == -1) return "Analysis completed"

            var contentEnd = contentStart + 1
            while (contentEnd < jsonStr.length) {
                if (jsonStr[contentEnd] == '"' && jsonStr[contentEnd - 1] != '\\') {
                    break
                }
                contentEnd++
            }

            if (contentEnd < jsonStr.length) {
                return jsonStr.substring(contentStart + 1, contentEnd)
            }

            "Analysis completed"
        } catch (e: Exception) {
            "Analysis completed"
        }
    }

    private fun detectScamInMultipleLanguages(jsonString: String): Boolean {
        val lowerJson = jsonString.lowercase()

        // Check for "not scam" patterns first
        val notScamPatterns = listOf(
            "\"label\":\\s*\"not[_\\s-]*scam\"",
            "\"label\":\\s*\"safe\"",
            "\"label\":\\s*\"legitimate\""
        )

        val foundNotScamPattern = notScamPatterns.any { pattern ->
            pattern.toRegex(RegexOption.IGNORE_CASE).containsMatchIn(lowerJson)
        }

        if (foundNotScamPattern) {
            return false
        }

        // Check for scam patterns
        val scamPatterns = listOf(
            "\"label\":\\s*\"scam\"",
            "\"label\":\\s*\"fraud\"",
            "\"label\":\\s*\"phishing\"",
            "\"label\":\\s*\"suspicious\""
        )

        return scamPatterns.any { pattern ->
            pattern.toRegex(RegexOption.IGNORE_CASE).containsMatchIn(lowerJson)
        }
    }

    private fun detectScamInResponse(response: String): Boolean {
        val lowerResponse = response.lowercase()
        val scamKeywords = listOf("scam", "fraud", "phishing", "suspicious")
        val safeKeywords = listOf("not scam", "safe", "legitimate")

        val hasSafeKeywords = safeKeywords.any { lowerResponse.contains(it) }
        if (hasSafeKeywords) return false

        return scamKeywords.any { lowerResponse.contains(it) }
    }

    // REMOVED: No automatic cleanup - only manual cleanup
    private fun scheduleIdleCleanup() {
        Log.d(TAG, "💾 Model will stay in GPU until user stops service")
    }

    // REMOVED: No automatic cleanup on memory pressure
    fun onTrimMemory(level: Int) {
        Log.d(TAG, "🗑️ Memory trim level: $level - keeping model loaded")
    }

    /**
     * Clean up model from GPU memory (called when user stops service)
     */
    private fun cleanUpInternal() {
        try {
            session?.close()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Session cleanup error: ${e.message}")
        } finally {
            session = null
        }

        try {
            llmInference?.close()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Inference cleanup error: ${e.message}")
        } finally {
            llmInference = null
        }

        isInitialized = false
        Log.d(TAG, "🧹 Model cleaned from GPU memory")
    }

    /**
     * Public cleanup method (called when user stops service)
     */
    fun cleanUp() {
        synchronized(this) {
            cleanUpInternal()
            Log.d(TAG, "🧹 GPU model cleanup completed")
        }
    }
}

// Data classes
data class ScamAnalysisResult(
    val isScam: Boolean,
    val confidence: Float,
    val explanation: String
)

data class ScamResult(
    val isScam: Boolean,
    val confidence: Float,
    val explanation: String,
    val appContext: String? = null,
    val appCategory: AppCategory = AppCategory.OTHER
)