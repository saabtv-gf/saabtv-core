package com.saab.tv.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.saab.tv.data.local.AddonDao
import com.saab.tv.data.local.CanonicalMetadataDao
import com.saab.tv.data.local.SaabTvDatabase
import com.saab.tv.data.repository.CanonicalTitleStore
import com.saab.tv.data.repository.CanonicalMetadataRepository
import com.saab.tv.data.repository.CanonicalSeriesMetadataStore
import com.saab.tv.data.repository.CanonicalIdentityStore
import com.saab.tv.data.account.AccountStorage
import com.saab.tv.domain.schema.CanonicalEntityKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN preferredAudioLanguage TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE profiles ADD COLUMN preferredAudioLanguageSecondary TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE profiles ADD COLUMN preferredSubtitleLanguage TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE profiles ADD COLUMN preferredSubtitleLanguageSecondary TEXT NOT NULL DEFAULT ''")
    }
}

private val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Version 28: no-op migration (schema unchanged, version was bumped without migration)
    }
}

private val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE addons ADD COLUMN supportsMeta INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE addons ADD COLUMN typesJson TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE addons ADD COLUMN idPrefixesJson TEXT NOT NULL DEFAULT '[]'")
    }
}

private val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE addons ADD COLUMN supportsStream INTEGER NOT NULL DEFAULT 1")
    }
}

private val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN splashEnabled INTEGER NOT NULL DEFAULT 1")
    }
}

private val MIGRATION_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN subtitleSize INTEGER NOT NULL DEFAULT 100")
        db.execSQL("ALTER TABLE profiles ADD COLUMN subtitleOffset INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE profiles ADD COLUMN subtitleTextColor INTEGER NOT NULL DEFAULT -1")
        db.execSQL("ALTER TABLE profiles ADD COLUMN subtitleBackgroundColor INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceSortingEnabled INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceEnabledQualities TEXT NOT NULL DEFAULT '4k,1080p,720p,unknown'")
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceExcludePhrases TEXT NOT NULL DEFAULT ''")
    }
}

private val MIGRATION_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceSortPrimary TEXT NOT NULL DEFAULT 'quality'")
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceSortSecondary TEXT NOT NULL DEFAULT 'size'")
    }
}

private val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceMaxSizeGb INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceExcludedFormats TEXT NOT NULL DEFAULT ''")
    }
}

private val MIGRATION_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN tmdbEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE profiles ADD COLUMN tmdbLanguage TEXT NOT NULL DEFAULT ''")
    }
}

private val MIGRATION_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN assRendererEnabled INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_37_38 = object : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS watchlist (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "type TEXT NOT NULL, " +
                "title TEXT NOT NULL, " +
                "poster TEXT, " +
                "addedAt INTEGER NOT NULL)"
        )
    }
}

private val MIGRATION_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN watchedThreshold INTEGER NOT NULL DEFAULT 85")
        db.execSQL("ALTER TABLE watch_history ADD COLUMN watched INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_39_40 = object : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE watch_history ADD COLUMN background TEXT")
        db.execSQL("ALTER TABLE watch_history ADD COLUMN logo TEXT")
    }
}

private val MIGRATION_40_41 = object : Migration(40, 41) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE watch_history ADD COLUMN scrobbled INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_41_42 = object : Migration(41, 42) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS series_next_up (" +
                "seriesId TEXT NOT NULL PRIMARY KEY, " +
                "title TEXT NOT NULL, " +
                "poster TEXT, " +
                "nextSeason INTEGER NOT NULL, " +
                "nextEpisode INTEGER NOT NULL, " +
                "nextEpisodeTitle TEXT, " +
                "nextReleased TEXT, " +
                "isComplete INTEGER NOT NULL DEFAULT 0, " +
                "updatedAt INTEGER NOT NULL)"
        )
    }
}

