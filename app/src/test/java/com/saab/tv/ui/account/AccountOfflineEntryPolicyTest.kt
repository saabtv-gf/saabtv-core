package com.saab.tv.ui.account

import com.saab.tv.data.account.AccountApiException
import org.junit.Assert.*
import org.junit.Test

class AccountOfflineEntryPolicyTest {
    @Test fun returningAccountCanOpenDuringRateLimitServiceOutageAndTransportFailure() {
        listOf(
            AccountApiException(429, "Limited"), AccountApiException(500, "Unavailable"),
            AccountApiException(599, "Unavailable"),
            java.net.SocketTimeoutException(), java.net.SocketException(), java.net.UnknownHostException()
        ).forEach { failure -> assertTrue(canContinueWithLocalAccount(true, true, failure)) }
    }

    @Test fun noSessionOrNoLocalAccountNeverSkipsRestore() {
        val error = AccountApiException(429, "Limited")
        assertFalse(canContinueWithLocalAccount(false, true, error))
        assertFalse(canContinueWithLocalAccount(true, false, error))
        assertFalse(canContinueWithLocalAccount(false, false, error))
    }

    @Test fun authenticationRestoreAndCryptoFailuresRequireExplicitRecovery() {
        listOf(
            AccountApiException(401, "Expired"), AccountApiException(403, "Denied"),
            AccountApiException(404, "Missing"), AccountApiException(409, "Conflict"),
            AccountApiException(413, "Oversized"), IllegalArgumentException("Unsupported backup"),
            AccountApiException(600, "Invalid status"),
            javax.crypto.AEADBadTagException(), java.io.IOException("Invalid cloud data")
        ).forEach { failure -> assertFalse(canContinueWithLocalAccount(true, true, failure)) }
    }
}
