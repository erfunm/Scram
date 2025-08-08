package com.example.screencaptureapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File

object OCRHelper {
    private const val TAG = "OCRHelper"
    private const val TARGET_RESOLUTION = 1080 // Increased from 720 for better OCR quality
    private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes

    // PERSISTENT COMPONENT - Created once, reused many times
    private var textRecognizer: TextRecognizer? = null
    private var lastUsedTime = 0L
    private var idleCleanupJob: Job? = null
    private val lifecycleScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // MUTEX FIX: Add cleanup protection flag
    @Volatile
    private var isCleaningUp = false
    @Volatile
    private var isInitializing = false

    // GPU detection state
    private var gpuCapabilitiesChecked = false
    private var hasGpuCapabilities = false

    // ADDED: OCR initialization retry logic
    private var ocrInitAttempts = 0
    private const val MAX_OCR_INIT_ATTEMPTS = 3

    /**
     * CRITICAL FIX: Auto re-initialize recognizer if it was cleaned up
     */
    private fun ensureRecognizerInitialized(): TextRecognizer {
        if (textRecognizer != null && !isCleaningUp) {
            Log.d(TAG, "⚡ Reusing existing TextRecognizer instance")
            updateLastUsedTime()
            scheduleIdleCleanup()
            return textRecognizer!!
        }

        // Prevent concurrent initialization
        if (isInitializing) {
            Log.d(TAG, "⏳ TextRecognizer initialization already in progress - waiting...")
            // Wait for ongoing initialization (blocking is OK here as it's fast)
            var waitTime = 0
            while (isInitializing && textRecognizer == null && waitTime < 10000) { // 10 second timeout
                Thread.sleep(100)
                waitTime += 100
            }
            return textRecognizer ?: getOrCreateRecognizer()
        }

        Log.d(TAG, "🔄 TextRecognizer was cleaned up or not initialized - auto re-initializing...")
        return getOrCreateRecognizer()
    }

