package com.saab.tv.data.model.trakt

import com.google.gson.annotations.SerializedName

/**
 * Response from POST /oauth/device/code
 * Contains the user code and URL to display, plus polling interval.
 */
data class TraktDeviceCodeResponse(
    @SerializedName("device_code") val deviceCode: String,
    @SerializedName("user_code") val userCode: String,
    @SerializedName("verification_url") val verificationUrl: String,
    @SerializedName("expires_in") val expiresIn: Int,
    @SerializedName("interval") val interval: Int
)

/**
 * Response from POST /oauth/device/token (successful authorization)
 */
data class TraktTokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("expires_in") val expiresIn: Long,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("scope") val scope: String,
    @SerializedName("created_at") val createdAt: Long
)
