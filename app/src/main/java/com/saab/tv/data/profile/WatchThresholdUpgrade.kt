package com.saab.tv.data.profile

import android.content.Context
import com.saab.tv.di.DatabaseModule

/** Move the old default to 95 once; leave deliberately customized values alone. */
object WatchThresholdUpgrade {
    fun apply(context: Context, force: Boolean = false) {
        val prefs = EpisodeSpoilerPreferences.preferences(context)
        if (!force && prefs.getBoolean("watch_threshold_95_migrated", false)) return
        val db = DatabaseModule.provideDatabase(context)
        try { db.openHelper.writableDatabase.execSQL("UPDATE profiles SET watchedThreshold = 95 WHERE watchedThreshold = 85") }
        finally { db.close() }
        check(prefs.edit().putBoolean("watch_threshold_95_migrated", true).commit())
    }
}
