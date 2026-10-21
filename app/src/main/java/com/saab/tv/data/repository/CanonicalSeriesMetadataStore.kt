package com.saab.tv.data.repository

import com.saab.tv.data.model.CanonicalEpisodeEntity
import com.saab.tv.data.model.CanonicalEpisodeListEntity
import com.saab.tv.data.model.CanonicalSeriesSnapshot
import com.saab.tv.data.model.CanonicalTitleEntity
import com.saab.tv.domain.schema.ContentIdentity

/** Local, rebuildable store for an authoritative complete series episode listing. */
interface CanonicalSeriesMetadataStore {
    suspend fun getSeriesSnapshot(identity: ContentIdentity, locale: String = ""): CanonicalSeriesSnapshot?

    suspend fun putSeriesSnapshot(
        title: CanonicalTitleEntity,
        manifest: CanonicalEpisodeListEntity,
        episodes: List<CanonicalEpisodeEntity>
    )
}

internal object NoopCanonicalSeriesMetadataStore : CanonicalSeriesMetadataStore {
    override suspend fun getSeriesSnapshot(identity: ContentIdentity, locale: String): CanonicalSeriesSnapshot? = null
    override suspend fun putSeriesSnapshot(
        title: CanonicalTitleEntity,
        manifest: CanonicalEpisodeListEntity,
        episodes: List<CanonicalEpisodeEntity>
    ) = Unit
}
