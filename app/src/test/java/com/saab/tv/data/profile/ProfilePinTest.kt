package com.saab.tv.data.profile

import com.saab.tv.data.model.ProfileEntity
import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePinTest {
    @Test
    fun saltedHashAcceptsOnlyTheCorrectPin() {
        val hash = ProfilePin.hash(profileId = 7, pin = "4826")
        val profile = ProfileEntity(id = 7, name = "Test", pinHash = hash)

        assertTrue(hash.startsWith("v2$"))
        assertTrue(ProfilePin.matches(profile, "4826"))
        assertFalse(ProfilePin.matches(profile, "4825"))
        assertFalse(ProfilePin.matches(profile, "12345"))
        assertFalse(ProfilePin.needsUpgrade(profile))
    }

    @Test
    fun legacyHashCanAuthenticateForOneTimeUpgrade() {
        val legacyHash = MessageDigest.getInstance("SHA-256")
            .digest("saabtv-profile:3:1357".toByteArray())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val profile = ProfileEntity(id = 3, name = "Legacy", pinHash = legacyHash)

        assertTrue(ProfilePin.matches(profile, "1357"))
        assertTrue(ProfilePin.needsUpgrade(profile))
    }
}
