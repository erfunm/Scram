package com.example.screencaptureapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

class PermissionRequestActivity : Activity() {

    companion object {
        private const val TAG = "PermissionRequest"
        private const val REQUEST_MEDIA_PROJECTION = 1000
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var requestType: String = "setup_persistent"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "🔐 PermissionRequestActivity started")

        requestType = intent.getStringExtra("requestType") ?: "setup_persistent"
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        requestScreenCapturePermission()
    }

    private fun requestScreenCapturePermission() {
        try {
            Log.d(TAG, "🔐 Requesting screen capture permission for: $requestType")
            val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
            startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION)
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting permission", e)
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        Log.d(TAG, "📥 Permission result: requestCode=$requestCode, resultCode=$resultCode, requestType=$requestType")

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.d(TAG, "✅ Permission granted - setting up screenshot")

                val serviceIntent = Intent(this, SafeScreenCaptureService::class.java).apply {
                    action = "SETUP_PERSISTENT_PROJECTION"
                    putExtra("mediaProjectionData", data)
                }
                startService(serviceIntent)

            } else {
                Log.w(TAG, "❌ Permission denied")
            }
        }

        finish()
    }
}