private val MIGRATION_42_43 = object : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE series_next_up ADD COLUMN isNewEpisode INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_43_44 = object : Migration(43, 44) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN pinHash TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS watchlist_new (" +
                "profileId INTEGER NOT NULL, " +
                "id TEXT NOT NULL, " +
                "type TEXT NOT NULL, " +
                "title TEXT NOT NULL, " +
                "poster TEXT, " +
                "addedAt INTEGER NOT NULL, " +
                "PRIMARY KEY(profileId, id))"
        )
        // Legacy watchlist entries had no owner. Preserve them on the oldest profile.
        db.execSQL(
            "INSERT INTO watchlist_new (profileId, id, type, title, poster, addedAt) " +
                "SELECT (SELECT MIN(id) FROM profiles), id, type, title, poster, addedAt " +
                "FROM watchlist WHERE EXISTS (SELECT 1 FROM profiles)"
        )
        db.execSQL("DROP TABLE watchlist")
        db.execSQL("ALTER TABLE watchlist_new RENAME TO watchlist")
    }
}

private val MIGRATION_44_45 = object : Migration(44, 45) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN isActive INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE profiles SET isActive = 1 WHERE id = (SELECT MIN(id) FROM profiles)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS watch_history_new (" +
                "profileId INTEGER NOT NULL, " +
                "id TEXT NOT NULL, title TEXT NOT NULL, poster TEXT, background TEXT, logo TEXT, " +
                "position INTEGER NOT NULL, duration INTEGER NOT NULL, lastWatched INTEGER NOT NULL, " +
                "type TEXT NOT NULL, watched INTEGER NOT NULL, scrobbled INTEGER NOT NULL, " +
                "PRIMARY KEY(profileId, id))"
        )
        db.execSQL(
            "INSERT INTO watch_history_new (profileId, id, title, poster, background, logo, position, duration, lastWatched, type, watched, scrobbled) " +
                "SELECT (SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), id, title, poster, background, logo, position, duration, lastWatched, type, watched, scrobbled " +
                "FROM watch_history WHERE EXISTS (SELECT 1 FROM profiles WHERE isActive = 1)"
        )
        db.execSQL("DROP TABLE watch_history")
        db.execSQL("ALTER TABLE watch_history_new RENAME TO watch_history")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS series_next_up_new (" +
                "profileId INTEGER NOT NULL, seriesId TEXT NOT NULL, title TEXT NOT NULL, poster TEXT, " +
                "nextSeason INTEGER NOT NULL, nextEpisode INTEGER NOT NULL, nextEpisodeTitle TEXT, nextReleased TEXT, " +
                "isComplete INTEGER NOT NULL, isNewEpisode INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                "PRIMARY KEY(profileId, seriesId))"
        )
        db.execSQL(
            "INSERT INTO series_next_up_new (profileId, seriesId, title, poster, nextSeason, nextEpisode, nextEpisodeTitle, nextReleased, isComplete, isNewEpisode, updatedAt) " +
                "SELECT (SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), seriesId, title, poster, nextSeason, nextEpisode, nextEpisodeTitle, nextReleased, isComplete, isNewEpisode, updatedAt " +
                "FROM series_next_up WHERE EXISTS (SELECT 1 FROM profiles WHERE isActive = 1)"
        )
        db.execSQL("DROP TABLE series_next_up")
        db.execSQL("ALTER TABLE series_next_up_new RENAME TO series_next_up")
    }
}

private val MIGRATION_45_46 = object : Migration(45, 46) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceSeasonPacksOnly INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_46_47 = object : Migration(46, 47) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceHideZeroSeeders INTEGER NOT NULL DEFAULT 0")
        // Older builds substituted the current position when a movie duration was not
        // available, which immediately marked that movie as completed. Restore those
        // exact-position records so they return to Continue Watching.
        db.execSQL(
            "UPDATE watch_history SET watched = 0, duration = 0 " +
                "WHERE type != 'series' AND watched = 1 AND position > 0 AND duration = position"
        )
    }
}

