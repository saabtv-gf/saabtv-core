package com.saab.tv.remote_input

import org.junit.Assert.*
import org.junit.Test

class PairingCryptoTest {
    @Test fun browserWebCryptoVectorDecryptsOnAndroidJvm() {
        val envelope = """{"iv":"CQkJCQkJCQkJCQkJ","data":"Yuvj-NeDqUFA0m_YV2xDb2ZQ0nLUXmAg3i9mRnHN4d5sBUNrAven8NsVRbPEiLNVRpaXK-iDEOtPFXvj0g"}"""
        assertEquals("English తెలుగు हिन्दी", PairingCrypto.decrypt(ByteArray(32) { 7 }, envelope, "cross|message|uuid"))
    }
    @Test fun roundTripAndFreshNonces() {
        val key = PairingCrypto.secret()
        val a = PairingCrypto.encrypt(key, "English తెలుగు हिन्दी", "session|message|uuid")
        val b = PairingCrypto.encrypt(key, "English తెలుగు हिन्दी", "session|message|uuid")
        assertNotEquals(a, b)
        assertEquals("English తెలుగు हिन्दी", PairingCrypto.decrypt(key, a, "session|message|uuid"))
    }
    @Test fun wrongSessionAndKeyFailAuthentication() {
        val key = PairingCrypto.secret()
        val envelope = PairingCrypto.encrypt(key, "private", "session|manifest")
        assertThrows(Exception::class.java) { PairingCrypto.decrypt(key, envelope, "other|manifest") }
        assertThrows(Exception::class.java) { PairingCrypto.decrypt(PairingCrypto.secret(), envelope, "session|manifest") }
    }
    @Test fun capabilitiesAre256BitsAndHashMatchesSql() {
        val secret = PairingCrypto.encode(PairingCrypto.secret())
        assertEquals(43, secret.length)
        assertEquals(32, PairingCrypto.decode(secret).size)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", PairingCrypto.hash("abc"))
    }
}
