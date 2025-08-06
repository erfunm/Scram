package com.example.screencaptureapp

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log

class ScreenCaptureApplication : Application(), ComponentCallbacks2 {

    companion object {
        private const val TAG = "ScreenCaptureApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🚀 Application starting...")

        // REMOVED: ML Kit initialization - Gemma will handle language detection
        Log.d(TAG, "💬 Multilingual scam detection: Gemma will detect languages automatically")

        // CRITICAL: DO NOT register ComponentCallbacks2 manually - Application already implements it
        // The system automatically calls our onTrimMemory() when we implement ComponentCallbacks2
        // DO NOT CALL: registerComponentCallbacks(this) - this would create duplicate callbacks!
    }

    override fun onTrimMemory(level: Int) {
        // ❌ REMOVED: super.onTrimMemory(level) - This caused infinite recursion!
        // ✅ FIXED: Application is already called by ComponentCallbacksController

        Log.d(TAG, "🗑️ System memory trim level: $level")

        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                Log.d(TAG, "📱 Moderate memory pressure")
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                Log.d(TAG, "⚠️ Low memory warning")
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                Log.d(TAG, "🚨 Critical memory pressure")
            }
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                Log.d(TAG, "👁️ UI hidden - app in background")
            }
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                Log.d(TAG, "🌙 App moved to background")
            }
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                Log.d(TAG, "📉 Moderate memory trim")
            }
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.d(TAG, "💾 Complete memory trim - aggressive cleanup")
            }
        }

        // Forward to our helpers for cleanup - this is safe
        try {
            ScamDetectionModelHelper.onTrimMemory(level)
            OCRHelper.onTrimMemory(level)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error in memory trim forwarding", e)
        }
    }

    override fun onLowMemory() {
        // ✅ KEEP super call for onLowMemory - this is safe and required
        super.onLowMemory()
        Log.w(TAG, "🚨 System low memory warning!")

        // Aggressive cleanup
        try {
            ScamDetectionModelHelper.cleanUp()
            OCRHelper.cleanup()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error in low memory cleanup", e)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        Log.d(TAG, "🔚 Application terminating...")

        // Final cleanup
        try {
            ScamDetectionModelHelper.cleanUp()
            OCRHelper.cleanup()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error in termination cleanup", e)
        }

        // CRITICAL: DO NOT unregister if we never registered manually
        // DO NOT CALL: unregisterComponentCallbacks(this)
    }
}