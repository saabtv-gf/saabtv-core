package com.saab.tv.data.trakt

import android.content.Context
import android.util.Log
import com.saab.tv.BuildConfig
import com.saab.tv.data.security.SecurePreferences
import com.saab.tv.data.model.trakt.TraktDeviceCodeResponse
import com.saab.tv.data.model.trakt.TraktTokenResponse
import com.saab.tv.data.profile.ProfileConfigurationManager
import com.saab.tv.data.remote.TraktApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

sealed class DeviceAuthState {
    data object Idle : DeviceAuthState()
    data class WaitingForUser(val userCode: String, val verificationUrl: String) : DeviceAuthState()
    data object Success : DeviceAuthState()
    data class Error(val message: String) : DeviceAuthState()
    data object Expired : DeviceAuthState()
}

@Singleton
class TraktAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traktApi: TraktApiService,
    private val profileConfigurationManager: ProfileConfigurationManager
) {
    companion object {
        private const val TAG = "TraktAuthManager"
        private const val PREFS_NAME = "trakt_auth"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_CLIENT_SECRET = "client_secret"
        private const val KEY_ALIAS = "saabtv_trakt_master_key"
    }

    private val securePrefs by lazy { SecurePreferences.create(context, PREFS_NAME, KEY_ALIAS) }
    private val prefs get() = securePrefs.preferences
    private val refreshMutex = Mutex()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _authState = MutableStateFlow<DeviceAuthState>(DeviceAuthState.Idle)
    val authState: StateFlow<DeviceAuthState> = _authState

    private fun activeProfileId(): Int = profileConfigurationManager.getLastActiveProfileId() ?: 1

    private fun profileKey(key: String, profileId: Int = activeProfileId()) = "${key}_$profileId"

    /** Runtime credentials take precedence so self-built APKs can configure Trakt securely. */
    fun getClientId(): String? = prefs.getString(KEY_CLIENT_ID, null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: BuildConfig.TRAKT_CLIENT_ID.trim().takeIf { it.isNotEmpty() }

    private fun getClientSecret(): String? = prefs.getString(KEY_CLIENT_SECRET, null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: BuildConfig.TRAKT_CLIENT_SECRET.trim().takeIf { it.isNotEmpty() }

    fun hasClientCredentials(): Boolean = getClientId() != null && getClientSecret() != null

    fun saveClientCredentials(clientId: String, clientSecret: String): Boolean {
        val cleanId = clientId.trim()
        val cleanSecret = clientSecret.trim()
        if (cleanId.isEmpty() || cleanSecret.isEmpty() || !securePrefs.isPersistent) return false

        clearAllTokens()
        prefs.edit()
            .putString(KEY_CLIENT_ID, cleanId)
            .putString(KEY_CLIENT_SECRET, cleanSecret)
            .apply()
        _authState.value = DeviceAuthState.Idle
        return true
    }

    fun clearClientCredentials() {
        clearAllTokens()
        prefs.edit()
            .remove(KEY_CLIENT_ID)
            .remove(KEY_CLIENT_SECRET)
            .apply()
        _authState.value = DeviceAuthState.Idle
    }

    init {
        migrateGlobalTokensToProfile()
        _isConnected.value = getAccessToken() != null
    }

    /** One-time migration: move tokens saved without a profile suffix to the active profile. */
    private fun migrateGlobalTokensToProfile() {
        val globalToken = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return
        val globalRefresh = prefs.getString(KEY_REFRESH_TOKEN, null)
        val globalExpiry = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val pid = activeProfileId()

        prefs.edit()
            .putString(profileKey(KEY_ACCESS_TOKEN, pid), globalToken)
            .apply {
                if (globalRefresh != null) putString(profileKey(KEY_REFRESH_TOKEN, pid), globalRefresh)
                putLong(profileKey(KEY_EXPIRES_AT, pid), globalExpiry)
            }
            // Remove old global keys
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .apply()

        Log.i(TAG, "Migrated Trakt tokens to profile $pid")
    }

    /** Refresh connection state for the current profile (call after profile switch). */
    fun refreshConnectionState() {
        _isConnected.value = getAccessToken() != null
    }

    // ── Token Storage (per-profile) ──

    private fun saveTokens(response: TraktTokenResponse) {
        val pid = activeProfileId()
        prefs.edit()
            .putString(profileKey(KEY_ACCESS_TOKEN, pid), response.accessToken)
            .putString(profileKey(KEY_REFRESH_TOKEN, pid), response.refreshToken)
            .putLong(profileKey(KEY_EXPIRES_AT, pid), response.createdAt + response.expiresIn)
            .apply()
        _isConnected.value = true
    }

    fun getAccessToken(): String? {
        val token = prefs.getString(profileKey(KEY_ACCESS_TOKEN), null) ?: return null
        // Proactive expiry check: flag as needing refresh if within 60 seconds of expiry
        val expiresAt = prefs.getLong(profileKey(KEY_EXPIRES_AT), 0L)
        if (expiresAt > 0 && System.currentTimeMillis() / 1000 >= expiresAt - 60) {
            Log.d(TAG, "Token expired or expiring soon, needs refresh")
            needsRefresh = true
        }
        return token
    }

    /** True if the token is expired or about to expire. Checked by the interceptor. */
    @Volatile var needsRefresh = false
        private set

    private fun getRefreshToken(): String? = prefs.getString(profileKey(KEY_REFRESH_TOKEN), null)

    private fun clearTokens() {
        val pid = activeProfileId()
        prefs.edit()
            .remove(profileKey(KEY_ACCESS_TOKEN, pid))
            .remove(profileKey(KEY_REFRESH_TOKEN, pid))
            .remove(profileKey(KEY_EXPIRES_AT, pid))
            .apply()
        _isConnected.value = false
    }

    private fun clearAllTokens() {
        val editor = prefs.edit()
        prefs.all.keys.forEach { key ->
            if (
                key == KEY_ACCESS_TOKEN || key == KEY_REFRESH_TOKEN || key == KEY_EXPIRES_AT ||
                key.startsWith("${KEY_ACCESS_TOKEN}_") || key.startsWith("${KEY_REFRESH_TOKEN}_") ||
                key.startsWith("${KEY_EXPIRES_AT}_")
            ) {
                editor.remove(key)
            }
        }
        editor.apply()
        needsRefresh = false
        _isConnected.value = false
    }

    /** Clear tokens for a specific profile (e.g., when deleting the profile). */
    fun clearTokensForProfile(profileId: Int) {
        prefs.edit()
            .remove(profileKey(KEY_ACCESS_TOKEN, profileId))
            .remove(profileKey(KEY_REFRESH_TOKEN, profileId))
            .remove(profileKey(KEY_EXPIRES_AT, profileId))
            .apply()
    }

    // ── Device Code Auth Flow ──

    suspend fun startDeviceAuth() {
        _authState.value = DeviceAuthState.Idle
        if (!securePrefs.isPersistent) {
            _authState.value = DeviceAuthState.Error("Secure credential storage is unavailable on this device.")
            return
        }
        val clientId = getClientId()
        val clientSecret = getClientSecret()
        if (clientId == null || clientSecret == null) {
            _authState.value = DeviceAuthState.Error(
                "Trakt API credentials are required for this custom build."
            )
            return
        }
        try {
            val response = traktApi.getDeviceCode(
                mapOf("client_id" to clientId)
            )
            val body = response.body()
            if (!response.isSuccessful || body == null) {
                _authState.value = DeviceAuthState.Error(
                    when (response.code()) {
                        401, 403 -> "Trakt rejected the Client ID. Check your API app credentials."
                        else -> "Trakt authorization failed (HTTP ${response.code()})."
                    }
                )
                return
            }

            _authState.value = DeviceAuthState.WaitingForUser(
                userCode = body.userCode,
                verificationUrl = body.verificationUrl
            )

            pollForToken(body, clientId, clientSecret)
        } catch (e: Exception) {
            Log.e(TAG, "Device auth failed", e)
            _authState.value = DeviceAuthState.Error(e.message ?: "Unknown error")
        }
    }

    private suspend fun pollForToken(
        deviceCode: TraktDeviceCodeResponse,
        clientId: String,
        clientSecret: String
    ) {
        val deadline = System.currentTimeMillis() + (deviceCode.expiresIn * 1000L)
        var interval = deviceCode.interval * 1000L

        while (System.currentTimeMillis() < deadline) {
            delay(interval)

            try {
                val response = traktApi.pollToken(
                    mapOf(
                        "code" to deviceCode.deviceCode,
                        "client_id" to clientId,
                        "client_secret" to clientSecret
                    )
                )

                when (response.code()) {
                    200 -> {
                        val tokenBody = response.body()
                        if (tokenBody != null) {
                            saveTokens(tokenBody)
                            _authState.value = DeviceAuthState.Success
                            return
                        }
                    }
                    400 -> { /* Pending — keep polling */ }
                    404 -> { _authState.value = DeviceAuthState.Error("Invalid device code"); return }
                    409 -> { _authState.value = DeviceAuthState.Error("Code already used"); return }
                    410 -> { _authState.value = DeviceAuthState.Expired; return }
                    418 -> { _authState.value = DeviceAuthState.Error("Authorization denied"); return }
                    429 -> { interval += 1000L }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Poll attempt failed: ${e.message}")
            }
        }

        _authState.value = DeviceAuthState.Expired
    }

    // ── Token Refresh ──

    /**
     * Refresh the access token using the stored refresh token.
     * Returns the new access token, or null if refresh failed.
     * Called by TraktAuthInterceptor on 401 responses.
     */
    suspend fun refreshAccessToken(): String? = refreshMutex.withLock {
        val refreshToken = getRefreshToken() ?: return null
        val clientId = getClientId() ?: return null
        val clientSecret = getClientSecret() ?: return null
        return try {
            val response = traktApi.refreshToken(
                mapOf(
                    "refresh_token" to refreshToken,
                    "client_id" to clientId,
                    "client_secret" to clientSecret,
                    "redirect_uri" to "urn:ietf:wg:oauth:2.0:oob",
                    "grant_type" to "refresh_token"
                )
            )
            val body = response.body()
            if (response.isSuccessful && body != null) {
                saveTokens(body)
                needsRefresh = false
                Log.i(TAG, "Token refreshed successfully")
                body.accessToken
            } else {
                Log.w(TAG, "Token refresh failed: ${response.code()}")
                if (response.code() == 401 || response.code() == 403) {
                    // Refresh token is also invalid — user needs to re-authenticate
                    clearTokens()
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh error", e)
            null
        }
    }

    // ── Disconnect ──

    suspend fun disconnect() {
        val token = getAccessToken()
        val clientId = getClientId()
        val clientSecret = getClientSecret()
        if (token != null && clientId != null && clientSecret != null) {
            try {
                traktApi.revokeToken(
                    mapOf(
                        "token" to token,
                        "client_id" to clientId,
                        "client_secret" to clientSecret
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Token revocation failed: ${e.message}")
            }
        }
        clearTokens()
        _authState.value = DeviceAuthState.Idle
    }

    fun resetAuthState() {
        _authState.value = DeviceAuthState.Idle
    }
}
