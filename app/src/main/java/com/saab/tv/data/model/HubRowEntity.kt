package com.saab.tv.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hub_rows")
data class HubRowEntity(
    @PrimaryKey val id: String,
    val title: String = "Hub Row",
    val shape: String,

    val showInHome: Boolean = false,
    val showInMovies: Boolean = false,
    val showInSeries: Boolean = false,

    val homeOrder: Int = 999,
    val moviesOrder: Int = 999,
    val seriesOrder: Int = 999,

    val createdAt: Long = System.currentTimeMillis()
)
