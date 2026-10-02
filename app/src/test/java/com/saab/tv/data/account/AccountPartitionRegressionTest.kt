package com.saab.tv.data.account

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class AccountPartitionRegressionTest {
    private fun snapshot(json: String) = JsonParser.parseString(json).asJsonObject
    private fun empty() = snapshot("""{"userId":"u1","tables":{},"preferences":{},"files":{}}""")

    @Test fun emptySnapshotRoundTripsWithoutInventingState() {
        val original = empty()
        val parts = AccountPartitions.split(original)
        assertTrue(parts.values.isEmpty())
        assertEquals(original, AccountPartitions.join(parts.layout) { error("Unexpected component") })
    }

    @Test fun splittingDoesNotMutateInputAndComponentsAreIndependentCopies() {
        val original = snapshot("""{"tables":{"profiles":[{"id":1,"name":"Before"}]},"preferences":{},"files":{}}""")
        val expected = original.deepCopy()
        val parts = AccountPartitions.split(original)
        parts.values.getValue("table:profiles").asJsonArray[0].asJsonObject.addProperty("name", "After")
        parts.layout.addProperty("userId", "other")
        assertEquals(expected, original)
    }

    @Test fun duplicateProgressIdentityIsRejectedRatherThanLosingOneRow() {
        val original = snapshot("""{"tables":{"watch_history":[{"profileId":1,"id":"tt1"},{"profileId":1,"id":"tt1"}]},
          "preferences":{},"files":{}}""")
        try { AccountPartitions.split(original); fail("Duplicate history must fail") }
        catch (error: IllegalArgumentException) { assertEquals("Duplicate progress identity.", error.message) }
    }

    @Test fun samePreferenceNameInDifferentStoresHasSeparateComponents() {
        val original = snapshot("""{"tables":{},"preferences":{"one":{"language":"en"},"two":{"language":"te"}},"files":{}}""")
        val parts = AccountPartitions.split(original)
        assertEquals(2, parts.values.size)
        assertEquals(original, AccountPartitions.join(parts.layout) { parts.values.getValue(it) })
    }

    @Test fun removingPreferencesAndFilesRemovesTheirReferences() {
        val original = snapshot("""{"tables":{},"preferences":{"settings":{"lang":"en"}},"files":{"avatars/a.png":"bytes"}}""")
        val before = AccountPartitions.split(original)
        original.getAsJsonObject("preferences").getAsJsonObject("settings").remove("lang")
        original.getAsJsonObject("files").remove("avatars/a.png")
        val after = AccountPartitions.split(original)
        assertEquals(2, (before.values.keys - after.values.keys).size)
        assertEquals(original, AccountPartitions.join(after.layout) { after.values.getValue(it) })
    }

    @Test fun missingComponentFailsInsteadOfRestoringPartialAccountState() {
        val original = snapshot("""{"tables":{"profiles":[{"id":1}]},"preferences":{},"files":{}}""")
        val parts = AccountPartitions.split(original)
        try { AccountPartitions.join(parts.layout) { emptyMap<String, com.google.gson.JsonElement>().getValue(it) }; fail() }
        catch (_: NoSuchElementException) { }
    }

    @Test fun emptyAndIncompressiblePayloadsPreserveBytes() {
        val small = byteArrayOf(1, 2, 3)
        assertArrayEquals(small, AccountSyncCodec.pack(small))
        assertArrayEquals(byteArrayOf(), AccountSyncCodec.unpack(AccountSyncCodec.pack(byteArrayOf())))
        val random = ByteArray(1000).also { java.util.Random(7).nextBytes(it) }
        assertArrayEquals(random, AccountSyncCodec.unpack(AccountSyncCodec.pack(random)))
    }

    @Test fun hashUsesStandardSha256AndIsContentSensitive() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", AccountSyncCodec.hash(byteArrayOf()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AccountSyncCodec.hash("abc".toByteArray()))
        assertNotEquals(AccountSyncCodec.hash("abc".toByteArray()), AccountSyncCodec.hash("abd".toByteArray()))
    }

    @Test fun oversizedPlainAndEncodedComponentsAreRejected() {
        val bytes = ByteArray(AccountSyncCodec.MAX_PLAIN + 1)
        try { AccountSyncCodec.pack(bytes); fail() } catch (_: IllegalArgumentException) { }
        try { AccountSyncCodec.unpack(bytes); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun truncatedCompressionHeaderFailsClosed() {
        try { AccountSyncCodec.unpack(byteArrayOf(83, 84, 90, 49)); fail() }
        catch (_: java.io.IOException) { }
    }
}
