package com.saab.tv.data.model

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "hub_rows",
    primaryKeys = ["profileOwnerId", "id"],
    indices = [Index("profileOwnerId")]
)
data class HubRowEntity(
    val id: String,
    val title: String = "Hub Row",
    val shape: String,

    val showInHome: Boolean = false,
    val showInMovies: Boolean = false,
    val showInSeries: Boolean = false,

    val homeOrder: Int = 999,
    val moviesOrder: Int = 999,
    val seriesOrder: Int = 999,

    val createdAt: Long = System.currentTimeMillis(),

    /** Stable profile ownership; unlike the Room profile row ID this survives cloud restore. */
    val profileOwnerId: String = LEGACY_PROFILE_OWNER_ID
)
