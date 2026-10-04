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
        SeriesNextUpEntity::class
    ],
    version = 52
)
abstract class SaabTvDatabase : RoomDatabase() {
    abstract fun addonDao(): AddonDao
}
