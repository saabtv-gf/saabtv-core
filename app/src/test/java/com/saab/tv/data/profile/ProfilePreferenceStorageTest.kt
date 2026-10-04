package com.saab.tv.data.profile

import android.app.Application
import com.saab.tv.data.account.AccountStorage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ProfilePreferenceStorageTest {
    @Test fun trailerDefaultsAndExplicitSettingsRoundTrip() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(TrailerPreviewSettings(), TrailerPreviewPreferences.read(context, 1))
        assertFalse(TrailerPreviewPreferences.read(context, 1).muted)
        val settings = TrailerPreviewSettings(false, 15, false, "fullscreen")
        TrailerPreviewPreferences.set(context, 1, settings)
        assertEquals(settings, TrailerPreviewPreferences.read(context, 1))
        TrailerPreviewPreferences.set(context, 1, settings.copy(muted = true))
        assertTrue(TrailerPreviewPreferences.read(context, 1).muted)
        assertEquals(TrailerPreviewSettings(), TrailerPreviewPreferences.read(context, 2))
    }
    @Test fun invalidDelayAndPresentationFallBackToSafeDefaults() {
        val context = RuntimeEnvironment.getApplication()
        TrailerPreviewPreferences.set(context, 1, TrailerPreviewSettings(delaySeconds = 999, presentation = "broken"))
        assertEquals(TrailerPreviewSettings(), TrailerPreviewPreferences.read(context, 1))
        listOf(3, 5, 10, 15).forEach { delay ->
            TrailerPreviewPreferences.set(context, 1, TrailerPreviewSettings(delaySeconds = delay))
            assertEquals(delay, TrailerPreviewPreferences.read(context, 1).delaySeconds)
        }
    }
    @Test fun copyingProfileIncludesTrailerAndSpoilerPreferences() {
        val context = RuntimeEnvironment.getApplication()
        val settings = TrailerPreviewSettings(delaySeconds = 10, muted = false)
        TrailerPreviewPreferences.set(context, 1, settings); TrailerPreviewPreferences.copy(context, 1, 2)
        EpisodeSpoilerPreferences.set(context, 1, true); EpisodeSpoilerPreferences.copy(context, 1, 2)
        assertEquals(settings, TrailerPreviewPreferences.read(context, 2)); assertTrue(EpisodeSpoilerPreferences.enabled(context, 2))
        EpisodeSpoilerPreferences.copy(context, 3, 2); assertFalse(EpisodeSpoilerPreferences.enabled(context, 2))
    }
    @Test fun preferenceStateDoesNotLeakBetweenAccounts() {
        val context = RuntimeEnvironment.getApplication()
        AccountStorage.setUserId(context, "first")
        EpisodeSpoilerPreferences.set(context, 1, true)
        TrailerPreviewPreferences.set(context, 1, TrailerPreviewSettings(enabled = false))
        val firstDatabase = AccountStorage.databaseName(context)
        val firstFiles = AccountStorage.files(context)
        AccountStorage.setUserId(context, "second")
        assertFalse(EpisodeSpoilerPreferences.enabled(context, 1)); assertTrue(TrailerPreviewPreferences.read(context, 1).enabled)
        assertNotEquals(firstDatabase, AccountStorage.databaseName(context)); assertNotEquals(firstFiles, AccountStorage.files(context))
        AccountStorage.setUserId(context, "first"); assertTrue(EpisodeSpoilerPreferences.enabled(context, 1))
    }
    @Test fun signedOutIdentityUsesSeparateStorageAndBlankUserIsNotAnAccount() {
        val context = RuntimeEnvironment.getApplication()
        assertNull(AccountStorage.userId(context)); assertEquals("signed_out", AccountStorage.scope(context))
        AccountStorage.setUserId(context, " "); assertNull(AccountStorage.userId(context))
        AccountStorage.setUserId(context, "user"); assertEquals("user", AccountStorage.userId(context))
        assertEquals(AccountStorage.scopeFor("user"), AccountStorage.scope(context))
        AccountStorage.setUserId(context, null); assertNull(AccountStorage.userId(context))
        assertEquals("signed_out", AccountStorage.scope(context))
    }
}
