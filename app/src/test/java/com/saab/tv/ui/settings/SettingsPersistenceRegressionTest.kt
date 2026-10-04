package com.saab.tv.ui.settings

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.saab.tv.data.auth.StremioAuthManager
import com.saab.tv.data.cache.SeekThumbnailCache
import com.saab.tv.data.local.SaabTvDatabase
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.stremio.*
import com.saab.tv.data.profile.DeviceDisplayPreferences
import com.saab.tv.data.profile.ProfileConfigurationManager
import com.saab.tv.data.remote.StremioApiService
import com.saab.tv.data.remote.StremioAuthService
import com.saab.tv.data.repository.AddonRepository
import com.saab.tv.data.stream.TorBoxAvailabilityService
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SettingsPersistenceRegressionTest {
    private lateinit var db: SaabTvDatabase
    private lateinit var vm: SettingsViewModel
    private var expected = ProfileEntity(id = 1, name = "One")
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, SaabTvDatabase::class.java).allowMainThreadQueries().build()
        db.addonDao().insertProfile(expected)
        val api = object : StremioApiService {
            override suspend fun getManifest(url: String): Manifest = error("No network allowed")
            override suspend fun getCatalog(url: String): CatalogResponse = error("No network allowed")
            override suspend fun getMeta(url: String): MetaResponse = error("No network allowed")
            override suspend fun getStreams(url: String): StreamResponse = error("No network allowed")
            override suspend fun getSubtitles(url: String): SubtitleResponse = error("No network allowed")
        }
        val display = DeviceDisplayPreferences(context)
        val repository = AddonRepository(api, db.addonDao(), TorBoxAvailabilityService(context))
        val config = ProfileConfigurationManager(context, db.addonDao(), StremioAuthManager(context, StremioAuthService()), repository, display)
        vm = SettingsViewModel(db.addonDao(), config, SeekThumbnailCache(context), context, display)
    }
    @After fun tearDown() { vm.viewModelScope.cancel(); db.close() }
    private fun change(next: ProfileEntity, action: () -> Unit) = runBlocking {
        action()
        withTimeout(5_000) { db.addonDao().getProfileFlow(1).first { it == next } }
        assertEquals(next, db.addonDao().getProfileById(1)); expected = next
    }

    @Test fun appearanceChangesPreserveOtherProfileSettings() {
        change(expected.copy(navPosition = "top")) { vm.updateNavPosition(1, "top") }
        change(expected.copy(roundCorners = false)) { vm.updateRoundCorners(1, false) }
        change(expected.copy(hubRoundCorners = false)) { vm.updateHubRoundCorners(1, false) }
        change(expected.copy(continueWatchingShape = "landscape")) { vm.updateContinueWatchingShape(1, "landscape") }
        change(expected.copy(splashEnabled = false)) { vm.updateSplashEnabled(1, false) }
    }
    @Test fun playbackHardwareChangesPersistWithoutOverwritingSourcePreferences() {
        change(expected.copy(mapDV7ToHevc = true)) { vm.updateMapDV7ToHevc(1, true) }
        change(expected.copy(decoderPriority = 2)) { vm.updateDecoderPriority(1, 2) }
        change(expected.copy(frameRateMatching = true)) { vm.updateFrameRateMatching(1, true) }
        change(expected.copy(playerPreference = "external")) { vm.updatePlayerPreference(1, "external") }
    }
    @Test fun seekIntervalAlwaysUpdatesBothLegacyColumnsAndNormalizesInvalidInput() {
        listOf(10, 20, 30).forEach { interval ->
            change(expected.copy(seekTimeIntervalSeconds = interval, seekThumbnailIntervalSeconds = interval)) { vm.updateSeekTimeInterval(1, interval) }
        }
        change(expected.copy(seekTimeIntervalSeconds = 10, seekThumbnailIntervalSeconds = 10)) { vm.updateSeekTimeInterval(1, 999) }
    }
    @Test fun autoplaySourceAndIntroChangesRoundTrip() {
        change(expected.copy(autoplayNextEpisode = false)) { vm.updateAutoplayNextEpisode(1, false) }
        change(expected.copy(autoSelectSource = false)) { vm.updateAutoSelectSource(1, false) }
        change(expected.copy(rememberSourceSelection = false)) { vm.updateRememberSourceSelection(1, false) }
        change(expected.copy(skipIntro = false)) { vm.updateSkipIntro(1, false) }
        change(expected.copy(skipRecap = false)) { vm.updateSkipRecap(1, false) }
        change(expected.copy(autoplayThresholdMode = "smart")) { vm.updateAutoplayThresholdMode(1, "smart") }
        change(expected.copy(autoplayThresholdPercent = 97)) { vm.updateAutoplayThresholdPercent(1, 97) }
        change(expected.copy(autoplayThresholdSeconds = 90)) { vm.updateAutoplayThresholdSeconds(1, 90) }
    }
    @Test fun sharedAutoSkipCountdownUpdatesIntroAndOutroTogether() {
        change(expected.copy(autoSkipIntro = true, introSkipCountdownSeconds = 10, outroSkipCountdownSeconds = 10)) { vm.updateAutoSkipCountdown(1, 10) }
        change(expected.copy(autoSkipIntro = false, introSkipCountdownSeconds = 0, outroSkipCountdownSeconds = 0)) { vm.updateAutoSkipCountdown(1, 0) }
        change(expected.copy(autoSkipIntro = true, introSkipCountdownSeconds = 5, outroSkipCountdownSeconds = 5)) { vm.updateAutoSkipCountdown(1, 5) }
    }
    @Test fun duplicateLanguagePriorityIsClearedAndAudioPreferencesStayAligned() {
        change(expected.copy(sourceLanguagePriority1 = "te", sourceLanguagePriority2 = "", preferredAudioLanguage = "te")) {
            vm.updateSourceLanguagePriority(1, 1, " te ")
        }
        change(expected.copy(sourceLanguagePriority2 = "ml", preferredAudioLanguageSecondary = "ml")) { vm.updatePreferredAudioLanguageSecondary(1, "ml") }
        change(expected.copy(sourceLanguagePriority1 = "kn", preferredAudioLanguage = "kn")) { vm.updatePreferredAudioLanguage(1, "kn") }
        change(expected.copy(sourceLanguagePriority3 = "en")) { vm.updateSourceLanguagePriority(1, 3, "en") }
    }
    @Test fun subtitleLanguageAndStyleChangesPersistIndependently() {
        change(expected.copy(preferredSubtitleLanguage = "ml")) { vm.updatePreferredSubtitleLanguage(1, "ml") }
        change(expected.copy(preferredSubtitleLanguageSecondary = "kn")) { vm.updatePreferredSubtitleLanguageSecondary(1, "kn") }
        change(expected.copy(subtitleSize = 150)) { vm.updateSubtitleSize(1, 150) }
        change(expected.copy(subtitleOffset = -5)) { vm.updateSubtitleOffset(1, -5) }
        change(expected.copy(subtitleTextColor = 0xFFFFAA00)) { vm.updateSubtitleTextColor(1, 0xFFFFAA00) }
        change(expected.copy(subtitleBackgroundColor = 0x88000000)) { vm.updateSubtitleBackgroundColor(1, 0x88000000) }
        change(expected.copy(assRendererEnabled = true)) { vm.updateAssRendererEnabled(1, true) }
    }
    @Test fun sortingFiltersPersistIndependently() {
        change(expected.copy(sourceSortingEnabled = false)) { vm.updateSourceSortingEnabled(1, false) }
        change(expected.copy(sourceExcludePhrases = "cam,ts")) { vm.updateSourceExcludePhrases(1, "cam,ts") }
        change(expected.copy(sourceSortPrimary = "smart_tcl_c755")) { vm.updateSourceSortPrimary(1, "smart_tcl_c755") }
        change(expected.copy(sourceMaxSizeGb = 20)) { vm.updateSourceMaxSizeGb(1, 20) }
        change(expected.copy(sourceSeasonPacksOnly = false)) { vm.updateSourceSeasonPacksOnly(1, false) }
        change(expected.copy(sourceHideZeroSeeders = false)) { vm.updateSourceHideZeroSeeders(1, false) }
    }
    @Test fun hardwareOverridesAreDeviceLocalRatherThanChangingCloudProfile() = runBlocking {
        vm.deviceDisplay.configure(true)
        vm.updateTunnelingEnabled(1, true); vm.updateSourceEnabledQualities(1, "720p"); vm.updateSourceExcludedFormats(1, "hdr")
        assertEquals(expected, db.addonDao().getProfileById(1))
        val effective = vm.deviceDisplay.effective(expected)
        assertTrue(effective.tunnelingEnabled); assertEquals("720p", effective.sourceEnabledQualities)
        assertEquals("hdr", effective.sourceExcludedFormats)
    }
}
