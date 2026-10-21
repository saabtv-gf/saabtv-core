package com.saab.tv.data.model

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "hub_row_items",
    primaryKeys = ["profileOwnerId", "hubRowId", "configUniqueId"],
    indices = [Index(value = ["profileOwnerId", "hubRowId"])]
)
data class HubRowItemEntity(
    val hubRowId: String,
    val configUniqueId: String,
    val title: String,
    val customImageUrl: String? = null,
    val itemOrder: Int = 0,

    /** Stable profile ownership; unlike the Room profile row ID this survives cloud restore. */
    val profileOwnerId: String = LEGACY_PROFILE_OWNER_ID
)
