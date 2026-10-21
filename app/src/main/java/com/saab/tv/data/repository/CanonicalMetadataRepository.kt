package com.saab.tv.data.repository

import com.saab.tv.data.local.CanonicalMetadataDao
import com.saab.tv.data.model.CanonicalTitleEntity
import com.saab.tv.data.model.CanonicalEpisodeEntity
import com.saab.tv.data.model.CanonicalEpisodeListEntity
import com.saab.tv.data.model.CanonicalSeriesSnapshot
import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.IdentityNamespace
import com.saab.tv.domain.schema.CanonicalEntityKey
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

interface CanonicalIdentityStore {
    suspend fun resolveAlias(identity: ContentIdentity, namespace: IdentityNamespace): ContentIdentity?
    suspend fun linkVerifiedIdentities(left: ContentIdentity, right: ContentIdentity)
    suspend fun canonicalKey(identity: ContentIdentity): String = identity.stableKey

    /** Known, verified provider identities for this title. Unknown IDs stay isolated. */
    suspend fun verifiedIdentities(identity: ContentIdentity): List<ContentIdentity> = listOf(identity)

    suspend fun canonicalTitleKey(id: String, type: String, addonInstallationId: String? = null): String {
        val identity = CanonicalEntityKey.titleIdentity(id, type, addonInstallationId)
        return identity?.let { canonicalKey(it) } ?: CanonicalEntityKey.title(id, type, addonInstallationId)
    }

    suspend fun canonicalPlayableKey(id: String, type: String, addonInstallationId: String? = null): String {
        val seriesIdentity = CanonicalEntityKey.playableSeriesIdentity(id, type, addonInstallationId)
        val canonicalSeriesKey = seriesIdentity?.let { canonicalKey(it) }
        return CanonicalEntityKey.playable(id, type, addonInstallationId, canonicalSeriesKey)
    }
}

internal object NoopCanonicalIdentityStore : CanonicalIdentityStore {
    override suspend fun resolveAlias(identity: ContentIdentity, namespace: IdentityNamespace): ContentIdentity? = null
    override suspend fun linkVerifiedIdentities(left: ContentIdentity, right: ContentIdentity) = Unit
}

/** Shared local cache/repository for canonical title facts and verified external IDs. */
@Singleton
class CanonicalMetadataRepository @Inject constructor(
    private val dao: CanonicalMetadataDao
) : CanonicalIdentityStore, CanonicalTitleStore, CanonicalSeriesMetadataStore {
    override suspend fun getTitle(identity: ContentIdentity, locale: String): CanonicalTitleEntity? {
        val canonicalKey = dao.getAlias(identity.stableKey)?.canonicalIdentityKey ?: identity.stableKey
        return dao.getTitle(canonicalKey, locale.trim().replace('_', '-').lowercase(Locale.ROOT))
    }

    override suspend fun putTitle(title: CanonicalTitleEntity) {
        // Callers may still hold a provider identity when a verified alias has
        // already selected a different canonical root. Normalize writes as well
        // as reads so facts cannot be stranded under an alias key.
        val canonicalKey = dao.getAlias(title.identityKey)?.canonicalIdentityKey ?: title.identityKey
        dao.putCanonicalTitle(title.copy(
            identityKey = canonicalKey,
            locale = title.locale.trim().replace('_', '-').lowercase(Locale.ROOT)
        ))
        // Metadata is rebuildable. Keep a fixed row ceiling and age out old cache facts.
        dao.deleteTitlesOlderThan(System.currentTimeMillis() - TITLE_RETENTION_MILLIS)
        dao.retainNewestTitles(MAX_TITLE_ROWS)
        dao.retainNewestTitleDetails(MAX_DETAILS_ROWS)
    }

    override suspend fun resolveAlias(identity: ContentIdentity, namespace: IdentityNamespace): ContentIdentity? =
        dao.resolveAlias(identity, namespace)

    override suspend fun linkVerifiedIdentities(left: ContentIdentity, right: ContentIdentity) {
        dao.linkVerified(left, right, System.currentTimeMillis())
    }

    override suspend fun canonicalKey(identity: ContentIdentity): String =
        dao.getAlias(identity.stableKey)?.canonicalIdentityKey ?: identity.stableKey

    override suspend fun verifiedIdentities(identity: ContentIdentity): List<ContentIdentity> {
        val rootKey = dao.getAlias(identity.stableKey)?.canonicalIdentityKey ?: identity.stableKey
        val aliases = dao.getAliasesForCanonicals(listOf(rootKey)).mapNotNull { alias ->
            val namespace = runCatching { IdentityNamespace.valueOf(alias.namespace) }.getOrNull() ?: return@mapNotNull null
            val kind = com.saab.tv.domain.schema.ContentKind.fromProvider(alias.kind) ?: return@mapNotNull null
            ContentIdentity.from(namespace, kind, alias.externalId, alias.providerInstallationId)
        }
        return (aliases + identity).distinct()
    }

    override suspend fun getSeriesSnapshot(identity: ContentIdentity, locale: String): CanonicalSeriesSnapshot? {
        val key = dao.getAlias(identity.stableKey)?.canonicalIdentityKey ?: identity.stableKey
        return dao.getSeriesSnapshot(key, locale.trim().replace('_', '-').lowercase(Locale.ROOT))
    }

    override suspend fun putSeriesSnapshot(
        title: CanonicalTitleEntity,
        manifest: CanonicalEpisodeListEntity,
        episodes: List<CanonicalEpisodeEntity>
    ) {
        dao.putSeriesSnapshot(title, manifest, episodes)
        dao.deleteEpisodeListsOlderThan(System.currentTimeMillis() - SERIES_RETENTION_MILLIS)
        dao.retainNewestEpisodeSnapshots(MAX_EPISODE_ROWS)
    }

    private companion object {
        const val TITLE_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1_000
        const val MAX_TITLE_ROWS = 5_000
        const val MAX_DETAILS_ROWS = 128
        const val SERIES_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1_000
        const val MAX_EPISODE_ROWS = 50_000
    }
}
