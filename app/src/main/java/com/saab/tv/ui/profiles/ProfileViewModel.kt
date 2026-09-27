package com.saab.tv.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.local.AddonDao
import com.saab.tv.data.cache.SeekThumbnailCache
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.profile.ProfileConfigurationManager
import com.saab.tv.data.profile.ProfilePin
import com.saab.tv.data.profile.withOnboardingPreferences
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
    private val _wizardError = MutableStateFlow<String?>(null)
    val wizardError: StateFlow<String?> = _wizardError

    // WIZARD DATA
    var tempName = ""
    var tempAvatarRef = "avatar_1"
    var tempThemeId = "void"  // Changed from tempColor
    var tempLanguages = listOf("en", "te", "hi")
        private set
    private var createdProfileId: Int? = null
    private var copySourceId: Int? = null

    fun setupSources(): List<ProfileEntity> = _profiles.value.filter {
        it.id != createdProfileId && !needsInitialSetup(it.id)
    }

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
        tempLanguages = listOf("en", "te", "hi")
        createdProfileId = null
        copySourceId = null
        _wizardError.value = null
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
        if (_isInitializingProfile.value) return
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
        if (editingProfileId != null) finishWizard()
        else _wizardStep.value = if (setupSources().isNotEmpty()) 4 else 6
    }

    fun chooseManualSetup() {
        copySourceId = null
        _wizardStep.value = 6
    }

    fun chooseCopySetup(sourceId: Int) {
        if (setupSources().none { it.id == sourceId }) return
        copySourceId = sourceId
        finishWizard()
    }

    fun setWizardLanguage(priority: Int, language: String) {
        if (_isInitializingProfile.value || _wizardStep.value != 6 + priority ||
            priority !in 0..2 || language.isBlank() || language in tempLanguages.take(priority)) return
        val updated = tempLanguages.toMutableList()
        updated[priority] = language
        // Keep defaults distinct when a prior step chooses a later step's default.
        for (index in priority + 1..2) {
            if (updated[index] in updated.take(index)) {
                updated[index] = listOf("en", "te", "hi", "es", "fr").first { it !in updated.take(index) }
            }
        }
        tempLanguages = updated
        if (priority == 2) finishWizard() else _wizardStep.value = 7 + priority
    }

    private fun finishWizard() {
        if (_isInitializingProfile.value) return
        _isInitializingProfile.value = true
        _wizardError.value = null
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            try {
                if (editingProfileId != null) {
                    val updatedProfile = _profiles.value.find { it.id == editingProfileId }?.copy(
                        name = tempName,
                        avatarRef = tempAvatarRef,
                        themeId = tempThemeId
                    )
                    if (updatedProfile != null) dao.updateProfile(updatedProfile)
                } else {
                    val newProfile = ProfileEntity(
                        name = tempName, avatarRef = tempAvatarRef, themeId = tempThemeId
                    ).withOnboardingPreferences(tempLanguages)
                    val profileId = createdProfileId?.also {
                        // A retry may follow changes to setup; keep the pending profile in sync.
                        dao.updateProfile(newProfile.copy(id = it))
                    } ?: dao.insertProfile(newProfile).toInt()
                    check(profileId > 0) { "Profile Was Not Created" }
                    createdProfileId = profileId
                    profileConfigurationManager.markPendingSetup(profileId)
                    val source = copySourceId
                    if (source != null) profileConfigurationManager.initializeByCopying(profileId, source)
                    else profileConfigurationManager.initializeFromScratch(profileId)
                }
                _wizardStep.value = 0
                editingProfileId = null
                createdProfileId = null
            } catch (_: Exception) {
                _wizardError.value = "Profile Setup Failed. Please Try Again."
            } finally {
                _isInitializingProfile.value = false
            }
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
        if (_isInitializingProfile.value) return
        _wizardError.value = null
        if (_wizardStep.value == 6) _wizardStep.value = if (setupSources().isEmpty()) 3 else 4
        else if (_wizardStep.value > 0) _wizardStep.value -= 1
    }
}
