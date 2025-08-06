package com.example.screencaptureapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class ModelDownloadActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, ModelDownloadActivity::class.java)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create simple layout programmatically
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
        }

        statusText = TextView(this).apply {
            text = "Preparing to download AI model..."
            textSize = 18f
            setPadding(0, 0, 0, 32)
        }
        layout.addView(statusText)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        layout.addView(progressBar)

        progressText = TextView(this).apply {
            text = "0 MB / 0 MB (0%)"
            setPadding(0, 16, 0, 0)
        }
        layout.addView(progressText)

        setContentView(layout)

        startModelDownload()
    }

    private fun startModelDownload() {
        lifecycleScope.launch {
            try {
                ModelDownloader.downloadModelIfNeeded(this@ModelDownloadActivity) { progress ->
                    runOnUiThread {
                        if (progress.isComplete) {
                            statusText.text = "Model downloaded successfully!"
                            progressBar.progress = 100
                            progressText.text = "Complete"

                            // Close activity after a delay
                            statusText.postDelayed({
                                finish()
                            }, 2000)
                        } else if (progress.error != null) {
                            statusText.text = "Download failed: ${progress.error}"
                            progressText.text = "Error"
                        } else {
                            statusText.text = "Downloading AI model..."
                            val progressPercent = if (progress.totalBytes > 0) {
                                (progress.downloadedBytes * 100 / progress.totalBytes).toInt()
                            } else 0

                            progressBar.progress = progressPercent
                            progressText.text = "${progress.downloadedBytes / 1024 / 1024} MB / ${progress.totalBytes / 1024 / 1024} MB ($progressPercent%)"
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Download failed: ${e.message}"
                }
            }
        }
    }
}