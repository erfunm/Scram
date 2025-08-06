package com.example.screencaptureapp

import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log

object MediaProjectionHelper {
    private const val TAG = "MediaProjectionHelper"

    private var mediaProjectionData: Intent? = null
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionManager: MediaProjectionManager? = null

    fun initialize(manager: MediaProjectionManager) {
        this.mediaProjectionManager = manager
        Log.d(TAG, "MediaProjectionManager initialized")
    }

    fun storeMediaProjectionData(data: Intent) {
        this.mediaProjectionData = data
        Log.d(TAG, "MediaProjection data stored: $data")
    }

    fun getStoredPermissionData(): Intent? {
        return mediaProjectionData
    }

    fun createFreshInstance(): MediaProjection? {
        return try {
            if (mediaProjectionData == null) {
                Log.e(TAG, "No MediaProjection data available")
                return null
            }

            if (mediaProjectionManager == null) {
                Log.e(TAG, "MediaProjectionManager not initialized")
                return null
            }

            Log.d(TAG, "Creating completely fresh MediaProjection instance")

            // Create completely new MediaProjection instance - don't store it
            val newInstance = mediaProjectionManager?.getMediaProjection(
                android.app.Activity.RESULT_OK,
                mediaProjectionData!!
            )

            if (newInstance != null) {
                // Register callback for this single-use instance
                try {
                    newInstance.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.d(TAG, "Single-use MediaProjection instance stopped")
                        }
                    }, null)
                    Log.d(TAG, "Callback registered for single-use instance")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to register callback", e)
                }

                Log.d(TAG, "Fresh single-use MediaProjection instance created: $newInstance")
            } else {
                Log.e(TAG, "Failed to create MediaProjection instance")
            }

            // Return the instance but don't store it anywhere
            newInstance

        } catch (e: Exception) {
            Log.e(TAG, "Error creating fresh MediaProjection instance", e)
            null
        }
    }

    fun getMediaProjection(): MediaProjection? {
        // For backward compatibility, return a fresh instance
        return createFreshInstance()
    }

    fun release() {
        mediaProjection?.stop()
        mediaProjection = null
        mediaProjectionData = null
        Log.d(TAG, "MediaProjection released")
    }
}