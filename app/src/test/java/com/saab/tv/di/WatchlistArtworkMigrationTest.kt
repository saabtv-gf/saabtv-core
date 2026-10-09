package com.saab.tv.di

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WatchlistArtworkMigrationTest {
    @Test fun migrationAddsOptionalLandscapeArtworkAndPreservesExistingRows() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
            .name("watchlist-artwork-migration-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(53) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE watchlist (profileId INTEGER NOT NULL, id TEXT NOT NULL, " +
                            "type TEXT NOT NULL, title TEXT NOT NULL, poster TEXT, addedAt INTEGER NOT NULL, " +
                            "PRIMARY KEY(profileId, id))"
                    )
                    db.execSQL("INSERT INTO watchlist VALUES (1, 'tt-old', 'movie', 'Old title', 'poster', 9)")
                }

                override fun onUpgrade(
                    db: androidx.sqlite.db.SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        try {
            val db = helper.writableDatabase
            MIGRATION_53_54.migrate(db)

            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(`watchlist`)").use { cursor ->
                while (cursor.moveToNext()) columns += cursor.getString(1)
            }
            assertTrue(columns.containsAll(setOf("background", "logo")))
            db.query("SELECT title, poster, background, logo FROM watchlist WHERE id = 'tt-old'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Old title", cursor.getString(0))
                assertEquals("poster", cursor.getString(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.isNull(3))
            }
        } finally {
            helper.close()
        }
    }
}