internal val MIGRATION_47_48 = object : Migration(47, 48) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN seekThumbnailsEnabled INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE profiles ADD COLUMN seekThumbnailIntervalSeconds INTEGER NOT NULL DEFAULT 10")
    }
}

internal val MIGRATION_48_49 = object : Migration(48, 49) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN seekTimeIntervalSeconds INTEGER NOT NULL DEFAULT 10")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SaabTvDatabase {
        return Room.databaseBuilder(
            context,
            SaabTvDatabase::class.java,
            AccountStorage.databaseName(context)
        )
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.execSQL("PRAGMA synchronous = 2")
                }
            })
            .addMigrations(MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38, MIGRATION_38_39, MIGRATION_39_40, MIGRATION_40_41, MIGRATION_41_42, MIGRATION_42_43, MIGRATION_43_44, MIGRATION_44_45, MIGRATION_45_46, MIGRATION_46_47, MIGRATION_47_48, MIGRATION_48_49)
            .addMigrations(MIGRATION_49_50)
            .addMigrations(MIGRATION_50_51)
            .addMigrations(MIGRATION_51_52)
            .addMigrations(MIGRATION_52_53)
            .addMigrations(MIGRATION_53_54)
            .addMigrations(MIGRATION_54_55)
            .addMigrations(MIGRATION_55_56)
            .addMigrations(MIGRATION_56_57)
            .addMigrations(MIGRATION_57_58)
            .addMigrations(MIGRATION_58_59)
            .addMigrations(MIGRATION_59_60)
            .addMigrations(MIGRATION_60_61)
            .addMigrations(MIGRATION_61_62)
            .addMigrations(MIGRATION_62_63)
            .addMigrations(MIGRATION_63_64)
            .addMigrations(MIGRATION_64_65)
            .addMigrations(MIGRATION_65_66)
            .build()
    }

    @Provides
    fun provideAddonDao(db: SaabTvDatabase): AddonDao {
        return db.addonDao()
    }

    @Provides
    fun provideCanonicalMetadataDao(db: SaabTvDatabase): CanonicalMetadataDao = db.canonicalMetadataDao()

    @Provides
    fun provideCanonicalTitleStore(repository: CanonicalMetadataRepository): CanonicalTitleStore = repository

    @Provides
    fun provideCanonicalSeriesMetadataStore(repository: CanonicalMetadataRepository): CanonicalSeriesMetadataStore = repository

    @Provides
    fun provideCanonicalIdentityStore(repository: CanonicalMetadataRepository): CanonicalIdentityStore = repository
}

