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
import com.saab.tv.data.model.LEGACY_PROFILE_OWNER_ID
import com.saab.tv.data.profile.withCanonicalSharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import androidx.room.Delete

@Dao
interface AddonDao {

    @Query("SELECT COALESCE((SELECT portableId FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT portableId FROM profiles ORDER BY id LIMIT 1), 'legacy-default')")
    suspend fun getActiveProfileOwnerId(): String?

    private suspend fun activeRuntimeOwner(): String = getActiveProfileOwnerId()?.takeIf(String::isNotBlank)
        ?: LEGACY_PROFILE_OWNER_ID

    @Query("SELECT * FROM addons WHERE profileOwnerId = COALESCE((SELECT portableId FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT portableId FROM profiles ORDER BY id LIMIT 1), 'legacy-default') ORDER BY sortOrder ASC")
    fun getAllAddons(): Flow<List<AddonEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAddonRow(addon: AddonEntity)

    @Transaction
    suspend fun insertAddon(addon: AddonEntity) {
        val owner = activeRuntimeOwner()
        if (addon.profileOwnerId != LEGACY_PROFILE_OWNER_ID && addon.profileOwnerId != owner) return
        insertAddonRow(addon.copy(profileOwnerId = owner))
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAddonRows(addons: List<AddonEntity>)

    @Transaction
    suspend fun insertAddons(addons: List<AddonEntity>) {
        val owner = activeRuntimeOwner()
        insertAddonRows(addons.filter { it.profileOwnerId == LEGACY_PROFILE_OWNER_ID || it.profileOwnerId == owner }
            .map { it.copy(profileOwnerId = owner) })
    }

    @Query("DELETE FROM addons WHERE profileOwnerId = COALESCE((SELECT portableId FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT portableId FROM profiles ORDER BY id LIMIT 1), 'legacy-default') AND transportUrl = :url")
    suspend fun deleteAddonByUrl(url: String)

    @Query("DELETE FROM addons WHERE profileOwnerId = COALESCE((SELECT portableId FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT portableId FROM profiles ORDER BY id LIMIT 1), 'legacy-default')")
    suspend fun clearAddons()

    @Query("SELECT * FROM addons WHERE profileOwnerId = COALESCE((SELECT portableId FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT portableId FROM profiles ORDER BY id LIMIT 1), 'legacy-default') AND transportUrl = :transportUrl")
    suspend fun getAddon(transportUrl: String): AddonEntity?

    @Query("SELECT * FROM addons WHERE profileOwnerId = :profileOwnerId ORDER BY sortOrder ASC")
    suspend fun getAddonsForProfile(profileOwnerId: String): List<AddonEntity>

    @Query("SELECT * FROM catalog_configs WHERE profileOwnerId = COALESCE((SELECT portableId FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT portableId FROM profiles ORDER BY id LIMIT 1), 'legacy-default')")
    fun getAllCatalogConfigs(): Flow<List<CatalogConfigEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCatalogConfigRow(config: CatalogConfigEntity)

    @Transaction
    suspend fun saveCatalogConfig(config: CatalogConfigEntity) {
        val owner = activeRuntimeOwner()
        if (config.profileOwnerId != LEGACY_PROFILE_OWNER_ID && config.profileOwnerId != owner) return
        saveCatalogConfigRow(config.copy(profileOwnerId = owner))
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCatalogConfigRows(configs: List<CatalogConfigEntity>)

    @Transaction
    suspend fun saveCatalogConfigs(configs: List<CatalogConfigEntity>) {
        val owner = activeRuntimeOwner()
        saveCatalogConfigRows(configs.filter { it.profileOwnerId == LEGACY_PROFILE_OWNER_ID || it.profileOwnerId == owner }
            .map { it.copy(profileOwnerId = owner) })
    }

    @Query("DELETE FROM catalog_configs WHERE profileOwnerId = COALESCE((SELECT portableId FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT portableId FROM profiles ORDER BY id LIMIT 1), 'legacy-default') AND transportUrl = :url")
    suspend fun deleteCatalogConfigs(url: String)

    @Query("DELETE FROM catalog_configs WHERE profileOwnerId = COALESCE((SELECT portableId FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT portableId FROM profiles ORDER BY id LIMIT 1), 'legacy-default')")
    suspend fun clearCatalogConfigs()

    @Query("SELECT * FROM catalog_configs WHERE profileOwnerId = COALESCE((SELECT portableId FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT portableId FROM profiles ORDER BY id LIMIT 1), 'legacy-default') AND uniqueId = :uniqueId")
    suspend fun getCatalogConfig(uniqueId: String): CatalogConfigEntity?

    @Query("SELECT * FROM catalog_configs WHERE profileOwnerId = :profileOwnerId")
    suspend fun getCatalogConfigsForProfile(profileOwnerId: String): List<CatalogConfigEntity>

    @Query("SELECT * FROM profiles")
    fun getProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getProfileById(id: Int): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE portableId = :portableId LIMIT 1")
    suspend fun getProfileByPortableId(portableId: String): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun getProfileFlow(id: Int): Flow<ProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfileRow(profile: ProfileEntity): Long

    @Transaction
    suspend fun insertProfile(profile: ProfileEntity): Long =
        insertProfileRow(profile.withCanonicalSharedPreferences())

    @Update
    suspend fun updateProfileRow(profile: ProfileEntity)

    @Transaction
    suspend fun updateProfile(profile: ProfileEntity) {
        updateProfileRow(profile.withCanonicalSharedPreferences())
    }

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

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND canonicalIdentityKey = :canonicalIdentityKey ORDER BY lastWatched DESC LIMIT 1")
    suspend fun getHistoryItemByCanonicalIdentity(profileId: Int, canonicalIdentityKey: String): WatchHistoryEntity?

    @Query("DELETE FROM watch_history WHERE profileId = :profileId AND id = :id")
    suspend fun deleteHistoryItemForProfile(profileId: Int, id: String): Int

    @Query("DELETE FROM watch_history WHERE profileId = :profileId AND canonicalIdentityKey = :canonicalIdentityKey")
    suspend fun deleteHistoryByCanonicalIdentity(profileId: Int, canonicalIdentityKey: String): Int

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND type = 'series' AND id LIKE :episodePrefix ORDER BY lastWatched DESC")
    suspend fun getSeriesEpisodeHistoryForProfile(profileId: Int, episodePrefix: String): List<WatchHistoryEntity>

    /** Uses the canonical episode index so verified series-ID aliases share resume state. */
    @Query("""
        SELECT * FROM watch_history
        WHERE profileId = :profileId AND type = 'series'
          AND substr(canonicalIdentityKey, 1, length(:canonicalEpisodePrefix)) = :canonicalEpisodePrefix
        ORDER BY lastWatched DESC
    """)
    suspend fun getSeriesEpisodeHistoryByCanonicalPrefix(
        profileId: Int,
        canonicalEpisodePrefix: String
    ): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1) AND id LIKE :prefix || '%'")
    suspend fun getHistoryItemsByPrefix(prefix: String): List<WatchHistoryEntity>

    @Query(
        "SELECT * FROM watch_history " +
            "WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1) AND type = 'series' AND id LIKE :episodePrefix " +
            "ORDER BY lastWatched DESC LIMIT 1"
    )
    suspend fun getLatestSeriesEpisodeHistory(episodePrefix: String): WatchHistoryEntity?

    @Query(
        "SELECT * FROM watch_history WHERE profileId = :profileId AND type = 'series' AND id LIKE :episodePrefix " +
            "ORDER BY lastWatched DESC LIMIT 1"
    )
    suspend fun getLatestSeriesEpisodeHistory(profileId: Int, episodePrefix: String): WatchHistoryEntity?

    @Query("DELETE FROM watch_history WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1) AND id = :id")
    suspend fun deleteHistoryItem(id: String)

    @Query("SELECT * FROM watch_history WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1) AND type = 'series' AND id LIKE :episodePrefix")
    suspend fun getSeriesEpisodeHistory(episodePrefix: String): List<WatchHistoryEntity>

    @Query("DELETE FROM watch_history WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1) AND type = 'series' AND id LIKE :episodePrefix")
    suspend fun deleteSeriesHistory(episodePrefix: String)

    @Query("DELETE FROM watch_history WHERE profileId = :profileId AND type = 'series' AND id LIKE :episodePrefix")
    suspend fun deleteSeriesHistoryForProfile(profileId: Int, episodePrefix: String): Int

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
        val profileId = getActiveProfileId()
        insertHistoryItems(if (profileId == null) items else items.map { it.copy(profileId = profileId) })
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
    suspend fun insertHubRowRecord(row: HubRowEntity)

    @Transaction
    suspend fun insertHubRow(row: HubRowEntity) {
        val owner = activeRuntimeOwner()
        if (row.profileOwnerId != LEGACY_PROFILE_OWNER_ID && row.profileOwnerId != owner) return
        insertHubRowRecord(row.copy(profileOwnerId = owner))
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHubRowRecords(rows: List<HubRowEntity>)

    @Transaction
    suspend fun insertHubRows(rows: List<HubRowEntity>) {
        val owner = activeRuntimeOwner()
        insertHubRowRecords(rows.filter { it.profileOwnerId == LEGACY_PROFILE_OWNER_ID || it.profileOwnerId == owner }
            .map { it.copy(profileOwnerId = owner) })
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHubRowItemRecords(items: List<HubRowItemEntity>)

    @Transaction
    suspend fun insertHubRowItems(items: List<HubRowItemEntity>) {
        val owner = activeRuntimeOwner()
        insertHubRowItemRecords(items.filter { it.profileOwnerId == LEGACY_PROFILE_OWNER_ID || it.profileOwnerId == owner }
            .map { it.copy(profileOwnerId = owner) })
    }

    @Transaction
    suspend fun insertHubRowWithItems(row: HubRowEntity, items: List<HubRowItemEntity>) {
        val owner = activeRuntimeOwner()
        if (row.profileOwnerId != LEGACY_PROFILE_OWNER_ID && row.profileOwnerId != owner) return
        insertHubRow(row)
        insertHubRowItems(items.filter { it.profileOwnerId == LEGACY_PROFILE_OWNER_ID || it.profileOwnerId == owner })
    }

    @Query("SELECT * FROM hub_rows WHERE profileOwnerId = COALESCE((SELECT portableId FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT portableId FROM profiles ORDER BY id LIMIT 1), 'legacy-default') ORDER BY homeOrder ASC, createdAt ASC")
    fun getAllHubRows(): Flow<List<HubRowEntity>>

    @Query("SELECT * FROM hub_row_items WHERE profileOwnerId = COALESCE((SELECT portableId FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT portableId FROM profiles ORDER BY id LIMIT 1), 'legacy-default') ORDER BY itemOrder ASC")
    fun getAllHubRowItems(): Flow<List<HubRowItemEntity>>

    @Query("DELETE FROM hub_rows WHERE profileOwnerId = :profileOwnerId AND id = :hubRowId")
    suspend fun deleteHubRowForProfile(profileOwnerId: String, hubRowId: String)

    @Query("DELETE FROM hub_row_items WHERE profileOwnerId = :profileOwnerId AND hubRowId = :hubRowId")
    suspend fun deleteHubRowItemsForProfile(profileOwnerId: String, hubRowId: String)

    @Query("DELETE FROM hub_row_items WHERE profileOwnerId = COALESCE((SELECT portableId FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT portableId FROM profiles ORDER BY id LIMIT 1), 'legacy-default')")
    suspend fun clearHubRowItems()

    @Query("DELETE FROM hub_rows WHERE profileOwnerId = COALESCE((SELECT portableId FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT portableId FROM profiles ORDER BY id LIMIT 1), 'legacy-default')")
    suspend fun clearHubRows()

    @Transaction
    suspend fun deleteHubRowWithItems(profileOwnerId: String, hubRowId: String) {
        if (profileOwnerId != activeRuntimeOwner()) return
        deleteHubRowItemsForProfile(profileOwnerId, hubRowId)
        deleteHubRowForProfile(profileOwnerId, hubRowId)
    }

    @Query("UPDATE hub_row_items SET customImageUrl = :imageUrl WHERE profileOwnerId = :profileOwnerId AND hubRowId = :hubRowId AND configUniqueId = :configUniqueId")
    suspend fun updateHubItemImageForProfile(profileOwnerId: String, hubRowId: String, configUniqueId: String, imageUrl: String?): Int

    @Transaction
    suspend fun updateHubItemImage(profileOwnerId: String, hubRowId: String, configUniqueId: String, imageUrl: String?) {
        if (profileOwnerId != activeRuntimeOwner()) return
        updateHubItemImageForProfile(profileOwnerId, hubRowId, configUniqueId, imageUrl)
    }

    fun getHubRowsWithItems(): Flow<List<HubRowWithItems>> = combine(getAllHubRows(), getAllHubRowItems()) { rows, items ->
        val itemsByOwnerAndRow = items.groupBy { it.profileOwnerId to it.hubRowId }
        rows.map { row -> HubRowWithItems(row, itemsByOwnerAndRow[row.profileOwnerId to row.id].orEmpty()) }
    }



    @Query("SELECT MAX(homeOrder) FROM hub_rows WHERE profileOwnerId = COALESCE((SELECT portableId FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT portableId FROM profiles ORDER BY id LIMIT 1), 'legacy-default')")
    suspend fun getMaxHubHomeOrder(): Int?

    @Query("SELECT MAX(moviesOrder) FROM hub_rows WHERE profileOwnerId = COALESCE((SELECT portableId FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT portableId FROM profiles ORDER BY id LIMIT 1), 'legacy-default')")
    suspend fun getMaxHubMoviesOrder(): Int?

    @Query("SELECT MAX(seriesOrder) FROM hub_rows WHERE profileOwnerId = COALESCE((SELECT portableId FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT portableId FROM profiles ORDER BY id LIMIT 1), 'legacy-default')")
    suspend fun getMaxHubSeriesOrder(): Int?



    @Query("""
        UPDATE hub_rows
        SET title = :title, shape = :shape, showInHome = :showInHome,
            showInMovies = :showInMovies, showInSeries = :showInSeries,
            homeOrder = :homeOrder, moviesOrder = :moviesOrder,
            seriesOrder = :seriesOrder, createdAt = :createdAt
        WHERE id = :id AND profileOwnerId = :profileOwnerId
    """)
    suspend fun updateHubRowForProfile(
        id: String,
        profileOwnerId: String,
        title: String,
        shape: String,
        showInHome: Boolean,
        showInMovies: Boolean,
        showInSeries: Boolean,
        homeOrder: Int,
        moviesOrder: Int,
        seriesOrder: Int,
        createdAt: Long
    ): Int

    @Transaction
    suspend fun updateHubRow(row: HubRowEntity) {
        val owner = activeRuntimeOwner()
        if (row.profileOwnerId != owner) return
        updateHubRowForProfile(row.id, owner, row.title, row.shape, row.showInHome,
            row.showInMovies, row.showInSeries, row.homeOrder, row.moviesOrder,
            row.seriesOrder, row.createdAt)
    }

    @Transaction
    suspend fun updateHubRows(rows: List<HubRowEntity>) {
        val owner = activeRuntimeOwner()
        rows.forEach { row ->
            if (row.profileOwnerId != owner) return@forEach
            updateHubRowForProfile(row.id, owner, row.title, row.shape, row.showInHome,
                row.showInMovies, row.showInSeries, row.homeOrder, row.moviesOrder,
                row.seriesOrder, row.createdAt)
        }
    }

    @Query("DELETE FROM hub_row_items WHERE profileOwnerId = :profileOwnerId AND hubRowId = :hubRowId AND configUniqueId = :configUniqueId")
    suspend fun deleteHubRowItemForProfile(profileOwnerId: String, hubRowId: String, configUniqueId: String)

    @Transaction
    suspend fun deleteHubRowItem(profileOwnerId: String, hubRowId: String, configUniqueId: String) {
        if (profileOwnerId != activeRuntimeOwner()) return
        deleteHubRowItemForProfile(profileOwnerId, hubRowId, configUniqueId)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHubRowItemRecord(item: HubRowItemEntity)

    @Transaction
    suspend fun insertHubRowItem(item: HubRowItemEntity, profileOwnerId: String) {
        if (profileOwnerId != activeRuntimeOwner()) return
        if (item.profileOwnerId != LEGACY_PROFILE_OWNER_ID && item.profileOwnerId != profileOwnerId) return
        insertHubRowItemRecord(item.copy(profileOwnerId = profileOwnerId))
    }

    @Query("SELECT MAX(itemOrder) FROM hub_row_items WHERE profileOwnerId = :profileOwnerId AND hubRowId = :hubRowId")
    suspend fun getMaxHubItemOrder(profileOwnerId: String, hubRowId: String): Int?

    @Query("""
        UPDATE hub_row_items
        SET title = :title, customImageUrl = :customImageUrl, itemOrder = :itemOrder
        WHERE profileOwnerId = :profileOwnerId AND hubRowId = :hubRowId
          AND configUniqueId = :configUniqueId
    """)
    suspend fun updateHubRowItemForProfile(
        profileOwnerId: String,
        hubRowId: String,
        configUniqueId: String,
        title: String,
        customImageUrl: String?,
        itemOrder: Int
    ): Int

    @Transaction
    suspend fun updateHubRowItem(item: HubRowItemEntity) {
        val owner = activeRuntimeOwner()
        if (item.profileOwnerId != owner) return
        updateHubRowItemForProfile(owner, item.hubRowId, item.configUniqueId,
            item.title, item.customImageUrl, item.itemOrder)
    }

    @Transaction
    suspend fun updateHubRowItems(items: List<HubRowItemEntity>) {
        val owner = activeRuntimeOwner()
        items.forEach { item ->
            if (item.profileOwnerId != owner) return@forEach
            updateHubRowItemForProfile(owner, item.hubRowId, item.configUniqueId,
                item.title, item.customImageUrl, item.itemOrder)
        }
    }

    // ── Watchlist ──

    @Query("SELECT * FROM watchlist WHERE profileId = :profileId ORDER BY addedAt DESC")
    fun getWatchlist(profileId: Int): Flow<List<WatchlistEntity>>

    @Query("SELECT * FROM watchlist WHERE profileId = :profileId AND type = :type ORDER BY addedAt DESC")
    fun getWatchlistByType(profileId: Int, type: String): Flow<List<WatchlistEntity>>

    @Query("SELECT * FROM watchlist WHERE profileId = :profileId ORDER BY addedAt DESC")
    suspend fun getWatchlistOnce(profileId: Int): List<WatchlistEntity>

    @Query("SELECT * FROM watchlist WHERE profileId = :profileId AND id = :id")
    suspend fun getWatchlistItem(profileId: Int, id: String): WatchlistEntity?

    @Query("SELECT * FROM watchlist WHERE profileId = :profileId AND id = :id AND sourceAddonKey = :sourceAddonKey LIMIT 1")
    suspend fun getWatchlistItemFromAddon(profileId: Int, id: String, sourceAddonKey: String): WatchlistEntity?

    @Query("SELECT * FROM watch_history WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1) AND scrobbled = 1 AND watched = 0")
    suspend fun getScrobbledInProgressItems(): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND scrobbled = 1 AND watched = 0")
    suspend fun getScrobbledInProgressItems(profileId: Int): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1) AND scrobbled = 1 AND watched = 1")
    suspend fun getScrobbledWatchedItems(): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND scrobbled = 1 AND watched = 1")
    suspend fun getScrobbledWatchedItems(profileId: Int): List<WatchHistoryEntity>

    @Query("SELECT id FROM watch_history WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1) AND watched = 1")
    fun getWatchedIds(): Flow<List<String>>

    @Query("SELECT id FROM watch_history WHERE profileId = :profileId AND watched = 1")
    fun getWatchedIdsForProfile(profileId: Int): Flow<List<String>>

    /** Raw IDs plus stable identities and every locally verified alias for immediate badges. */
    @Query("""
        SELECT id FROM watch_history WHERE profileId = :profileId AND watched = 1
        UNION SELECT canonicalIdentityKey FROM watch_history
            WHERE profileId = :profileId AND watched = 1 AND canonicalIdentityKey != ''
        UNION SELECT alias.identityKey FROM canonical_identity_aliases AS alias
            WHERE alias.canonicalIdentityKey IN (
                SELECT canonicalIdentityKey FROM watch_history
                WHERE profileId = :profileId AND watched = 1 AND canonicalIdentityKey != ''
            )
    """)
    fun getWatchedBadgeKeysForProfile(profileId: Int): Flow<List<String>>

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

    @Query("SELECT * FROM series_next_up WHERE profileId = :profileId AND seriesId = :seriesId")
    suspend fun getSeriesNextUpForProfile(profileId: Int, seriesId: String): SeriesNextUpEntity?

    @Query("SELECT * FROM series_next_up WHERE profileId = :profileId AND canonicalIdentityKey = :canonicalIdentityKey ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getSeriesNextUpByCanonicalIdentity(profileId: Int, canonicalIdentityKey: String): SeriesNextUpEntity?

    @Query("SELECT * FROM series_next_up WHERE profileId = :profileId")
    suspend fun getSeriesNextUpForProfile(profileId: Int): List<SeriesNextUpEntity>

    @Query("SELECT * FROM series_next_up WHERE profileId = :profileId")
    fun getSeriesNextUpForProfileFlow(profileId: Int): Flow<List<SeriesNextUpEntity>>

    @Query("DELETE FROM series_next_up WHERE profileId = COALESCE((SELECT id FROM profiles WHERE isActive = 1 LIMIT 1), (SELECT MIN(id) FROM profiles), 1) AND seriesId = :seriesId")
    suspend fun deleteSeriesNextUp(seriesId: String)

    @Query("DELETE FROM series_next_up WHERE profileId = :profileId AND seriesId = :seriesId")
    suspend fun deleteSeriesNextUpForProfile(profileId: Int, seriesId: String): Int

    @Query("DELETE FROM series_next_up WHERE profileId = :profileId AND canonicalIdentityKey = :canonicalIdentityKey")
    suspend fun deleteSeriesNextUpByCanonicalIdentity(profileId: Int, canonicalIdentityKey: String): Int

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

    @Query("SELECT * FROM watchlist WHERE profileId = :profileId AND canonicalIdentityKey = :canonicalIdentityKey ORDER BY addedAt DESC")
    suspend fun getWatchlistItemsByCanonicalIdentity(profileId: Int, canonicalIdentityKey: String): List<WatchlistEntity>

    @Query("""
        SELECT id FROM watchlist WHERE profileId = :profileId
        UNION SELECT canonicalIdentityKey FROM watchlist
            WHERE profileId = :profileId AND canonicalIdentityKey != ''
        UNION SELECT alias.identityKey FROM canonical_identity_aliases AS alias
            WHERE alias.canonicalIdentityKey IN (
                SELECT canonicalIdentityKey FROM watchlist
                WHERE profileId = :profileId AND canonicalIdentityKey != ''
            )
    """)
    fun getWatchlistBadgeKeysForProfile(profileId: Int): Flow<List<String>>

    @Query("DELETE FROM watchlist WHERE profileId = :profileId AND canonicalIdentityKey = :canonicalIdentityKey")
    suspend fun removeWatchlistItemsByCanonicalIdentity(profileId: Int, canonicalIdentityKey: String): Int

    @Query("DELETE FROM watchlist WHERE profileId = :profileId AND id = :id")
    suspend fun removeFromWatchlist(profileId: Int, id: String)

    @Query("DELETE FROM watchlist WHERE profileId = :profileId AND id = :id AND sourceAddonKey = :sourceAddonKey")
    suspend fun removeWatchlistItemFromAddon(profileId: Int, id: String, sourceAddonKey: String)

    @Query("DELETE FROM watchlist WHERE profileId = :profileId")
    suspend fun deleteWatchlistForProfile(profileId: Int)

    @Transaction
    suspend fun replaceRuntimeState(
        addons: List<AddonEntity>,
        catalogConfigs: List<CatalogConfigEntity>,
        hubRows: List<HubRowEntity>,
        hubRowItems: List<HubRowItemEntity>
    ) = replaceRuntimeState(activeRuntimeOwner(), addons, catalogConfigs, hubRows, hubRowItems)

    @Transaction
    suspend fun replaceRuntimeState(
        profileOwnerId: String,
        addons: List<AddonEntity>,
        catalogConfigs: List<CatalogConfigEntity>,
        hubRows: List<HubRowEntity>,
        hubRowItems: List<HubRowItemEntity>
    ) {
        deleteRuntimeState(profileOwnerId)

        if (addons.isNotEmpty()) insertAddonRows(addons.map { it.copy(profileOwnerId = profileOwnerId) })
        if (catalogConfigs.isNotEmpty()) saveCatalogConfigRows(catalogConfigs.map { it.copy(profileOwnerId = profileOwnerId) })
        if (hubRows.isNotEmpty()) insertHubRowRecords(hubRows.map { it.copy(profileOwnerId = profileOwnerId) })
        if (hubRowItems.isNotEmpty()) insertHubRowItemRecords(hubRowItems.map { it.copy(profileOwnerId = profileOwnerId) })
    }

    @Query("DELETE FROM addons WHERE profileOwnerId = :profileOwnerId")
    suspend fun deleteAddonsForProfile(profileOwnerId: String)

    @Query("DELETE FROM catalog_configs WHERE profileOwnerId = :profileOwnerId")
    suspend fun deleteCatalogConfigsForProfile(profileOwnerId: String)

    @Query("DELETE FROM hub_row_items WHERE profileOwnerId = :profileOwnerId")
    suspend fun deleteHubRowItemsForProfile(profileOwnerId: String)

    @Query("DELETE FROM hub_rows WHERE profileOwnerId = :profileOwnerId")
    suspend fun deleteHubRowsForProfile(profileOwnerId: String)

    @Transaction
    suspend fun deleteRuntimeState(profileOwnerId: String) {
        deleteHubRowItemsForProfile(profileOwnerId)
        deleteHubRowsForProfile(profileOwnerId)
        deleteCatalogConfigsForProfile(profileOwnerId)
        deleteAddonsForProfile(profileOwnerId)
    }

    @Transaction
    suspend fun getRuntimeStateForProfile(profileOwnerId: String): RuntimeStateRows = RuntimeStateRows(
        addons = getAddonsForProfile(profileOwnerId),
        catalogConfigs = getCatalogConfigsForProfile(profileOwnerId),
        hubRows = getHubRowsForProfile(profileOwnerId),
        hubRowItems = getHubRowItemsForProfile(profileOwnerId)
    )

    @Query("SELECT * FROM hub_rows WHERE profileOwnerId = :profileOwnerId ORDER BY homeOrder ASC, createdAt ASC")
    suspend fun getHubRowsForProfile(profileOwnerId: String): List<HubRowEntity>

    @Query("SELECT * FROM hub_row_items WHERE profileOwnerId = :profileOwnerId ORDER BY itemOrder ASC")
    suspend fun getHubRowItemsForProfile(profileOwnerId: String): List<HubRowItemEntity>
}

data class RuntimeStateRows(
    val addons: List<AddonEntity>,
    val catalogConfigs: List<CatalogConfigEntity>,
    val hubRows: List<HubRowEntity>,
    val hubRowItems: List<HubRowItemEntity>
)
