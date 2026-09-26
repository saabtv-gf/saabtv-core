package com.saab.tv.data.auth

import android.content.Context
import android.content.SharedPreferences
import com.saab.tv.data.security.SecurePreferences
import com.saab.tv.data.remote.StremioAddonEntry
import com.saab.tv.data.remote.StremioAuthError
import com.saab.tv.data.remote.StremioAuthService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connection state for the Stremio integration.
 */
sealed class StremioConnectionState {
    object Disconnected : StremioConnectionState()
    data class Connected(val email: String) : StremioConnectionState()
}

/**
 * Manages Stremio authentication state using EncryptedSharedPreferences.
 * Provides secure storage for auth tokens and handles login/logout operations.
 */
@Singleton
class StremioAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stremioAuthService: StremioAuthService
) {
    companion object {
        private const val PREFS_FILE = "stremio_secure_prefs"
        private const val KEY_AUTH_KEY = "stremio_auth_key"
        private const val KEY_EMAIL = "stremio_email"
        private const val KEY_ACTIVE_PROFILE_ID = "stremio_active_profile_id"
        private const val KEY_ALIAS = "saabtv_stremio_master_key"
    }

    private val securePrefs by lazy { SecurePreferences.create(context, PREFS_FILE, KEY_ALIAS) }
    private val encryptedPrefs: SharedPreferences get() = securePrefs.preferences

    private val _connectionState = MutableStateFlow<StremioConnectionState>(StremioConnectionState.Disconnected)
    val connectionState: StateFlow<StremioConnectionState> = _connectionState.asStateFlow()

    init {
        refreshConnectionState()
    }

    /**
     * Refreshes the connection state from stored credentials.
     */
    fun refreshConnectionState() {
        val authKey = getStoredAuthKey()
        val email = getStoredEmail()

        _connectionState.value = if (authKey != null && email != null) {
            StremioConnectionState.Connected(email)
        } else {
            StremioConnectionState.Disconnected
        }
    }

    /**
     * Gets the stored auth key (for API calls).
     */
    fun getStoredAuthKey(): String? {
        return encryptedPrefs.getString(KEY_AUTH_KEY, null)
    }

    /**
     * Gets the stored email (for display purposes).
     */
    fun getStoredEmail(): String? {
        return encryptedPrefs.getString(KEY_EMAIL, null)
    }

    private fun profileScopedAuthKey(profileId: Int): String = "${KEY_AUTH_KEY}_profile_$profileId"
    private fun profileScopedEmail(profileId: Int): String = "${KEY_EMAIL}_profile_$profileId"

    fun saveCredentialsForProfile(profileId: Int) {
        val authKey = getStoredAuthKey()
        val email = getStoredEmail()
        val authProfileKey = profileScopedAuthKey(profileId)
        val emailProfileKey = profileScopedEmail(profileId)

        encryptedPrefs.edit().apply {
            putInt(KEY_ACTIVE_PROFILE_ID, profileId)
            if (authKey != null && email != null) {
                putString(authProfileKey, authKey)
                putString(emailProfileKey, email)
            } else {
                remove(authProfileKey)
                remove(emailProfileKey)
            }
        }.apply()
    }

    fun loadCredentialsForProfile(profileId: Int) {
        val authKey = encryptedPrefs.getString(profileScopedAuthKey(profileId), null)
        val email = encryptedPrefs.getString(profileScopedEmail(profileId), null)

        encryptedPrefs.edit().apply {
            putInt(KEY_ACTIVE_PROFILE_ID, profileId)
            if (authKey != null && email != null) {
                putString(KEY_AUTH_KEY, authKey)
                putString(KEY_EMAIL, email)
            } else {
                remove(KEY_AUTH_KEY)
                remove(KEY_EMAIL)
            }
        }.apply()

        refreshConnectionState()
    }

    fun copyCredentialsBetweenProfiles(sourceProfileId: Int, targetProfileId: Int) {
        val authKey = encryptedPrefs.getString(profileScopedAuthKey(sourceProfileId), null)
        val email = encryptedPrefs.getString(profileScopedEmail(sourceProfileId), null)
        val authProfileKey = profileScopedAuthKey(targetProfileId)
        val emailProfileKey = profileScopedEmail(targetProfileId)

        encryptedPrefs.edit().apply {
            if (authKey != null && email != null) {
                putString(authProfileKey, authKey)
                putString(emailProfileKey, email)
            } else {
                remove(authProfileKey)
                remove(emailProfileKey)
            }
        }.apply()
    }

    fun clearCredentialsForProfile(profileId: Int) {
        encryptedPrefs.edit().apply {
            remove(profileScopedAuthKey(profileId))
            remove(profileScopedEmail(profileId))
            if (encryptedPrefs.getInt(KEY_ACTIVE_PROFILE_ID, -1) == profileId) {
                remove(KEY_AUTH_KEY)
                remove(KEY_EMAIL)
                remove(KEY_ACTIVE_PROFILE_ID)
            }
        }.apply()
    }

    /**
     * Logs in to Stremio and stores the credentials securely.
     * Returns the auth key on success.
     */
    suspend fun login(email: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        if (!securePrefs.isPersistent) {
            return@withContext Result.failure(
                StremioAuthError.UnknownError("Secure credential storage is unavailable on this device")
            )
        }
        try {
            val authKey = stremioAuthService.login(email, password)
            
            encryptedPrefs.edit()
                .putString(KEY_AUTH_KEY, authKey)
                .putString(KEY_EMAIL, email)
                .apply {
                    val profileId = encryptedPrefs.getInt(KEY_ACTIVE_PROFILE_ID, -1)
                    if (profileId >= 0) {
                        putString(profileScopedAuthKey(profileId), authKey)
                        putString(profileScopedEmail(profileId), email)
                    }
                }
                .apply()

            _connectionState.value = StremioConnectionState.Connected(email)

            Result.success(authKey)
        } catch (e: StremioAuthError.InvalidCredentials) {
            Result.failure(e)
        } catch (e: StremioAuthError.NetworkError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(StremioAuthError.UnknownError(e.message ?: "Unknown error"))
        }
    }

    /**
     * Fetches the user's addon collection using the stored auth key.
     */
    suspend fun fetchAddons(): Result<List<StremioAddonEntry>> = withContext(Dispatchers.IO) {
        val authKey = getStoredAuthKey()
            ?: return@withContext Result.failure(StremioAuthError.InvalidCredentials("Not logged in"))

        try {
            val addons = stremioAuthService.getAddonCollection(authKey)
            Result.success(addons)
        } catch (e: StremioAuthError) {
            // If auth fails, the token might be expired - clear it
            if (e is StremioAuthError.InvalidCredentials) {
                disconnect()
            }
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(StremioAuthError.NetworkError(e.message ?: "Network error"))
        }
    }

    /**
     * Disconnects from Stremio by clearing stored credentials.
     */
    fun disconnect() {
        val profileId = encryptedPrefs.getInt(KEY_ACTIVE_PROFILE_ID, -1)
        encryptedPrefs.edit()
            .remove(KEY_AUTH_KEY)
            .remove(KEY_EMAIL)
            .remove(KEY_ACTIVE_PROFILE_ID)
            .apply {
                if (profileId >= 0) {
                    remove(profileScopedAuthKey(profileId))
                    remove(profileScopedEmail(profileId))
                }
            }
            .apply()

        _connectionState.value = StremioConnectionState.Disconnected
    }
}
