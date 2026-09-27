package com.saab.tv.data.profile

import android.content.Context
import com.saab.tv.data.account.AccountStorage
import com.saab.tv.data.model.ProfileEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Deliberately excluded from account snapshots: hardware preferences belong to this device. */
@Singleton
class DeviceDisplayPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private val changes = MutableStateFlow(0L)
    val revision: StateFlow<Long> = changes
    private fun prefs() = AccountStorage.preferences(context, "device_display_settings")
    fun isConfigured(): Boolean = prefs().contains("is_4k")
    fun is4k(): Boolean = prefs().getBoolean("is_4k", true)
    fun configure(is4k: Boolean) {
        check(prefs().edit().clear().putBoolean("is_4k", is4k).commit())
        changes.update { it + 1 }
    }
    fun effective(profile: ProfileEntity): ProfileEntity {
        val p = prefs()
        val defaults = profile.forDeviceDisplay(is4k())
        return defaults.copy(
            tunnelingEnabled = p.getBoolean("tunnel_${profile.id}", defaults.tunnelingEnabled),
            sourceEnabledQualities = p.getString("qualities_${profile.id}", defaults.sourceEnabledQualities)!!,
            sourceExcludedFormats = p.getString("formats_${profile.id}", defaults.sourceExcludedFormats)!!
        ).let { if (is4k()) it else it.copy(sourceEnabledQualities = it.sourceEnabledQualities.split(",").filterNot { q -> q == "4k" }.joinToString(",")) }
    }
    fun setTunneling(id: Int, enabled: Boolean) { prefs().edit().putBoolean("tunnel_$id", enabled).apply(); changes.update { it + 1 } }
    fun setQualities(id: Int, values: String) { prefs().edit().putString("qualities_$id", values).apply(); changes.update { it + 1 } }
    fun setFormats(id: Int, values: String) { prefs().edit().putString("formats_$id", values).apply(); changes.update { it + 1 } }
    fun copyProfile(source: Int, target: Int) {
        val p = prefs(); val edit = p.edit().remove("tunnel_$target").remove("qualities_$target").remove("formats_$target")
        if (p.contains("tunnel_$source")) edit.putBoolean("tunnel_$target", p.getBoolean("tunnel_$source", false))
        for (key in listOf("qualities", "formats")) p.getString("${key}_$source", null)?.let { edit.putString("${key}_$target", it) }
        edit.apply(); changes.update { it + 1 }
    }
}

internal fun ProfileEntity.forDeviceDisplay(is4k: Boolean): ProfileEntity = copy(
    tunnelingEnabled = is4k,
    sourceEnabledQualities = if (is4k) "4k,1080p,720p,unknown" else "1080p,720p,unknown",
    sourceExcludedFormats = if (is4k) "3d" else "dv,hdr,dts,dolby,hevc,av1,3d"
)
