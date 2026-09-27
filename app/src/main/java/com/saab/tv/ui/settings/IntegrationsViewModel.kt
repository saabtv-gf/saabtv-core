package com.saab.tv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.auth.StremioAuthManager
import com.saab.tv.data.auth.StremioConnectionState
import com.saab.tv.data.local.AddonDao
import com.saab.tv.data.model.StremioAddonItem
import com.saab.tv.data.profile.ProfileConfigurationManager
import com.saab.tv.data.remote.StremioAuthError
import com.saab.tv.data.repository.AddonRepository
import com.saab.tv.data.trakt.DeviceAuthState
import com.saab.tv.data.trakt.TraktAuthManager
import com.saab.tv.data.trakt.TraktSyncManager
import com.saab.tv.data.tmdb.TmdbService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import javax.inject.Inject

sealed class IntegrationsEvent {
    object LoginSuccess : IntegrationsEvent()
    data class LoginError(val message: String) : IntegrationsEvent()
    data class SyncComplete(val count: Int) : IntegrationsEvent()
    object Disconnected : IntegrationsEvent()
}

data class IntegrationsUiState(
    val connectionState: StremioConnectionState = StremioConnectionState.Disconnected,
    val isLoading: Boolean = false,
    val pendingAddons: List<StremioAddonItem>? = null,
    val tmdbEnabled: Boolean = false,
    val tmdbLanguage: String = "",
    val tmdbApiKeyConfigured: Boolean = false,
    val traktConnected: Boolean = false,
    val traktCredentialsConfigured: Boolean = false,
    val traktAuthState: DeviceAuthState = DeviceAuthState.Idle,
    val torBoxConfigured: Boolean = false
)

