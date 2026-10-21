package com.saab.tv.data.repository

import com.saab.tv.data.model.CanonicalTitleEntity
import com.saab.tv.domain.schema.ContentIdentity

/** Read/write boundary for rebuildable, provider-independent title facts. */
interface CanonicalTitleStore {
    suspend fun getTitle(identity: ContentIdentity, locale: String = ""): CanonicalTitleEntity?
    suspend fun putTitle(title: CanonicalTitleEntity)

    suspend fun getFreshTitle(
        identity: ContentIdentity,
        locale: String,
        maxAgeMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): CanonicalTitleEntity? {
        require(maxAgeMillis > 0) { "maxAgeMillis must be positive" }
        val title = getTitle(identity, locale) ?: return null
        val ageMillis = nowMillis - title.lastFetchedAtMillis
        return title.takeIf { ageMillis in 0..maxAgeMillis }
    }
}

internal object NoopCanonicalTitleStore : CanonicalTitleStore {
    override suspend fun getTitle(identity: ContentIdentity, locale: String): CanonicalTitleEntity? = null
    override suspend fun putTitle(title: CanonicalTitleEntity) = Unit
}
