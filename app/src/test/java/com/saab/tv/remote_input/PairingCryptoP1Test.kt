package com.saab.tv.remote_input

import com.google.gson.JsonParser
import org.junit.Test
import org.junit.Assert.*

class PairingCryptoP1Test {
    private val key=ByteArray(32) { it.toByte() }
    private val aad="session|message|uuid"
    private fun rejected(block: () -> Unit) { try { block(); fail("Unauthenticated input was accepted") } catch (e: Exception) { /* expected */ } }
    @Test fun unicodeCredentialsRoundTripWithoutPlaintextInEnvelope() {
        val text="{\"username\":\"പേര്\",\"password\":\"Aa1!secret\"}"
        val encrypted=PairingCrypto.encrypt(key,text,aad)
        assertFalse(encrypted.contains("secret")); assertEquals(text,PairingCrypto.decrypt(key,encrypted,aad))
    }
    @Test fun sessionDirectionAndMessageIdentityAreAuthenticated() {
        val envelope=PairingCrypto.encrypt(key,"search",aad)
        listOf("other|message|uuid","session|manifest","session|message|replayed-other-id").forEach {
            rejected { PairingCrypto.decrypt(key,envelope,it) }
        }
    }
    @Test fun wrongKeyAndTamperedCiphertextAreRejected() {
        val envelope=PairingCrypto.encrypt(key,"secret",aad)
        rejected { PairingCrypto.decrypt(ByteArray(32),envelope,aad) }
        val json=JsonParser.parseString(envelope).asJsonObject
        val bytes=PairingCrypto.decode(json["data"].asString); bytes[0]=(bytes[0].toInt() xor 1).toByte()
        json.addProperty("data",PairingCrypto.encode(bytes))
        rejected { PairingCrypto.decrypt(key,json.toString(),aad) }
    }
    @Test fun malformedIvMissingFieldsAndOversizedEnvelopeAreRejected() {
        listOf("{}","not json","{\"iv\":\"AA\",\"data\":\"AA\"}","x".repeat(1048577)).forEach {
            rejected { PairingCrypto.decrypt(key,it,aad) }
        }
    }
    @Test fun randomizedIvProducesUniqueCiphertextForSamePlaintext() {
        val envelopes=(1..100).map { PairingCrypto.encrypt(key,"same",aad) }
        assertEquals(100,envelopes.toSet().size)
        envelopes.forEach { assertEquals("same",PairingCrypto.decrypt(key,it,aad)) }
    }
    @Test fun capabilitySecretsAre256BitUrlSafeAndHashesDeterministic() {
        val secret=PairingCrypto.secret(); assertEquals(32,secret.size)
        val encoded=PairingCrypto.encode(secret); assertFalse(encoded.contains("="))
        assertArrayEquals(secret,PairingCrypto.decode(encoded))
        assertEquals(64,PairingCrypto.hash(encoded).length)
        assertEquals(PairingCrypto.hash(encoded),PairingCrypto.hash(encoded))
        assertNotEquals(PairingCrypto.hash(encoded),PairingCrypto.hash(encoded+"a"))
    }
}
