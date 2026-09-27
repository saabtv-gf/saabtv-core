package com.saab.tv.data.account

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saab.tv.data.local.SaabTvDatabase
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.WatchHistoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AccountSnapshotStoreTest {
    // Do not touch the device's actual selected-account preference or files.
    private class TestContext(base: Context, private val prefix: String) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(prefix + name, mode)
        override fun getFilesDir(): File = File(super.getCacheDir(), prefix).apply { mkdirs() }
    }

    @Test fun roundTripKeepsSettingsHistoryAndPortableAvatarWithoutLosingLongPrecision() = runBlocking {
        val context = TestContext(ApplicationProvider.getApplicationContext(), "qa_snapshot_${UUID.randomUUID()}_")
        AccountStorage.setUserId(context, UUID.randomUUID().toString())
        val db = Room.inMemoryDatabaseBuilder(context, SaabTvDatabase::class.java).allowMainThreadQueries().build()
        try {
            val avatar = File(AccountStorage.files(context), "avatars/avatar_test.png")
            avatar.parentFile!!.mkdirs(); avatar.writeBytes(byteArrayOf(1, 2, 3))
            val profile = ProfileEntity(id = 1, name = "QA", isActive = true, pinHash = "test-hash",
                sourceLanguagePriority1 = "te", avatarRef = "custom:${avatar.absolutePath}")
            db.addonDao().insertProfile(profile)
            val exactLong = 9_007_199_254_740_993L
            db.addonDao().insertHistory(WatchHistoryEntity(profileId = 1, id = "qa-title", title = "QA",
                poster = null, position = 333_000, duration = 9_999_000, lastWatched = exactLong, type = "movie"))
            db.addonDao().insertHistory(WatchHistoryEntity(profileId = 1, id = "tt123:1:2", title = "Episode",
                poster = null, position = 950_000, duration = 1_000_000, lastWatched = exactLong, type = "series", watched = true))
            val prefs = AccountStorage.preferences(context, "source_selection_prefs")
            assertTrue(prefs.edit().putString("selected", "qa-source").commit())
            val store = AccountSnapshotStore(context, db)
            val bytes = store.capture()
            db.addonDao().updateProfile(profile.copy(name = "Changed", sourceLanguagePriority1 = "en"))
            prefs.edit().clear().commit()
            store.restore(bytes)
            assertEquals(profile, db.addonDao().getProfileById(1))
            assertEquals(exactLong, db.addonDao().getHistoryItem("qa-title")!!.lastWatched)
            assertTrue(db.addonDao().getHistoryItemForProfile(1, "tt123:1:2")!!.watched)
            assertEquals(950_000L, db.addonDao().getHistoryItemForProfile(1, "tt123:1:2")!!.position)
            assertNull(db.addonDao().getHistoryItemForProfile(2, "tt123:1:2"))
            assertEquals("qa-source", prefs.getString("selected", null))
            assertArrayEquals(byteArrayOf(1, 2, 3), avatar.readBytes())

            val firstDatabaseName = AccountStorage.databaseName(context)
            AccountStorage.setUserId(context, UUID.randomUUID().toString())
            assertNotEquals(firstDatabaseName, AccountStorage.databaseName(context))
            assertNull(AccountStorage.preferences(context, "source_selection_prefs").getString("selected", null))
            try { store.restore(bytes); fail("Another account must not restore this snapshot") }
            catch (_: IllegalArgumentException) { /* expected */ }
            assertEquals(profile, db.addonDao().getProfileById(1))
        } finally { db.close() }
    }
}
