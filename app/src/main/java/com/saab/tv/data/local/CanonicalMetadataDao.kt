package com.saab.tv.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.saab.tv.data.model.CanonicalIdentityAliasEntity
import com.saab.tv.data.model.CanonicalEpisodeEntity
import com.saab.tv.data.model.CanonicalEpisodeListEntity
import com.saab.tv.data.model.CanonicalSeriesSnapshot
import com.saab.tv.data.model.CanonicalTitleEntity
import com.saab.tv.domain.schema.ContentIdentity
import com.saab.tv.domain.schema.IdentityNamespace
import com.saab.tv.data.repository.CanonicalTitleMergePolicy

@Dao
interface CanonicalMetadataDao {
    @Query("SELECT * FROM canonical_titles WHERE identityKey = :identityKey AND locale = :locale LIMIT 1")
    suspend fun getTitle(identityKey: String, locale: String): CanonicalTitleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTitle(title: CanonicalTitleEntity)

    @Query("SELECT * FROM canonical_episode_lists WHERE seriesIdentityKey = :identityKey AND locale = :locale LIMIT 1")
    suspend fun getEpisodeList(identityKey: String, locale: String): CanonicalEpisodeListEntity?

    @Query("SELECT * FROM canonical_episode_lists WHERE seriesIdentityKey IN (:identityKeys) ORDER BY observedAtMillis DESC, seriesIdentityKey ASC")
    suspend fun getEpisodeLists(identityKeys: List<String>): List<CanonicalEpisodeListEntity>

    @Query("SELECT * FROM canonical_episodes WHERE seriesIdentityKey = :identityKey AND locale = :locale ORDER BY episodeOrder ASC")
    suspend fun getEpisodes(identityKey: String, locale: String): List<CanonicalEpisodeEntity>

    @Query("DELETE FROM canonical_episodes WHERE seriesIdentityKey = :identityKey AND locale = :locale")
    suspend fun deleteEpisodes(identityKey: String, locale: String): Int

    @Query("DELETE FROM canonical_episode_lists WHERE seriesIdentityKey = :identityKey AND locale = :locale")
    suspend fun deleteEpisodeList(identityKey: String, locale: String): Int

    @Query("DELETE FROM canonical_episode_lists WHERE observedAtMillis < :olderThanMillis")
    suspend fun deleteEpisodeListsOlderThan(olderThanMillis: Long): Int

    @Query("SELECT COUNT(*) FROM canonical_episodes")
    suspend fun countEpisodes(): Int

    @Query("SELECT * FROM canonical_episode_lists ORDER BY observedAtMillis ASC, seriesIdentityKey, locale LIMIT 1")
    suspend fun getOldestEpisodeList(): CanonicalEpisodeListEntity?

