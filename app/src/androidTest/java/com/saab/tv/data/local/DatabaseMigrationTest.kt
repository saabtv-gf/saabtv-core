package com.saab.tv.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.saab.tv.di.MIGRATION_47_48
import com.saab.tv.di.MIGRATION_48_49
import com.saab.tv.di.MIGRATION_49_50
import com.saab.tv.di.MIGRATION_51_52
import com.saab.tv.di.MIGRATION_54_55
import com.saab.tv.di.MIGRATION_55_56
import com.saab.tv.di.MIGRATION_56_57
import com.saab.tv.di.MIGRATION_57_58
import com.saab.tv.di.MIGRATION_58_59
import com.saab.tv.di.MIGRATION_59_60
import com.saab.tv.di.MIGRATION_60_61
import com.saab.tv.di.MIGRATION_61_62
import com.saab.tv.di.MIGRATION_62_63
import com.saab.tv.di.MIGRATION_63_64
import com.saab.tv.di.MIGRATION_64_65
import com.saab.tv.domain.schema.CanonicalEntityKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        requireNotNull(SaabTvDatabase::class.java.canonicalName),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate47To48AddsThumbnailSettingsWithSafeDefaults() {
        helper.createDatabase(TEST_DATABASE, 47).close()

        helper.runMigrationsAndValidate(TEST_DATABASE, 48, true, MIGRATION_47_48).use { database ->
            database.query(
                "SELECT seekThumbnailsEnabled, seekThumbnailIntervalSeconds FROM profiles LIMIT 0"
            ).use { cursor ->
                check(cursor.columnNames.contentEquals(
                    arrayOf("seekThumbnailsEnabled", "seekThumbnailIntervalSeconds")
                ))
            }
        }
    }

    @Test
    fun migrate48To49AddsSeekTimeIntervalWithSafeDefault() {
        helper.createDatabase(TEST_DATABASE, 48).close()

        helper.runMigrationsAndValidate(TEST_DATABASE, 49, true, MIGRATION_48_49).use { database ->
            database.query("SELECT seekTimeIntervalSeconds FROM profiles LIMIT 0").use { cursor ->
                check(cursor.columnNames.contentEquals(arrayOf("seekTimeIntervalSeconds")))
            }
        }
    }

    @Test
    fun migrate62To63ValidatesCanonicalProfilePreferenceStorage() {
        helper.createDatabase(TEST_DATABASE, 62).close()

        helper.runMigrationsAndValidate(TEST_DATABASE, 63, true, MIGRATION_62_63).use { database ->
            database.query(
                "SELECT seekTimeIntervalSeconds, seekThumbnailIntervalSeconds, autoSkipIntro, introSkipCountdownSeconds, outroSkipCountdownSeconds FROM profiles LIMIT 0"
            ).use { cursor ->
                check(cursor.columnNames.contentEquals(arrayOf(
                    "seekTimeIntervalSeconds", "seekThumbnailIntervalSeconds", "autoSkipIntro",
                    "introSkipCountdownSeconds", "outroSkipCountdownSeconds"
                )))
            }
        }
    }

    @Test
    fun migrate63To64AssignsLegacyRuntimeRowsToPortableActiveProfile() {
        val legacy = helper.createDatabase(TEST_DATABASE, 63)
        insertLegacyProfile(legacy)
        legacy.execSQL("INSERT INTO addons VALUES ('https://addon.invalid','addon','Legacy Addon','1',NULL,NULL,0,1,NULL,'[]',0,1,'[]','[]',7)")
        legacy.execSQL("INSERT INTO catalog_configs VALUES ('movie:top','https://addon.invalid','Legacy Addon','movie','top','Top',NULL,1,1,0,2,3,999,0,15,1)")
        legacy.execSQL("INSERT INTO hub_rows VALUES ('legacy-hub','Legacy Hub','poster',1,0,0,4,999,999,123)")
        legacy.execSQL("INSERT INTO hub_row_items VALUES ('legacy-hub','movie:top','Legacy Item',NULL,5)")
        legacy.close()

        helper.runMigrationsAndValidate(TEST_DATABASE, 64, true, MIGRATION_63_64).use { database ->
            for (table in listOf("addons", "catalog_configs", "hub_rows", "hub_row_items")) {
                database.query("SELECT profileOwnerId FROM `$table`").use { cursor ->
                    check(cursor.moveToFirst())
                    check(cursor.getString(0) == "legacy-portable-profile")
                }
            }
            database.query("SELECT name FROM addons WHERE profileOwnerId='legacy-portable-profile'").use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "Legacy Addon")
            }
        }
    }

    @Test
    fun migrate64To65AddsFullDetailCacheFieldsWithoutChangingExistingRows() {
        val legacy = helper.createDatabase(TEST_DATABASE, 64)
        legacy.execSQL("""
            INSERT INTO canonical_titles (
                identityKey, locale, kind, titleObservedAtMillis, synopsisObservedAtMillis,
                posterObservedAtMillis, backdropObservedAtMillis, logoObservedAtMillis,
                lastFetchedAtMillis, genresObservedAtMillis, releaseDateObservedAtMillis,
                ratingObservedAtMillis, runtimeObservedAtMillis, statusObservedAtMillis,
                countriesObservedAtMillis, originalLanguageObservedAtMillis, collectionObservedAtMillis
            ) VALUES ('tmdb/movie//42','en','movie',0,0,0,0,0,123,0,0,0,0,0,0,0,0)
        """.trimIndent())
        legacy.close()

        helper.runMigrationsAndValidate(TEST_DATABASE, 65, true, MIGRATION_64_65).use { database ->
            database.query("SELECT ageRating, ageRatingRegion, ageRatingSource, ageRatingObservedAtMillis, creditsJson, creditsSource, creditsObservedAtMillis, lastFetchedAtMillis FROM canonical_titles").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.isNull(0) && cursor.isNull(1) && cursor.isNull(2))
                check(cursor.getLong(3) == 0L)
                check(cursor.isNull(4) && cursor.isNull(5))
                check(cursor.getLong(6) == 0L)
                check(cursor.getLong(7) == 123L)
            }
        }
    }

    private fun insertLegacyProfile(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        val columns = mutableListOf<String>()
        val values = mutableListOf<String>()
        database.query("PRAGMA table_info(profiles)").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) != 0
                val defaultValue = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                val primaryKey = cursor.getInt(cursor.getColumnIndexOrThrow("pk")) != 0
                val value = when (name) {
                    "id" -> "1"
                    "name" -> "'Legacy profile'"
                    "portableId" -> "'legacy-portable-profile'"
                    "isActive" -> "1"
                    else -> defaultValue ?: if (!notNull || primaryKey) "NULL" else when {
                        cursor.getString(cursor.getColumnIndexOrThrow("type")).contains("INT", ignoreCase = true) -> "0"
                        cursor.getString(cursor.getColumnIndexOrThrow("type")).contains("REAL", ignoreCase = true) -> "0.0"
                        else -> "''"
                    }
                }
                columns += "`$name`"
                values += value
            }
        }
        database.execSQL("INSERT INTO profiles (${columns.joinToString(",")}) VALUES (${values.joinToString(",")})")
    }

    private companion object {
        const val TEST_DATABASE = "migration-test"
    }

    @Test
    fun migrate49To50KeepsProfilesAndAddsSkipDefaults() {
        helper.createDatabase(TEST_DATABASE, 49).close()
        helper.runMigrationsAndValidate(TEST_DATABASE, 50, true, MIGRATION_49_50).use { database ->
            database.query("PRAGMA table_info(profiles)").use { cursor ->
                val defaults = mutableMapOf<String, String?>()
                while (cursor.moveToNext()) {
                    defaults[cursor.getString(cursor.getColumnIndexOrThrow("name"))] =
                        cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                }
                check(defaults["autoSkipIntro"] == "1")
                check(defaults["introSkipCountdownSeconds"] == "5")
                check(defaults["outroSkipCountdownSeconds"] == "5")
            }
        }
    }

    @Test
    fun migrate51To52AddsSkipRecapWithCompatibleDefault() {
        val database = helper.createDatabase(TEST_DATABASE, 51)
        try {
            MIGRATION_51_52.migrate(database)
            database.query("PRAGMA table_info(profiles)").use { cursor ->
                var defaultValue: String? = null
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "skipRecap") {
                        defaultValue = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                    }
                }
                check(defaultValue == "1")
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate54To56AddsCanonicalCachesWithoutReplacingUserTables() {
        val database = helper.createDatabase(TEST_DATABASE, 54)
        try {
            MIGRATION_54_55.migrate(database)
            MIGRATION_55_56.migrate(database)
            database.query("SELECT * FROM canonical_titles LIMIT 0").use { cursor ->
                check(cursor.columnNames.contains("titleSource"))
                check(cursor.columnNames.contains("ratingScale"))
            }
            database.query("SELECT * FROM canonical_identity_aliases LIMIT 0").use { cursor ->
                check(cursor.columnNames.contains("canonicalIdentityKey"))
                check(cursor.columnNames.contains("verifiedAtMillis"))
            }
            database.query("SELECT * FROM canonical_episode_lists LIMIT 0").use { cursor ->
                check(cursor.columnNames.contains("episodeCount"))
                check(cursor.columnNames.contains("observedAtMillis"))
            }
            database.query("SELECT * FROM canonical_episodes LIMIT 0").use { cursor ->
                check(cursor.columnNames.contains("episodeOrder"))
                check(cursor.columnNames.contains("overview"))
            }
            database.query("SELECT * FROM profiles LIMIT 0").use { cursor ->
                check(cursor.columnNames.contains("id"))
            }
            database.query("SELECT * FROM watch_history LIMIT 0").use { cursor ->
                check(cursor.columnNames.contains("position"))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate55To61AddsPortableProfilesSyncJournalAndScalarProvenance() {
        val database = helper.createDatabase(TEST_DATABASE, 55)
        try {
            MIGRATION_55_56.migrate(database)
            MIGRATION_56_57.migrate(database)
            MIGRATION_57_58.migrate(database)
            MIGRATION_58_59.migrate(database)
            MIGRATION_59_60.migrate(database)
            database.execSQL("""
                INSERT INTO canonical_titles (
                    identityKey, locale, kind, titleObservedAtMillis, synopsisObservedAtMillis,
                    posterObservedAtMillis, backdropObservedAtMillis, logoObservedAtMillis,
                    genresJson, releaseDate, ratingValue, ratingSource, ratingScale,
                    runtimeEstimateMillis, status, countriesJson, originalLanguage,
                    collectionId, collectionName, lastFetchedAtMillis
                ) VALUES (
                    'tmdb:movie:42', 'en', 'movie', 0, 0, 0, 0, 0,
                    '["Drama"]', '2024-01-02', 8.1, 'IMDb', '0-10',
                    60000, 'Released', '["IN"]', 'en', 7, 'Set', 1234
                )
            """.trimIndent())
            MIGRATION_60_61.migrate(database)
            database.query("SELECT portableId FROM profiles LIMIT 0").use { cursor ->
                check(cursor.columnNames.contentEquals(arrayOf("portableId")))
            }
            database.query("SELECT operationId, entityKey, entityRevision, sequence, payloadJson FROM sync_outbox LIMIT 0").use { cursor ->
                check(cursor.columnNames.contentEquals(arrayOf("operationId", "entityKey", "entityRevision", "sequence", "payloadJson")))
            }
            database.query("SELECT entityKey, revision FROM sync_entity_revisions LIMIT 0").use { cursor ->
                check(cursor.columnNames.contentEquals(arrayOf("entityKey", "revision")))
            }
            database.query("SELECT deviceId, nextSequence, serverCursor FROM sync_client_state LIMIT 0").use { cursor ->
                check(cursor.columnNames.contentEquals(arrayOf("deviceId", "nextSequence", "serverCursor")))
            }
            database.query("SELECT sourceAddonBaseUrl, sourceAddonKey FROM watchlist LIMIT 0").use { cursor ->
                check(cursor.columnNames.contentEquals(arrayOf("sourceAddonBaseUrl", "sourceAddonKey")))
            }
            database.query("SELECT accountUserId FROM sync_outbox LIMIT 0").use { cursor ->
                check(cursor.columnNames.contentEquals(arrayOf("accountUserId")))
            }
            database.query("PRAGMA index_list(sync_outbox)").use { cursor ->
                val indexes = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                check("index_sync_outbox_accountUserId_sequence" in indexes)
            }
            database.query("""
                SELECT genresSource, genresObservedAtMillis, releaseDateSource,
                    releaseDateObservedAtMillis, ratingSource, ratingObservedAtMillis,
                    runtimeSource, runtimeObservedAtMillis, statusSource, countriesSource,
                    originalLanguageSource, collectionSource, lastFetchedAtMillis
                FROM canonical_titles WHERE identityKey = 'tmdb:movie:42'
            """.trimIndent()).use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getString(0) == "LEGACY")
                check(cursor.getLong(1) == 1234L)
                check(cursor.getString(2) == "LEGACY")
                check(cursor.getLong(3) == 1234L)
                check(cursor.getString(4) == "IMDb")
                check(cursor.getLong(5) == 1234L)
                check(cursor.getString(6) == "LEGACY")
                check(cursor.getLong(7) == 1234L)
                check(cursor.getString(8) == "LEGACY")
                check(cursor.getString(9) == "LEGACY")
                check(cursor.getString(10) == "LEGACY")
                check(cursor.getString(11) == "LEGACY")
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate61To62BackfillsCanonicalKeysWithoutChangingLegacyPrimaryIds() {
        val database = helper.createDatabase(TEST_DATABASE, 61)
        try {
            database.execSQL("""
                INSERT INTO watch_history (profileId, id, title, poster, background, logo, position, duration, lastWatched, type, watched, scrobbled)
                VALUES (1, 'tt1234567:2:5', 'Episode', NULL, NULL, NULL, 120, 900, 10, 'series', 0, 0)
            """.trimIndent())
            database.execSQL("""
                INSERT INTO watchlist (profileId, id, type, title, poster, addedAt, background, logo, sourceAddonBaseUrl, sourceAddonKey)
                VALUES (1, 'opaque-42', 'series', 'Opaque series', NULL, 10, NULL, NULL, NULL, 'installation-hash')
            """.trimIndent())
            database.execSQL("""
                INSERT INTO series_next_up (profileId, seriesId, title, poster, nextSeason, nextEpisode, nextEpisodeTitle, nextReleased, isComplete, isNewEpisode, updatedAt)
                VALUES (1, 'tt7654321', 'Next', NULL, 2, 3, NULL, NULL, 0, 0, 10)
            """.trimIndent())

            MIGRATION_61_62.migrate(database)

            database.query("SELECT id, canonicalIdentityKey FROM watch_history").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getString(0) == "tt1234567:2:5")
                check(cursor.getString(1) == CanonicalEntityKey.playable("tt1234567:2:5", "series"))
            }
            database.query("SELECT id, canonicalIdentityKey FROM watchlist").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getString(0) == "opaque-42")
                check(cursor.getString(1) == CanonicalEntityKey.title("opaque-42", "series", "installation-hash"))
            }
            database.query("SELECT seriesId, canonicalIdentityKey FROM series_next_up").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getString(0) == "tt7654321")
                check(cursor.getString(1) == CanonicalEntityKey.title("tt7654321", "series"))
            }
            database.query("PRAGMA index_list(watch_history)").use { cursor ->
                val names = buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
                check("index_watch_history_profileId_canonicalIdentityKey" in names)
            }
        } finally {
            database.close()
        }
    }
}
