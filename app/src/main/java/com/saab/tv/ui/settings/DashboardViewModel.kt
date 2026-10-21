package com.saab.tv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.local.AddonDao
import com.saab.tv.data.account.UserStateCommandRepository
import com.saab.tv.data.model.CatalogConfigEntity
import com.saab.tv.data.profile.ProfileConfigurationManager
import com.saab.tv.data.model.HubRowEntity
import com.saab.tv.data.model.HubRowItemEntity
import com.saab.tv.data.model.HubRowWithItems
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.domain.DashboardTab
import com.saab.tv.domain.HeroConfig
import com.saab.tv.domain.HubShape
import com.saab.tv.domain.heroFor
import com.saab.tv.domain.layoutFor
import com.saab.tv.domain.withHero
import com.saab.tv.domain.withLayout
import com.saab.tv.domain.TmdbHomeRail
import com.saab.tv.domain.TmdbHomeRails
import com.saab.tv.ui.home.personalizedRailOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.UUID
import javax.inject.Inject

sealed interface EditorListItem {
    val key: String
    val order: Int

    data class HubRowItem(
        val hub: HubRowEntity,
        val items: List<HubRowItemEntity>,
        override val order: Int
    ) : EditorListItem {
        override val key: String = "hub:${hub.id}"
    }

    data class CategoryItem(
        val config: CatalogConfigEntity,
        override val order: Int
    ) : EditorListItem {
        override val key: String = "cat:${config.uniqueId}"
    }

    data class TmdbRecommendationItem(
        val rail: TmdbHomeRail,
        override val order: Int
    ) : EditorListItem {
        override val key: String = "tmdb:${rail.id}"
    }
}

