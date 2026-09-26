package com.saab.tv.data.update

import org.junit.Assert.*
import org.junit.Test

class ApkSignerPolicyTest {
    private val key = ApkSignerPolicy.releaseKeys.single()
    @Test fun officialInstalledAndCandidateMatch() { assertTrue(ApkSignerPolicy.matches(setOf(key), setOf(key), false)) }
    @Test fun unavailableCertificatesNeverBypassTrust() {
        assertFalse(ApkSignerPolicy.matches(emptySet(), setOf(key), false))
        assertFalse(ApkSignerPolicy.matches(setOf(key), emptySet(), false))
    }
    @Test fun otherSignerAndExtraSignerAreRejected() {
        assertFalse(ApkSignerPolicy.matches(setOf("other"), setOf("other"), false))
        assertFalse(ApkSignerPolicy.matches(setOf(key, "other"), setOf(key), false))
        assertFalse(ApkSignerPolicy.matches(setOf(key), setOf("other"), false))
    }
    @Test fun multipleInstalledSignersMustMatchExactly() {
        assertFalse(ApkSignerPolicy.matches(setOf(key), setOf(key, "other"), true))
        assertFalse(ApkSignerPolicy.matches(setOf(key, "other"), setOf(key, "other"), true))
    }
}
