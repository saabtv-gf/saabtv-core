package com.saab.tv.data.account

import org.junit.Assert.*
import org.junit.Test

class AccountSnapshotFreshnessTest {
    @Test fun newerLocalWins() { assertTrue(localSnapshotIsNewer(200, 100)) }
    @Test fun newerCloudWins() { assertFalse(localSnapshotIsNewer(100, 200)) }
    @Test fun tiePrefersCloud() { assertFalse(localSnapshotIsNewer(200, 200)) }
    @Test fun missingLocalTimePrefersCloud() { assertFalse(localSnapshotIsNewer(0, 200)) }
    @Test fun missingCloudTimeNeverAuthorizesOverwrite() { assertFalse(localSnapshotIsNewer(200, 0)) }
}
