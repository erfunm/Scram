package com.example.screencaptureapp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object ModelDownloader {
    private const val TAG = "ModelDownloader"

    // MediaPipe .task format models
    private const val MODEL_URL = "https://erfan.uk/dl/gemma-3n-E2B-it-int4.task"

    // FIXED: Use the actual model filename to match your download
    private const val MODEL_FILE_NAME = "gemma-3n-E2B-it-int4.task"

    data class DownloadProgress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val isComplete: Boolean,
        val error: String? = null
    )

    suspend fun downloadModelIfNeeded(
        context: Context,
        onProgress: (DownloadProgress) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {

        val modelFile = getModelFile(context)

        // Check if model already exists and is valid
        if (modelFile.exists() && modelFile.length() > 0) {
            Log.d(TAG, "Model already exists: ${modelFile.length()} bytes")
            onProgress(DownloadProgress(modelFile.length(), modelFile.length(), true))
            return@withContext modelFile
        }

        // Create models directory
        modelFile.parentFile?.mkdirs()

        try {
            Log.d(TAG, "Starting model download from: $MODEL_URL")
            Log.d(TAG, "Saving as: ${modelFile.absolutePath}")

            val url = URL(MODEL_URL)
            val connection = url.openConnection() as HttpURLConnection

            // Check if we can resume a partial download
            val existingBytes = if (modelFile.exists()) modelFile.length() else 0L
            if (existingBytes > 0) {
                connection.setRequestProperty("Range", "bytes=$existingBytes-")
                Log.d(TAG, "Resuming download from byte: $existingBytes")
            }

            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK &&
                connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("HTTP error: ${connection.responseCode}")
            }

            val totalBytes = if (connection.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                // Parse Content-Range header for total size
                val contentRange = connection.getHeaderField("Content-Range")
                contentRange?.substringAfter("/")?.toLongOrNull() ?: connection.contentLengthLong
            } else {
                connection.contentLengthLong
            }

            Log.d(TAG, "Total file size: $totalBytes bytes")

            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(modelFile, existingBytes > 0) // append if resuming

            val buffer = ByteArray(8192)
            var downloadedBytes = existingBytes
            var bytesRead: Int
            var lastProgressUpdate = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead

                // Update progress every 500ms
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastProgressUpdate > 500) {
                    onProgress(DownloadProgress(downloadedBytes, totalBytes, false))
                    lastProgressUpdate = currentTime
                    Log.d(TAG, "Downloaded: ${downloadedBytes / 1024 / 1024} MB / ${totalBytes / 1024 / 1024} MB")
                }
            }

            outputStream.close()
            inputStream.close()
            connection.disconnect()

            // Verify download
            if (modelFile.exists() && modelFile.length() > 0) {
                Log.d(TAG, "✅ Model download completed: ${modelFile.name} (${modelFile.length()} bytes)")
                onProgress(DownloadProgress(downloadedBytes, totalBytes, true))
                return@withContext modelFile
            } else {
                throw IOException("Downloaded file is invalid")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to download model", e)
            onProgress(DownloadProgress(0, 0, false, e.message))
            modelFile.delete() // Clean up partial file
            return@withContext null
        }
    }

    fun getModelFile(context: Context): File {
        val modelsDir = File(context.getExternalFilesDir(null), "models")
        return File(modelsDir, MODEL_FILE_NAME)
    }

    fun isModelDownloaded(context: Context): Boolean {
        val modelFile = getModelFile(context)
        // Model must exist AND be larger than 1GB (since our model is ~3.1GB)
        val isValid = modelFile.exists() && modelFile.length() > 1000000000
        Log.d(TAG, "Model check: ${modelFile.name} exists=${modelFile.exists()}, size=${modelFile.length()}, valid=$isValid")
        return isValid
    }

    fun deleteModel(context: Context): Boolean {
        val modelFile = getModelFile(context)
        return if (modelFile.exists()) {
            val deleted = modelFile.delete()
            Log.d(TAG, "Model deleted: ${modelFile.name} success=$deleted")
            deleted
        } else {
            Log.d(TAG, "Model file doesn't exist: ${modelFile.name}")
            true
        }
    }
}