package com.saab.tv.remote_input

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Standard AES-256-GCM; identifiers/direction are bound as authenticated data. */
internal object PairingCrypto {
    private val random = SecureRandom()
    fun secret(): ByteArray = ByteArray(32).also(random::nextBytes)
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
    fun hash(secret: String): String = MessageDigest.getInstance("SHA-256")
        .digest(secret.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun encrypt(key: ByteArray, plaintext: String, aad: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return JsonObject().apply {
            addProperty("iv", encode(iv))
            addProperty("data", encode(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))))
        }.toString()
    }
    fun decrypt(key: ByteArray, envelope: String, aad: String): String {
        require(envelope.length <= 1048576)
        val json = JsonParser.parseString(envelope).asJsonObject
        val iv = decode(json.get("iv").asString)
        require(iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(decode(json.get("data").asString)).toString(Charsets.UTF_8)
    }
}