sealed interface DialogState {
    data object None : DialogState
    data class ManageCategory(val config: CatalogConfigEntity) : DialogState
    data class RenameCategory(val config: CatalogConfigEntity, val currentName: String) : DialogState
    data class LayoutSettings(val config: CatalogConfigEntity) : DialogState
    data class ManageTmdbRail(val item: EditorListItem.TmdbRecommendationItem) : DialogState
    data class AddToTab(val tab: String) : DialogState
    data object CreateHub : DialogState
    data class ManageHub(val hubItem: EditorListItem.HubRowItem) : DialogState
    data class EditHub(val hubItem: EditorListItem.HubRowItem) : DialogState
    data class HeroCarouselConfig(val tab: DashboardTab) : DialogState
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val dao: AddonDao,
    private val profileConfigurationManager: ProfileConfigurationManager,
    private val userStateCommands: UserStateCommandRepository
) : ViewModel() {

    private suspend fun persistProfileState() {
        profileConfigurationManager.saveActiveRuntimeState()
    }

    private suspend fun persistProfile(profile: ProfileEntity) {
        userStateCommands.saveProfile(profile)
    }

    private val _configs = MutableStateFlow<List<CatalogConfigEntity>>(emptyList())
    val configs: StateFlow<List<CatalogConfigEntity>> = _configs

    private val _hubRows = MutableStateFlow<List<HubRowWithItems>>(emptyList())
    val hubRows: StateFlow<List<HubRowWithItems>> = _hubRows

    init {
        viewModelScope.launch {
            try {
                dao.getAllCatalogConfigs().collect { list ->
                    _configs.value = list
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (currentCoroutineContext().isActive) throw failure
            }
        }
        viewModelScope.launch {
            try {
                dao.getHubRowsWithItems().collect { list ->
                    _hubRows.value = list
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (currentCoroutineContext().isActive) throw failure
            }
        }
    }

    fun renameCatalog(config: CatalogConfigEntity, newName: String) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.saveCatalogConfig(config.copy(customTitle = newName))
            persistProfileState()
        }
    }

    fun addItemToTab(item: EditorListItem, screen: String) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val currentItems = getEditorItems(screen)
            val maxOrder = currentItems.maxOfOrNull { it.order } ?: -1
            val newOrder = maxOrder + 1

            when(item) {
                is EditorListItem.CategoryItem -> {
                    val config = item.config
                    val updated = when(screen) {
                        "home" -> config.copy(showInHome = true, homeOrder = newOrder)
                        "movies" -> config.copy(showInMovies = true, moviesOrder = newOrder)
                        "series" -> config.copy(showInSeries = true, seriesOrder = newOrder)
                        else -> config
                    }
                    dao.saveCatalogConfig(updated)
                }
                is EditorListItem.HubRowItem -> {
                    val hub = item.hub
                    val updated = when(screen) {
                        "home" -> hub.copy(showInHome = true, homeOrder = newOrder)
                        "movies" -> hub.copy(showInMovies = true, moviesOrder = newOrder)
                        "series" -> hub.copy(showInSeries = true, seriesOrder = newOrder)
                        else -> hub
                    }
                    dao.updateHubRow(updated)
                }
                is EditorListItem.TmdbRecommendationItem -> return@launch
            }
            persistProfileState()
        }
    }

    fun removeItemFromTab(item: EditorListItem, screen: String) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            when(item) {
                is EditorListItem.CategoryItem -> {
                    val config = item.config
                    val updated = when(screen) {
                        "home" -> config.copy(showInHome = false)
                        "movies" -> config.copy(showInMovies = false)
                        "series" -> config.copy(showInSeries = false)
                        else -> config
                    }
                    dao.saveCatalogConfig(updated)
                }
                is EditorListItem.HubRowItem -> {
                    val hub = item.hub
                    val updated = when(screen) {
                        "home" -> hub.copy(showInHome = false)
                        "movies" -> hub.copy(showInMovies = false)
                        "series" -> hub.copy(showInSeries = false)
                        else -> hub
                    }
                    dao.updateHubRow(updated)
                }
                is EditorListItem.TmdbRecommendationItem -> return@launch
            }
            persistProfileState()
        }
    }

    fun moveEditorItem(item: EditorListItem, direction: Int, screen: String) {
        val currentList = getEditorItems(screen).toMutableList()
        val index = currentList.indexOfFirst { it.key == item.key }
        if (index == -1) return

        val newIndex = index + direction
        if (newIndex in 0 until currentList.size) {
            Collections.swap(currentList, index, newIndex)

            // Split and Update (Normalize Orders)
            val updatedConfigs = ArrayList<CatalogConfigEntity>()
            val updatedHubs = ArrayList<HubRowEntity>()

            currentList.forEachIndexed { i, listItem ->
                when(listItem) {
                    is EditorListItem.CategoryItem -> {
                        val updated = when(screen) {
                            "home" -> listItem.config.copy(homeOrder = i)
                            "movies" -> listItem.config.copy(moviesOrder = i)
                            "series" -> listItem.config.copy(seriesOrder = i)
                            else -> listItem.config
                        }
                        updatedConfigs.add(updated)
                    }
                    is EditorListItem.HubRowItem -> {
                        val updated = when(screen) {
                            "home" -> listItem.hub.copy(homeOrder = i)
                            "movies" -> listItem.hub.copy(moviesOrder = i)
                            "series" -> listItem.hub.copy(seriesOrder = i)
                            else -> listItem.hub
                        }
                        updatedHubs.add(updated)
                    }
                    is EditorListItem.TmdbRecommendationItem -> Unit
                }
            }

            viewModelScope.launch(Dispatchers.IO + NonCancellable) {
                if (updatedHubs.isNotEmpty()) dao.updateHubRows(updatedHubs)
                if (updatedConfigs.isNotEmpty()) dao.saveCatalogConfigs(updatedConfigs)
                persistProfileState()
            }
        }
    }

    fun updateLayoutSettings(
        config: CatalogConfigEntity,
        isInfiniteLoopEnabled: Boolean,
        visibleItemCount: Int,
        isInfiniteScrollingEnabled: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.saveCatalogConfig(
                config.copy(
                    isInfiniteLoopEnabled = isInfiniteLoopEnabled,
                    visibleItemCount = visibleItemCount.coerceIn(5, 50),
                    isInfiniteScrollingEnabled = isInfiniteScrollingEnabled
                )
            )
            persistProfileState()
        }
    }

    fun createHubRow(name: String, shape: HubShape, items: List<HubRowItemEntity>, tab: String = "home") {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val maxOrder = when (tab) {
                "movies" -> dao.getMaxHubMoviesOrder() ?: -1
                "series" -> dao.getMaxHubSeriesOrder() ?: -1
                else -> dao.getMaxHubHomeOrder() ?: -1
            }
            val hubRowId = UUID.randomUUID().toString()
            val hubRow = HubRowEntity(
                id = hubRowId,
                title = name,
                shape = shape.name,

                showInHome = tab == "home",
                homeOrder = if (tab == "home") maxOrder + 1 else 999,

                showInMovies = tab == "movies",
                moviesOrder = if (tab == "movies") maxOrder + 1 else 999,

                showInSeries = tab == "series",
                seriesOrder = if (tab == "series") maxOrder + 1 else 999
            )
            // Relink items to the new hub ID
            val hubItems = items.mapIndexed { index, item ->
                item.copy(
                    hubRowId = hubRowId,
                    itemOrder = index
                )
            }
            dao.insertHubRowWithItems(hubRow, hubItems)
            persistProfileState()
        }
    }

    fun deleteHubRow(hubRowId: String) {
        val profileOwnerId = _hubRows.value.find { it.hub.id == hubRowId }?.hub?.profileOwnerId ?: return
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.deleteHubRowWithItems(profileOwnerId, hubRowId)
            persistProfileState()
        }
    }

    fun renameHubRow(hubRowId: String, newTitle: String) {
        val currentHub = _hubRows.value.find { it.hub.id == hubRowId }?.hub ?: return
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.updateHubRow(currentHub.copy(title = newTitle))
            persistProfileState()
        }
    }

    fun changeHubRowShape(hubRowId: String, shape: HubShape) {
        val currentHub = _hubRows.value.find { it.hub.id == hubRowId }?.hub ?: return
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.updateHubRow(currentHub.copy(shape = shape.name))
            persistProfileState()
        }
    }

    fun addCategoryToHubRow(hubRowId: String, config: CatalogConfigEntity) {
        val profileOwnerId = _hubRows.value.find { it.hub.id == hubRowId }?.hub?.profileOwnerId ?: return
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val maxOrder = dao.getMaxHubItemOrder(profileOwnerId, hubRowId) ?: -1
            val item = HubRowItemEntity(
                hubRowId = hubRowId,
                configUniqueId = config.uniqueId,
                title = config.customTitle ?: if (config.catalogName != null) "${config.catalogName} - ${config.catalogType.replaceFirstChar { it.uppercase() }}" else "${config.addonName} - ${config.catalogId}",
                itemOrder = maxOrder + 1,
                profileOwnerId = profileOwnerId
            )
            dao.insertHubRowItem(item, profileOwnerId)
            persistProfileState()
        }
    }

    fun removeCategoryFromHubRow(hubRowId: String, configUniqueId: String) {
        val profileOwnerId = _hubRows.value.find { it.hub.id == hubRowId }?.hub?.profileOwnerId ?: return
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.deleteHubRowItem(profileOwnerId, hubRowId, configUniqueId)
            persistProfileState()
        }
    }

    fun updateHubItemImage(hubRowId: String, configUniqueId: String, imageUrl: String?) {
        val row = _hubRows.value.find { it.hub.id == hubRowId } ?: return
        val itemOwnerId = row.items.find { it.configUniqueId == configUniqueId }?.profileOwnerId ?: return
        val rowOwnerId = row.hub.profileOwnerId
        if (itemOwnerId != rowOwnerId) return
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.updateHubItemImage(rowOwnerId, hubRowId, configUniqueId, imageUrl)
            persistProfileState()
        }
    }

    fun renameHubRowItem(hubRowId: String, configUniqueId: String, newTitle: String) {
        val currentItems = _hubRows.value.find { it.hub.id == hubRowId }?.items ?: return
        val item = currentItems.find { it.configUniqueId == configUniqueId } ?: return
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.updateHubRowItem(item.copy(title = newTitle))
            persistProfileState()
        }
    }

    fun updateHubRowItemsOrder(hubRowId: String, orderedItems: List<HubRowItemEntity>) {
        if (_hubRows.value.none { it.hub.id == hubRowId }) return
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val updatedItems = orderedItems.mapIndexed { index, item ->
                item.copy(hubRowId = hubRowId, itemOrder = index)
            }
            dao.updateHubRowItems(updatedItems)
            persistProfileState()
        }
    }

    fun updateTabLayout(profileId: Int, tab: DashboardTab, layout: String) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val profile = dao.getProfileById(profileId) ?: return@launch
            persistProfile(profile.withLayout(tab, layout))
            persistProfileState()
        }
    }

    fun updateTabHero(profileId: Int, tab: DashboardTab, config: HeroConfig) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val profile = dao.getProfileById(profileId) ?: return@launch
            persistProfile(profile.withHero(tab, config))
            persistProfileState()
        }
    }

    fun updateTmdbHomeRails(profileId: Int, rails: List<String>) {
        val encodedRails = TmdbHomeRails.encode(rails)
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val profile = dao.getProfileById(profileId) ?: return@launch
            persistProfile(profile.copy(homeTmdbRails = encodedRails))
            persistProfileState()
        }
    }

    fun getEditorItems(selectedTab: String, homeTmdbRails: String? = null): List<EditorListItem> {
        val categoryItems = _configs.value
            .filter {
                when (selectedTab) {
                    "home" -> it.showInHome
                    "movies" -> it.showInMovies
                    "series" -> it.showInSeries
                    else -> false
                }
            }
            .map { 
                val order = when(selectedTab) {
                    "home" -> it.homeOrder
                    "movies" -> it.moviesOrder
                    "series" -> it.seriesOrder
                    else -> 0
                }
                EditorListItem.CategoryItem(it, order)
            }

        val hubItems = _hubRows.value
            .filter {
                when (selectedTab) {
                    "home" -> it.hub.showInHome
                    "movies" -> it.hub.showInMovies
                    "series" -> it.hub.showInSeries
                    else -> false
                }
            }
            .map { 
                val order = when(selectedTab) {
                    "home" -> it.hub.homeOrder
                    "movies" -> it.hub.moviesOrder
                    "series" -> it.hub.seriesOrder
                    else -> 0
                }
                EditorListItem.HubRowItem(it.hub, it.items.sortedBy { i -> i.itemOrder }, order) 
            }

        val tmdbItems = if (selectedTab == "home" && homeTmdbRails != null) {
            val configuredIds = homeTmdbRails
            TmdbHomeRails.parse(configuredIds)
                .mapNotNull { id -> TmdbHomeRails.all.find { it.id == id } }
                .mapIndexed { index, rail ->
                    EditorListItem.TmdbRecommendationItem(rail, personalizedRailOrder(10 + index))
                }
        } else emptyList()

        return (hubItems + categoryItems + tmdbItems).sortedBy { it.order }
    }

}
