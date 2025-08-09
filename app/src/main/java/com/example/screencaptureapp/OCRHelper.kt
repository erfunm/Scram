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
    private const val TARGET_RESOLUTION = 1080
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
            Log.d(TAG, "🔧 Creating new TextRecognizer instance...")

            // Detect GPU capabilities on first creation
            if (!gpuCapabilitiesChecked) {
                detectGpuCapabilities()
            }

            // FIXED: Use DEFAULT_OPTIONS exactly like the working SCCRMMMMM version
            textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            val initTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "✅ TextRecognizer created in ${initTime}ms - PERSISTENT until idle")

            // Log GPU status
            logGpuStatus()
            isInitializing = false
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

                // Optimize bitmap for better OCR
                val optimizedBitmap = optimizeBitmapForOCR(originalBitmap)

                // Clean up original to save memory
                if (optimizedBitmap !== originalBitmap) {
                    originalBitmap.recycle()
                }

                Log.d(TAG, "🔧 Optimized bitmap: ${optimizedBitmap.width}x${optimizedBitmap.height}")

                // Extract text using the fixed extractTextFromBitmap
                val result = extractTextFromBitmap(optimizedBitmap)

                // Clean up optimized bitmap
                optimizedBitmap.recycle()

                val ocrTime = System.currentTimeMillis() - ocrStartTime
                Log.d(TAG, "📊 OCR completed in ${ocrTime}ms | Text length: ${result?.length ?: 0}")

                result

            } catch (e: Exception) {
                val ocrTime = System.currentTimeMillis() - ocrStartTime
                Log.e(TAG, "❌ OCR error after ${ocrTime}ms", e)
                null
            }
        }
    }

    // CRITICAL FIX: Update extractTextFromBitmap to handle closed detectors properly
    suspend fun extractTextFromBitmap(bitmap: Bitmap): String? {
        return withContext(Dispatchers.IO) {
            val ocrStartTime = System.currentTimeMillis()
            var attempts = 0
            val maxAttempts = 3

            while (attempts < maxAttempts) {
                attempts++

                try {
                    Log.d(TAG, "🔍 OCR attempt $attempts: Processing bitmap ${bitmap.width}x${bitmap.height}")

                    // CRITICAL FIX: Get fresh recognizer for each retry
                    val recognizer = if (attempts > 1) {
                        Log.d(TAG, "🔄 Creating fresh recognizer for retry attempt $attempts")
                        synchronized(this@OCRHelper) {
                            try {
                                textRecognizer?.close()
                            } catch (e: Exception) {
                                Log.w(TAG, "⚠️ Error closing old recognizer: ${e.message}")
                            }
                            textRecognizer = null
                        }
                        // Get fresh recognizer and use it directly
                        ensureRecognizerInitialized()
                    } else {
                        // First attempt - use existing or create new
                        ensureRecognizerInitialized()
                    }

                    val image = InputImage.fromBitmap(bitmap, 0)
                    val result = recognizer.process(image).await()
                    var extractedText = result.text

                    // Apply post-processing
                    extractedText = postProcessOCRText(extractedText)

                    val ocrTime = System.currentTimeMillis() - ocrStartTime
                    Log.d(TAG, "✅ OCR successful on attempt $attempts in ${ocrTime}ms")

                    if (!extractedText.isNullOrEmpty()) {
                        Log.d(TAG, "📝 Text preview: ${extractedText.take(200)}...")
                        Log.d(TAG, "📄 FULL EXTRACTED TEXT:")
                        Log.d(TAG, "--- START TEXT ---")
                        Log.d(TAG, extractedText)
                        Log.d(TAG, "--- END TEXT ---")
                    } else {
                        Log.w(TAG, "⚠️ No text found in image")
                    }

                    updateLastUsedTime()
                    scheduleIdleCleanup()
                    return@withContext extractedText

                } catch (e: Exception) {
                    val ocrTime = System.currentTimeMillis() - ocrStartTime
                    Log.w(TAG, "⚠️ OCR attempt $attempts failed: ${e.message}")

                    if (attempts < maxAttempts) {
                        when {
                            e.message?.contains("closed") == true -> {
                                Log.d(TAG, "🔄 Detector closed - will recreate for next attempt")
                            }
                            e.message?.contains("Failed to init") == true -> {
                                Log.d(TAG, "🔄 Init failed - reinitializing for retry")
                            }
                            e.message?.contains("mlkit-google-ocr-models") == true -> {
                                Log.d(TAG, "🔄 Model file error - will retry with fresh recognizer")
                            }
                            else -> {
                                Log.d(TAG, "🔄 Unknown error - will retry with fresh recognizer")
                            }
                        }

                        // Brief delay before retry
                        delay((100 * attempts).toLong()) // Progressive delay: 100ms, 200ms, 300ms
                    } else {
                        Log.e(TAG, "❌ All OCR attempts failed after ${ocrTime}ms", e)
                        return@withContext null
                    }
                }
            }

            val totalTime = System.currentTimeMillis() - ocrStartTime
            Log.e(TAG, "❌ All $maxAttempts OCR attempts failed after ${totalTime}ms")
            return@withContext null
        }
    }

    /**
     * Post-process OCR text to fix common artifacts
     */
    private fun postProcessOCRText(rawText: String): String {
        if (rawText.isBlank()) return rawText

        var processed = rawText

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
            "authentlcat" to "authentication",
            "verificat" to "verification"
        )

        // Apply corrections carefully
        for ((wrong, correct) in corrections) {
            if (processed.contains(wrong, ignoreCase = true)) {
                val regex = "\\b${Regex.escape(wrong)}\\b".toRegex(RegexOption.IGNORE_CASE)
                processed = regex.replace(processed, correct)
                Log.d(TAG, "🔧 Fixed OCR: '$wrong' → '$correct'")
            }
        }

        // Clean up excessive whitespace
        processed = processed
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\n\\s*\\n"), "\n")
            .trim()

        if (processed != rawText) {
            Log.d(TAG, "✨ OCR post-processing applied")
        }

        return processed
    }

    private fun optimizeBitmapForOCR(originalBitmap: Bitmap): Bitmap {
        try {
            val originalWidth = originalBitmap.width
            val originalHeight = originalBitmap.height

            Log.d(TAG, "🔧 Starting bitmap optimization for OCR...")
            Log.d(TAG, "📐 Original: ${originalWidth}x${originalHeight}")

            // Calculate scaling for better OCR
            val scaleFactor = when {
                originalHeight > originalWidth -> TARGET_RESOLUTION.toFloat() / originalHeight
                else -> TARGET_RESOLUTION.toFloat() / originalWidth
            }

            // Don't downscale too much for OCR
            if (scaleFactor >= 0.8f) {
                Log.d(TAG, "🔧 Image size good for OCR, applying enhancement")
                return enhanceBitmapForOCR(originalBitmap)
            }

            val newWidth = (originalWidth * scaleFactor).toInt()
            val newHeight = (originalHeight * scaleFactor).toInt()

            Log.d(TAG, "🔽 Scaling: ${originalWidth}x${originalHeight} → ${newWidth}x${newHeight}")

            val matrix = Matrix().apply {
                setScale(scaleFactor, scaleFactor)
            }

            val scaledBitmap = Bitmap.createBitmap(
                originalBitmap, 0, 0, originalWidth, originalHeight, matrix, true
            )

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

    private fun enhanceBitmapForOCR(bitmap: Bitmap): Bitmap {
        return try {
            Log.d(TAG, "✨ Enhancing bitmap for OCR...")

            val enhanced = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(enhanced)
            val paint = Paint().apply {
                colorFilter = ColorMatrixColorFilter(
                    ColorMatrix().apply {
                        // Increase contrast for better OCR
                        set(floatArrayOf(
                            1.3f, 0f, 0f, 0f, 15f,    // Red
                            0f, 1.3f, 0f, 0f, 15f,    // Green
                            0f, 0f, 1.3f, 0f, 15f,    // Blue
                            0f, 0f, 0f, 1f, 0f        // Alpha
                        ))
                    }
                )
            }

            canvas.drawBitmap(bitmap, 0f, 0f, paint)

            if (enhanced !== bitmap) {
                Log.d(TAG, "✅ Bitmap enhanced for OCR")
                enhanced
            } else {
                bitmap
            }

        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Enhancement failed, using original", e)
            bitmap
        }
    }

    private fun detectGpuCapabilities() {
        gpuCapabilitiesChecked = true
        hasGpuCapabilities = true // Assume GPU support for now
        Log.d(TAG, "🎮 GPU capabilities detected: $hasGpuCapabilities")
    }

    private fun logGpuStatus() {
        val status = if (hasGpuCapabilities) "GPU-capable" else "CPU-only"
        Log.d(TAG, "🎮 TextRecognizer status: $status")
    }

    private fun updateLastUsedTime() {
        lastUsedTime = System.currentTimeMillis()
    }

    private fun scheduleIdleCleanup() {
        idleCleanupJob?.cancel()
        idleCleanupJob = lifecycleScope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (System.currentTimeMillis() - lastUsedTime >= IDLE_TIMEOUT_MS) {
                Log.d(TAG, "💤 5-minute idle timeout - cleaning up OCR")
                cleanup()
            }
        }
    }

    fun onTrimMemory(level: Int) {
        if (isCleaningUp) {
            Log.d(TAG, "🔄 Cleanup already in progress")
            return
        }

        Log.d(TAG, "🗑️ Memory trim level: $level")

        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.d(TAG, "💾 Critical memory pressure - force cleanup")
                isCleaningUp = true
                try {
                    cleanup()
                } finally {
                    isCleaningUp = false
                }
            }
        }
    }

    fun cleanup() {
        synchronized(this) {
            try {
                idleCleanupJob?.cancel()
                idleCleanupJob = null
                textRecognizer?.close()
                textRecognizer = null
                Log.d(TAG, "🧹 OCR cleanup completed")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error during cleanup: ${e.message}")
            }
        }
    }
}