package com.saab.tv.data.account

import java.util.Locale

object AccountCredentials {
    const val ALIAS_DOMAIN = "accounts.saabtv.invalid"

    fun normalizeUsername(value: String): String = value.trim().lowercase(Locale.ROOT)

    fun usernameError(value: String): String? {
        val username = normalizeUsername(value)
        return when {
            username.length !in 3..32 -> "Use 3–32 characters for your username."
            !username.matches(Regex("[a-z0-9_]+")) -> "Use letters, numbers, and underscores only."
            else -> null
        }
    }

    fun alias(value: String): String {
        require(usernameError(value) == null)
        return "${normalizeUsername(value)}@$ALIAS_DOMAIN"
    }

    fun passwordError(value: String): String? = when {
        value.length !in 8..128 -> "Use at least 8 characters (Neon minimum; maximum 128)."
        !value.any { it in 'A'..'Z' } -> "Add an uppercase letter."
        !value.any { it in 'a'..'z' } -> "Add a lowercase letter."
        !value.any { it in '0'..'9' } -> "Add a number."
        !value.any { !it.isLetterOrDigit() && !it.isWhitespace() } -> "Add a special character."
        else -> null
    }
}
