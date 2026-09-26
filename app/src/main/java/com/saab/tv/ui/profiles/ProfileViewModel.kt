package com.saab.tv.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.local.AddonDao
import com.saab.tv.data.cache.SeekThumbnailCache
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.profile.ProfileConfigurationManager
import com.saab.tv.data.profile.ProfilePin
import com.saab.tv.data.trakt.TraktAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val dao: AddonDao,
    private val profileConfigurationManager: ProfileConfigurationManager,
    private val traktAuthManager: TraktAuthManager,
    private val seekThumbnailCache: SeekThumbnailCache
) : ViewModel() {

    private data class PinAttempt(var failures: Int = 0, var lockedUntilMs: Long = 0L)
    private val pinAttempts = ConcurrentHashMap<Int, PinAttempt>()

    private val _profiles = MutableStateFlow<List<ProfileEntity>>(emptyList())
    val profiles: StateFlow<List<ProfileEntity>> = _profiles

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _wizardStep = MutableStateFlow(0)
    val wizardStep: StateFlow<Int> = _wizardStep

    private val _isInitializingProfile = MutableStateFlow(false)
    val isInitializingProfile: StateFlow<Boolean> = _isInitializingProfile

    // WIZARD DATA
    var tempName = ""
    var tempAvatarRef = "avatar_1"
    var tempThemeId = "void"  // Changed from tempColor

    private var editingProfileId: Int? = null

    init {
        loadProfiles()
    }

    private fun loadProfiles() {
        viewModelScope.launch {
            dao.getProfiles().collect { list ->
                _profiles.value = list
                _isLoading.value = false
            }
        }
    }

    // --- WIZARD ACTIONS ---

    fun startWizard() {
        editingProfileId = null
        tempName = ""
        tempAvatarRef = "avatar_1"
        tempThemeId = "void"
        _wizardStep.value = 1
    }

    fun startEditWizard(profile: ProfileEntity) {
        editingProfileId = profile.id
        tempName = profile.name
        tempAvatarRef = profile.avatarRef
        tempThemeId = profile.themeId
        _wizardStep.value = 1
    }

    fun cancelWizard() {
        _wizardStep.value = 0
        editingProfileId = null
    }

    fun setWizardName(name: String) {
        tempName = name
        _wizardStep.value = 2
    }

    fun setWizardAvatar(avatarKey: String) {
        tempAvatarRef = avatarKey
        _wizardStep.value = 3
    }

    fun setWizardTheme(themeId: String) {
        tempThemeId = themeId
        finishWizard()
    }

    private fun finishWizard() {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            if (editingProfileId != null) {
                val updatedProfile = _profiles.value.find { it.id == editingProfileId }?.copy(
                    name = tempName,
                    avatarRef = tempAvatarRef,
                    themeId = tempThemeId
                )
                if (updatedProfile != null) dao.updateProfile(updatedProfile)
            } else {
                val profileId = dao.insertProfile(
                    ProfileEntity(
                        name = tempName,
                        avatarRef = tempAvatarRef,
                        themeId = tempThemeId,
                        navPosition = "left",
                        homeTabLayout = "cinematic",
                        roundCorners = true
                    )
                ).toInt()
                if (profileId > 0) {
                    profileConfigurationManager.markPendingSetup(profileId)
                }
            }
            _wizardStep.value = 0
            editingProfileId = null
        }
    }

    fun needsInitialSetup(profileId: Int): Boolean {
        return profileConfigurationManager.needsInitialSetup(profileId)
    }

    fun initializeProfileFromScratch(profileId: Int, onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            _isInitializingProfile.value = true
            try {
                profileConfigurationManager.initializeFromScratch(profileId)
                onComplete()
            } finally {
                _isInitializingProfile.value = false
            }
        }
    }

    fun initializeProfileByCopy(targetProfileId: Int, sourceProfileId: Int, onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            _isInitializingProfile.value = true
            try {
                profileConfigurationManager.initializeByCopying(targetProfileId, sourceProfileId)
                onComplete()
            } finally {
                _isInitializingProfile.value = false
            }
        }
    }

    fun deleteProfile(id: Int) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.deleteWatchlistForProfile(id)
            dao.deleteHistoryForProfile(id)
            dao.deleteSeriesNextUpForProfile(id)
            seekThumbnailCache.clearProfile(id)
            traktAuthManager.clearTokensForProfile(id)
            dao.deleteProfile(id)
            profileConfigurationManager.deleteProfileState(id)
            pinAttempts.remove(id)
        }
    }

    fun verifyPin(profile: ProfileEntity, pin: String): Boolean {
        val now = System.currentTimeMillis()
        val attempt = pinAttempts.getOrPut(profile.id) { PinAttempt() }
        if (now < attempt.lockedUntilMs) return false

        val matches = ProfilePin.matches(profile, pin)
        if (matches) {
            pinAttempts.remove(profile.id)
            if (ProfilePin.needsUpgrade(profile)) {
                viewModelScope.launch(Dispatchers.IO) {
                    dao.getProfileById(profile.id)?.let { current ->
                        if (current.pinHash == profile.pinHash) {
                            dao.updateProfile(current.copy(pinHash = ProfilePin.hash(profile.id, pin)))
                        }
                    }
                }
            }
            return true
        }

        attempt.failures += 1
        if (attempt.failures >= 5) {
            val lockSeconds = (30L shl (attempt.failures - 5).coerceAtMost(3)).coerceAtMost(300L)
            attempt.lockedUntilMs = now + lockSeconds * 1_000L
        }
        return false
    }

    fun setProfilePin(profileId: Int, pin: String) {
        if (!ProfilePin.isValid(pin)) return
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val profile = dao.getProfileById(profileId) ?: return@launch
            dao.updateProfile(profile.copy(pinHash = ProfilePin.hash(profileId, pin)))
            pinAttempts.remove(profileId)
        }
    }

    fun removeProfilePin(profileId: Int) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val profile = dao.getProfileById(profileId) ?: return@launch
            dao.updateProfile(profile.copy(pinHash = null))
            pinAttempts.remove(profileId)
        }
    }

    fun goBackStep() {
        if (_wizardStep.value > 0) _wizardStep.value -= 1
    }
}