internal val MIGRATION_54_55 = object : Migration(54, 55) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `canonical_titles` (
                `identityKey` TEXT NOT NULL,
                `locale` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `title` TEXT,
                `titleSource` TEXT,
                `titleLanguage` TEXT,
                `titleObservedAtMillis` INTEGER NOT NULL,
                `synopsis` TEXT,
                `synopsisSource` TEXT,
                `synopsisLanguage` TEXT,
                `synopsisObservedAtMillis` INTEGER NOT NULL,
                `poster` TEXT,
                `posterSource` TEXT,
                `posterObservedAtMillis` INTEGER NOT NULL,
                `backdrop` TEXT,
                `backdropSource` TEXT,
                `backdropObservedAtMillis` INTEGER NOT NULL,
                `logo` TEXT,
                `logoSource` TEXT,
                `logoLanguage` TEXT,
                `logoObservedAtMillis` INTEGER NOT NULL,
                `genresJson` TEXT,
                `genreIdsJson` TEXT,
                `releaseDate` TEXT,
                `releaseDatePrecision` TEXT,
                `ratingValue` REAL,
                `ratingSource` TEXT,
                `ratingScale` TEXT,
                `runtimeEstimateMillis` INTEGER,
                `status` TEXT,
                `countriesJson` TEXT,
                `originalLanguage` TEXT,
                `collectionId` INTEGER,
                `collectionName` TEXT,
                `lastFetchedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`identityKey`, `locale`)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_canonical_titles_kind` ON `canonical_titles` (`kind`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_canonical_titles_lastFetchedAtMillis` ON `canonical_titles` (`lastFetchedAtMillis`)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `canonical_identity_aliases` (
                `identityKey` TEXT NOT NULL,
                `namespace` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `providerInstallationId` TEXT,
                `externalId` TEXT NOT NULL,
                `canonicalIdentityKey` TEXT NOT NULL,
                `canonicalNamespace` TEXT NOT NULL,
                `canonicalKind` TEXT NOT NULL,
                `canonicalProviderInstallationId` TEXT,
                `canonicalExternalId` TEXT NOT NULL,
                `verifiedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`identityKey`)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_canonical_identity_aliases_canonicalIdentityKey_namespace` ON `canonical_identity_aliases` (`canonicalIdentityKey`, `namespace`)")
    }
}

/** Rebuildable Cinemeta episode cache only; portable account snapshot tables stay unchanged. */
internal val MIGRATION_55_56 = object : Migration(55, 56) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `canonical_episode_lists` (
                `seriesIdentityKey` TEXT NOT NULL,
                `locale` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                `episodeCount` INTEGER NOT NULL,
                `observedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`seriesIdentityKey`, `locale`)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `canonical_episodes` (
                `seriesIdentityKey` TEXT NOT NULL,
                `locale` TEXT NOT NULL,
                `episodeKey` TEXT NOT NULL,
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `released` TEXT,
                `thumbnail` TEXT,
                `overview` TEXT,
                `season` INTEGER NOT NULL,
                `episode` INTEGER NOT NULL,
                `episodeOrder` INTEGER NOT NULL,
                `observedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`seriesIdentityKey`, `locale`, `episodeKey`)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_canonical_episodes_observedAtMillis` ON `canonical_episodes` (`observedAtMillis`)")
    }
}

/** Portable profile identities and local-only durable Sync v3 command journal. */
internal val MIGRATION_56_57 = object : Migration(56, 57) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN portableId TEXT NOT NULL DEFAULT ''")
        // Deterministic legacy IDs let separate upgraded copies converge when their profile row
        // IDs came from the same v1/v2 snapshot. New profiles receive UUIDs in ProfileEntity.
        db.execSQL("UPDATE profiles SET portableId = 'legacy-profile-' || id WHERE portableId = ''")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_profiles_portableId` ON `profiles` (`portableId`)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `sync_outbox` (
                `operationId` TEXT NOT NULL,
                `portableProfileId` TEXT NOT NULL,
                `entityKey` TEXT NOT NULL,
                `entityType` TEXT NOT NULL,
                `operation` TEXT NOT NULL,
                `entityRevision` INTEGER NOT NULL,
                `deviceId` TEXT NOT NULL,
                `sequence` INTEGER NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                `payloadJson` TEXT NOT NULL,
                PRIMARY KEY(`operationId`)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_sequence` ON `sync_outbox` (`sequence`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_portableProfileId_sequence` ON `sync_outbox` (`portableProfileId`, `sequence`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_entityKey_entityRevision` ON `sync_outbox` (`entityKey`, `entityRevision`)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `sync_entity_revisions` (
                `entityKey` TEXT NOT NULL,
                `revision` INTEGER NOT NULL,
                PRIMARY KEY(`entityKey`)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `sync_client_state` (
                `id` INTEGER NOT NULL,
                `deviceId` TEXT NOT NULL,
                `nextSequence` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
    }
}

/** The per-account pull cursor is committed with each remote page application. */
internal val MIGRATION_57_58 = object : Migration(57, 58) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sync_client_state ADD COLUMN serverCursor INTEGER NOT NULL DEFAULT 0")
    }
}

