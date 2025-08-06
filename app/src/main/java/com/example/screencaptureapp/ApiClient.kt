package com.example.screencaptureapp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

// Data classes for API requests/responses
data class OCRRequest(
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val deviceId: String = "android_device"
)

data class OCRResponse(
    val success: Boolean,
    val message: String,
    val processedText: String? = null
)

data class NotificationResponse(
    val message: String,
    val timestamp: Long,
    val priority: String = "normal"
)

// Retrofit API interface
interface ApiService {
    @POST("api/ocr/submit")
    suspend fun submitOCRText(@Body request: OCRRequest): Response<OCRResponse>

    @GET("api/notifications/latest")
    suspend fun getLatestNotification(): Response<NotificationResponse>
}

object ApiClient {
    private const val TAG = "ApiClient"

    // TODO: Replace with your actual API base URL
    private const val BASE_URL = "https://3af7268a6f2e.ngrok-free.app"

    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val apiService = retrofit.create(ApiService::class.java)

    suspend fun sendOCRResult(text: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = OCRRequest(text = text)
                val response = apiService.submitOCRText(request)

                Log.d(TAG, "HTTP Response Code: ${response.code()}")
                Log.d(TAG, "HTTP Response Successful: ${response.isSuccessful}")

                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        Log.d(TAG, "OCR API Response: $body")
                        if (body.success) {
                            Log.d(TAG, "OCR result sent successfully to API")
                            true
                        } else {
                            Log.w(TAG, "API received request but returned success=false: ${body.message}")
                            false
                        }
                    } else {
                        Log.w(TAG, "API response body is null")
                        false
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "HTTP request failed with code ${response.code()}: $errorBody")
                    false
                }

            } catch (e: Exception) {
                Log.e(TAG, "Exception occurred while sending OCR result to API", e)
                false
            }
        }
    }

    suspend fun getLatestNotification(): NotificationResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.getLatestNotification()

                Log.d(TAG, "Notification HTTP Response Code: ${response.code()}")
                Log.d(TAG, "Notification HTTP Response Successful: ${response.isSuccessful}")

                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        Log.d(TAG, "Notification API Response: $body")
                        body
                    } else {
                        Log.w(TAG, "Notification API response body is null")
                        null
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Notification HTTP request failed with code ${response.code()}: $errorBody")
                    null
                }

            } catch (e: Exception) {
                Log.e(TAG, "Exception occurred while fetching notification from API", e)
                null
            }
        }
    }
}