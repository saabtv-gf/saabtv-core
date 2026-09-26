package com.saab.tv.data.profile

import com.saab.tv.data.model.ProfileEntity
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object ProfilePin {
    private val fourDigits = Regex("\\d{4}")
    private const val VERSION = "v2"
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16

    fun isValid(pin: String): Boolean = fourDigits.matches(pin)

    fun hash(profileId: Int, pin: String): String {
        require(isValid(pin)) { "Profile PIN must contain exactly four digits" }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val derived = derive(pin, salt, ITERATIONS)
        return listOf(
            VERSION,
            ITERATIONS.toString(),
            Base64.getEncoder().withoutPadding().encodeToString(salt),
            Base64.getEncoder().withoutPadding().encodeToString(derived)
        ).joinToString("\$")
    }

    fun matches(profile: ProfileEntity, pin: String): Boolean {
        val expected = profile.pinHash ?: return true
        if (!isValid(pin)) return false
        if (expected.startsWith("$VERSION\$")) {
            val parts = expected.split("\$")
            if (parts.size != 4) return false
            return runCatching {
                val iterations = parts[1].toInt().coerceIn(10_000, 1_000_000)
                val salt = Base64.getDecoder().decode(parts[2])
                val stored = Base64.getDecoder().decode(parts[3])
                MessageDigest.isEqual(stored, derive(pin, salt, iterations))
            }.getOrDefault(false)
        }

        // One-time compatibility for existing SHA-256 PINs. Successful login upgrades it.
        val legacy = MessageDigest.getInstance("SHA-256")
            .digest("saabtv-profile:${profile.id}:$pin".toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return MessageDigest.isEqual(expected.toByteArray(), legacy.toByteArray())
    }

    fun needsUpgrade(profile: ProfileEntity): Boolean =
        profile.pinHash != null && !profile.pinHash.startsWith("$VERSION\$")

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
