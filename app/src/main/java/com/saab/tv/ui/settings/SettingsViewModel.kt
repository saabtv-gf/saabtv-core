package com.saab.tv.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.local.AddonDao
import com.saab.tv.data.cache.SeekThumbnailCache
import com.saab.tv.data.cache.SeekThumbnailWorkerService
import com.saab.tv.ui.player.base.SeekIntervalPolicy
import com.saab.tv.data.profile.ProfileConfigurationManager
import com.saab.tv.data.profile.withAutoSkipCountdown
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsViewModel @Inject constructor(
    private val dao: AddonDao,
    private val profileConfigurationManager: ProfileConfigurationManager,
    private val seekThumbnailCache: SeekThumbnailCache,
    @ApplicationContext private val appContext: Context,
    val deviceDisplay: com.saab.tv.data.profile.DeviceDisplayPreferences
) : ViewModel() {

    // All profile mutations are serialized so rapid slider/toggle changes cannot
    // read the same stale row and overwrite one another out of order.
    private val mutationDispatcher = Dispatchers.IO.limitedParallelism(1)

    fun updateNavPosition(profileId: Int, position: String) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(navPosition = position))
        }
    }

    fun updateRoundCorners(profileId: Int, roundCorners: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(roundCorners = roundCorners))
        }
    }

    fun updateHubRoundCorners(profileId: Int, hubRoundCorners: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(hubRoundCorners = hubRoundCorners))
        }
    }

    fun updateSplashEnabled(profileId: Int, enabled: Boolean) {
        profileConfigurationManager.cacheSplashEnabled(profileId, enabled)
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(splashEnabled = enabled))
        }
    }

    fun updateContinueWatchingShape(profileId: Int, shape: String) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(continueWatchingShape = shape))
        }
    }

    fun updateTunnelingEnabled(profileId: Int, enabled: Boolean) {
        deviceDisplay.setTunneling(profileId, enabled)
    }

    fun updateMapDV7ToHevc(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(mapDV7ToHevc = enabled))
        }
    }

    fun updateDecoderPriority(profileId: Int, priority: Int) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(decoderPriority = priority))
        }
    }

    fun updateFrameRateMatching(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(frameRateMatching = enabled))
        }
    }

    fun updatePlayerPreference(profileId: Int, preference: String) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(playerPreference = preference))
        }
    }

    fun updateSeekThumbnailsEnabled(profileId: Int, enabled: Boolean) {
        SeekThumbnailWorkerService.setProfileEnabled(appContext, profileId, enabled)
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(seekThumbnailsEnabled = enabled))
            if (!enabled) seekThumbnailCache.clearProfile(profileId)
        }
    }

    fun updateSeekTimeInterval(profileId: Int, seconds: Int) {
        val safeInterval = SeekIntervalPolicy.normalizeSeconds(seconds)
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) {
                dao.insertProfile(profile.copy(
                    seekTimeIntervalSeconds = safeInterval,
                    seekThumbnailIntervalSeconds = safeInterval
                ))
            }
        }
    }

    fun updateAutoplayNextEpisode(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(autoplayNextEpisode = enabled))
        }
    }

    fun updateAutoSkipCountdown(profileId: Int, seconds: Int) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            dao.getProfileById(profileId)?.let {
                dao.insertProfile(it.withAutoSkipCountdown(seconds))
            }
        }
    }

    fun updateAutoSelectSource(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(autoSelectSource = enabled))
        }
    }

    fun updateRememberSourceSelection(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(rememberSourceSelection = enabled))
        }
    }

    fun updateSkipIntro(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(skipIntro = enabled))
        }
    }

    fun updateSkipRecap(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(skipRecap = enabled))
        }
    }


    fun updateSourceLanguagePriority(profileId: Int, priority: Int, language: String) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId) ?: return@launch
            val normalized = language.trim()
            val existing = listOf(
                profile.sourceLanguagePriority1,
                profile.sourceLanguagePriority2,
                profile.sourceLanguagePriority3
            ).toMutableList()
            val index = (priority - 1).coerceIn(0, 2)
            existing[index] = normalized
            // A language can occupy only one priority. Clear the older slot so
            // the displayed order is also the exact scoring order.
            existing.indices.filter { it != index && existing[it] == normalized }
                .forEach { existing[it] = "" }
            dao.insertProfile(profile.copy(
                sourceLanguagePriority1 = existing[0],
                sourceLanguagePriority2 = existing[1],
                sourceLanguagePriority3 = existing[2],
                preferredAudioLanguage = existing[0],
                preferredAudioLanguageSecondary = existing[1]
            ))
        }
    }

    fun updateAutoplayThresholdMode(profileId: Int, mode: String) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(autoplayThresholdMode = mode))
        }
    }

    fun updateAutoplayThresholdPercent(profileId: Int, percent: Int) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(autoplayThresholdPercent = percent))
        }
    }

    fun updateAutoplayThresholdSeconds(profileId: Int, seconds: Int) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(autoplayThresholdSeconds = seconds))
        }
    }

    fun updatePreferredAudioLanguage(profileId: Int, language: String) {
        updateSourceLanguagePriority(profileId, 1, language)
    }

    fun updatePreferredAudioLanguageSecondary(profileId: Int, language: String) {
        updateSourceLanguagePriority(profileId, 2, language)
    }

    fun updatePreferredSubtitleLanguage(profileId: Int, language: String) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(preferredSubtitleLanguage = language))
        }
    }

    fun updatePreferredSubtitleLanguageSecondary(profileId: Int, language: String) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(preferredSubtitleLanguageSecondary = language))
        }
    }

    fun updateSubtitleSize(profileId: Int, size: Int) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(subtitleSize = size))
        }
    }

    fun updateSubtitleOffset(profileId: Int, offset: Int) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(subtitleOffset = offset))
        }
    }

    fun updateSubtitleTextColor(profileId: Int, color: Long) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(subtitleTextColor = color))
        }
    }

    fun updateSubtitleBackgroundColor(profileId: Int, color: Long) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(subtitleBackgroundColor = color))
        }
    }

    fun updateAssRendererEnabled(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(assRendererEnabled = enabled))
        }
    }

    fun updateSourceSortingEnabled(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(sourceSortingEnabled = enabled))
        }
    }

    fun updateSourceEnabledQualities(profileId: Int, qualities: String) {
        deviceDisplay.setQualities(profileId, qualities)
    }

    fun updateSourceExcludePhrases(profileId: Int, phrases: String) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(sourceExcludePhrases = phrases))
        }
    }

    fun updateSourceSortPrimary(profileId: Int, sort: String) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(sourceSortPrimary = sort))
        }
    }

    fun updateSourceMaxSizeGb(profileId: Int, sizeGb: Int) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(sourceMaxSizeGb = sizeGb))
        }
    }

    fun updateSourceExcludedFormats(profileId: Int, formats: String) {
        deviceDisplay.setFormats(profileId, formats)
    }

    fun updateSourceSeasonPacksOnly(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(sourceSeasonPacksOnly = enabled))
        }
    }

    fun updateSourceHideZeroSeeders(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(sourceHideZeroSeeders = enabled))
        }
    }
}