@HiltViewModel
class IntegrationsViewModel @Inject constructor(
    private val stremioAuthManager: StremioAuthManager,
    private val addonRepository: AddonRepository,
    private val profileConfigurationManager: ProfileConfigurationManager,
    private val dao: AddonDao,
    private val tmdbService: TmdbService,
    private val traktAuthManager: TraktAuthManager,
    private val traktSyncManager: TraktSyncManager,
    private val torBox: com.saab.tv.data.stream.TorBoxAvailabilityService
) : ViewModel() {

    private var traktAuthJob: Job? = null

    fun saveTorBoxKey(key: String): Boolean {
        if (!torBox.save(key)) return false
        _uiState.value = _uiState.value.copy(torBoxConfigured = true)
        return true
    }
    fun clearTorBoxKey() {
        torBox.clear()
        _uiState.value = _uiState.value.copy(torBoxConfigured = false)
    }

    private val _uiState = MutableStateFlow(IntegrationsUiState())
    val uiState: StateFlow<IntegrationsUiState> = _uiState.asStateFlow()

    private val _events = Channel<IntegrationsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        _uiState.value = _uiState.value.copy(
            traktCredentialsConfigured = traktAuthManager.hasClientCredentials(),
            tmdbApiKeyConfigured = tmdbService.hasApiKey(),
            torBoxConfigured = torBox.configured()
        )
        // Observe connection state
        viewModelScope.launch {
            stremioAuthManager.connectionState.collect { state ->
                _uiState.value = _uiState.value.copy(connectionState = state)
            }
        }
        // Observe Trakt connection state
        viewModelScope.launch {
            traktAuthManager.isConnected.collect { connected ->
                _uiState.value = _uiState.value.copy(traktConnected = connected)
            }
        }
        viewModelScope.launch {
            traktAuthManager.authState.collect { authState ->
                _uiState.value = _uiState.value.copy(traktAuthState = authState)
            }
        }
        // Load TMDB settings from active profile
        viewModelScope.launch(Dispatchers.IO) {
            val profileId = profileConfigurationManager.getLastActiveProfileId() ?: 1
            val profile = dao.getProfileById(profileId)
            if (profile != null) {
                _uiState.value = _uiState.value.copy(
                    tmdbEnabled = profile.tmdbEnabled,
                    tmdbLanguage = profile.tmdbLanguage
                )
            }
        }
    }

    fun updateTmdbEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(tmdbEnabled = enabled)
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val profileId = profileConfigurationManager.getLastActiveProfileId() ?: 1
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(tmdbEnabled = enabled))
        }
    }

    fun updateTmdbLanguage(language: String) {
        _uiState.value = _uiState.value.copy(tmdbLanguage = language)
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val profileId = profileConfigurationManager.getLastActiveProfileId() ?: 1
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(tmdbLanguage = language))
        }
    }

    fun saveTmdbApiKey(apiKey: String) {
        if (!tmdbService.saveApiKey(apiKey)) return
        _uiState.value = _uiState.value.copy(tmdbApiKeyConfigured = true)
    }

    fun clearTmdbApiKey() {
        tmdbService.clearApiKey()
        _uiState.value = _uiState.value.copy(tmdbApiKeyConfigured = false)
    }

    /**
     * Logs in to Stremio with email/password.
     * On success, triggers addon sync automatically.
     */
    fun login(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val result = stremioAuthManager.login(email, password)

            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    _events.send(IntegrationsEvent.LoginSuccess)
                    // Auto-sync addons after login
                    syncAddons()
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    val message = when (error) {
                        is StremioAuthError.InvalidCredentials -> "Invalid email or password"
                        is StremioAuthError.NetworkError -> "Network error: ${error.message}"
                        else -> error.message ?: "Unknown error"
                    }
                    _events.send(IntegrationsEvent.LoginError(message))
                }
            )
        }
    }

    /**
     * Syncs addons from the connected Stremio account.
     * Uses the stored authKey - does not require password.
     */
    fun syncAddons() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val result = stremioAuthManager.fetchAddons()

            result.fold(
                onSuccess = { entries ->
                    // Get currently installed addon URLs
                    val installedUrls = addonRepository.getAddons()
                        .firstOrNull()
                        ?.map { it.transportUrl }
                        ?.toSet()
                        ?: emptySet()

                    // Convert to StremioAddonItem with duplicate detection
                    val addonItems = entries.mapNotNull { entry ->
                        val manifest = entry.manifest ?: return@mapNotNull null
                        val transportUrl = entry.transportUrl.removeSuffix("/manifest.json")
                        val isInstalled = installedUrls.contains(transportUrl)

                        StremioAddonItem(
                            name = manifest.name ?: "Unknown Addon",
                            transportUrl = transportUrl,
                            description = manifest.description,
                            isSelected = !isInstalled,
                            isAlreadyInstalled = isInstalled
                        )
                    }

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        pendingAddons = addonItems.ifEmpty { null }
                    )

                    if (addonItems.isEmpty()) {
                        _events.send(IntegrationsEvent.SyncComplete(0))
                    }
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    val message = when (error) {
                        is StremioAuthError.InvalidCredentials -> "Session expired. Please reconnect."
                        is StremioAuthError.NetworkError -> "Network error: ${error.message}"
                        else -> error.message ?: "Unknown error"
                    }
                    _events.send(IntegrationsEvent.LoginError(message))
                }
            )
        }
    }

    /**
     * Imports selected addons to the local database.
     */
    fun importAddons(addons: List<StremioAddonItem>) {
        if (addons.isEmpty()) {
            _uiState.value = _uiState.value.copy(pendingAddons = null)
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, pendingAddons = null)
            var successCount = 0

            for (addon in addons) {
                try {
                    val manifestUrl = if (addon.transportUrl.endsWith("manifest.json")) {
                        addon.transportUrl
                    } else {
                        "${addon.transportUrl}/manifest.json"
                    }
                    addonRepository.installAddon(manifestUrl)
                    successCount++
                } catch (e: Exception) {
                    // Continue with next addon
                }
            }

            profileConfigurationManager.saveActiveRuntimeState()
            _uiState.value = _uiState.value.copy(isLoading = false)
            _events.send(IntegrationsEvent.SyncComplete(successCount))
        }
    }

    /**
     * Dismisses the import dialog.
     */
    fun dismissImportDialog() {
        _uiState.value = _uiState.value.copy(pendingAddons = null)
    }

    /**
     * Disconnects from Stremio.
     */
    fun disconnect() {
        stremioAuthManager.disconnect()
        viewModelScope.launch {
            _events.send(IntegrationsEvent.Disconnected)
        }
    }

    // ── Trakt ──

    fun startTraktAuth() {
        traktAuthJob?.cancel()
        traktAuthJob = viewModelScope.launch { traktAuthManager.startDeviceAuth() }
    }

    fun configureAndStartTraktAuth(clientId: String, clientSecret: String) {
        if (!traktAuthManager.saveClientCredentials(clientId, clientSecret)) return
        _uiState.value = _uiState.value.copy(traktCredentialsConfigured = true)
        startTraktAuth()
    }

    fun clearTraktCredentials() {
        traktAuthJob?.cancel()
        traktAuthManager.clearClientCredentials()
        _uiState.value = _uiState.value.copy(
            traktConnected = false,
            traktCredentialsConfigured = traktAuthManager.hasClientCredentials(),
            traktAuthState = DeviceAuthState.Idle
        )
    }

    fun disconnectTrakt() {
        traktAuthJob?.cancel()
        viewModelScope.launch { traktAuthManager.disconnect() }
    }

    fun resetTraktAuthState() {
        traktAuthJob?.cancel()
        traktAuthManager.resetAuthState()
    }
}
