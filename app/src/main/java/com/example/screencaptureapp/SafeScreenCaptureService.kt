package com.example.screencaptureapp

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class SafeScreenCaptureService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ScreenCaptureChannel"
        private const val ACTION_QUICK_SHOT = "QUICK_SHOT"
        private const val ACTION_STOP_SERVICE = "STOP_SERVICE"
        private const val TAG = "SafeScreenService"

        // ADDED: Broadcast constants for MainActivity communication
        const val ACTION_SERVICE_STATE_CHANGED = "com.example.screencaptureapp.SERVICE_STATE_CHANGED"
        const val EXTRA_SERVICE_RUNNING = "service_running"
    }

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var currentVirtualDisplay: VirtualDisplay? = null
    private var currentImageReader: ImageReader? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val isProcessingScreenshot = AtomicBoolean(false)

    // FIXED: Model initialization state tracking
    private var isModelInitialized = false
    private var modelInitializationInProgress = false

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🚀 SafeScreenCaptureService onCreate")

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        getScreenMetrics()
        createNotificationChannel()
        ScamNotificationHelper.createScamAlertChannel(this)

        // FIXED: Initialize AI model with proper error handling and re-initialization
        initializeModelAsync()

        // ADDED: Notify MainActivity that service started
        broadcastServiceState(true)
    }

    /**
     * ADDED: Broadcast service state changes to MainActivity
     */
    private fun broadcastServiceState(isRunning: Boolean) {
        try {
            val intent = Intent(ACTION_SERVICE_STATE_CHANGED).apply {
                putExtra(EXTRA_SERVICE_RUNNING, isRunning)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            Log.d(TAG, "📡 Broadcasted service state: isRunning=$isRunning")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to broadcast service state", e)
        }
    }

    /**
     * FIXED: Proper async model initialization with retry capability
     */
    private fun initializeModelAsync() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                if (modelInitializationInProgress) {
                    Log.d(TAG, "⏳ Model initialization already in progress")
                    return@launch
                }

                modelInitializationInProgress = true

                withContext(Dispatchers.Main) {
                    updateNotificationWithMessage("Initializing AI model...")
                }

                Log.d(TAG, "🔧 Starting model initialization...")

                val initialized = ScamDetectionModelHelper.initialize(this@SafeScreenCaptureService)

                withContext(Dispatchers.Main) {
                    if (initialized) {
                        Log.d(TAG, "✅ Model initialized successfully")
                        isModelInitialized = true
                        updateNotificationWithMessage("Ready - Tap Scan to check for scams")
                    } else {
                        Log.e(TAG, "❌ Model initialization failed")
                        isModelInitialized = false
                        updateNotificationWithMessage("AI model failed to load - tap to retry")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Model initialization error", e)
                withContext(Dispatchers.Main) {
                    isModelInitialized = false
                    updateNotificationWithMessage("Model initialization error - tap to retry")
                }
            } finally {
                modelInitializationInProgress = false
            }
        }
    }

    /**
     * FIXED: Ensure model is ready before processing, with auto re-initialization
     */
    private suspend fun ensureModelReady(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // If model is already initialized, just verify it's working
                if (isModelInitialized) {
                    Log.d(TAG, "✅ Model already initialized")
                    return@withContext true
                }

                // If initialization is in progress, wait for it
                if (modelInitializationInProgress) {
                    Log.d(TAG, "⏳ Waiting for model initialization to complete...")
                    var waitTime = 0
                    while (modelInitializationInProgress && waitTime < 30000) { // 30 second timeout
                        delay(500)
                        waitTime += 500
                    }
                    return@withContext isModelInitialized
                }

                // Model not initialized, try to initialize it
                Log.d(TAG, "🔄 Model not ready - attempting initialization...")

                withContext(Dispatchers.Main) {
                    updateNotificationWithMessage("Preparing AI model...")
                }

                val initialized = ScamDetectionModelHelper.initialize(this@SafeScreenCaptureService)

                if (initialized) {
                    Log.d(TAG, "✅ Model successfully initialized on-demand")
                    isModelInitialized = true
                    withContext(Dispatchers.Main) {
                        updateNotificationWithMessage("AI model ready - processing...")
                    }
                    return@withContext true
                } else {
                    Log.e(TAG, "❌ Failed to initialize model on-demand")
                    isModelInitialized = false
                    withContext(Dispatchers.Main) {
                        updateNotificationWithMessage("AI model initialization failed")
                    }
                    return@withContext false
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error ensuring model readiness", e)
                isModelInitialized = false
                withContext(Dispatchers.Main) {
                    updateNotificationWithMessage("Model initialization error")
                }
                return@withContext false
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "📥 onStartCommand: action='${intent?.action}'")

        // FIXED: Only start foreground if not a stop action
        when (intent?.action) {
            ACTION_STOP_SERVICE, "com.example.screencaptureapp.STOP_SERVICE" -> {
                Log.d(TAG, "🛑 Stop service requested via notification")

                // FIXED: Update shared preferences to reflect stopped state
                val prefs = getSharedPreferences("service_state", MODE_PRIVATE)
                prefs.edit().putBoolean("is_running", false).apply()
                Log.d(TAG, "✅ Updated shared preferences: is_running=false")

                // ADDED: Broadcast service stopping to MainActivity
                broadcastServiceState(false)

                serviceScope.cancel()
                cleanupScreenshotResources()
                updateNotificationWithMessage("Stopping...")
                stopSelf()
                return START_NOT_STICKY
            }

            "SETUP_PERSISTENT_PROJECTION" -> {
                // Start foreground first for this action
                startForeground(NOTIFICATION_ID, createNotification())

                val permissionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("mediaProjectionData", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>("mediaProjectionData")
                }

                if (permissionData != null) {
                    setupPermissionData(permissionData)
                } else {
                    Log.e(TAG, "❌ No permission data received")
                    updateNotificationWithMessage("Permission setup failed")
                    isProcessingScreenshot.set(false)
                }
            }

            else -> {
                // FIXED: Normal service start - start foreground immediately
                try {
                    startForeground(NOTIFICATION_ID, createNotification())
                    Log.d(TAG, "✅ Service started in foreground successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to start foreground service", e)
                    stopSelf()
                    return START_NOT_STICKY
                }

                Log.d(TAG, "🏁 Service started normally")
                if (isModelInitialized) {
                    updateNotificationWithMessage("Ready - Tap Scan to check for scams")
                } else {
                    updateNotificationWithMessage("Initializing AI model...")
                }
            }
        }

        return START_STICKY
    }

    private fun setupPermissionData(permissionData: Intent) {
        try {
            if (!isProcessingScreenshot.compareAndSet(false, true)) {
                Log.w(TAG, "⚠️ Already processing screenshot")
                return
            }

            Log.d(TAG, "🔧 Using fresh permission data for screenshot")
            updateNotificationWithMessage("✅ Permission granted - preparing screenshot...")

            // ADDED: Debounce delay to let system UI dismiss
            Handler(Looper.getMainLooper()).postDelayed({
                performQuickScreenshotWithFreshPermission(permissionData)
            }, 500) // 500ms delay to let permission dialog disappear

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error using fresh permission data", e)
            updateNotificationWithMessage("Setup error: ${e.message}")
            isProcessingScreenshot.set(false)
        }
    }

    private fun performQuickScreenshotWithFreshPermission(permissionData: Intent) {
        Log.d(TAG, "📸 Creating MediaProjection with fresh permission (after UI delay)")

        try {
            updateNotificationWithMessage("Taking screenshot...")

            if (mediaProjectionManager == null) {
                Log.e(TAG, "❌ MediaProjectionManager is null")
                updateNotificationWithMessage("Error: MediaProjectionManager unavailable")
                isProcessingScreenshot.set(false)
                return
            }

            cleanupScreenshotResources()

            val freshMediaProjection = mediaProjectionManager!!.getMediaProjection(
                Activity.RESULT_OK,
                permissionData
            )

            if (freshMediaProjection == null) {
                Log.e(TAG, "❌ Failed to create fresh MediaProjection")
                updateNotificationWithMessage("Failed to create screen projection")
                isProcessingScreenshot.set(false)
                return
            }

            Log.d(TAG, "✅ Fresh MediaProjection created")
            performSingleScreenshot(freshMediaProjection)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creating MediaProjection with fresh permission", e)
            updateNotificationWithMessage("Screenshot failed: ${e.message}")
            cleanupScreenshotResources()
            isProcessingScreenshot.set(false)
        }
    }

    private fun performSingleScreenshot(mediaProjection: MediaProjection) {
        Log.d(TAG, "📸 Setting up single screenshot capture")

        try {
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "📱 MediaProjection stopped")
                }
            }, Handler(Looper.getMainLooper()))

            currentImageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                1
            )

            setupImageListener(mediaProjection)

            currentVirtualDisplay = mediaProjection.createVirtualDisplay(
                "ScramShot_${System.currentTimeMillis()}",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                currentImageReader?.surface, null, null
            )

            Log.d(TAG, "⚡ Screenshot setup completed successfully")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in screenshot setup", e)
            updateNotificationWithMessage("Screenshot setup failed: ${e.message}")

            try {
                mediaProjection.stop()
            } catch (cleanup: Exception) {
                Log.w(TAG, "⚠️ Error cleaning up MediaProjection", cleanup)
            }

            cleanupScreenshotResources()
            isProcessingScreenshot.set(false)
        }
    }

    private fun setupImageListener(mediaProjection: MediaProjection) {
        val imageProcessed = AtomicBoolean(false)
        var frameCount = 0 // ADDED: Frame counter to skip initial frames

        currentImageReader?.setOnImageAvailableListener({ reader ->
            frameCount++

            // OPTION A: Skip first 2-3 frames to avoid permission dialog
            if (frameCount <= 2) {
                Log.d(TAG, "⏭️ Skipping frame $frameCount (letting UI settle)")
                try {
                    // Consume and discard the frame
                    reader.acquireLatestImage()?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Error discarding frame $frameCount", e)
                }
                return@setOnImageAvailableListener
            }

            if (!imageProcessed.compareAndSet(false, true)) {
                return@setOnImageAvailableListener
            }

            Log.d(TAG, "📷 Processing screenshot image (frame $frameCount)")

            var image: Image? = null
            var bitmap: Bitmap? = null

            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    bitmap = imageToBitmapOptimized(image)
                    val file = saveBitmapOptimized(bitmap)

                    if (file != null) {
                        Log.d(TAG, "✅ Screenshot saved (frame $frameCount)")

                        val bitmapCopy = bitmap.copy(bitmap.config, false)

                        serviceScope.launch(Dispatchers.IO) {
                            try {
                                processScreenshotAsync(file, bitmapCopy)
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Processing error", e)
                                withContext(Dispatchers.Main) {
                                    updateNotificationWithMessage("Processing error")
                                    restoreReadyState()
                                }
                            } finally {
                                try {
                                    bitmapCopy.recycle()
                                    file.delete()
                                } catch (e: Exception) {
                                    Log.w(TAG, "⚠️ Cleanup error", e)
                                }
                            }
                        }
                    } else {
                        updateNotificationWithMessage("Failed to save screenshot")
                        restoreReadyState()
                    }
                } else {
                    updateNotificationWithMessage("No image captured")
                    restoreReadyState()
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Image processing error", e)
                updateNotificationWithMessage("Image processing failed")
                restoreReadyState()
            } finally {
                try {
                    image?.close()
                    bitmap?.recycle()
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Resource cleanup error", e)
                }

                try {
                    mediaProjection.stop()
                    Log.d(TAG, "✅ Fresh MediaProjection stopped after single use (frame $frameCount)")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Error stopping fresh MediaProjection", e)
                }

                cleanupScreenshotResources()
                isProcessingScreenshot.set(false)
            }

        }, Handler(Looper.getMainLooper()))
    }

    private suspend fun processScreenshotAsync(file: File, bitmapCopy: Bitmap) {
        Log.d(TAG, "🔍 Starting scam analysis...")

        try {
            // FIXED: Ensure model is ready before processing
            if (!ensureModelReady()) {
                Log.e(TAG, "❌ Model not ready for analysis")
                withContext(Dispatchers.Main) {
                    updateNotificationWithMessage("❌ AI model not ready - initialization failed")
                    Handler(Looper.getMainLooper()).postDelayed({
                        restoreReadyState()
                    }, 3000)
                }
                return
            }

            val extractedText = OCRHelper.extractTextFromBitmap(bitmapCopy)

            if (!extractedText.isNullOrBlank()) {
                Log.d(TAG, "📝 OCR found ${extractedText.length} characters")

                // FIXED: Proper text cleaning pipeline
                val cleanedText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                    AppDetectorHelper.hasUsageStatsPermission(this@SafeScreenCaptureService)) {

                    // Get current app package name
                    val currentAppPackage = AppDetectorHelper.getCurrentApp(this@SafeScreenCaptureService)

                    Log.d(TAG, "📱 Current app: $currentAppPackage")

                    // FIXED: Always apply basic cleaning first, then app-specific if needed
                    val basicCleaned = TextFilterHelper.cleanExtractedText(extractedText)

                    if (currentAppPackage != null) {
                        // Apply app-specific cleaning with package name
                        TextFilterHelper.applyAppSpecificCleaning(basicCleaned, currentAppPackage)
                    } else {
                        basicCleaned
                    }
                } else {
                    // No app detection permission - just use basic cleaning
                    Log.d(TAG, "📱 No app detection permission - using basic cleaning")
                    TextFilterHelper.cleanExtractedText(extractedText)
                }

                // Check if the cleaned text is worth analyzing
                if (!TextFilterHelper.isTextWorthAnalyzing(cleanedText)) {
                    Log.d(TAG, "⚠️ Cleaned text is not worth analyzing (too short or system UI only)")
                    withContext(Dispatchers.Main) {
                        updateNotificationWithMessage("📷 No meaningful content found")
                        Handler(Looper.getMainLooper()).postDelayed({
                            restoreReadyState()
                        }, 2000)
                    }
                    return
                }

                Log.d(TAG, "✨ Using cleaned text for analysis (${cleanedText.length} chars)")
                Log.d(TAG, "🧹 Cleaned text: $cleanedText")

                // FIXED: Use cleaned text for analysis instead of raw OCR text
                val scamResult = try {
                    ScamDetectionModelHelper.analyzeTextForScamWithContext(this@SafeScreenCaptureService, cleanedText)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Model analysis failed, attempting re-initialization", e)

                    // Mark model as not initialized and try again
                    isModelInitialized = false

                    if (ensureModelReady()) {
                        Log.d(TAG, "🔄 Model re-initialized, retrying analysis...")
                        ScamDetectionModelHelper.analyzeTextForScamWithContext(this@SafeScreenCaptureService, cleanedText)
                    } else {
                        Log.e(TAG, "❌ Model re-initialization failed")
                        ScamResult(
                            isScam = false,
                            confidence = 0f,
                            explanation = "Model re-initialization failed"
                        )
                    }
                }

                Log.d(TAG, "🔍 Scam analysis: isScam=${scamResult.isScam}, confidence=${scamResult.confidence}")

                // Log app context if available
                scamResult.appContext?.let { appName ->
                    Log.d(TAG, "📱 Analyzed content from: $appName (${scamResult.appCategory})")
                }

                withContext(Dispatchers.Main) {
                    // Show notification for analysis result
                    ScamNotificationHelper.showScamAlert(this@SafeScreenCaptureService, scamResult)

                    if (scamResult.isScam && scamResult.confidence > 0.6f) {
                        Log.w(TAG, "🚨 SCAM DETECTED!")
                        val contextMessage = scamResult.appContext?.let { " in $it" } ?: ""
                        updateNotificationWithMessage("🚨 SCAM DETECTED$contextMessage!")

                        Handler(Looper.getMainLooper()).postDelayed({
                            restoreReadyState()
                        }, 5000)

                    } else {
                        Log.d(TAG, "✅ No scam detected")
                        val contextMessage = scamResult.appContext?.let { " in $it" } ?: ""
                        updateNotificationWithMessage("✅ Safe content$contextMessage")

                        Handler(Looper.getMainLooper()).postDelayed({
                            restoreReadyState()
                        }, 3000)
                    }
                }
            } else {
                Log.d(TAG, "📝 No text found")
                withContext(Dispatchers.Main) {
                    updateNotificationWithMessage("📷 No text found")
                    Handler(Looper.getMainLooper()).postDelayed({
                        restoreReadyState()
                    }, 2000)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Analysis error", e)
            withContext(Dispatchers.Main) {
                updateNotificationWithMessage("Analysis failed: ${e.message}")
                Handler(Looper.getMainLooper()).postDelayed({
                    restoreReadyState()
                }, 3000)
            }
        }
    }

    private fun restoreReadyState() {
        if (isModelInitialized) {
            updateNotificationWithMessage("Ready - Tap Scan to check for scams")
        } else {
            updateNotificationWithMessage("AI model not ready - tap to retry initialization")
        }
    }

    private fun cleanupScreenshotResources() {
        try {
            currentVirtualDisplay?.release()
            currentVirtualDisplay = null
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error releasing VirtualDisplay", e)
        }

        try {
            currentImageReader?.close()
            currentImageReader = null
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error closing ImageReader", e)
        }

        Log.d(TAG, "✅ Screenshot resources cleaned")
    }

    private fun getScreenMetrics() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowManager = getSystemService(WindowManager::class.java)
                val windowMetrics = windowManager.currentWindowMetrics
                val bounds = windowMetrics.bounds
                screenWidth = bounds.width()
                screenHeight = bounds.height()
                screenDensity = resources.displayMetrics.densityDpi
            } else {
                @Suppress("DEPRECATION")
                val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                @Suppress("DEPRECATION")
                val display = windowManager.defaultDisplay

                val realMetrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(realMetrics)
                screenWidth = realMetrics.widthPixels
                screenHeight = realMetrics.heightPixels
                screenDensity = realMetrics.densityDpi
            }
            Log.d(TAG, "📏 Screen: ${screenWidth}x${screenHeight}, density: $screenDensity")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error getting screen metrics", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service for screen capture"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val permissionIntent = Intent(this, PermissionRequestActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("requestType", "fresh_screenshot")
        }
        val quickShotPendingIntent = PendingIntent.getActivity(
            this, 0, permissionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, SafeScreenCaptureService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ Scram Protection Active")
            .setContentText("Tap Scan to check for scams")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_menu_camera, "\uD83D\uDD0D Scan", quickShotPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "🛑 Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotificationWithMessage(message: String) {
        try {
            val permissionIntent = Intent(this, PermissionRequestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("requestType", "fresh_screenshot")
            }
            val quickShotPendingIntent = PendingIntent.getActivity(
                this, 0, permissionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val stopIntent = Intent(this, SafeScreenCaptureService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            val stopPendingIntent = PendingIntent.getService(
                this, 2, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("🛡️ Scram Protection Active")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .addAction(android.R.drawable.ic_menu_camera, "\uD83D\uDD0D Scan", quickShotPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "🛑 Stop", stopPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to update notification", e)
        }
    }

    private fun imageToBitmapOptimized(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        val bitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) {
            bitmap
        } else {
            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            bitmap.recycle()
            croppedBitmap
        }
    }

    private fun saveBitmapOptimized(bitmap: Bitmap): File? {
        return try {
            val screenshotsDir = File(getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            val file = File(screenshotsDir, "screenshot_${System.currentTimeMillis()}.png")

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save bitmap", e)
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🔄 Service destroying...")

        // ADDED: Broadcast service stopping to MainActivity when service is destroyed
        broadcastServiceState(false)

        cleanupScreenshotResources()

        try {
            // FIXED: Cleanup with proper method names
            ScamDetectionModelHelper.cleanUp()
            OCRHelper.cleanup()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Cleanup error", e)
        }

        serviceScope.cancel()
        Log.d(TAG, "✅ Service destroyed")
    }
}