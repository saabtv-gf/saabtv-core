package com.saab.tv.data.profile

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.saab.tv.data.model.ProfileEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DeviceDisplayStorageTest {
    private fun device(): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        private val prefix = UUID.randomUUID().toString()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
    }
    @Test fun hdAnd4kDevicesHaveIndependentHardwareDefaults() {
        val hd = DeviceDisplayPreferences(device()); val uhd = DeviceDisplayPreferences(device())
        assertFalse(hd.isConfigured()); assertTrue(hd.is4k())
        hd.configure(false); uhd.configure(true)
        val profile = ProfileEntity(id = 1, name = "Same account")
        assertFalse(hd.effective(profile).tunnelingEnabled); assertTrue(uhd.effective(profile).tunnelingEnabled)
        assertFalse("4k" in hd.effective(profile).sourceEnabledQualities.split(','))
        assertTrue("4k" in uhd.effective(profile).sourceEnabledQualities.split(','))
        assertEquals("dv,hdr,dts,dolby,hevc,av1,3d", hd.effective(profile).sourceExcludedFormats)
        assertEquals("3d", uhd.effective(profile).sourceExcludedFormats)
    }
    @Test fun profileOverridesPersistAndCannotEnable4kOnHdDevice() {
        val context = device(); val settings = DeviceDisplayPreferences(context)
        settings.configure(false); settings.setTunneling(7, true)
        settings.setQualities(7, "4k,720p"); settings.setFormats(7, "dv")
        val profile = DeviceDisplayPreferences(context).effective(ProfileEntity(id = 7, name = "One"))
        assertTrue(profile.tunnelingEnabled); assertEquals("720p", profile.sourceEnabledQualities)
        assertEquals("dv", profile.sourceExcludedFormats); assertEquals(4L, settings.revision.value)
    }
    @Test fun copyProfileCopiesOverridesButNotDeviceCapability() {
        val settings = DeviceDisplayPreferences(device()); settings.configure(true)
        settings.setTunneling(1, false); settings.setQualities(1, "1080p"); settings.setFormats(1, "hdr")
        settings.copyProfile(1, 2)
        val copied = settings.effective(ProfileEntity(id = 2, name = "Target"))
        assertFalse(copied.tunnelingEnabled); assertEquals("1080p", copied.sourceEnabledQualities)
        assertEquals("hdr", copied.sourceExcludedFormats); assertTrue(settings.is4k())
    }
    @Test fun copyingMissingOverridesRemovesStaleTargetOverrides() {
        val settings = DeviceDisplayPreferences(device()); settings.configure(true)
        settings.setTunneling(2, false); settings.setQualities(2, "720p"); settings.setFormats(2, "hdr")
        settings.copyProfile(1, 2)
        val profile = ProfileEntity(id = 2, name = "Target")
        assertEquals(profile.forDeviceDisplay(true), settings.effective(profile))
    }
    @Test fun reconfigurationClearsPreviousHardwareOverrides() {
        val settings = DeviceDisplayPreferences(device()); settings.configure(false)
        settings.setFormats(1, "custom"); settings.configure(true)
        assertEquals("3d", settings.effective(ProfileEntity(id = 1, name = "One")).sourceExcludedFormats)
        assertTrue(settings.isConfigured())
    }
}
