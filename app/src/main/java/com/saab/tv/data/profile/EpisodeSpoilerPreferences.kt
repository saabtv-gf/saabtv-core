package com.saab.tv.data.profile

import android.content.Context
import com.saab.tv.data.account.AccountStorage

/** Profile-owned preference uses an existing synced store; no database schema change. */
object EpisodeSpoilerPreferences {
    fun preferences(context: Context) = AccountStorage.preferences(context, "profile_configuration_prefs")
    fun enabled(context: Context, profileId: Int) = preferences(context).getBoolean("hide_episode_spoilers_$profileId", false)
    fun set(context: Context, profileId: Int, enabled: Boolean) {
        preferences(context).edit().putBoolean("hide_episode_spoilers_$profileId", enabled).apply()
    }
    fun copy(context: Context, source: Int, target: Int) = set(context, target, enabled(context, source))
}

internal fun shouldHideEpisodeSpoilers(enabled: Boolean, watched: Boolean): Boolean = enabled && !watched
