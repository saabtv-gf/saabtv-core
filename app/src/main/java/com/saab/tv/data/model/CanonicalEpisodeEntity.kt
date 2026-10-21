package com.saab.tv.data.model

import androidx.room.Entity
import androidx.room.Index

/** Rebuildable episode facts; episode IDs remain exactly as supplied by Cinemeta. */
@Entity(
    tableName = "canonical_episodes",
    primaryKeys = ["seriesIdentityKey", "locale", "episodeKey"],
    indices = [Index(value = ["observedAtMillis"])]
)
data class CanonicalEpisodeEntity(
    val seriesIdentityKey: String,
    val locale: String,
    val episodeKey: String,
    val id: String,
    val title: String,
    val released: String?,
    val thumbnail: String?,
    val overview: String?,
    val season: Int,
    val episode: Int,
    val episodeOrder: Int,
    val observedAtMillis: Long
)

/** Marks a complete provider episode-list fetch, including an intentionally empty list. */
@Entity(tableName = "canonical_episode_lists", primaryKeys = ["seriesIdentityKey", "locale"])
data class CanonicalEpisodeListEntity(
    val seriesIdentityKey: String,
    val locale: String,
    val source: String,
    val episodeCount: Int,
    val observedAtMillis: Long
)

data class CanonicalSeriesSnapshot(
    val manifest: CanonicalEpisodeListEntity,
    val episodes: List<CanonicalEpisodeEntity>
)
