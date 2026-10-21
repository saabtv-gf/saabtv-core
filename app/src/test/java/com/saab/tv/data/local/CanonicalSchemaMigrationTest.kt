package com.saab.tv.data.local

import android.app.Application
import androidx.room.Room
import com.saab.tv.data.account.AccountSnapshotStore
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.ContentKind
import com.saab.tv.data.model.CanonicalTitleEntity
import com.saab.tv.di.MIGRATION_62_63
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CanonicalSchemaMigrationTest {
    @Test fun schema63MigrationRepairsLegacyPreferenceMirrors() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val databaseUnderTest = Room.inMemoryDatabaseBuilder(context, SaabTvDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = databaseUnderTest.addonDao()
            dao.insertProfile(ProfileEntity(
                id = 1,
                name = "Pre-migration",
                seekTimeIntervalSeconds = 20,
                autoSkipIntro = true,
                introSkipCountdownSeconds = 10
            ))
            databaseUnderTest.openHelper.writableDatabase.execSQL(
                "UPDATE profiles SET seekThumbnailIntervalSeconds = 30, outroSkipCountdownSeconds = 5 WHERE id = 1"
            )

            MIGRATION_62_63.migrate(databaseUnderTest.openHelper.writableDatabase)

            val migrated = dao.getProfileById(1)!!
            assertEquals(20, migrated.seekThumbnailIntervalSeconds)
            assertEquals(10, migrated.outroSkipCountdownSeconds)
        } finally {
            databaseUnderTest.close()
        }
    }

    @Test fun canonicalContentCacheDoesNotMutatePortableUserSnapshot() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "canonical-migration-${UUID.randomUUID()}.db"
        var database: SaabTvDatabase? = null
        try {
            val databaseUnderTest = Room.databaseBuilder(context, SaabTvDatabase::class.java, name)
                .allowMainThreadQueries().build()
            database = databaseUnderTest
            val profile = ProfileEntity(id = 1, name = "Existing", isActive = true, preferredSubtitleLanguage = "te")
            val history = WatchHistoryEntity(profileId = 1, id = "tt1234567:2:5", title = "Series",
                poster = null, type = "series", position = 543_210, duration = 2_000_000, lastWatched = 1_234)
            databaseUnderTest.addonDao().insertProfile(profile)
            databaseUnderTest.addonDao().insertHistory(history)
            val portableBefore = AccountSnapshotStore(context, databaseUnderTest).capture().toString(Charsets.UTF_8)
            val identity = requireNotNull(ContentIdentity.tmdb(ContentKind.MOVIE, 42))
            databaseUnderTest.canonicalMetadataDao().putTitle(CanonicalTitleEntity(identity.stableKey, "en", "movie",
                title = "Cached", lastFetchedAtMillis = 1_000))
            assertEquals("Cached", databaseUnderTest.canonicalMetadataDao().getTitle(identity.stableKey, "en")?.title)
            assertEquals(profile, databaseUnderTest.addonDao().getProfileById(1))
            assertEquals(history, databaseUnderTest.addonDao().getHistoryItem(history.id))
            assertEquals(portableBefore, AccountSnapshotStore(context, databaseUnderTest).capture().toString(Charsets.UTF_8))
        } finally {
            database?.close()
            context.deleteDatabase(name)
        }
    }
}
