package com.saab.tv.data.model

import androidx.room.Embedded
import androidx.room.Relation

data class HubRowWithItems(
    @Embedded val hub: HubRowEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "hubRowId"
    )
    val items: List<HubRowItemEntity>
)
