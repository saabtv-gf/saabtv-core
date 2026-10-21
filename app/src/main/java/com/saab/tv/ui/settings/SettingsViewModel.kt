package com.saab.tv.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.local.AddonDao
import com.saab.tv.data.account.UserStateCommandRepository
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.cache.SeekThumbnailCache
import com.saab.tv.data.cache.SeekThumbnailWorkerService
import com.saab.tv.ui.player.base.SeekIntervalPolicy
import com.saab.tv.data.profile.ProfileConfigurationManager
import com.saab.tv.data.profile.AudioLanguagePreferencePolicy
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
    val deviceDisplay: com.saab.tv.data.profile.DeviceDisplayPreferences,
    private val userStateCommands: UserStateCommandRepository
) : ViewModel() {

    // All profile mutations are serialized so rapid slider/toggle changes cannot
    // read the same stale row and overwrite one another out of order.
    private val mutationDispatcher = Dispatchers.IO.limitedParallelism(1)

    private suspend fun persistProfile(profile: ProfileEntity) {
        userStateCommands.saveProfile(profile)
    }

    fun updateNavPosition(profileId: Int, position: String) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(navPosition = position))
        }
    }

    fun updateRoundCorners(profileId: Int, roundCorners: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(roundCorners = roundCorners))
        }
    }

    fun updateHubRoundCorners(profileId: Int, hubRoundCorners: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(hubRoundCorners = hubRoundCorners))
        }
    }

    fun updateSplashEnabled(profileId: Int, enabled: Boolean) {
        profileConfigurationManager.cacheSplashEnabled(profileId, enabled)
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(splashEnabled = enabled))
        }
    }

    fun updateContinueWatchingShape(profileId: Int, shape: String) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(continueWatchingShape = shape))
        }
    }

    fun updateTitleCardShape(profileId: Int, shape: String) {
        val normalized = if (shape == "landscape") "landscape" else "poster"
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(titleCardShape = normalized))
        }
    }

    fun updateTunnelingEnabled(profileId: Int, enabled: Boolean) {
        deviceDisplay.setTunneling(profileId, enabled)
    }

    fun updateMapDV7ToHevc(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(mapDV7ToHevc = enabled))
        }
    }

    fun updateDecoderPriority(profileId: Int, priority: Int) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(decoderPriority = priority))
        }
    }

    fun updateFrameRateMatching(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(frameRateMatching = enabled))
        }
    }

    fun updatePlayerPreference(profileId: Int, preference: String) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(playerPreference = preference))
        }
    }

    fun updateSeekThumbnailsEnabled(profileId: Int, enabled: Boolean) {
        SeekThumbnailWorkerService.setProfileEnabled(appContext, profileId, enabled)
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(seekThumbnailsEnabled = enabled))
            if (!enabled) seekThumbnailCache.clearProfile(profileId)
        }
    }

    fun updateSeekTimeInterval(profileId: Int, seconds: Int) {
        val safeInterval = SeekIntervalPolicy.normalizeSeconds(seconds)
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) {
                persistProfile(profile.copy(
                    seekTimeIntervalSeconds = safeInterval,
                    seekThumbnailIntervalSeconds = safeInterval
                ))
            }
        }
    }

    fun updateAutoplayNextEpisode(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(autoplayNextEpisode = enabled))
        }
    }

    fun updateAutoSkipCountdown(profileId: Int, seconds: Int) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            dao.getProfileById(profileId)?.let {
                persistProfile(it.withAutoSkipCountdown(seconds))
            }
        }
    }

    fun updateAutoSelectSource(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(autoSelectSource = enabled))
        }
    }

    fun updateRememberSourceSelection(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(rememberSourceSelection = enabled))
        }
    }

    fun updateSkipIntro(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(skipIntro = enabled))
        }
    }

    fun updateSkipRecap(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(skipRecap = enabled))
        }
    }


    fun updateSourceLanguagePriority(profileId: Int, priority: Int, language: String) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId) ?: return@launch
            persistProfile(AudioLanguagePreferencePolicy.setPriority(profile, priority, language))
        }
    }

    fun updateAutoplayThresholdMode(profileId: Int, mode: String) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(autoplayThresholdMode = mode))
        }
    }

    fun updateAutoplayThresholdPercent(profileId: Int, percent: Int) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(autoplayThresholdPercent = percent))
        }
    }

    fun updateAutoplayThresholdSeconds(profileId: Int, seconds: Int) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(autoplayThresholdSeconds = seconds))
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
            if (profile != null) persistProfile(profile.copy(preferredSubtitleLanguage = language))
        }
    }

    fun updatePreferredSubtitleLanguageSecondary(profileId: Int, language: String) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(preferredSubtitleLanguageSecondary = language))
        }
    }

    fun updateSubtitleSize(profileId: Int, size: Int) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(subtitleSize = size))
        }
    }

    fun updateSubtitleOffset(profileId: Int, offset: Int) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(subtitleOffset = offset))
        }
    }

    fun updateSubtitleTextColor(profileId: Int, color: Long) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(subtitleTextColor = color))
        }
    }

    fun updateSubtitleBackgroundColor(profileId: Int, color: Long) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(subtitleBackgroundColor = color))
        }
    }

    fun updateAssRendererEnabled(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(assRendererEnabled = enabled))
        }
    }

    fun updateSourceSortingEnabled(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(sourceSortingEnabled = enabled))
        }
    }

    fun updateSourceEnabledQualities(profileId: Int, qualities: String) {
        deviceDisplay.setQualities(profileId, qualities)
    }

    fun updateSourceExcludePhrases(profileId: Int, phrases: String) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(sourceExcludePhrases = phrases))
        }
    }

    fun updateSourceSortPrimary(profileId: Int, sort: String) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(sourceSortPrimary = sort))
        }
    }

    fun updateSourceMaxSizeGb(profileId: Int, sizeGb: Int) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(sourceMaxSizeGb = sizeGb))
        }
    }

    fun updateSourceExcludedFormats(profileId: Int, formats: String) {
        deviceDisplay.setFormats(profileId, formats)
    }

    fun updateSourceSeasonPacksOnly(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(sourceSeasonPacksOnly = enabled))
        }
    }

    fun updateSourceHideZeroSeeders(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(mutationDispatcher + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) persistProfile(profile.copy(sourceHideZeroSeeders = enabled))
        }
    }
}
