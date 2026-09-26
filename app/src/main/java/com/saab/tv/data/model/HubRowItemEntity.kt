package com.saab.tv.data.model

import androidx.room.Entity

@Entity(
    tableName = "hub_row_items",
    primaryKeys = ["hubRowId", "configUniqueId"]
)
data class HubRowItemEntity(
    val hubRowId: String,
    val configUniqueId: String,
    val title: String,
    val customImageUrl: String? = null,
    val itemOrder: Int = 0
)
