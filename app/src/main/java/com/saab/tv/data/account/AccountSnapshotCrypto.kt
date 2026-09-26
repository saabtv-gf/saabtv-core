package com.saab.tv.data.account

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Encrypt settings/integration credentials before they leave the device. */
object AccountSnapshotCrypto {
    fun deriveKey(password: CharArray, userId: String): ByteArray {
        val spec = PBEKeySpec(password, ("saabtv-account-v1:$userId").toByteArray(), 210_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword(); password.fill('\u0000') }
    }

    fun encrypt(plain: ByteArray, key: ByteArray, userId: String): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(("saabtv-snapshot-v1:$userId").toByteArray())
        return iv + cipher.doFinal(plain)
    }

    fun decrypt(encrypted: ByteArray, key: ByteArray, userId: String): ByteArray {
        require(encrypted.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        cipher.updateAAD(("saabtv-snapshot-v1:$userId").toByteArray())
        return cipher.doFinal(encrypted, 12, encrypted.size - 12)
    }
}
