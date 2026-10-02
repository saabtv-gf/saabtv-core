package com.saab.tv.testing

import android.content.Context
import androidx.room.Room
import com.saab.tv.data.auth.StremioAuthManager
import com.saab.tv.data.local.SaabTvDatabase
import com.saab.tv.data.model.stremio.*
import com.saab.tv.data.profile.DeviceDisplayPreferences
import com.saab.tv.data.profile.ProfileConfigurationManager
import com.saab.tv.data.remote.StremioApiService
import com.saab.tv.data.remote.StremioAuthService
import com.saab.tv.data.repository.AddonRepository
import com.saab.tv.data.stream.TorBoxAvailabilityService
import org.robolectric.shadows.ShadowLooper

/** Production persistence/orchestration with an API that fails any unexpected request. */
class OfflineAppFixture(context: Context, apiOverride: StremioApiService? = null) : AutoCloseable {
    val db = Room.inMemoryDatabaseBuilder(context, SaabTvDatabase::class.java).allowMainThreadQueries().build()
    val dao = db.addonDao()
    val display = DeviceDisplayPreferences(context)
    private val api = apiOverride ?: object : StremioApiService {
        override suspend fun getManifest(url: String): Manifest = error("Unexpected network: $url")
        override suspend fun getCatalog(url: String): CatalogResponse = error("Unexpected network: $url")
        override suspend fun getMeta(url: String): MetaResponse = error("Unexpected network: $url")
        override suspend fun getStreams(url: String): StreamResponse = error("Unexpected network: $url")
        override suspend fun getSubtitles(url: String): SubtitleResponse = error("Unexpected network: $url")
    }
    val repository = AddonRepository(api, dao, TorBoxAvailabilityService(context))
    val configuration = ProfileConfigurationManager(context, dao,
        StremioAuthManager(context, StremioAuthService()), repository, display)
    override fun close() = db.close()
}

/** Pump the Android main loop while awaiting Room/IO work; bounded, no fixed long sleeps. */
fun awaitAppState(predicate: () -> Boolean) {
    val deadline = System.nanoTime() + 5_000_000_000L
    do {
        ShadowLooper.idleMainLooper()
        if (predicate()) return
        Thread.sleep(10)
    } while (System.nanoTime() < deadline)
    throw AssertionError("App state did not settle within five seconds")
}
