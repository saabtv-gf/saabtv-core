package com.saab.tv.data.account

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class AccountSyncCodecTest {
    private fun snapshot() = JsonParser.parseString("""{
        "format":1,"schema":52,"userId":"test-user","changedAt":42,
        "tables":{"profiles":[{"id":1}],"watch_history":[
          {"profileId":1,"id":"episode:1","position":100},
          {"profileId":1,"id":"episode:2","position":200}]},
        "preferences":{"source_selection_prefs":{"episode:1":{"type":"string","value":"selected-source"}}},
        "files":{"avatars/avatar.png":"image-bytes","hub_images/image.png":"another-image"}}
        """).asJsonObject

    @Test fun partitionRoundTripPreservesAllPortableStateAndOrder() {
        val original = snapshot(); val parts = AccountPartitions.split(original)
        assertEquals(original.toString(), AccountPartitions.join(parts.layout) { parts.values.getValue(it) }.toString())
    }
    @Test fun progressChangeDoesNotReuploadSettingsOrImagesOrOtherEpisodes() {
        val original = snapshot(); val before = AccountPartitions.split(original)
        original.getAsJsonObject("tables").getAsJsonArray("watch_history")[0].asJsonObject.addProperty("position", 300)
        val after = AccountPartitions.split(original)
        val changed = after.values.keys.filter { after.values[it] != before.values[it] }
        assertEquals(1, changed.size); assertTrue(changed.single().startsWith("history:"))
        assertEquals(before.layout, after.layout)
    }
    @Test fun changingSourceOnlyChangesOnePreferenceComponent() {
        val original = snapshot(); val before = AccountPartitions.split(original)
        original.getAsJsonObject("preferences").getAsJsonObject("source_selection_prefs")
            .getAsJsonObject("episode:1").addProperty("value", "different-source")
        val after = AccountPartitions.split(original)
        assertEquals(1, after.values.keys.count { after.values[it] != before.values[it] })
    }
    @Test fun clearingProgressRemovesReferenceRatherThanKeepingDeletedEpisode() {
        val original = snapshot(); val before = AccountPartitions.split(original)
        original.getAsJsonObject("tables").getAsJsonArray("watch_history").remove(0)
        val after = AccountPartitions.split(original)
        assertEquals(1, (before.values.keys - after.values.keys).size)
        assertEquals(original, AccountPartitions.join(after.layout) { after.values.getValue(it) })
    }
    @Test fun profilesWithSameTitleHaveSeparateProgressComponents() {
        val original = snapshot(); val rows = original.getAsJsonObject("tables").getAsJsonArray("watch_history")
        val other = rows[0].deepCopy().asJsonObject.apply { addProperty("profileId", 2) }
        rows.add(other)
        assertEquals(3, AccountPartitions.split(original).values.keys.count { it.startsWith("history:") })
    }
    @Test fun compressionRoundTripsAndSignificantlyReducesStructuredData() {
        val bytes = "repeated-json-settings-and-progress,".repeat(10000).toByteArray()
        val packed = AccountSyncCodec.pack(bytes)
        assertTrue(packed.size < bytes.size / 20)
        assertArrayEquals(bytes, AccountSyncCodec.unpack(packed))
    }
    @Test fun oldUncompressedPayloadsAreStillReadable() {
        val bytes = snapshot().toString().toByteArray()
        assertArrayEquals(bytes, AccountSyncCodec.unpack(bytes))
    }
    @Test fun compressionAndEncryptionRoundTripWithAccountIsolation() {
        val key = ByteArray(32) { it.toByte() }; val plain = snapshot().toString().toByteArray()
        val encrypted = AccountSnapshotCrypto.encrypt(AccountSyncCodec.pack(plain), key, "test-user")
        assertArrayEquals(plain, AccountSyncCodec.unpack(AccountSnapshotCrypto.decrypt(encrypted, key, "test-user")))
        try { AccountSnapshotCrypto.decrypt(encrypted, key, "other-user"); fail("Cross-account decrypt succeeded") }
        catch (_: javax.crypto.AEADBadTagException) { }
    }
    @Test(expected = IllegalArgumentException::class)
    fun decompressionBombCannotExceedSnapshotLimit() {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { stream ->
            val chunk = ByteArray(8192)
            repeat(AccountSyncCodec.MAX_PLAIN / chunk.size + 2) { stream.write(chunk) }
        }
        AccountSyncCodec.unpack(byteArrayOf(83, 84, 90, 49) + output.toByteArray())
    }
}
