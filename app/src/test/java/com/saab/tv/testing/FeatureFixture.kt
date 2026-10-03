package com.saab.tv.testing

import android.content.Context
import com.saab.tv.data.account.*
import com.saab.tv.data.cache.SeekThumbnailCache
import com.saab.tv.data.model.stremio.*
import com.saab.tv.data.player.*
import com.saab.tv.data.remote.*
import com.saab.tv.data.repository.SubtitleRepository
import com.saab.tv.data.stream.StreamSortingService
import com.saab.tv.data.tmdb.*
import com.saab.tv.data.trakt.*
import com.saab.tv.ui.details.DetailsViewModel
import com.saab.tv.ui.player.PlayerViewModel
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections

/** Suspend provider interfaces fail closed. No real network or Hilt graph is initialized. */
inline fun <reified T> forbiddenApi(): T = Proxy.newProxyInstance(T::class.java.classLoader,
    arrayOf(T::class.java)) { _, method, _ -> error("Unexpected provider call: ${method.name}") } as T

class FixtureStremioApi : StremioApiService {
    val calls = Collections.synchronizedList(mutableListOf<String>())
    val metadata = ConcurrentHashMap<String, MetaItem>()
    val streams = ConcurrentHashMap<String, List<Stream>>()
    val catalogPages = ConcurrentHashMap<String, CatalogResponse>()
    var catalogs: List<MetaItem> = emptyList()
    var metadataGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    override suspend fun getManifest(url: String) = Manifest(id = "fixture", name = "Fixture", version = "1")
    override suspend fun getCatalog(url: String): CatalogResponse {
        calls += url
        return catalogPages[url] ?: CatalogResponse(catalogs)
    }
    override suspend fun getMeta(url: String): MetaResponse {
        calls += url
        metadataGate?.await()
        return MetaResponse(metadata.entries.firstOrNull { url.endsWith("/${it.key}.json") }?.value
            ?: throw java.io.IOException("No metadata fixture"))
    }
    override suspend fun getStreams(url: String): StreamResponse {
        calls += url
        return StreamResponse(streams.entries.firstOrNull { url.endsWith("/${it.key}.json") }?.value.orEmpty())
    }
    override suspend fun getSubtitles(url: String) = SubtitleResponse(emptyList())
}

class FeatureFixture(val context: Context) : AutoCloseable {
    val api = FixtureStremioApi()
    val app = OfflineAppFixture(context, api)
    val dao = app.dao
    val cache = SeekThumbnailCache(context)
    val auth = AccountAuthManager(context)
    val sync = AccountSyncManager(context, auth, app.db, app.configuration)
    val traktAuth = TraktAuthManager(context, forbiddenApi<TraktApiService>(), app.configuration)
    private val traktApi = forbiddenApi<TraktSyncApiService>()
    val traktSync = TraktSyncManager(traktApi, traktAuth, dao, app.configuration, cache)
    val sources = SourceSelectionStore(context, app.configuration)
    val tracks = PlaybackTrackSelectionStore(context, app.configuration)
    val subtitles = SubtitleRepository(api, dao)
    val sorting = StreamSortingService()
    val tmdbApi = forbiddenApi<TmdbApiService>()
    val tmdb = TmdbService(context, tmdbApi)
    val tmdbMeta = TmdbMetadataService(tmdbApi, tmdb)
    fun player() = PlayerViewModel(dao, TraktScrobbleManager(traktApi, traktAuth, dao), subtitles, cache, sync)
    fun details() = DetailsViewModel(dao, sources, tracks, app.repository, subtitles, app.configuration,
        sorting, tmdb, tmdbMeta, traktSync, cache, app.display, sync)
    fun home() = com.saab.tv.ui.home.HomeViewModel(app.repository, dao, context, tmdb, tmdbMeta,
        com.saab.tv.data.ott.OttCatalogRepository(context, okhttp3.OkHttpClient.Builder()
            .addInterceptor { error("Unexpected OTT request in home fixture") }.build()),
        cache, sync, sources, tracks, traktSync)
    override fun close() { sync.stop(); app.close() }
}
