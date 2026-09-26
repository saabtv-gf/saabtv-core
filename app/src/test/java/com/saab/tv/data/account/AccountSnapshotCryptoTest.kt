package com.saab.tv.data.account

import org.junit.Assert.*
import org.junit.Test

class AccountSnapshotCryptoTest {
    private val key = ByteArray(32) { it.toByte() }
    @Test fun encryptedSnapshotRoundTripsAndUsesRandomNonces() {
        val plain = "private-settings-and-tokens".toByteArray()
        val one = AccountSnapshotCrypto.encrypt(plain, key, "account-one")
        val two = AccountSnapshotCrypto.encrypt(plain, key, "account-one")
        assertFalse(one.contentEquals(two))
        assertArrayEquals(plain, AccountSnapshotCrypto.decrypt(one, key, "account-one"))
    }
    @Test(expected = javax.crypto.AEADBadTagException::class)
    fun aDifferentAccountCannotUnlockSnapshot() {
        AccountSnapshotCrypto.decrypt(AccountSnapshotCrypto.encrypt("private".toByteArray(), key, "one"), key, "two")
    }
    @Test(expected = javax.crypto.AEADBadTagException::class)
    fun modifiedSnapshotIsRejected() {
        val encrypted = AccountSnapshotCrypto.encrypt("private".toByteArray(), key, "one")
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        AccountSnapshotCrypto.decrypt(encrypted, key, "one")
    }
    @Test fun derivationIsStableAndClearsPasswordCharacters() {
        val password = "Ab1!xy".toCharArray()
        val one = AccountSnapshotCrypto.deriveKey(password, "one")
        assertTrue(password.all { it == '\u0000' })
        assertArrayEquals(one, AccountSnapshotCrypto.deriveKey("Ab1!xy".toCharArray(), "one"))
        assertFalse(one.contentEquals(AccountSnapshotCrypto.deriveKey("Ab1!xy".toCharArray(), "two")))
    }
}
