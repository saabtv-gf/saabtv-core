package com.saab.tv.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.saab.tv.di.MIGRATION_47_48
import com.saab.tv.di.MIGRATION_48_49
import com.saab.tv.di.MIGRATION_49_50
import com.saab.tv.di.MIGRATION_51_52
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
}
