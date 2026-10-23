package com.saab.tv.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.saab.tv.data.model.AddonEntity
import com.saab.tv.data.model.CatalogConfigEntity
import com.saab.tv.data.model.HubRowEntity
import com.saab.tv.data.model.HubRowItemEntity
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.model.ThemeEntity
import com.saab.tv.data.model.SeriesNextUpEntity
import com.saab.tv.data.model.WatchHistoryEntity
import com.saab.tv.data.model.WatchlistEntity
import com.saab.tv.data.model.CanonicalTitleEntity
import com.saab.tv.data.model.CanonicalIdentityAliasEntity
import com.saab.tv.data.model.CanonicalEpisodeEntity
import com.saab.tv.data.model.CanonicalEpisodeListEntity
import com.saab.tv.data.model.SyncOutboxEntity
import com.saab.tv.data.model.SyncEntityRevisionEntity
import com.saab.tv.data.model.SyncClientStateEntity


@Database(
    entities = [
        AddonEntity::class,
        ProfileEntity::class,
        WatchHistoryEntity::class,
        CatalogConfigEntity::class,
        ThemeEntity::class,
        HubRowEntity::class,
        HubRowItemEntity::class,
        WatchlistEntity::class,
        SeriesNextUpEntity::class,
        CanonicalTitleEntity::class,
        CanonicalIdentityAliasEntity::class,
        CanonicalEpisodeEntity::class,
        CanonicalEpisodeListEntity::class,
        SyncOutboxEntity::class,
        SyncEntityRevisionEntity::class,
        SyncClientStateEntity::class
    ],
    version = 67
)
abstract class SaabTvDatabase : RoomDatabase() {
    abstract fun addonDao(): AddonDao
    abstract fun canonicalMetadataDao(): CanonicalMetadataDao
    abstract fun syncOutboxDao(): SyncOutboxDao
}
