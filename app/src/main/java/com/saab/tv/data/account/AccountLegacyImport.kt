package com.saab.tv.data.account

import android.content.Context
import com.saab.tv.data.security.SecurePreferences
import java.io.File

/** Explicit, one-time claim; original local files remain recoverable. */
object AccountLegacyImport {
    fun isAvailable(context: Context): Boolean = context.getDatabasePath("saabtv_db").isFile &&
        context.getSharedPreferences("saabtv_legacy_import", Context.MODE_PRIVATE).getString("owner", null) == null

    fun import(context: Context) {
        check(isAvailable(context))
        val owner = requireNotNull(AccountStorage.userId(context))
        val target = context.getDatabasePath(AccountStorage.databaseName(context))
        // The account gate closes its temporary Room instance before import.
        // Only an empty, never-synced account can reach this action.
        target.parentFile?.mkdirs()
        for (suffix in listOf("", "-wal", "-shm")) {
            val source = context.getDatabasePath("saabtv_db$suffix")
            val destination = File(target.path + suffix)
            if (source.isFile) source.copyTo(destination, overwrite = true)
            else if (suffix.isNotEmpty() && destination.exists()) check(destination.delete())
        }
        for (name in listOf("profile_configuration_prefs", "source_selection_prefs",
            "playback_track_selection_prefs", "playback_diagnostics_settings")) {
            copyPreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE), AccountStorage.preferences(context, name))
        }
        for ((name, alias) in mapOf("stremio_secure_prefs" to "saabtv_stremio_master_key",
            "trakt_auth" to "saabtv_trakt_master_key", "tmdb_credentials" to "saabtv_tmdb_master_key")) {
            copyPreferences(SecurePreferences.create(context, name, alias, accountScoped = false).preferences,
                SecurePreferences.create(context, name, alias).preferences)
        }
        for (directory in listOf("profile_snapshots", "avatars")) {
            File(context.filesDir, directory).listFiles()?.filter { it.isFile }?.forEach {
                val destination = File(AccountStorage.files(context), "$directory/${it.name}")
                destination.parentFile?.mkdirs()
                it.copyTo(destination, overwrite = true)
            }
        }
        context.filesDir.listFiles()?.filter { it.isFile && it.name.matches(Regex("hub_item_[a-zA-Z0-9_-]+\\.jpg")) }?.forEach {
            val destination = File(AccountStorage.files(context), "hub_images/${it.name}")
            destination.parentFile?.mkdirs()
            it.copyTo(destination, overwrite = true)
        }
        check(context.getSharedPreferences("saabtv_legacy_import", Context.MODE_PRIVATE)
            .edit().putString("owner", owner).commit())
    }

    private fun copyPreferences(source: android.content.SharedPreferences, destination: android.content.SharedPreferences) {
        val editor = destination.edit().clear()
        source.all.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        check(editor.commit())
    }
}
