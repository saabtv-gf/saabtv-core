package com.saab.tv.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.saab.tv.data.local.AddonDao
import com.saab.tv.data.local.SaabTvDatabase
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
            "saabtv_db"
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
            .build()
    }

    @Provides
    fun provideAddonDao(db: SaabTvDatabase): AddonDao {
        return db.addonDao()
    }
}

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
