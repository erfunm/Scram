package com.example.screencaptureapp

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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

    // FIXED: Track foreground service state
    private var isForegroundServiceActive = false

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

        // FIXED: Start with SPECIAL_USE only - upgrade to MEDIA_PROJECTION when we have permission
        startInitialForegroundService()
        broadcastServiceState(true)
    }

    /**
     * FIXED: Start foreground service with SPECIAL_USE only initially
     */
    private fun startInitialForegroundService() {
        try {
            val notification = createNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // FIXED: Start with SPECIAL_USE only - no MEDIA_PROJECTION yet
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            isForegroundServiceActive = true
            Log.d(TAG, "✅ Started as foreground service (SPECIAL_USE)")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start initial foreground service", e)
            stopSelf()
        }
    }

    /**
     * FIXED: Upgrade to MEDIA_PROJECTION type when we're about to create MediaProjection
     */
    private fun upgradeToMediaProjectionService() {
        try {
            if (!isForegroundServiceActive) {
                Log.w(TAG, "⚠️ Cannot upgrade - foreground service not active")
                return
            }

            val notification = createNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // FIXED: Now we can use MEDIA_PROJECTION type before creating projection
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                Log.d(TAG, "✅ Upgraded to MEDIA_PROJECTION foreground service")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to upgrade foreground service", e)
            // This is critical - if we can't upgrade, we can't create MediaProjection
            throw e
        }
    }

    /**
     * Broadcast service state to MainActivity
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "📥 onStartCommand: action='${intent?.action}'")

        when (intent?.action) {
            ACTION_STOP_SERVICE, "com.example.screencaptureapp.STOP_SERVICE" -> {
                Log.d(TAG, "🛑 Stop service requested")

                val prefs = getSharedPreferences("service_state", MODE_PRIVATE)
                prefs.edit().putBoolean("is_running", false).apply()

                broadcastServiceState(false)
                serviceScope.cancel()
                cleanupScreenshotResources()
                updateNotificationWithMessage("Stopping...")
                stopSelf()
                return START_NOT_STICKY
            }

            "SETUP_PERSISTENT_PROJECTION" -> {
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
                }
            }

            else -> {
                Log.d(TAG, "🏁 Service started normally")
                updateNotificationWithMessage("Ready - Tap Scan to check for scams")
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

            Log.d(TAG, "🔧 Using permission data for screenshot")
            updateNotificationWithMessage("✅ Permission granted - upgrading service...")

            // FIXED: Upgrade to MEDIA_PROJECTION type BEFORE creating MediaProjection
            upgradeToMediaProjectionService()

            Handler(Looper.getMainLooper()).postDelayed({
                performQuickScreenshotWithFreshPermission(permissionData)
            }, 500)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error using permission data", e)
            updateNotificationWithMessage("Setup error: ${e.message}")
            isProcessingScreenshot.set(false)
        }
    }

    private fun performQuickScreenshotWithFreshPermission(permissionData: Intent) {
        Log.d(TAG, "📸 Creating MediaProjection with fresh permission")

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
                Log.e(TAG, "❌ Failed to create MediaProjection")
                updateNotificationWithMessage("Failed to create screen projection")
                isProcessingScreenshot.set(false)
                return
            }

            Log.d(TAG, "✅ MediaProjection created")

            // Service type already upgraded before creating MediaProjection
            performSingleScreenshot(freshMediaProjection)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creating MediaProjection", e)
            updateNotificationWithMessage("Screenshot failed: ${e.message}")
            cleanupScreenshotResources()
            isProcessingScreenshot.set(false)
        }
    }

    private fun performSingleScreenshot(mediaProjection: MediaProjection) {
        Log.d(TAG, "📸 Setting up screenshot capture")

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

            Log.d(TAG, "⚡ Screenshot setup completed")

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
        var frameCount = 0

        currentImageReader?.setOnImageAvailableListener({ reader ->
            frameCount++

            // Skip first 2 frames to avoid permission dialog
            if (frameCount <= 2) {
                Log.d(TAG, "⏭️ Skipping frame $frameCount")
                try {
                    reader.acquireLatestImage()?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Error discarding frame", e)
                }
                return@setOnImageAvailableListener
            }

            if (!imageProcessed.compareAndSet(false, true)) {
                return@setOnImageAvailableListener
            }

            Log.d(TAG, "📷 Processing screenshot (frame $frameCount)")

            var image: Image? = null
            var bitmap: Bitmap? = null

            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    bitmap = imageToBitmapOptimized(image)
                    val file = saveBitmapOptimized(bitmap)

                    if (file != null) {
                        Log.d(TAG, "✅ Screenshot saved")

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
                    Log.d(TAG, "✅ MediaProjection stopped after single use")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Error stopping MediaProjection", e)
                }

                cleanupScreenshotResources()
                isProcessingScreenshot.set(false)
            }

        }, Handler(Looper.getMainLooper()))
    }

    /**
     * SIMPLIFIED: Just check if model is ready - no initialization
     */
    private suspend fun ensureModelReady(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Just check if model is ready (should be loaded by MainActivity)
                if (ScamDetectionModelHelper.isModelReady()) {
                    Log.d(TAG, "✅ Model is ready in GPU - proceeding with analysis")
                    withContext(Dispatchers.Main) {
                        updateNotificationWithMessage("AI model ready - processing...")
                    }
                    return@withContext true
                } else {
                    Log.w(TAG, "⚠️ Model not loaded - user should restart service")
                    withContext(Dispatchers.Main) {
                        updateNotificationWithMessage("Model not loaded - restart service")
                    }
                    return@withContext false
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error checking model readiness", e)
                withContext(Dispatchers.Main) {
                    updateNotificationWithMessage("Model check error")
                }
                return@withContext false
            }
        }
    }

    private suspend fun processScreenshotAsync(file: File, bitmapCopy: Bitmap) {
        Log.d(TAG, "🔍 Starting scam analysis...")

        try {
            // Check if model is ready (should be loaded already)
            if (!ensureModelReady()) {
                Log.e(TAG, "❌ Model not ready")
                withContext(Dispatchers.Main) {
                    updateNotificationWithMessage("❌ AI model not loaded")
                    Handler(Looper.getMainLooper()).postDelayed({
                        restoreReadyState()
                    }, 3000)
                }
                return
            }

            // Extract text from image
            val extractedText = OCRHelper.extractTextFromImage(this@SafeScreenCaptureService, file)

            if (!extractedText.isNullOrBlank()) {
                Log.d(TAG, "📝 OCR found ${extractedText.length} characters")

                // Clean extracted text
                val cleanedText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                    AppDetectorHelper.hasUsageStatsPermission(this@SafeScreenCaptureService)) {

                    val currentAppPackage = AppDetectorHelper.getCurrentApp(this@SafeScreenCaptureService)
                    Log.d(TAG, "📱 Current app: $currentAppPackage")

                    val basicCleaned = TextFilterHelper.cleanExtractedText(extractedText)

                    if (currentAppPackage != null) {
                        TextFilterHelper.applyAppSpecificCleaning(basicCleaned, currentAppPackage)
                    } else {
                        basicCleaned
                    }
                } else {
                    Log.d(TAG, "📱 No app detection permission - using basic cleaning")
                    TextFilterHelper.cleanExtractedText(extractedText)
                }

                // Check if text is worth analyzing
                if (!TextFilterHelper.isTextWorthAnalyzing(cleanedText)) {
                    Log.d(TAG, "⚠️ Text not worth analyzing")
                    withContext(Dispatchers.Main) {
                        updateNotificationWithMessage("📷 No meaningful content found")
                        Handler(Looper.getMainLooper()).postDelayed({
                            restoreReadyState()
                        }, 2000)
                    }
                    return
                }

                Log.d(TAG, "✨ Using cleaned text for analysis (${cleanedText.length} chars)")

                // Analyze with GPU-loaded model (should be instant)
                val scamResult = ScamDetectionModelHelper.analyzeTextForScamWithContext(
                    this@SafeScreenCaptureService,
                    cleanedText
                )

                Log.d(TAG, "🔍 Analysis result: isScam=${scamResult.isScam}, confidence=${scamResult.confidence}")

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
        updateNotificationWithMessage("Ready - Tap Scan to check for scams")
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
                setSound(null, null)
                enableVibration(false)
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
            .setContentText("Ready - Tap Scan to check for scams")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_menu_camera, "\uD83D\uDD0D Scan", quickShotPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "🛑 Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
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
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
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

        broadcastServiceState(false)
        cleanupScreenshotResources()
        isForegroundServiceActive = false

        try {
            OCRHelper.cleanup()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Cleanup error", e)
        }

        serviceScope.cancel()
        Log.d(TAG, "✅ Service destroyed")
    }
}