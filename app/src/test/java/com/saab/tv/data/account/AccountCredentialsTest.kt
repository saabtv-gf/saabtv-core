package com.saab.tv.data.account

import org.junit.Assert.*
import org.junit.Test

class AccountCredentialsTest {
    @Test fun usernamesAreCanonicalAndCannotCollideByCase() {
        assertEquals("alice@accounts.saabtv.invalid", AccountCredentials.alias(" Alice "))
        assertEquals(AccountCredentials.alias("ALICE"), AccountCredentials.alias("alice"))
        assertNotNull(AccountCredentials.usernameError("ab"))
        assertNotNull(AccountCredentials.usernameError("a@b"))
        assertNotNull(AccountCredentials.usernameError("a.b"))
        assertNotNull(AccountCredentials.usernameError("a b"))
        assertNotNull(AccountCredentials.usernameError("éab"))
        assertNull(AccountCredentials.usernameError("user_123"))
    }
    @Test fun passwordRequiresAllFourClassesAndNeonMinimumEightCharacters() {
        assertNull(AccountCredentials.passwordError("Ab1!xyzt"))
        assertNotNull(AccountCredentials.passwordError("Ab1!xy"))
        for (value in listOf("abcd1!xy", "ABCD1!XY", "Abcdef!x", "Abcd1234", "Ab1 xyzt")) {
            assertNotNull(value, AccountCredentials.passwordError(value))
        }
        assertNull(AccountCredentials.passwordError("LongerGoodPassword!1"))
    }
}
