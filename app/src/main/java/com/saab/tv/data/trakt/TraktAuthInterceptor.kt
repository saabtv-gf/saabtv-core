package com.saab.tv.data.trakt

import android.util.Log
import com.saab.tv.BuildConfig
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktAuthInterceptor @Inject constructor(
    private val traktAuthManager: TraktAuthManager
) : Interceptor {

    companion object {
        private const val TAG = "TraktAuthInterceptor"
    }

    private val refreshLock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        var token = traktAuthManager.getAccessToken()

        // Fix #7: proactively refresh if token is expired or about to expire
        if (token != null && traktAuthManager.needsRefresh) {
            Log.d(TAG, "Token expiring soon, proactive refresh")
            val newToken = synchronized(refreshLock) {
                runBlocking { traktAuthManager.refreshAccessToken() }
            }
            if (newToken != null) {
                token = newToken
            }
        }

        val response = chain.proceed(buildRequest(chain.request(), token))

        // If we get a 401, try refreshing the token and retry once
        if (response.code == 401 && token != null) {
            Log.d(TAG, "Got 401, attempting token refresh")
            val newToken = synchronized(refreshLock) {
                val latestToken = traktAuthManager.getAccessToken()
                if (!latestToken.isNullOrBlank() && latestToken != token) {
                    latestToken
                } else {
                    runBlocking { traktAuthManager.refreshAccessToken() }
                }
            }

            if (!newToken.isNullOrBlank() && newToken != token) {
                Log.d(TAG, "Token refreshed, retrying request")
                response.close()
                return chain.proceed(buildRequest(chain.request(), newToken))
            } else {
                Log.w(TAG, "Token refresh failed")
                return response
            }
        }

        return response
    }

    private fun buildRequest(original: okhttp3.Request, token: String?): okhttp3.Request {
        val builder = original.newBuilder()
            .header("Content-Type", "application/json")
            .header("trakt-api-version", "2")
            .header("trakt-api-key", traktAuthManager.getClientId().orEmpty())
            .header("User-Agent", "SaabTv/${BuildConfig.VERSION_NAME}")

        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }

        return builder.build()
    }
}
