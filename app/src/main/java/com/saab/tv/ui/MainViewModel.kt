package com.saab.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.local.AddonDao
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.profile.ProfileConfigurationManager
import com.saab.tv.data.trakt.TraktAuthManager
import com.saab.tv.data.trakt.TraktSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import kotlinx.coroutines.flow.combine

@HiltViewModel
class MainViewModel @Inject constructor(
    private val dao: AddonDao,
    private val profileConfigurationManager: ProfileConfigurationManager,
    private val traktAuthManager: TraktAuthManager,
    private val traktSyncManager: TraktSyncManager,
    private val deviceDisplay: com.saab.tv.data.profile.DeviceDisplayPreferences
) : ViewModel() {

    private val _activeProfile = MutableStateFlow<ProfileEntity?>(null)
    val activeProfile: StateFlow<ProfileEntity?> = _activeProfile

    private var profileJob: Job? = null
    private var loginJob: Job? = null
    private var traktSyncJob: Job? = null
    private var activeProfileId: Int? = null
    private val profileTransitionMutex = Mutex()

    companion object {
        private const val TRAKT_POLL_INTERVAL_MS = 30_000L // 30 seconds
    }

    init {
        viewModelScope.launch {
            traktAuthManager.isConnected.collect { connected ->
                if (connected && traktSyncJob?.isActive != true) {
                    startTraktPeriodicSync()
                } else if (!connected) {
                    traktSyncJob?.cancel()
                }
            }
        }
    }

    // Call this when user clicks a profile
    fun login(id: Int) {
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            profileTransitionMutex.withLock {
                profileConfigurationManager.captureStartupRuntimeIfNeeded()

                val previousProfileId = activeProfileId
                if (previousProfileId != null && previousProfileId != id) {
                    profileConfigurationManager.saveRuntimeState(previousProfileId)
                }

                profileConfigurationManager.loadRuntimeState(id)
                activeProfileId = id

                profileJob?.cancel()
                profileJob = viewModelScope.launch {
                    combine(dao.getProfileFlow(id), deviceDisplay.revision) { profile, _ -> deviceDisplay.effective(profile) }.collect { profile ->
                        _activeProfile.value = profile
                        profile?.let {
                            profileConfigurationManager.cacheSplashEnabled(it.id, it.splashEnabled)
                        }
                    }
                }

                // Refresh Trakt connection for this profile and start periodic sync
                traktAuthManager.refreshConnectionState()
                traktSyncManager.resetActivityState()
                startTraktPeriodicSync()
            }
        }
    }

    private fun startTraktPeriodicSync() {
        traktSyncJob?.cancel()
        if (!traktAuthManager.isConnected.value) return

        traktSyncJob = viewModelScope.launch(Dispatchers.IO) {
            // Immediate full sync on login
            traktSyncManager.syncWatchlist()
            traktSyncManager.syncPlaybackProgress()
            traktSyncManager.syncSeriesNextUp()

            // Then lightweight activity check every 30 seconds —
            // only triggers a full sync when Trakt detects changes
            while (isActive) {
                delay(TRAKT_POLL_INTERVAL_MS)
                traktSyncManager.checkAndSync()
            }
        }
    }

    fun logout() {
        loginJob?.cancel()
        viewModelScope.launch {
            profileTransitionMutex.withLock {
                activeProfileId?.let { profileConfigurationManager.saveRuntimeState(it) }
                profileConfigurationManager.clearLastActiveProfileId()
                activeProfileId = null
                profileJob?.cancel()
                traktSyncJob?.cancel()
                _activeProfile.value = null
            }
        }
    }

    suspend fun persistActiveProfileState() {
        activeProfileId?.let { profileConfigurationManager.saveRuntimeState(it) }
    }
}