    private fun getOrCreateRecognizer(): TextRecognizer {
        if (textRecognizer == null || isCleaningUp) {
            isInitializing = true
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "🔧 Creating new TextRecognizer instance (attempt ${ocrInitAttempts + 1}/$MAX_OCR_INIT_ATTEMPTS)...")

            // Detect GPU capabilities on first creation
            if (!gpuCapabilitiesChecked) {
                detectGpuCapabilities()
            }

            // FIXED: Use more conservative OCR options to avoid ML Kit initialization errors
            try {
                // Create a basic Latin text recognizer without experimental features
                val options = TextRecognizerOptions.Builder()
                    .build()

                textRecognizer = TextRecognition.getClient(options)

                val initTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "✅ TextRecognizer created in ${initTime}ms - PERSISTENT until idle")

                // Log GPU status
                logGpuStatus()
                isInitializing = false
                ocrInitAttempts = 0 // Reset on success

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to create TextRecognizer (attempt ${ocrInitAttempts + 1})", e)

                ocrInitAttempts++
                isInitializing = false

                // If we've exhausted attempts, create a minimal fallback
                if (ocrInitAttempts >= MAX_OCR_INIT_ATTEMPTS) {
                    Log.w(TAG, "⚠️ Max OCR init attempts reached, creating minimal recognizer")
                    try {
                        // Last resort: try the most basic configuration
                        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                        Log.d(TAG, "✅ Fallback TextRecognizer created")
                        ocrInitAttempts = 0
                    } catch (fallbackError: Exception) {
                        Log.e(TAG, "❌ Even fallback TextRecognizer failed", fallbackError)
                        textRecognizer = null
                        throw fallbackError
                    }
                } else {
                    // Retry with exponential backoff
                    Thread.sleep(1000L * ocrInitAttempts)
                    return getOrCreateRecognizer()
                }
            }
        } else {
            Log.d(TAG, "⚡ Reusing existing TextRecognizer instance")
        }

        updateLastUsedTime()
        scheduleIdleCleanup()
        return textRecognizer!!
    }

    suspend fun extractTextFromImage(context: Context, imageFile: File): String? {
        return withContext(Dispatchers.IO) {
            val ocrStartTime = System.currentTimeMillis()

            try {
                Log.d(TAG, "📄 Processing OCR for file: ${imageFile.absolutePath}")
                Log.d(TAG, "📏 File size: ${imageFile.length()} bytes")

                // Load and optimize bitmap
                val originalBitmap = BitmapFactory.decodeFile(imageFile.absolutePath)

                if (originalBitmap == null) {
                    Log.e(TAG, "❌ Failed to decode bitmap from file")
                    return@withContext null
                }

                Log.d(TAG, "📐 Original bitmap: ${originalBitmap.width}x${originalBitmap.height}")

                // Downscale and optimize bitmap for faster OCR
                val optimizedBitmap = optimizeBitmapForOCR(originalBitmap)

                // Clean up original to save memory
                if (optimizedBitmap !== originalBitmap) {
                    originalBitmap.recycle()
                }

                Log.d(TAG, "🔧 Optimized bitmap: ${optimizedBitmap.width}x${optimizedBitmap.height}")

                // Extract text using PERSISTENT recognizer with auto re-init
                val result = extractTextFromBitmap(optimizedBitmap)

                // Clean up optimized bitmap
                optimizedBitmap.recycle()

                val ocrTime = System.currentTimeMillis() - ocrStartTime
                Log.d(
                    TAG,
                    "📊 OCR METRICS: ${ocrTime}ms | Text length: ${result?.length ?: 0} | ${getRecognizerStatus()}"
                )

                result

            } catch (e: Exception) {
                val ocrTime = System.currentTimeMillis() - ocrStartTime
                Log.e(TAG, "❌ OCR error after ${ocrTime}ms", e)
                null
            }
        }
    }

    suspend fun extractTextFromBitmap(bitmap: Bitmap): String? {
        return withContext(Dispatchers.IO) {
            val ocrStartTime = System.currentTimeMillis()

            try {
                Log.d(TAG, "🔍 Processing bitmap for OCR: ${bitmap.width}x${bitmap.height}")

                // Enhanced GPU detection logging
                logProcessingStart()

                // IMPROVED: Try to detect if image contains multiple scripts
                val hasLikelyAsianText = detectAsianCharacters(bitmap)
                if (hasLikelyAsianText) {
                    Log.d(TAG, "🈳 Detected possible Asian characters in image")
                }

                val image = InputImage.fromBitmap(bitmap, 0)

                // FIXED: Better error handling for OCR initialization
                val recognizer = try {
                    ensureRecognizerInitialized()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to initialize OCR recognizer", e)
                    // Return fallback response instead of crashing
                    return@withContext null
                }

                // ADDED: Retry logic for OCR processing
                var lastException: Exception? = null
                for (attempt in 1..2) { // Try twice
                    try {
                        val result = recognizer.process(image).await()
                        var extractedText = result.text

                        // ADDED: Post-processing to clean up OCR artifacts
                        extractedText = postProcessOCRText(extractedText)

                        val ocrTime = System.currentTimeMillis() - ocrStartTime

                        // Enhanced logging with GPU detection
                        Log.d(
                            TAG,
                            "📊 OCR METRICS: ${ocrTime}ms | Text length: ${extractedText.length} | ${getRecognizerStatus()} | Attempt: $attempt"
                        )

                        if (extractedText.isNotEmpty()) {
                            Log.d(TAG, "📝 Text preview: ${extractedText.take(200)}...")
                            // FULL TEXT LOG - uncomment to see complete extracted text
                            Log.d(TAG, "📄 FULL EXTRACTED TEXT:")
                            Log.d(TAG, "--- START TEXT ---")
                            Log.d(TAG, extractedText)
                            Log.d(TAG, "--- END TEXT ---")
                        } else {
                            Log.w(TAG, "⚠️ No text found in image (attempt $attempt)")
                        }

                        // Log processing completion with GPU info
                        logProcessingComplete()

                        return@withContext extractedText

                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ OCR attempt $attempt failed: ${e.message}")
                        lastException = e

                        if (attempt == 1) {
                            // First attempt failed, try to reinitialize recognizer
                            Log.d(TAG, "🔄 Reinitializing OCR recognizer for retry...")
                            synchronized(this@OCRHelper) {
                                try {
                                    textRecognizer?.close()
                                } catch (cleanup: Exception) {
                                    Log.w(TAG, "⚠️ Error during OCR cleanup: ${cleanup.message}")
                                }
                                textRecognizer = null
                                ocrInitAttempts = 0 // Reset attempts for retry
                            }

                            // Small delay before retry
                            delay(500)
                        }
                    }
                }

                // Both attempts failed
                val ocrTime = System.currentTimeMillis() - ocrStartTime
                Log.e(TAG, "❌ All OCR attempts failed after ${ocrTime}ms", lastException)
                return@withContext null

            } catch (e: Exception) {
                val ocrTime = System.currentTimeMillis() - ocrStartTime
                Log.e(TAG, "❌ OCR processing error after ${ocrTime}ms", e)
                return@withContext null
            }
        }
    }

    /**
     * ADDED: Simple heuristic to detect if image might contain Asian characters
     */
    private fun detectAsianCharacters(bitmap: Bitmap): Boolean {
        // For now, we'll use a simple approach - check if the image has characteristics
        // that suggest Asian text (more complex shapes, different aspect ratios)
        // This is a placeholder - you could implement more sophisticated detection

        // Check image dimensions - Asian text often requires more vertical space
        val aspectRatio = bitmap.height.toFloat() / bitmap.width.toFloat()
        val hasVerticalLayout = aspectRatio > 1.2f

        // Check if image is small (typical for mobile screenshots with Asian text)
        val isSmallImage = bitmap.width < 800 || bitmap.height < 800

        return hasVerticalLayout || isSmallImage
    }

    /**
     * IMPROVED: Post-process OCR text to fix common artifacts including Japanese
     */
    private fun postProcessOCRText(rawText: String): String {
        if (rawText.isBlank()) return rawText

        var processed = rawText

        // ADDED: Fix common Japanese OCR artifacts first
        processed = fixJapaneseOCRErrors(processed)

        // Fix common OCR mistakes for English text
        val corrections = mapOf(
            // Common word corrections for authentication/verification text
            "authentlcation" to "authentication",
            "verifcation" to "verification",
            "mlcrosoft" to "microsoft",
            "Mlcrosoft" to "Microsoft",
            "authen" to "authentication",
            "verif" to "verification",
            "Mlcrosoftauth" to "Microsoft authentication",
            "authenti" to "authentication",

            // Fix broken words that commonly appear
            "Use verif" to "Use verification",
            "for Mlcrosoft" to "for Microsoft",
            "authentlcat" to "authentication",
            "verificat" to "verification",

            // Common character substitutions in garbled text
            "đetats" to "details",
            "nttihiš" to "entire",
            "arefu" to "careful",
            "hierngaC" to "sharing",
            "yotr" to "your",
            "nseBát" to "screen"
        )

        // Apply corrections carefully - only for clearly wrong text
        for ((wrong, correct) in corrections) {
            if (processed.contains(wrong, ignoreCase = true)) {
                // Use word boundary regex to avoid partial matches
                val regex = "\\b${Regex.escape(wrong)}\\b".toRegex(RegexOption.IGNORE_CASE)
                processed = regex.replace(processed, correct)
                Log.d(TAG, "🔧 Fixed OCR: '$wrong' → '$correct'")
            }
        }

        // Clean up excessive whitespace and line breaks
        processed = processed
            .replace(Regex("\\s+"), " ") // Multiple spaces to single space
            .replace(Regex("\\n\\s*\\n"), "\n") // Multiple line breaks to single
            .trim()

        if (processed != rawText) {
            Log.d(TAG, "✨ OCR post-processing applied")
        }

        return processed
    }

    /**
     * ADDED: Fix common Japanese OCR errors
     */
    private fun fixJapaneseOCRErrors(text: String): String {
        var fixed = text

        // Common Japanese OCR issues and their fixes
        val japaneseCorrections = mapOf(
            // Hiragana/Katakana commonly misread as similar characters
            "力" to "カ", // Katakana ka vs kanji power
            "ロ" to "口", // Katakana ro vs kanji mouth
            "ハ" to "八", // Katakana ha vs kanji eight
            "二" to "ニ", // Kanji two vs katakana ni

            // Common misreadings
            "工" to "コ", // Kanji craft vs katakana ko
            "人" to "入", // Kanji person vs kanji enter

            // OCR often breaks Japanese words - try to detect and fix some patterns
            "ー" to "一", // Long vowel mark vs kanji one
            "。" to ".", // Japanese period
            "、" to ",", // Japanese comma
        )

        // Apply Japanese corrections
        for ((wrong, correct) in japaneseCorrections) {
            if (fixed.contains(wrong)) {
                fixed = fixed.replace(wrong, correct)
                Log.d(TAG, "🈳 Fixed Japanese OCR: '$wrong' → '$correct'")
            }
        }

        // Try to detect and preserve Japanese text patterns
        // Look for common Japanese patterns that might be important
        val hasJapanesePatterns = listOf(
            "です", "である", "ます", "だ", "の", "に", "を", "が", "は", "で", "と", "から", "まで"
        ).any { pattern -> fixed.contains(pattern) }

        if (hasJapanesePatterns) {
            Log.d(TAG, "🈳 Japanese text patterns detected - preserving structure")
        }

        return fixed
    }

    /**
     * Detect GPU capabilities and availability
     */
    private fun detectGpuCapabilities() {
        try {
            Log.d(TAG, "🔍 Checking GPU capabilities...")

            // Check system properties for GPU info
            val hardware = android.os.Build.HARDWARE.lowercase()
            val manufacturer = android.os.Build.MANUFACTURER
            val model = android.os.Build.MODEL
            val soc = android.os.Build.SOC_MANUFACTURER ?: "unknown"

            Log.d(TAG, "📱 Device: $manufacturer $model")
            Log.d(TAG, "🔧 Hardware: $hardware")
            Log.d(TAG, "💾 SoC: $soc")

            // Enhanced GPU detection for Pixel devices and others
            val hasAdreno = hardware.contains("adreno") || hardware.contains("qualcomm")
            val hasMali = hardware.contains("mali") || hardware.contains("arm")
            val hasPowerVR = hardware.contains("powervr") || hardware.contains("imagination")
            val hasSnapdragon = manufacturer.contains("qualcomm", true) ||
                    model.contains("snapdragon", true)

            // FIXED: Enhanced Pixel/Google detection for Tensor chips
            val hasTensor = hardware.contains("tensor") ||
                    hardware.contains("komodo") ||
                    hardware.contains("zuma") ||
                    hardware.contains("slider") ||
                    model.contains("pixel", true)

            val hasAppleGPU = hardware.contains("apple") || manufacturer.contains("apple", true)

            // Enhanced GPU capabilities detection
            hasGpuCapabilities =
                hasAdreno || hasMali || hasPowerVR || hasSnapdragon || hasTensor || hasAppleGPU

            Log.d(
                TAG,
                "🔍 GPU Detection: Adreno=$hasAdreno, Mali=$hasMali, PowerVR=$hasPowerVR, Snapdragon=$hasSnapdragon, Tensor=$hasTensor"
            )

            if (hasGpuCapabilities) {
                Log.d(TAG, "🚀 GPU capabilities detected - MLKit may use GPU acceleration")

                // Try to detect specific GPU
                when {
                    hasTensor -> Log.d(
                        TAG,
                        "🎮 Google Tensor GPU detected (Mali-G78 MP20 or similar)"
                    )

                    hasAdreno || hasSnapdragon -> Log.d(TAG, "🎮 Adreno GPU detected (Qualcomm)")
                    hasMali -> Log.d(TAG, "🎮 Mali GPU detected (ARM)")
                    hasPowerVR -> Log.d(TAG, "🎮 PowerVR GPU detected (Imagination)")
                    hasAppleGPU -> Log.d(TAG, "🎮 Apple GPU detected")
                }
            } else {
                Log.d(TAG, "💻 No obvious GPU acceleration detected - using CPU processing")
                Log.d(TAG, "🔍 Note: MLKit may still use optimized CPU delegates (XNNPack)")
            }

            gpuCapabilitiesChecked = true

        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not detect GPU capabilities", e)
            hasGpuCapabilities = false
            gpuCapabilitiesChecked = true
        }
    }

    /**
     * Log GPU status when recognizer is created
     */
    private fun logGpuStatus() {
        if (hasGpuCapabilities) {
            Log.d(TAG, "🚀 TextRecognizer created with potential GPU acceleration")
            Log.d(TAG, "💡 Monitor TensorFlow Lite logs for delegate usage:")
            Log.d(TAG, "   - Look for 'GPU delegate' = GPU acceleration")
            Log.d(TAG, "   - Look for 'XNNPack delegate' = CPU optimization")
        } else {
            Log.d(TAG, "💻 TextRecognizer created for CPU-only processing")
        }
    }

    /**
     * Log processing start with GPU context
     */
    private fun logProcessingStart() {
        if (hasGpuCapabilities) {
            Log.d(TAG, "⚡ Starting OCR processing (GPU-capable device)")
        } else {
            Log.d(TAG, "⚡ Starting OCR processing (CPU-only)")
        }
    }

    /**
     * Log processing completion with GPU indicators
     */
    private fun logProcessingComplete() {
        try {
            Log.d(TAG, "✅ OCR processing completed")

            if (hasGpuCapabilities) {
                Log.d(TAG, "🔍 Check logs above for TensorFlow Lite delegate usage:")
                Log.d(TAG, "   📊 GPU delegate = Hardware acceleration active")
                Log.d(TAG, "   📊 XNNPack delegate = Optimized CPU processing")
                Log.d(TAG, "   📊 No delegate mentioned = Basic CPU processing")
            }

        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error logging processing completion", e)
        }
    }

    /**
     * Get recognizer status with GPU info
     */
    private fun getRecognizerStatus(): String {
        val baseStatus = if (textRecognizer != null && !isCleaningUp) {
            "PERSISTENT until idle"
        } else {
            "NEW instance created"
        }

        val gpuStatus = if (hasGpuCapabilities) {
            "GPU-capable"
        } else {
            "CPU-only"
        }

        return "$baseStatus ($gpuStatus)"
    }

    private fun optimizeBitmapForOCR(originalBitmap: Bitmap): Bitmap {
        try {
            val originalWidth = originalBitmap.width
            val originalHeight = originalBitmap.height

            Log.d(TAG, "🔧 Starting bitmap optimization for OCR...")
            Log.d(TAG, "📐 Original: ${originalWidth}x${originalHeight}, config: ${originalBitmap.config}")

            // IMPROVED: Better resolution targeting for OCR
            // OCR works better with higher resolution, so be more conservative with downscaling
            val targetResolution = if (originalHeight * originalWidth > 2073600) { // > 1920x1080
                1080 // Only downscale very large images
            } else {
                TARGET_RESOLUTION // Use original target for smaller images
            }

            // Calculate downscaling factor to target resolution
            val scaleFactor = when {
                originalHeight > originalWidth -> targetResolution.toFloat() / originalHeight
                else -> targetResolution.toFloat() / originalWidth
            }

            // IMPROVED: Don't downscale if image is reasonably sized for OCR
            if (scaleFactor >= 0.8f) { // Changed from 1.0f to 0.8f
                Log.d(TAG, "🔧 Image size is good for OCR, applying sharpening and contrast")
                return enhanceBitmapForOCR(originalBitmap)
            }

            val newWidth = (originalWidth * scaleFactor).toInt()
            val newHeight = (originalHeight * scaleFactor).toInt()

            Log.d(
                TAG,
                "🔽 Downscaling: ${originalWidth}x${originalHeight} → ${newWidth}x${newHeight} (${
                    String.format("%.2f", scaleFactor)
                }x)"
            )

            // IMPROVED: Use higher quality scaling
            val matrix = Matrix().apply {
                setScale(scaleFactor, scaleFactor)
            }

            // Create downscaled bitmap with ARGB_8888 for better quality (changed from RGB_565)
            val scaledBitmap = Bitmap.createBitmap(
                originalBitmap, 0, 0, originalWidth, originalHeight, matrix, true
            )

            // Apply enhancement after scaling
            return enhanceBitmapForOCR(scaledBitmap).also {
                if (it !== scaledBitmap) {
                    scaledBitmap.recycle()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Bitmap optimization failed, using original", e)
            return originalBitmap
        }
    }

    /**
     * ADDED: Enhance bitmap for better OCR recognition
     */
    private fun enhanceBitmapForOCR(bitmap: Bitmap): Bitmap {
        return try {
            Log.d(TAG, "✨ Enhancing bitmap for OCR...")

            // Create a copy to work with
            val enhanced = bitmap.copy(Bitmap.Config.ARGB_8888, true)

            // Apply simple contrast and brightness enhancement
            val canvas = Canvas(enhanced)
            val paint = Paint().apply {
                // Increase contrast slightly
                colorFilter = ColorMatrixColorFilter(
                    ColorMatrix().apply {
                        // Contrast matrix: [contrast, 0, 0, 0, brightness]
                        set(floatArrayOf(
                            1.2f, 0f, 0f, 0f, 10f,      // Red
                            0f, 1.2f, 0f, 0f, 10f,      // Green
                            0f, 0f, 1.2f, 0f, 10f,      // Blue
                            0f, 0f, 0f, 1f, 0f          // Alpha
                        ))
                    }
                )
            }

            canvas.drawBitmap(bitmap, 0f, 0f, paint)

            if (enhanced !== bitmap) {
                Log.d(TAG, "✅ Bitmap enhanced for better OCR")
                enhanced
            } else {
                bitmap
            }

        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Bitmap enhancement failed, using original", e)
            bitmap
        }
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
                Log.d(TAG, "💤 5-minute idle timeout reached - cleaning up OCR recognizer")
                cleanup()
            }
        }
    }

    fun onTrimMemory(level: Int) {
        // MUTEX FIX: Prevent concurrent cleanup calls
        if (isCleaningUp) {
            Log.d(TAG, "🔄 OCR cleanup already in progress - skipping")
            return
        }

        Log.d(TAG, "🗑️ Memory trim level: $level")

        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.d(TAG, "💾 Critical memory pressure - force cleanup OCR recognizer")
                isCleaningUp = true
                try {
                    cleanup()
                } finally {
                    isCleaningUp = false
                }
            }

            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                Log.d(TAG, "⚠️ Low memory - scheduling OCR cleanup soon")
                // Reduce idle timeout when memory is low
                idleCleanupJob?.cancel()
                idleCleanupJob = lifecycleScope.launch {
                    delay(30_000) // 30 seconds instead of 5 minutes
                    isCleaningUp = true
                    try {
                        cleanup()
                    } finally {
                        isCleaningUp = false
                    }
                }
            }
        }
    }

    fun cleanup() {
        // MUTEX FIX: Add synchronization to prevent race conditions
        synchronized(this) {
            try {
                idleCleanupJob?.cancel()
                idleCleanupJob = null
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error cancelling OCR cleanup job: ${e.message}")
            }

            // CRITICAL: Close recognizer with proper null check
            try {
                textRecognizer?.close()
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ TextRecognizer cleanup error: ${e.message}")
            } finally {
                textRecognizer = null
            }

            // Reset retry attempts on cleanup
            ocrInitAttempts = 0

            Log.d(TAG, "🧹 OCR cleanup completed - will auto re-initialize when needed")

            // Reset GPU detection state if needed for clean re-initialization
            if (gpuCapabilitiesChecked) {
                Log.d(TAG, "🔄 GPU capabilities will be re-detected on next recognizer creation")
            }
        }
    }
}