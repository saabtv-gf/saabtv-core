package com.saab.tv.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.saab.tv.data.model.AddonEntity
import com.saab.tv.data.model.CatalogConfigEntity
import com.saab.tv.data.model.HubRowEntity
import com.saab.tv.data.model.HubRowWithItems
import com.saab.tv.data.model.HubRowItemEntity
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.ThemeEntity
import com.saab.tv.data.model.SeriesNextUpEntity
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.model.WatchlistEntity
import kotlinx.coroutines.flow.Flow
import androidx.room.Delete

@Dao
interface AddonDao {

    @Query("SELECT * FROM addons ORDER BY sortOrder ASC")
    fun getAllAddons(): Flow<List<AddonEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAddon(addon: AddonEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAddons(addons: List<AddonEntity>)

    @Query("DELETE FROM addons WHERE transportUrl = :url")
    suspend fun deleteAddonByUrl(url: String)

    @Query("DELETE FROM addons")
    suspend fun clearAddons()

    @Query("SELECT * FROM addons WHERE transportUrl = :transportUrl")
    suspend fun getAddon(transportUrl: String): AddonEntity?

    @Query("SELECT * FROM catalog_configs")
    fun getAllCatalogConfigs(): Flow<List<CatalogConfigEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCatalogConfig(config: CatalogConfigEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCatalogConfigs(configs: List<CatalogConfigEntity>)

    @Query("DELETE FROM catalog_configs WHERE transportUrl = :url")
    suspend fun deleteCatalogConfigs(url: String)

    @Query("DELETE FROM catalog_configs")
    suspend fun clearCatalogConfigs()

    @Query("SELECT * FROM catalog_configs WHERE uniqueId = :uniqueId")
    suspend fun getCatalogConfig(uniqueId: String): CatalogConfigEntity?

    @Query("SELECT * FROM profiles")
    fun getProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getProfileById(id: Int): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun getProfileFlow(id: Int): Flow<ProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: ProfileEntity): Long

    @Update
    suspend fun updateProfile(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteProfile(id: Int)

    @Query("SELECT * FROM watch_history WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1) ORDER BY lastWatched DESC")
    fun getWatchHistory(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId ORDER BY lastWatched DESC")
    fun getWatchHistoryForProfile(profileId: Int): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId ORDER BY lastWatched DESC")
    suspend fun getWatchHistoryForProfileOnce(profileId: Int): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1)")
    suspend fun getAllWatchHistoryOnce(): List<WatchHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(item: WatchHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryItems(items: List<WatchHistoryEntity>)

    @Transaction
    suspend fun mergeRemotePlaybackHistory(items: List<WatchHistoryEntity>, allowMissing: Boolean) {
        for (incoming in items) {
            if (getProfileById(incoming.profileId) == null || incoming.lastWatched <= 0) continue
            val current = getHistoryItemForProfile(incoming.profileId, incoming.id)
            if ((current == null && allowMissing) ||
                (current != null && incoming.lastWatched > current.lastWatched)) insertHistory(incoming)
        }
    }

    @Query("DELETE FROM watch_history WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1)")
    suspend fun clearWatchHistory()

    @Query("SELECT * FROM watch_history WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1) AND id = :id")
    suspend fun getHistoryItem(id: String): WatchHistoryEntity?

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND id = :id")
    suspend fun getHistoryItemForProfile(profileId: Int, id: String): WatchHistoryEntity?

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND type = 'series' AND id LIKE :episodePrefix ORDER BY lastWatched DESC")
    suspend fun getSeriesEpisodeHistoryForProfile(profileId: Int, episodePrefix: String): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1) AND id LIKE :prefix || '%'")
    suspend fun getHistoryItemsByPrefix(prefix: String): List<WatchHistoryEntity>

    @Query(
        "SELECT * FROM watch_history " +
            "WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1) AND type = 'series' AND id LIKE :episodePrefix " +
            "ORDER BY lastWatched DESC LIMIT 1"
    )
    suspend fun getLatestSeriesEpisodeHistory(episodePrefix: String): WatchHistoryEntity?

    @Query("DELETE FROM watch_history WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1) AND id = :id")
    suspend fun deleteHistoryItem(id: String)

    @Query("SELECT * FROM watch_history WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1) AND type = 'series' AND id LIKE :episodePrefix")
    suspend fun getSeriesEpisodeHistory(episodePrefix: String): List<WatchHistoryEntity>

    @Query("DELETE FROM watch_history WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1) AND type = 'series' AND id LIKE :episodePrefix")
    suspend fun deleteSeriesHistory(episodePrefix: String)

    @Query("SELECT id FROM profiles WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveProfileId(): Int?

    @Query("UPDATE profiles SET isActive = 0")
    suspend fun clearActiveProfile()

    @Query("UPDATE profiles SET isActive = 1 WHERE id = :profileId")
    suspend fun markProfileActive(profileId: Int)

    @Transaction
    suspend fun activateProfile(profileId: Int) {
        clearActiveProfile()
        markProfileActive(profileId)
    }

    @Transaction
    suspend fun upsertHistory(item: WatchHistoryEntity) {
        val profileId = getActiveProfileId() ?: item.profileId
        insertHistory(item.copy(profileId = profileId))
    }

    @Transaction
    suspend fun upsertHistoryItems(items: List<WatchHistoryEntity>) {
        val profileId = getActiveProfileId() ?: 1
        insertHistoryItems(items.map { it.copy(profileId = profileId) })
    }

    @Query("SELECT * FROM themes")
    fun getAllThemes(): Flow<List<ThemeEntity>>

    @Query("SELECT * FROM themes WHERE id = :id")
    suspend fun getThemeById(id: String): ThemeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTheme(theme: ThemeEntity)

    @Delete
    suspend fun deleteTheme(theme: ThemeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHubRow(row: HubRowEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHubRows(rows: List<HubRowEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHubRowItems(items: List<HubRowItemEntity>)

    @Transaction
    suspend fun insertHubRowWithItems(row: HubRowEntity, items: List<HubRowItemEntity>) {
        insertHubRow(row)
        insertHubRowItems(items)
    }

    @Query("SELECT * FROM hub_rows ORDER BY homeOrder ASC, createdAt ASC")
    fun getAllHubRows(): Flow<List<HubRowEntity>>

    @Query("SELECT * FROM hub_row_items ORDER BY itemOrder ASC")
    fun getAllHubRowItems(): Flow<List<HubRowItemEntity>>

    @Query("DELETE FROM hub_rows WHERE id = :hubRowId")
    suspend fun deleteHubRow(hubRowId: String)

    @Query("DELETE FROM hub_row_items WHERE hubRowId = :hubRowId")
    suspend fun deleteHubRowItems(hubRowId: String)

    @Query("DELETE FROM hub_row_items")
    suspend fun clearHubRowItems()

    @Query("DELETE FROM hub_rows")
    suspend fun clearHubRows()

    @Transaction
    suspend fun deleteHubRowWithItems(hubRowId: String) {
        deleteHubRowItems(hubRowId)
        deleteHubRow(hubRowId)
    }

    @Query("UPDATE hub_row_items SET customImageUrl = :imageUrl WHERE hubRowId = :hubRowId AND configUniqueId = :configUniqueId")
    suspend fun updateHubItemImage(hubRowId: String, configUniqueId: String, imageUrl: String?)

    @Transaction
    @Query("SELECT * FROM hub_rows ORDER BY homeOrder ASC, createdAt ASC")
    fun getHubRowsWithItems(): Flow<List<HubRowWithItems>>



    @Query("SELECT MAX(homeOrder) FROM hub_rows")
    suspend fun getMaxHubHomeOrder(): Int?

    @Query("SELECT MAX(moviesOrder) FROM hub_rows")
    suspend fun getMaxHubMoviesOrder(): Int?

    @Query("SELECT MAX(seriesOrder) FROM hub_rows")
    suspend fun getMaxHubSeriesOrder(): Int?



    @Update
    suspend fun updateHubRow(row: HubRowEntity)

    @Update
    suspend fun updateHubRows(rows: List<HubRowEntity>)

    @Query("DELETE FROM hub_row_items WHERE hubRowId = :hubRowId AND configUniqueId = :configUniqueId")
    suspend fun deleteHubRowItem(hubRowId: String, configUniqueId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHubRowItem(item: HubRowItemEntity)

    @Query("SELECT MAX(itemOrder) FROM hub_row_items WHERE hubRowId = :hubRowId")
    suspend fun getMaxHubItemOrder(hubRowId: String): Int?

    @Update
    suspend fun updateHubRowItem(item: HubRowItemEntity)

    @Update
    suspend fun updateHubRowItems(items: List<HubRowItemEntity>)

    // ── Watchlist ──

    @Query("SELECT * FROM watchlist WHERE profileId = :profileId ORDER BY addedAt DESC")
    fun getWatchlist(profileId: Int): Flow<List<WatchlistEntity>>

    @Query("SELECT * FROM watchlist WHERE profileId = :profileId AND type = :type ORDER BY addedAt DESC")
    fun getWatchlistByType(profileId: Int, type: String): Flow<List<WatchlistEntity>>

    @Query("SELECT * FROM watchlist WHERE profileId = :profileId ORDER BY addedAt DESC")
    suspend fun getWatchlistOnce(profileId: Int): List<WatchlistEntity>

    @Query("SELECT * FROM watchlist WHERE profileId = :profileId AND id = :id")
    suspend fun getWatchlistItem(profileId: Int, id: String): WatchlistEntity?

    @Query("SELECT * FROM watch_history WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1) AND scrobbled = 1 AND watched = 0")
    suspend fun getScrobbledInProgressItems(): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1) AND scrobbled = 1 AND watched = 1")
    suspend fun getScrobbledWatchedItems(): List<WatchHistoryEntity>

    @Query("SELECT id FROM watch_history WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1) AND watched = 1")
    fun getWatchedIds(): Flow<List<String>>

    @Query("SELECT id FROM watch_history WHERE profileId = :profileId AND watched = 1")
    fun getWatchedIdsForProfile(profileId: Int): Flow<List<String>>

    @Query("UPDATE watch_history SET poster = :poster, background = :background, logo = :logo WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1) AND id = :id")
    suspend fun updateHistoryImages(id: String, poster: String?, background: String?, logo: String?)

    // ── Series Next Up ──

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeriesNextUp(entry: SeriesNextUpEntity)

    @Transaction
    suspend fun upsertSeriesNextUp(entry: SeriesNextUpEntity) {
        val profileId = getActiveProfileId() ?: entry.profileId
        insertSeriesNextUp(entry.copy(profileId = profileId))
    }

    @Query("SELECT * FROM series_next_up WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1) AND isComplete = 0 ORDER BY updatedAt DESC")
    fun getActiveSeriesNextUp(): Flow<List<SeriesNextUpEntity>>

    @Query("SELECT * FROM series_next_up WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1) AND seriesId = :seriesId")
    suspend fun getSeriesNextUp(seriesId: String): SeriesNextUpEntity?

    @Query("SELECT * FROM series_next_up WHERE profileId = :profileId")
    suspend fun getSeriesNextUpForProfile(profileId: Int): List<SeriesNextUpEntity>

    @Query("SELECT * FROM series_next_up WHERE profileId = :profileId")
    fun getSeriesNextUpForProfileFlow(profileId: Int): Flow<List<SeriesNextUpEntity>>

    @Query("DELETE FROM series_next_up WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1) AND seriesId = :seriesId")
    suspend fun deleteSeriesNextUp(seriesId: String)

    @Query("DELETE FROM watch_history WHERE profileId = :profileId")
    suspend fun deleteHistoryForProfile(profileId: Int)

    @Query("DELETE FROM series_next_up WHERE profileId = :profileId")
    suspend fun deleteSeriesNextUpForProfile(profileId: Int)

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE profileId = :profileId AND id = :id)")
    suspend fun isInWatchlist(profileId: Int, id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE profileId = :profileId AND id = :id)")
    fun isInWatchlistFlow(profileId: Int, id: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addToWatchlist(item: WatchlistEntity)

    @Query("DELETE FROM watchlist WHERE profileId = :profileId AND id = :id")
    suspend fun removeFromWatchlist(profileId: Int, id: String)

    @Query("DELETE FROM watchlist WHERE profileId = :profileId")
    suspend fun deleteWatchlistForProfile(profileId: Int)

    @Transaction
    suspend fun replaceRuntimeState(
        addons: List<AddonEntity>,
        catalogConfigs: List<CatalogConfigEntity>,
        hubRows: List<HubRowEntity>,
        hubRowItems: List<HubRowItemEntity>
    ) {
        // Replace addon/catalog/hub state from snapshot
        clearHubRowItems()
        clearHubRows()
        clearCatalogConfigs()
        clearAddons()

        if (addons.isNotEmpty()) insertAddons(addons)
        if (catalogConfigs.isNotEmpty()) saveCatalogConfigs(catalogConfigs)
        if (hubRows.isNotEmpty()) insertHubRows(hubRows)
        if (hubRowItems.isNotEmpty()) insertHubRowItems(hubRowItems)
    }
}