    @Query("DELETE FROM canonical_episodes WHERE NOT EXISTS (SELECT 1 FROM canonical_episode_lists l WHERE l.seriesIdentityKey = canonical_episodes.seriesIdentityKey AND l.locale = canonical_episodes.locale)")
    suspend fun deleteOrphanEpisodes(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putEpisodeList(manifest: CanonicalEpisodeListEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putEpisodes(episodes: List<CanonicalEpisodeEntity>)

    @Transaction
    suspend fun getSeriesSnapshot(identityKey: String, locale: String): CanonicalSeriesSnapshot? {
        val manifest = getEpisodeList(identityKey, locale) ?: return null
        return CanonicalSeriesSnapshot(manifest, getEpisodes(identityKey, locale))
    }

    @Transaction
    suspend fun putSeriesSnapshot(
        title: CanonicalTitleEntity,
        manifest: CanonicalEpisodeListEntity,
        episodes: List<CanonicalEpisodeEntity>
    ) {
        require(title.kind == "series" && manifest.source == "CINEMETA")
        require(title.identityKey == manifest.seriesIdentityKey && title.locale == manifest.locale)
        require(manifest.episodeCount == episodes.size)
        require(episodes.all {
            it.seriesIdentityKey == manifest.seriesIdentityKey && it.locale == manifest.locale &&
                it.observedAtMillis == manifest.observedAtMillis
        })
        val canonicalKey = getAlias(title.identityKey)?.canonicalIdentityKey ?: title.identityKey
        val canonicalTitle = title.copy(identityKey = canonicalKey)
        putCanonicalTitle(canonicalTitle)
        deleteEpisodes(canonicalKey, manifest.locale)
        putEpisodeList(manifest.copy(seriesIdentityKey = canonicalKey))
        if (episodes.isNotEmpty()) putEpisodes(episodes.map { it.copy(seriesIdentityKey = canonicalKey) })
    }

    /** Evict whole snapshots; partial episode lists are never presented as complete. */
    @Transaction
    suspend fun retainNewestEpisodeSnapshots(maxRows: Int) {
        require(maxRows >= 0)
        while (countEpisodes() > maxRows) {
            val oldest = getOldestEpisodeList() ?: break
            deleteEpisodes(oldest.seriesIdentityKey, oldest.locale)
            deleteEpisodeList(oldest.seriesIdentityKey, oldest.locale)
        }
        deleteOrphanEpisodes()
    }

    @Transaction
    suspend fun putCanonicalTitle(title: CanonicalTitleEntity) {
        val canonicalKey = getAlias(title.identityKey)?.canonicalIdentityKey ?: title.identityKey
        val existing = getTitle(canonicalKey, title.locale)
        putTitle(CanonicalTitleMergePolicy.merge(listOfNotNull(existing, title), canonicalKey, System.currentTimeMillis()))
    }

    @Query("SELECT * FROM canonical_titles WHERE identityKey IN (:identityKeys)")
    suspend fun getTitles(identityKeys: List<String>): List<CanonicalTitleEntity>

    @Query("DELETE FROM canonical_titles WHERE identityKey IN (:identityKeys)")
    suspend fun deleteTitles(identityKeys: List<String>): Int

    @Query("DELETE FROM canonical_titles WHERE lastFetchedAtMillis < :olderThanMillis")
    suspend fun deleteTitlesOlderThan(olderThanMillis: Long): Int

    @Query("DELETE FROM canonical_titles WHERE rowid IN (SELECT rowid FROM canonical_titles ORDER BY lastFetchedAtMillis DESC, identityKey, locale LIMIT -1 OFFSET :maxRows)")
    suspend fun retainNewestTitles(maxRows: Int): Int

    /** Keep bulky full-Details credits in a much smaller pool than lightweight title facts. */
    @Query("""
        UPDATE canonical_titles
        SET creditsJson = NULL, creditsSource = NULL, creditsObservedAtMillis = 0
        WHERE rowid IN (
            SELECT rowid FROM canonical_titles
            WHERE creditsSource = 'TMDB'
            ORDER BY creditsObservedAtMillis DESC, identityKey, locale
            LIMIT -1 OFFSET :maxRows
        )
    """)
    suspend fun retainNewestTitleDetails(maxRows: Int): Int

    @Query("SELECT * FROM canonical_identity_aliases WHERE identityKey = :identityKey LIMIT 1")
    suspend fun getAlias(identityKey: String): CanonicalIdentityAliasEntity?

    @Query("SELECT * FROM canonical_identity_aliases WHERE canonicalIdentityKey = :canonicalKey AND namespace = :namespace ORDER BY identityKey LIMIT 1")
    suspend fun getAliasForCanonical(canonicalKey: String, namespace: String): CanonicalIdentityAliasEntity?

    @Query("SELECT * FROM canonical_identity_aliases WHERE canonicalIdentityKey IN (:canonicalKeys)")
    suspend fun getAliasesForCanonicals(canonicalKeys: List<String>): List<CanonicalIdentityAliasEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAliases(aliases: List<CanonicalIdentityAliasEntity>)

    @Query("DELETE FROM canonical_identity_aliases WHERE verifiedAtMillis < :olderThanMillis")
    suspend fun deleteAliasesOlderThan(olderThanMillis: Long): Int

    @Query("DELETE FROM canonical_identity_aliases WHERE identityKey NOT IN (SELECT identityKey FROM canonical_identity_aliases ORDER BY verifiedAtMillis DESC LIMIT :maxRows)")
    suspend fun retainNewestAliases(maxRows: Int): Int

    @Query("UPDATE watch_history SET canonicalIdentityKey = :newKey WHERE canonicalIdentityKey = :oldKey")
    suspend fun rekeyTitleHistory(oldKey: String, newKey: String): Int

    @Query("""
        UPDATE watch_history
        SET canonicalIdentityKey = 'episode/' || :newKey || substr(canonicalIdentityKey, length('episode/' || :oldKey) + 1)
        WHERE canonicalIdentityKey LIKE 'episode/' || :oldKey || '/%'
    """)
    suspend fun rekeyEpisodeHistory(oldKey: String, newKey: String): Int

    @Query("UPDATE watchlist SET canonicalIdentityKey = :newKey WHERE canonicalIdentityKey = :oldKey")
    suspend fun rekeyLibrary(oldKey: String, newKey: String): Int

    @Query("UPDATE series_next_up SET canonicalIdentityKey = :newKey WHERE canonicalIdentityKey = :oldKey")
    suspend fun rekeySeriesNextUp(oldKey: String, newKey: String): Int

    /**
     * Promotes verified provider IDs to one deterministic root and rewrites all known aliases in
     * the same SQLite transaction. Provider/network arrival order cannot choose the canonical ID.
     */
    @Transaction
    suspend fun linkVerified(left: ContentIdentity, right: ContentIdentity, nowMillis: Long) {
        require(left.kind == right.kind) { "Verified aliases must refer to the same content kind" }
        val leftExisting = getAlias(left.stableKey)
        val rightExisting = getAlias(right.stableKey)
        val priorRoots = listOfNotNull(
            leftExisting?.canonicalIdentity(),
            rightExisting?.canonicalIdentity()
        )
        val canonical = (listOf(left, right) + priorRoots)
            .minWith(compareBy<ContentIdentity> { it.namespace.canonicalRank() }.thenBy { it.stableKey })
        val oldRootKeys = listOfNotNull(leftExisting?.canonicalIdentityKey, rightExisting?.canonicalIdentityKey)
            .plus(listOf(left.stableKey, right.stableKey))
            .distinct()
        if (left.kind == com.saab.tv.domain.schema.ContentKind.SERIES) {
            // Alias promotion must move the authoritative episode cache with title facts.
            // Select the newest complete snapshot independently per locale; never combine
            // partial provider lists or rewrite opaque episode IDs/order.
            val snapshotOrder = compareBy<Pair<CanonicalEpisodeListEntity, List<CanonicalEpisodeEntity>>> {
                it.first.observedAtMillis
            }.thenBy { it.first.seriesIdentityKey }
            val newestCompleteByLocale = mutableMapOf<String, Pair<CanonicalEpisodeListEntity, List<CanonicalEpisodeEntity>>>()
            for (manifest in getEpisodeLists(oldRootKeys)) {
                val episodes = getEpisodes(manifest.seriesIdentityKey, manifest.locale)
                if (manifest.episodeCount != episodes.size) continue
                val candidate = manifest to episodes
                val existing = newestCompleteByLocale[manifest.locale]
                if (existing == null || snapshotOrder.compare(candidate, existing) > 0) {
                    newestCompleteByLocale[manifest.locale] = candidate
                }
            }
            for ((locale, snapshot) in newestCompleteByLocale) {
                oldRootKeys.forEach { key ->
                    deleteEpisodes(key, locale)
                    deleteEpisodeList(key, locale)
                }
                putEpisodeList(snapshot.first.copy(seriesIdentityKey = canonical.stableKey))
                if (snapshot.second.isNotEmpty()) {
                    putEpisodes(snapshot.second.map { it.copy(seriesIdentityKey = canonical.stableKey) })
                }
            }
        }
        val existingAliases = getAliasesForCanonicals(oldRootKeys)
        val updatedAliases = (existingAliases.map { it.withCanonical(canonical) } +
            listOf(left, right).map { it.asAlias(canonical, nowMillis) })
            .distinctBy { it.identityKey }
        putAliases(updatedAliases)
        // Canonical identity is a secondary index over durable state. Move every known old
        // alias in the same transaction so a provider-ID promotion cannot strand progress,
        // library membership, or next-up under its former identity.
        (oldRootKeys + existingAliases.map { it.identityKey }).distinct()
            .filterNot { it == canonical.stableKey }
            .forEach { oldKey ->
                rekeyTitleHistory(oldKey, canonical.stableKey)
                rekeyEpisodeHistory(oldKey, canonical.stableKey)
                rekeyLibrary(oldKey, canonical.stableKey)
                rekeySeriesNextUp(oldKey, canonical.stableKey)
            }
        val existingTitles = getTitles(oldRootKeys)
        existingTitles.groupBy { it.locale }.values.forEach { localizedRows ->
            putTitle(CanonicalTitleMergePolicy.merge(localizedRows, canonical.stableKey, nowMillis))
        }
        deleteTitles(oldRootKeys.filterNot { it == canonical.stableKey })
        deleteAliasesOlderThan(nowMillis - IDENTITY_RETENTION_MILLIS)
        retainNewestAliases(MAX_ALIAS_ROWS)
    }

    @Transaction
    suspend fun resolveAlias(identity: ContentIdentity, targetNamespace: IdentityNamespace): ContentIdentity? {
        val alias = getAlias(identity.stableKey) ?: return null
        if (alias.canonicalNamespace == targetNamespace.name) return alias.canonicalIdentity()
        return getAliasForCanonical(alias.canonicalIdentityKey, targetNamespace.name)?.asIdentity()
    }
}

private const val IDENTITY_RETENTION_MILLIS = 180L * 24 * 60 * 60 * 1_000
private const val MAX_ALIAS_ROWS = 20_000

private fun IdentityNamespace.canonicalRank(): Int = when (this) {
    IdentityNamespace.IMDB -> 0
    IdentityNamespace.TMDB -> 1
    IdentityNamespace.ADDON -> 2
}

private fun ContentIdentity.asAlias(canonical: ContentIdentity, nowMillis: Long) =
    CanonicalIdentityAliasEntity(
        identityKey = stableKey,
        namespace = namespace.name,
        kind = kind.keyPart,
        providerInstallationId = providerInstallationId,
        externalId = externalId,
        canonicalIdentityKey = canonical.stableKey,
        canonicalNamespace = canonical.namespace.name,
        canonicalKind = canonical.kind.keyPart,
        canonicalProviderInstallationId = canonical.providerInstallationId,
        canonicalExternalId = canonical.externalId,
        verifiedAtMillis = nowMillis
    )

private fun CanonicalIdentityAliasEntity.withCanonical(canonical: ContentIdentity) = copy(
    canonicalIdentityKey = canonical.stableKey,
    canonicalNamespace = canonical.namespace.name,
    canonicalKind = canonical.kind.keyPart,
    canonicalProviderInstallationId = canonical.providerInstallationId,
    canonicalExternalId = canonical.externalId
)

private fun CanonicalIdentityAliasEntity.canonicalIdentity(): ContentIdentity? =
    toIdentity(canonicalNamespace, canonicalKind, canonicalExternalId, canonicalProviderInstallationId)

private fun CanonicalIdentityAliasEntity.asIdentity(): ContentIdentity? =
    toIdentity(namespace, kind, externalId, providerInstallationId)

private fun toIdentity(
    namespaceName: String,
    kindName: String,
    id: String,
    installationId: String?
): ContentIdentity? {
    val namespace = runCatching { IdentityNamespace.valueOf(namespaceName) }.getOrNull() ?: return null
    val kind = com.saab.tv.domain.schema.ContentKind.fromProvider(kindName) ?: return null
    return ContentIdentity.from(namespace, kind, id, installationId)
}
