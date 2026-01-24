package com.openmdm.agent.network

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp interceptor that implements exponential backoff retry logic.
 *
 * Retry behavior:
 * - Retries on IOException (network errors)
 * - Retries on 5xx server errors
 * - Does NOT retry on 4xx client errors
 * - Uses exponential backoff: 1s, 2s, 4s...
 *
 * This provides network-level resilience before WorkManager retry kicks in.
 */
@Singleton
class RetryInterceptor @Inject constructor() : Interceptor {

    companion object {
        private const val TAG = "OpenMDM.RetryInterceptor"
        private const val MAX_RETRIES = 3
        private const val INITIAL_BACKOFF_MS = 1000L
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var lastException: IOException? = null
        var response: Response? = null

        for (attempt in 0 until MAX_RETRIES) {
            try {
                // Close previous response if retrying
                response?.close()

                response = chain.proceed(request)

                // Success or client error - don't retry
                if (response.isSuccessful || response.code in 400..499) {
                    return response
                }

                // Server error (5xx) - retry with backoff
                if (response.code in 500..599) {
                    Log.w(TAG, "Server error ${response.code} on attempt ${attempt + 1}/$MAX_RETRIES")

                    if (attempt < MAX_RETRIES - 1) {
                        response.close()
                        val backoffMs = calculateBackoff(attempt)
                        Log.d(TAG, "Retrying in ${backoffMs}ms...")
                        Thread.sleep(backoffMs)
                        continue
                    }
                }

                return response
            } catch (e: IOException) {
                lastException = e
                Log.w(TAG, "Network error on attempt ${attempt + 1}/$MAX_RETRIES: ${e.message}")

                if (attempt < MAX_RETRIES - 1) {
                    val backoffMs = calculateBackoff(attempt)
                    Log.d(TAG, "Retrying in ${backoffMs}ms...")
                    try {
                        Thread.sleep(backoffMs)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Retry interrupted", ie)
                    }
                }
            }
        }

        // All retries exhausted
        throw lastException ?: IOException("Request failed after $MAX_RETRIES attempts")
    }

    /**
     * Calculate exponential backoff delay.
     * attempt 0 -> 1000ms
     * attempt 1 -> 2000ms
     * attempt 2 -> 4000ms
     */
    private fun calculateBackoff(attempt: Int): Long {
        return INITIAL_BACKOFF_MS * (1L shl attempt)
    }
}
