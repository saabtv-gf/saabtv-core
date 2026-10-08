package com.saab.tv.di

import android.content.Context
import com.saab.tv.data.remote.StremioApiService
import com.saab.tv.data.remote.IntroDbService
import com.saab.tv.data.remote.TmdbApiService
import com.saab.tv.data.remote.TraktApiService
import com.saab.tv.data.remote.TraktSyncApiService
import com.saab.tv.data.trakt.TraktAuthInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Cache
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TmdbRetrofit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TraktRetrofit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TraktAuthenticatedRetrofit

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 8
        }
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val started = android.os.SystemClock.elapsedRealtime()
                val request = chain.request()
                val tmdbEndpoint = request.url.encodedPath
                    .takeIf { request.url.host.equals("api.themoviedb.org", ignoreCase = true) }
                    ?.removePrefix("/3/")
                    ?.replace(Regex("(?<=/)(?:tt)?\\d+(?=/|$)"), "{id}")
                try {
                    val response = chain.proceed(request)
                    if (tmdbEndpoint != null) {
                        com.saab.tv.AppDiagnostics.event(context, "TMDB", "Request Completed",
                            "method=${request.method} endpoint=$tmdbEndpoint code=${response.code} ms=${android.os.SystemClock.elapsedRealtime() - started}")
                    }
                    com.saab.tv.AppDiagnostics.event(context, "Network", "Request Completed",
                        "method=${request.method} code=${response.code} ms=${android.os.SystemClock.elapsedRealtime() - started}")
                    response
                } catch (failure: java.io.IOException) {
                    // Changing a focused title cancels obsolete metadata calls. This is
                    // normal navigation, not a network failure (including socket resets).
                    if (chain.call().isCanceled()) {
                        if (tmdbEndpoint != null) com.saab.tv.AppDiagnostics.event(context, "TMDB", "Request Cancelled", "endpoint=$tmdbEndpoint")
                        com.saab.tv.AppDiagnostics.event(context, "Network", "Request Cancelled")
                    } else {
                        if (tmdbEndpoint != null) {
                            com.saab.tv.AppDiagnostics.event(context, "TMDB", "Request Failed",
                                "method=${request.method} endpoint=$tmdbEndpoint type=${failure.javaClass.simpleName}")
                            com.saab.tv.AppDiagnostics.failure(context, "TMDB", "Request Failed", failure)
                        }
                        com.saab.tv.AppDiagnostics.event(context, "Network", "Failure Context",
                            "method=${request.method} host=${request.url.host} ms=${android.os.SystemClock.elapsedRealtime() - started}", important = true)
                        com.saab.tv.AppDiagnostics.failure(context, "Network", "Request Failed", failure)
                    }
                    throw failure
                }
            }
            .cache(Cache(context.cacheDir.resolve("http_metadata/${com.saab.tv.data.account.AccountStorage.scope(context)}"), 50L * 1024L * 1024L))
            .dispatcher(dispatcher)
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://stremio-addons.netlify.app/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    @TmdbRetrofit
    fun provideTmdbRetrofit(okHttpClient: OkHttpClient): Retrofit {
        // TMDB is supplemental metadata; keep it from occupying the app's shared
        // network dispatcher when Home/Details issue several requests together.
        val tmdbDispatcher = Dispatcher().apply {
            maxRequests = 8
            maxRequestsPerHost = 4
        }
        val tmdbClient = okHttpClient.newBuilder().dispatcher(tmdbDispatcher).build()
        return Retrofit.Builder()
            .baseUrl("https://api.themoviedb.org/3/")
            .client(tmdbClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideStremioApi(retrofit: Retrofit): StremioApiService {
        return retrofit.create(StremioApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideIntroDbService(retrofit: Retrofit): IntroDbService {
        return retrofit.create(IntroDbService::class.java)
    }

    @Provides
    @Singleton
    fun provideTmdbApiService(@TmdbRetrofit retrofit: Retrofit): TmdbApiService {
        return retrofit.create(TmdbApiService::class.java)
    }

    @Provides
    @Singleton
    @TraktRetrofit
    fun provideTraktRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://auth.trakt.tv/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideTraktApiService(@TraktRetrofit retrofit: Retrofit): TraktApiService {
        return retrofit.create(TraktApiService::class.java)
    }

    @Provides
    @Singleton
    @TraktAuthenticatedRetrofit
    fun provideTraktAuthenticatedRetrofit(
        okHttpClient: OkHttpClient,
        traktAuthInterceptor: TraktAuthInterceptor
    ): Retrofit {
        val authenticatedClient = okHttpClient.newBuilder()
            .addInterceptor(traktAuthInterceptor)
            .build()
        return Retrofit.Builder()
            .baseUrl("https://api.trakt.tv/")
            .client(authenticatedClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideTraktSyncApiService(@TraktAuthenticatedRetrofit retrofit: Retrofit): TraktSyncApiService {
        return retrofit.create(TraktSyncApiService::class.java)
    }
}
