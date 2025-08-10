package com.example.screencaptureapp

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // Service state management
    private var isServiceRunning by mutableStateOf(false)
    private var isModelLoading by mutableStateOf(true)
    private var modelStatus by mutableStateOf("Initializing AI model...")

    // Loading and log state management
    private var isActivating by mutableStateOf(false)
    private var initializationLogs by mutableStateOf(listOf<String>())

    // ADDED: Broadcast receiver for service state changes
    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SafeScreenCaptureService.ACTION_SERVICE_STATE_CHANGED) {
                val serviceRunning = intent.getBooleanExtra(SafeScreenCaptureService.EXTRA_SERVICE_RUNNING, false)
                Log.d(TAG, "📡 Received service state broadcast: isRunning=$serviceRunning")

                // Update UI state
                isServiceRunning = serviceRunning
                isActivating = false // Stop any activation animation

                // Update shared preferences to match
                val prefs = getSharedPreferences("service_state", MODE_PRIVATE)
                prefs.edit().putBoolean("is_running", serviceRunning).apply()

                Log.d(TAG, "✅ UI state updated: isServiceRunning=$isServiceRunning")
            }
        }
    }

    // Permission launcher for notifications (Android 13+)
    private val notificationPermissionResult = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        try {
            if (granted) {
                Log.d(TAG, "Notification permission granted")
                startScreenCaptureService()
            } else {
                Log.w(TAG, "Notification permission denied")
                Toast.makeText(this, "Notification permission required for service", Toast.LENGTH_SHORT).show()
                isActivating = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in notification permission result", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            isActivating = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            Log.d(TAG, "MainActivity onCreate started")

            // FIXED: DO NOT start/stop any service during onCreate
            // Just ensure clean state in preferences
            val prefs = getSharedPreferences("service_state", MODE_PRIVATE)
            prefs.edit().putBoolean("is_running", false).apply()
            Log.d(TAG, "✅ Ensured clean inactive state in preferences")


            // ADDED: Register broadcast receiver for service state changes
            val filter = IntentFilter(SafeScreenCaptureService.ACTION_SERVICE_STATE_CHANGED)
            LocalBroadcastManager.getInstance(this).registerReceiver(serviceStateReceiver, filter)
            Log.d(TAG, "📡 Registered service state broadcast receiver")

            // Debug functions
            debugModelStatus()
            exploreAndCleanModelsFolder()
            cleanupInvalidModelFiles()

            // Re-check after cleanup
            Log.d(TAG, "🔄 RE-CHECKING AFTER CLEANUP:")
            debugModelStatus()

            // Debug screen metrics
            debugScreenMetrics()

            // Check model and setup UI
            checkModelAndSetupUI()

            setContent {
                ScramTheme {
                    ScramMainScreen(
                        isServiceRunning = isServiceRunning,
                        isModelLoading = isModelLoading,
                        isActivating = isActivating,
                        modelStatus = modelStatus,
                        initializationLogs = initializationLogs,
                        onToggleService = { toggleService() }
                    )
                }
            }

            Log.d(TAG, "MainActivity onCreate completed - Service state: INACTIVE (no auto-start)")

        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            Toast.makeText(this, "Setup error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            Log.d(TAG, "App resumed")

            // Read current service state from preferences
            val prefs = getSharedPreferences("service_state", MODE_PRIVATE)
            val savedServiceState = prefs.getBoolean("is_running", false)

            // Update UI to match saved state
            isServiceRunning = savedServiceState

            Log.d(TAG, "Service state restored from preferences: isServiceRunning=$isServiceRunning")

            // FIX: If user was activating and now has usage stats permission, continue activation
            if (isActivating && !isServiceRunning && AppDetectorHelper.hasUsageStatsPermission(this)) {
                Log.d(TAG, "🔄 Resuming activation after permission grant")
                isActivating = false
                startServiceWithoutAppDetection() // Continue the activation process
            }

            // Check if user granted usage stats permission while away
            if (AppDetectorHelper.hasUsageStatsPermission(this)) {
                Log.d(TAG, "✅ Usage stats permission is granted")
            }

            // If we returned from download activity, update model status
            if (ModelDownloader.isModelDownloaded(this)) {
                isModelLoading = false
                modelStatus = "AI model ready"
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in onResume", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            // ADDED: Unregister broadcast receiver
            LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceStateReceiver)
            Log.d(TAG, "📡 Unregistered service state broadcast receiver")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error unregistering broadcast receiver", e)
        }
    }

    @Composable
    private fun ScramTheme(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            content = content
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ScramMainScreen(
        isServiceRunning: Boolean,
        isModelLoading: Boolean,
        isActivating: Boolean,
        modelStatus: String,
        initializationLogs: List<String>,
        onToggleService: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF5F5F5))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Spacer(modifier = Modifier.height(80.dp))

            // App Title
            Text(
                text = "Scram",
                fontSize = 90.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2D3748),
                letterSpacing = (-0.5).sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Tagline
            Text(
                text = "Spot the scam",
                fontSize = 28.sp,
                fontWeight = FontWeight.Normal,
                color = Color(0xFF4A5568)
            )

            Spacer(modifier = Modifier.weight(1f))

            // Main Protection Circle
            ProtectionCircle(
                isServiceRunning = isServiceRunning,
                isModelLoading = isModelLoading,
                isActivating = isActivating,
                onClick = onToggleService
            )

            // Loading logs section
            if (isActivating && initializationLogs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                InitializationLogsSection(logs = initializationLogs)
            }

            Spacer(modifier = Modifier.weight(1f))

            // Status Card at Bottom
            StatusCard(
                isServiceRunning = isServiceRunning,
                isModelLoading = isModelLoading,
                isActivating = isActivating,
                modelStatus = modelStatus
            )

            // ADD THIS: ML Kit Test Button (for debugging)
            Spacer(modifier = Modifier.height(16.dp))

            Spacer(modifier = Modifier.height(48.dp))
        }
    }


    @Composable
    private fun ProtectionCircle(
        isServiceRunning: Boolean,
        isModelLoading: Boolean,
        isActivating: Boolean,
        onClick: () -> Unit
    ) {
        val animatedScale by animateFloatAsState(
            targetValue = when {
                isActivating -> 1.05f
                isServiceRunning -> 1.02f
                else -> 1f
            },
            animationSpec = tween(300)
        )

        val circleColor = when {
            isActivating -> Color(0xFF6B46C1).copy(alpha = 0.2f)
            isModelLoading -> Color(0xFF6B46C1).copy(alpha = 0.1f)
            isServiceRunning -> Color(0xFF6B46C1).copy(alpha = 0.15f)
            else -> Color(0xFFE2E8F0).copy(alpha = 0.7f)
        }

        val shieldColor = when {
            isActivating -> Color(0xFF6B46C1)
            isModelLoading -> Color(0xFF6B46C1)
            isServiceRunning -> Color(0xFF6B46C1)
            else -> Color(0xFF94A3B8)
        }

        val textColor = when {
            isActivating -> Color(0xFF6B46C1)
            isModelLoading -> Color(0xFF6B46C1)
            isServiceRunning -> Color(0xFF2D3748)
            else -> Color(0xFF4A5568)
        }

        Box(
            modifier = Modifier
                .size(280.dp)
                .scale(animatedScale)
                .background(
                    color = circleColor,
                    shape = CircleShape
                )
                .clickable(
                    enabled = !isModelLoading && !isActivating,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Shield Icon or Loading Indicator
                if (isModelLoading || isActivating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(80.dp),
                        strokeWidth = 4.dp,
                        color = Color(0xFF6B46C1)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(150.dp),
                        tint = shieldColor
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Status Text
                Text(
                    text = when {
                        isActivating -> "Activating..."
                        isModelLoading -> "Loading..."
                        isServiceRunning -> "Active"
                        else -> "Protect"
                    },
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
            }
        }
    }

    @Composable
    private fun InitializationLogsSection(logs: List<String>) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1A1A1A).copy(alpha = 0.9f)
            ),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            val listState = rememberLazyListState()

            // Auto-scroll to bottom when new logs are added
            LaunchedEffect(logs.size) {
                if (logs.isNotEmpty()) {
                    listState.animateScrollToItem(logs.size - 1)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(logs) { log ->
                    Text(
                        text = log,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF00FF41), // Matrix green
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }

    @Composable
    private fun StatusCard(
        isServiceRunning: Boolean,
        isModelLoading: Boolean,
        isActivating: Boolean,
        modelStatus: String
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFE2E8F0).copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // First status line - Protection status
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🔒",
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = when {
                            isActivating -> "Activating protection..."
                            isModelLoading -> "Initializing protection..."
                            isServiceRunning -> "Protection is active - scanning for scams"
                            else -> "Enable protection to start scanning for scams"
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF4A5568)
                    )
                }

                // Second status line - AI model status
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🤖",
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = when {
                            isActivating -> "Loading AI model..."
                            isModelLoading -> modelStatus
                            else -> "AI-powered detection ready"
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF4A5568)
                    )
                }
            }
        }
    }

    // Add log message to the UI
    private fun addLogMessage(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        initializationLogs = initializationLogs + "[$timestamp] $message"

        // Keep only last 20 log messages to prevent memory issues
        if (initializationLogs.size > 20) {
            initializationLogs = initializationLogs.takeLast(20)
        }
    }

    private fun toggleService() {
        try {
            if (isServiceRunning) {
                stopScreenCaptureService()
            } else {
                // Start activation process with loading state
                isActivating = true
                initializationLogs = listOf() // Clear previous logs
                addLogMessage("Starting activation process...")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    when (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)) {
                        PackageManager.PERMISSION_GRANTED -> startScreenCaptureService()
                        else -> {
                            addLogMessage("Requesting notification permission...")
                            notificationPermissionResult.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                } else {
                    startScreenCaptureService()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling service", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            isActivating = false
            addLogMessage("ERROR: ${e.message}")
        }
    }

    private fun checkModelAndSetupUI() {
        if (!ModelDownloader.isModelDownloaded(this)) {
            Log.d(TAG, "Model not downloaded, launching download activity")
            isModelLoading = true
            modelStatus = "Model not downloaded"
            try {
                ModelDownloadActivity.start(this)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start ModelDownloadActivity", e)
                Toast.makeText(this, "Failed to start download: ${e.message}", Toast.LENGTH_LONG).show()
                isModelLoading = false
                modelStatus = "Download failed"
            }
        } else {
            Log.d(TAG, "Model already downloaded, setting up UI")
            isModelLoading = false
            modelStatus = "AI model ready"
        }
    }

    private fun startScreenCaptureService() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        scope.launch {
            try {
                addLogMessage("Checking AI model...")
                delay(500)

                if (!ModelDownloader.isModelDownloaded(this@MainActivity)) {
                    addLogMessage("AI model not found - starting download...")
                    Toast.makeText(this@MainActivity, "Please download the AI model first", Toast.LENGTH_LONG).show()
                    ModelDownloadActivity.start(this@MainActivity)
                    isActivating = false
                    return@launch
                }

                addLogMessage("AI model verified ✓")
                delay(300)

                if (!AppDetectorHelper.hasUsageStatsPermission(this@MainActivity)) {
                    addLogMessage("Requesting enhanced protection permissions...")
                    showUsageStatsPermissionDialog()
                    return@launch
                }

                addLogMessage("Permissions verified ✓")
                delay(300)

                // CRITICAL FIX: Load model into GPU memory NOW (during activation)
                addLogMessage("Loading AI model into GPU memory...")

                val modelLoaded = try {
                    ScamDetectionModelHelper.initialize(this@MainActivity)
                } catch (e: Exception) {
                    Log.e(TAG, "Model initialization failed", e)
                    false
                }

                if (!modelLoaded) {
                    addLogMessage("ERROR: Failed to load AI model into GPU")
                    Toast.makeText(this@MainActivity, "AI model loading failed", Toast.LENGTH_LONG).show()
                    isActivating = false
                    return@launch
                }

                addLogMessage("AI model loaded into GPU ✓")
                delay(300)

                // Now start the service (model is already in GPU memory)
                addLogMessage("Starting background service...")
                val serviceIntent = Intent(this@MainActivity, SafeScreenCaptureService::class.java)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                delay(500)

                val prefs = getSharedPreferences("service_state", MODE_PRIVATE)
                prefs.edit().putBoolean("is_running", true).apply()

                addLogMessage("Service started successfully ✓")
                delay(300)

                addLogMessage("Scram protection is now active!")
                delay(500)

                isServiceRunning = true
                isActivating = false

                Toast.makeText(this@MainActivity, "🛡️ AI model loaded - Ready for instant scam detection!", Toast.LENGTH_LONG).show()

            } catch (e: Exception) {
                Log.e(TAG, "Error starting service", e)
                addLogMessage("ERROR: ${e.message}")
                Toast.makeText(this@MainActivity, "Failed to start service: ${e.message}", Toast.LENGTH_LONG).show()
                isActivating = false
            }
        }
    }

    private fun showUsageStatsPermissionDialog() {
        try {
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                android.app.AlertDialog.Builder(this)
            } else {
                android.app.AlertDialog.Builder(this)
            }

            val dialog = builder
                .setTitle("Enhanced Protection Available")
                .setMessage("""
                    For better scam detection, Scram can analyze which app you're using to provide more accurate warnings.
                    
                    For example:
                    • WhatsApp/SMS: Detect fake emergency requests
                    • Gmail/Email: Identify phishing attempts  
                    • General apps: Standard scam detection
                    
                    This permission allows Scram to see which app is open, but cannot read your app content.
                    
                    Grant "Usage Access" permission for enhanced protection?
                """.trimIndent())
                .setPositiveButton("Grant Permission") { _, _ ->
                    addLogMessage("Opening permission settings...")
                    AppDetectorHelper.requestUsageStatsPermission(this)
                }
                .setNegativeButton("Skip (Basic Protection)") { _, _ ->
                    addLogMessage("Continuing with basic protection...")
                    startServiceWithoutAppDetection()
                }
                .setCancelable(false)
                .create()

            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing usage stats dialog", e)
            addLogMessage("Permission dialog error - using basic protection")
            startServiceWithoutAppDetection()
        }
    }

    private fun startServiceWithoutAppDetection() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        scope.launch {
            try {
                addLogMessage("Starting basic protection mode...")
                delay(300)

                // FIXED: Pre-initialize model for basic protection too
                addLogMessage("Loading AI model...")
                val modelInitialized = try {
                    ScamDetectionModelHelper.initialize(this@MainActivity)
                } catch (e: Exception) {
                    Log.e(TAG, "Model initialization failed", e)
                    false
                }

                if (!modelInitialized) {
                    addLogMessage("ERROR: AI model initialization failed")
                    Toast.makeText(this@MainActivity, "AI model initialization failed", Toast.LENGTH_LONG).show()
                    isActivating = false
                    return@launch
                }

                addLogMessage("AI model ready ✓")
                delay(300)

                // FIXED: Start service without action
                val serviceIntent = Intent(this@MainActivity, SafeScreenCaptureService::class.java)
                // DO NOT SET ACTION

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                val prefs = getSharedPreferences("service_state", MODE_PRIVATE)
                prefs.edit().putBoolean("is_running", true).apply()

                addLogMessage("Basic protection activated ✓")
                delay(300)

                isServiceRunning = true
                isActivating = false

                Toast.makeText(this@MainActivity, "🛡️ Basic scam protection enabled", Toast.LENGTH_LONG).show()

            } catch (e: Exception) {
                Log.e(TAG, "Error starting service without app detection", e)
                addLogMessage("ERROR: ${e.message}")
                Toast.makeText(this@MainActivity, "Failed to start service: ${e.message}", Toast.LENGTH_LONG).show()
                isActivating = false
            }
        }
    }

    private fun stopScreenCaptureService() {
        try {
            Log.d(TAG, "Stopping SafeScreenCaptureService")

            val serviceIntent = Intent(this, SafeScreenCaptureService::class.java).apply {
                action = "com.example.screencaptureapp.STOP_SERVICE"
            }
            startService(serviceIntent)

            // CRITICAL FIX: Clean up GPU model when stopping
            ScamDetectionModelHelper.cleanUp()
            Log.d(TAG, "🧹 AI model cleaned from GPU memory")

            val prefs = getSharedPreferences("service_state", MODE_PRIVATE)
            prefs.edit().putBoolean("is_running", false).apply()
            isServiceRunning = false

            initializationLogs = listOf()

            Log.d(TAG, "🛑 Scram protection service stopped and AI model unloaded")
            Toast.makeText(this, "🔒 Scram protection disabled - AI model unloaded", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service", e)
            Toast.makeText(this, "Error stopping service: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Debug methods
    private fun debugScreenMetrics() {
        Log.d(TAG, "🖥️ ========== SCREEN METRICS DEBUG ==========")
        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowMetrics = windowManager.currentWindowMetrics
                val bounds = windowMetrics.bounds
                Log.d(TAG, "Screen bounds: ${bounds.width()}x${bounds.height()}")
                Log.d(TAG, "Density DPI: ${resources.displayMetrics.densityDpi}")
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getMetrics(displayMetrics)
                Log.d(TAG, "Screen size: ${displayMetrics.widthPixels}x${displayMetrics.heightPixels}")
                Log.d(TAG, "Density DPI: ${displayMetrics.densityDpi}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting screen metrics", e)
        }
        Log.d(TAG, "=========================================")
    }

    private fun debugModelStatus() {
        Log.d(TAG, "🔍 ========== MODEL STATUS DEBUG ==========")
        try {
            val isDownloaded = ModelDownloader.isModelDownloaded(this)
            val modelFile = ModelDownloader.getModelFile(this)

            Log.d(TAG, "Model downloaded: $isDownloaded")
            Log.d(TAG, "Model file path: ${modelFile.absolutePath}")
            Log.d(TAG, "Model file exists: ${modelFile.exists()}")
            if (modelFile.exists()) {
                Log.d(TAG, "Model file size: ${modelFile.length()} bytes (${modelFile.length() / 1024 / 1024} MB)")
            }

            // Check models directory
            val modelsDir = modelFile.parentFile
            if (modelsDir?.exists() == true) {
                Log.d(TAG, "Models directory contents:")
                modelsDir.listFiles()?.forEach { file ->
                    Log.d(TAG, "  - ${file.name}: ${file.length()} bytes")
                }
            } else {
                Log.d(TAG, "Models directory does not exist")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error debugging model status", e)
        }
        Log.d(TAG, "=========================================")
    }

    private fun exploreAndCleanModelsFolder() {
        try {
            val modelsDir = File(getExternalFilesDir(null), "models")
            Log.d(TAG, "🔍 Exploring models folder: ${modelsDir.absolutePath}")

            if (!modelsDir.exists()) {
                Log.d(TAG, "Models directory doesn't exist, creating...")
                modelsDir.mkdirs()
                return
            }

            modelsDir.listFiles()?.forEach { file ->
                Log.d(TAG, "Found file: ${file.name}, size: ${file.length()} bytes")

                // Clean up invalid files
                if (file.length() < 100000000) { // Less than 100MB
                    Log.d(TAG, "Deleting invalid model file: ${file.name}")
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error exploring models folder", e)
        }
    }

    private fun cleanupInvalidModelFiles() {
        try {
            val modelFile = ModelDownloader.getModelFile(this)
            if (modelFile.exists() && modelFile.length() < 1000000000) { // Less than 1GB
                Log.d(TAG, "Cleaning up invalid model file: ${modelFile.name}")
                modelFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up invalid model files", e)
        }
    }
}