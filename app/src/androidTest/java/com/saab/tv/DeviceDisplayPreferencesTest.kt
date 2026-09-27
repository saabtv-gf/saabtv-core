package com.saab.tv

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.profile.DeviceDisplayPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceDisplayPreferencesTest {
    @Test fun twoDevicesKeepIndependentChoicesAndProfileOverrides() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val stores = mutableListOf<SharedPreferences>()
        fun device(): Context {
            val prefix = "display-test-${java.util.UUID.randomUUID()}-"
            return object : ContextWrapper(base) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    base.getSharedPreferences(prefix + name, mode).also { if (it !in stores) stores.add(it) }
            }
        }
        try {
            val hdContext = device()
            val hd = DeviceDisplayPreferences(hdContext)
            val fourK = DeviceDisplayPreferences(device())
            assertFalse(hd.isConfigured())
            hd.configure(false); fourK.configure(true)
            val profile = ProfileEntity(id = 7, name = "Shared")
            assertFalse(hd.effective(profile).sourceEnabledQualities.contains("4k"))
            assertTrue(fourK.effective(profile).sourceEnabledQualities.contains("4k"))
            hd.setQualities(7, "4k,720p")
            assertEquals("720p", hd.effective(profile).sourceEnabledQualities)
            assertTrue(fourK.effective(profile).tunnelingEnabled)
            assertTrue(DeviceDisplayPreferences(hdContext).isConfigured())
            assertFalse(DeviceDisplayPreferences(hdContext).is4k())
        } finally { stores.forEach { it.edit().clear().commit() } }
    }
}
