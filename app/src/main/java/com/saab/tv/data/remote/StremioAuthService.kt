package com.saab.tv.data.remote

import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class StremioLoginRequest(
    val type: String = "Login",
    val email: String,
    val password: String,
    val facebook: Boolean = false
)

data class StremioLoginResponse(
    val result: StremioAuthResult?
)

data class StremioAuthResult(
    @SerializedName("authKey") val authKey: String
)

data class StremioAddonCollectionRequest(
    val type: String = "AddonCollectionGet",
    val authKey: String,
    val update: Boolean = true
)

data class StremioAddonCollectionResponse(
    val result: StremioAddonCollectionResult?
)

data class StremioAddonCollectionResult(
    val addons: List<StremioAddonEntry>?
)

data class StremioAddonEntry(
    val transportUrl: String,
    val manifest: StremioAddonManifest?
)

data class StremioAddonManifest(
    val id: String?,
    val name: String?,
    val version: String?,
    val description: String?,
    val logo: String?
)

sealed class StremioAuthError : Exception() {
    data class InvalidCredentials(override val message: String = "Invalid email or password") : StremioAuthError()
    data class NetworkError(override val message: String) : StremioAuthError()
    data class UnknownError(override val message: String) : StremioAuthError()
}

@Singleton
class StremioAuthService @Inject constructor() {

    private val client = OkHttpClient.Builder().build()
    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()
    
    companion object {
        private const val STREMIO_API_BASE = "https://api.strem.io/api"
        private const val LOGIN_ENDPOINT = "$STREMIO_API_BASE/login"
        private const val ADDON_COLLECTION_ENDPOINT = "$STREMIO_API_BASE/addonCollectionGet"
    }
    
    /**
     * Authenticates with Stremio and returns the authKey.
     */
    suspend fun login(email: String, password: String): String = withContext(Dispatchers.IO) {
        val requestBody = gson.toJson(StremioLoginRequest(email = email, password = password))
        
        val request = Request.Builder()
            .url(LOGIN_ENDPOINT)
            .post(requestBody.toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .build()
        
        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (!response.isSuccessful || responseBody == null) {
                throw StremioAuthError.NetworkError("Server returned ${response.code}")
            }
            
            val loginResponse = gson.fromJson(responseBody, StremioLoginResponse::class.java)
            
            loginResponse.result?.authKey
                ?: throw StremioAuthError.InvalidCredentials()
                
        } catch (e: StremioAuthError) {
            throw e
        } catch (e: Exception) {
            throw StremioAuthError.NetworkError(e.message ?: "Network error")
        }
    }
    
    /**
     * Fetches the user's addon collection using their authKey.
     */
    suspend fun getAddonCollection(authKey: String): List<StremioAddonEntry> = withContext(Dispatchers.IO) {
        val requestBody = gson.toJson(StremioAddonCollectionRequest(authKey = authKey))
        
        val request = Request.Builder()
            .url(ADDON_COLLECTION_ENDPOINT)
            .post(requestBody.toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .build()
        
        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (!response.isSuccessful || responseBody == null) {
                throw StremioAuthError.NetworkError("Failed to fetch addons: ${response.code}")
            }
            
            val collectionResponse = gson.fromJson(responseBody, StremioAddonCollectionResponse::class.java)
            
            collectionResponse.result?.addons ?: emptyList()
            
        } catch (e: StremioAuthError) {
            throw e
        } catch (e: Exception) {
            throw StremioAuthError.NetworkError(e.message ?: "Network error")
        }
    }
}