/** Preserve addon ownership and permit same opaque ID from independent installations. */
internal val MIGRATION_58_59 = object : Migration(58, 59) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `watchlist_v59` (
                `profileId` INTEGER NOT NULL,
                `id` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `poster` TEXT,
                `addedAt` INTEGER NOT NULL,
                `background` TEXT,
                `logo` TEXT,
                `sourceAddonBaseUrl` TEXT,
                `sourceAddonKey` TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(`profileId`, `id`, `sourceAddonKey`)
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO `watchlist_v59` (`profileId`, `id`, `type`, `title`, `poster`, `addedAt`, `background`, `logo`, `sourceAddonBaseUrl`, `sourceAddonKey`)
            SELECT `profileId`, `id`, `type`, `title`, `poster`, `addedAt`, `background`, `logo`, NULL, '' FROM `watchlist`
        """.trimIndent())
        db.execSQL("DROP TABLE `watchlist`")
        db.execSQL("ALTER TABLE `watchlist_v59` RENAME TO `watchlist`")
    }
}

/** Never send an offline journal row to whichever account happens to be signed in later. */
internal val MIGRATION_59_60 = object : Migration(59, 60) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sync_outbox ADD COLUMN accountUserId TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_accountUserId_sequence ON sync_outbox(accountUserId, sequence)")
    }
}

/** Adds field-level provenance for canonical scalar facts without discarding the existing cache. */
internal val MIGRATION_60_61 = object : Migration(60, 61) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE canonical_titles ADD COLUMN genresSource TEXT")
        db.execSQL("ALTER TABLE canonical_titles ADD COLUMN genresObservedAtMillis INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE canonical_titles ADD COLUMN releaseDateSource TEXT")
        db.execSQL("ALTER TABLE canonical_titles ADD COLUMN releaseDateObservedAtMillis INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE canonical_titles ADD COLUMN ratingObservedAtMillis INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE canonical_titles ADD COLUMN runtimeSource TEXT")
        db.execSQL("ALTER TABLE canonical_titles ADD COLUMN runtimeObservedAtMillis INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE canonical_titles ADD COLUMN statusSource TEXT")
        db.execSQL("ALTER TABLE canonical_titles ADD COLUMN statusObservedAtMillis INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE canonical_titles ADD COLUMN countriesSource TEXT")
        db.execSQL("ALTER TABLE canonical_titles ADD COLUMN countriesObservedAtMillis INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE canonical_titles ADD COLUMN originalLanguageSource TEXT")
        db.execSQL("ALTER TABLE canonical_titles ADD COLUMN originalLanguageObservedAtMillis INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE canonical_titles ADD COLUMN collectionSource TEXT")
        db.execSQL("ALTER TABLE canonical_titles ADD COLUMN collectionObservedAtMillis INTEGER NOT NULL DEFAULT 0")
        // Previous versions stored already-merged values but did not retain each scalar's
        // origin. Keep those values, mark their provenance as legacy/lowest-priority, and
        // never mislabel them as TMDB or Cinemeta during projection.
        db.execSQL("""
            UPDATE canonical_titles SET
                genresSource = CASE WHEN genresJson IS NOT NULL THEN 'LEGACY' END,
                genresObservedAtMillis = CASE WHEN genresJson IS NOT NULL THEN lastFetchedAtMillis ELSE 0 END,
                releaseDateSource = CASE WHEN releaseDate IS NOT NULL THEN 'LEGACY' END,
                releaseDateObservedAtMillis = CASE WHEN releaseDate IS NOT NULL THEN lastFetchedAtMillis ELSE 0 END,
                ratingObservedAtMillis = CASE WHEN ratingValue IS NOT NULL THEN lastFetchedAtMillis ELSE 0 END,
                runtimeSource = CASE WHEN runtimeEstimateMillis IS NOT NULL THEN 'LEGACY' END,
                runtimeObservedAtMillis = CASE WHEN runtimeEstimateMillis IS NOT NULL THEN lastFetchedAtMillis ELSE 0 END,
                statusSource = CASE WHEN status IS NOT NULL THEN 'LEGACY' END,
                statusObservedAtMillis = CASE WHEN status IS NOT NULL THEN lastFetchedAtMillis ELSE 0 END,
                countriesSource = CASE WHEN countriesJson IS NOT NULL THEN 'LEGACY' END,
                countriesObservedAtMillis = CASE WHEN countriesJson IS NOT NULL THEN lastFetchedAtMillis ELSE 0 END,
                originalLanguageSource = CASE WHEN originalLanguage IS NOT NULL THEN 'LEGACY' END,
                originalLanguageObservedAtMillis = CASE WHEN originalLanguage IS NOT NULL THEN lastFetchedAtMillis ELSE 0 END,
                collectionSource = CASE WHEN collectionId IS NOT NULL THEN 'LEGACY' END,
                collectionObservedAtMillis = CASE WHEN collectionId IS NOT NULL THEN lastFetchedAtMillis ELSE 0 END
        """.trimIndent())
    }
}

/** Add canonical secondary identities without changing provider-ID primary keys or losing rows. */
internal val MIGRATION_61_62 = object : Migration(61, 62) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE watch_history ADD COLUMN canonicalIdentityKey TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE watchlist ADD COLUMN canonicalIdentityKey TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE series_next_up ADD COLUMN canonicalIdentityKey TEXT NOT NULL DEFAULT ''")

        db.query("SELECT profileId, id, type FROM watch_history").use { cursor ->
            val rows = buildList {
                while (cursor.moveToNext()) add(Triple(cursor.getInt(0), cursor.getString(1), cursor.getString(2)))
            }
            rows.forEach { (profileId, id, type) ->
                db.execSQL(
                    "UPDATE watch_history SET canonicalIdentityKey = ? WHERE profileId = ? AND id = ?",
                    arrayOf(CanonicalEntityKey.playable(id, type), profileId, id)
                )
            }
        }
        db.query("SELECT profileId, id, type, sourceAddonKey FROM watchlist").use { cursor ->
            val rows = buildList {
                while (cursor.moveToNext()) add(Quadruple(cursor.getInt(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)))
            }
            rows.forEach { (profileId, id, type, addonKey) ->
                db.execSQL(
                    "UPDATE watchlist SET canonicalIdentityKey = ? WHERE profileId = ? AND id = ? AND sourceAddonKey = ?",
                    arrayOf(CanonicalEntityKey.title(id, type, addonKey), profileId, id, addonKey)
                )
            }
        }
        db.query("SELECT profileId, seriesId FROM series_next_up").use { cursor ->
            val rows = buildList {
                while (cursor.moveToNext()) add(Pair(cursor.getInt(0), cursor.getString(1)))
            }
            rows.forEach { (profileId, id) ->
                db.execSQL(
                    "UPDATE series_next_up SET canonicalIdentityKey = ? WHERE profileId = ? AND seriesId = ?",
                    arrayOf(CanonicalEntityKey.title(id, "series"), profileId, id)
                )
            }
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS index_watch_history_profileId_canonicalIdentityKey ON watch_history(profileId, canonicalIdentityKey)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_watchlist_profileId_canonicalIdentityKey ON watchlist(profileId, canonicalIdentityKey)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_series_next_up_profileId_canonicalIdentityKey ON series_next_up(profileId, canonicalIdentityKey)")
    }
}

/** Keep retired duplicate profile columns canonical for old snapshot/client compatibility. */
internal val MIGRATION_62_63 = object : Migration(62, 63) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE profiles SET seekThumbnailIntervalSeconds = seekTimeIntervalSeconds")
        db.execSQL("""
            UPDATE profiles
            SET outroSkipCountdownSeconds = CASE
                WHEN autoSkipIntro = 0 THEN 0
                WHEN introSkipCountdownSeconds = 10 THEN 10
                ELSE 5
            END
        """.trimIndent())
    }
}

/** Give addon/catalog/dashboard configuration durable profile ownership instead of swapping one global set. */
internal val MIGRATION_63_64 = object : Migration(63, 64) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val owner = "COALESCE((SELECT portableId FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT portableId FROM profiles ORDER BY id LIMIT 1), 'legacy-default')"

        db.execSQL("CREATE TABLE IF NOT EXISTS addons_new (`transportUrl` TEXT NOT NULL, `id` TEXT NOT NULL, `name` TEXT NOT NULL, `version` TEXT NOT NULL, `description` TEXT, `iconUrl` TEXT, `isTrusted` INTEGER NOT NULL, `isEnabled` INTEGER NOT NULL, `nickname` TEXT, `catalogsJson` TEXT NOT NULL, `supportsMeta` INTEGER NOT NULL, `supportsStream` INTEGER NOT NULL, `typesJson` TEXT NOT NULL, `idPrefixesJson` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, `profileOwnerId` TEXT NOT NULL, PRIMARY KEY(`profileOwnerId`, `transportUrl`))")
        db.execSQL("INSERT INTO addons_new SELECT transportUrl, id, name, version, description, iconUrl, isTrusted, isEnabled, nickname, catalogsJson, supportsMeta, supportsStream, typesJson, idPrefixesJson, sortOrder, $owner FROM addons")
        db.execSQL("DROP TABLE addons")
        db.execSQL("ALTER TABLE addons_new RENAME TO addons")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_addons_profileOwnerId ON addons(profileOwnerId)")

        db.execSQL("CREATE TABLE IF NOT EXISTS catalog_configs_new (`uniqueId` TEXT NOT NULL, `transportUrl` TEXT NOT NULL, `addonName` TEXT NOT NULL, `catalogType` TEXT NOT NULL, `catalogId` TEXT NOT NULL, `catalogName` TEXT, `customTitle` TEXT, `showInHome` INTEGER NOT NULL, `showInMovies` INTEGER NOT NULL, `showInSeries` INTEGER NOT NULL, `homeOrder` INTEGER NOT NULL, `moviesOrder` INTEGER NOT NULL, `seriesOrder` INTEGER NOT NULL, `isInfiniteLoopEnabled` INTEGER NOT NULL, `visibleItemCount` INTEGER NOT NULL, `isInfiniteScrollingEnabled` INTEGER NOT NULL, `profileOwnerId` TEXT NOT NULL, PRIMARY KEY(`profileOwnerId`, `uniqueId`))")
        db.execSQL("INSERT INTO catalog_configs_new SELECT uniqueId, transportUrl, addonName, catalogType, catalogId, catalogName, customTitle, showInHome, showInMovies, showInSeries, homeOrder, moviesOrder, seriesOrder, isInfiniteLoopEnabled, visibleItemCount, isInfiniteScrollingEnabled, $owner FROM catalog_configs")
        db.execSQL("DROP TABLE catalog_configs")
        db.execSQL("ALTER TABLE catalog_configs_new RENAME TO catalog_configs")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_catalog_configs_profileOwnerId ON catalog_configs(profileOwnerId)")

        db.execSQL("CREATE TABLE IF NOT EXISTS hub_rows_new (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `shape` TEXT NOT NULL, `showInHome` INTEGER NOT NULL, `showInMovies` INTEGER NOT NULL, `showInSeries` INTEGER NOT NULL, `homeOrder` INTEGER NOT NULL, `moviesOrder` INTEGER NOT NULL, `seriesOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `profileOwnerId` TEXT NOT NULL, PRIMARY KEY(`profileOwnerId`, `id`))")
        db.execSQL("INSERT INTO hub_rows_new SELECT id, title, shape, showInHome, showInMovies, showInSeries, homeOrder, moviesOrder, seriesOrder, createdAt, $owner FROM hub_rows")
        db.execSQL("DROP TABLE hub_rows")
        db.execSQL("ALTER TABLE hub_rows_new RENAME TO hub_rows")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_hub_rows_profileOwnerId ON hub_rows(profileOwnerId)")

        db.execSQL("CREATE TABLE IF NOT EXISTS hub_row_items_new (`hubRowId` TEXT NOT NULL, `configUniqueId` TEXT NOT NULL, `title` TEXT NOT NULL, `customImageUrl` TEXT, `itemOrder` INTEGER NOT NULL, `profileOwnerId` TEXT NOT NULL, PRIMARY KEY(`profileOwnerId`, `hubRowId`, `configUniqueId`))")
        db.execSQL("INSERT INTO hub_row_items_new SELECT hubRowId, configUniqueId, title, customImageUrl, itemOrder, $owner FROM hub_row_items")
        db.execSQL("DROP TABLE hub_row_items")
        db.execSQL("ALTER TABLE hub_row_items_new RENAME TO hub_row_items")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_hub_row_items_profileOwnerId_hubRowId ON hub_row_items(profileOwnerId, hubRowId)")
    }
}

/** Persist bounded full-detail facts without changing the provider-compatible card cache. */
internal val MIGRATION_64_65 = object : Migration(64, 65) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE canonical_titles ADD COLUMN ageRating TEXT")
        db.execSQL("ALTER TABLE canonical_titles ADD COLUMN ageRatingRegion TEXT")
        db.execSQL("ALTER TABLE canonical_titles ADD COLUMN ageRatingSource TEXT")
        db.execSQL("ALTER TABLE canonical_titles ADD COLUMN ageRatingObservedAtMillis INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE canonical_titles ADD COLUMN creditsJson TEXT")
        db.execSQL("ALTER TABLE canonical_titles ADD COLUMN creditsSource TEXT")
        db.execSQL("ALTER TABLE canonical_titles ADD COLUMN creditsObservedAtMillis INTEGER NOT NULL DEFAULT 0")
    }
}

/** Store visibility/order for personalized TMDB Home catalogs per profile. */
internal val MIGRATION_65_66 = object : Migration(65, 66) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE profiles ADD COLUMN homeTmdbRails TEXT NOT NULL " +
                "DEFAULT 'tmdb-top-suggestions,tmdb-favorite-genres,tmdb-new-for-you,tmdb-popular-in-taste'"
        )
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

internal val MIGRATION_49_50 = object : Migration(49, 50) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN autoSkipIntro INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE profiles ADD COLUMN introSkipCountdownSeconds INTEGER NOT NULL DEFAULT 5")
        db.execSQL("ALTER TABLE profiles ADD COLUMN outroSkipCountdownSeconds INTEGER NOT NULL DEFAULT 5")
    }
}

internal val MIGRATION_50_51 = object : Migration(50, 51) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceLanguagePriority1 TEXT NOT NULL DEFAULT 'en'")
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceLanguagePriority2 TEXT NOT NULL DEFAULT 'te'")
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceLanguagePriority3 TEXT NOT NULL DEFAULT 'hi'")
        // The seek step and preview cadence are now one setting. Preserve the
        // user's seek value as the authoritative value during migration.
        db.execSQL("UPDATE profiles SET seekThumbnailIntervalSeconds = seekTimeIntervalSeconds")
    }
}

internal val MIGRATION_51_52 = object : Migration(51, 52) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Preserve the previous shared IntroDB behavior for upgraded profiles.
        db.execSQL("ALTER TABLE profiles ADD COLUMN skipRecap INTEGER NOT NULL DEFAULT 1")
    }
}

internal val MIGRATION_52_53 = object : Migration(52, 53) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN titleCardShape TEXT NOT NULL DEFAULT 'poster'")
        db.execSQL("UPDATE profiles SET titleCardShape = continueWatchingShape")
    }
}

internal val MIGRATION_53_54 = object : Migration(53, 54) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE watchlist ADD COLUMN background TEXT")
        db.execSQL("ALTER TABLE watchlist ADD COLUMN logo TEXT")
    }
}
