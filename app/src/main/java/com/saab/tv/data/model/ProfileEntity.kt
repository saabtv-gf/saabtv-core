package com.saab.tv.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,

    val themeId: String = "void",

    val avatarRef: String = "avatar_1",

    // Versioned, salted PBKDF2 hash. Null means no PIN.
    val pinHash: String? = null,

    // Exactly one profile is active. Profile-owned DAO queries use this scope.
    val isActive: Boolean = false,

    val roundCorners: Boolean = true,
    val hubRoundCorners: Boolean = true,
    val continueWatchingShape: String = "poster",  // "poster" or "landscape"
    val navPosition: String = "left",
    val splashEnabled: Boolean = true,

    val homeTabLayout: String = "cinematic",
    val moviesTabLayout: String = "cinematic",
    val seriesTabLayout: String = "cinematic",

    val homeHeroCategory: String? = null,
    val homeHeroPosterCount: Int = 10,
    val homeHeroAutoScrollSeconds: Int = 0,

    val moviesHeroCategory: String? = null,
    val moviesHeroPosterCount: Int = 10,
    val moviesHeroAutoScrollSeconds: Int = 0,

    val seriesHeroCategory: String? = null,
    val seriesHeroPosterCount: Int = 10,
    val seriesHeroAutoScrollSeconds: Int = 0,

    val tunnelingEnabled: Boolean = false,
    val mapDV7ToHevc: Boolean = false,
    val decoderPriority: Int = 1,       // 0=device only, 1=prefer device, 2=prefer app
    val frameRateMatching: Boolean = false,
    val playerPreference: String = "internal",  // "internal", "external", "ask"
    val seekTimeIntervalSeconds: Int = 30,       // 10, 20, or 30 seconds
    val seekThumbnailsEnabled: Boolean = true,
    val seekThumbnailIntervalSeconds: Int = 30,  // Kept in sync with seekTimeIntervalSeconds
    val autoplayNextEpisode: Boolean = true,
    val autoplayThresholdMode: String = "introdb",  // "introdb", "percentage" or "time"
    val autoplayThresholdPercent: Int = 95,             // 50..99
    val autoplayThresholdSeconds: Int = 30,             // 10..300
    val autoSelectSource: Boolean = true,
    val rememberSourceSelection: Boolean = true,
    val sourceSortingEnabled: Boolean = true,
    val sourceSortPrimary: String = "quality",    // "quality", "size", "seeds", "smart_tcl_c755"
    val sourceSortSecondary: String = "size",     // "quality", "size", "seeds"
    val sourceEnabledQualities: String = "4k,1080p,720p,unknown",
    val sourceExcludePhrases: String = "",
    val sourceMaxSizeGb: Int = 0,                // 0 = no limit
    val sourceExcludedFormats: String = "3d",       // comma-separated: "dv,hdr,dts,dolby,hevc,av1,3d"
    val sourceSeasonPacksOnly: Boolean = true,    // series: hide single-episode torrents
    val sourceHideZeroSeeders: Boolean = true,    // hide streams that explicitly report zero seeders
    val sourceLanguagePriority1: String = "en",
    val sourceLanguagePriority2: String = "te",
    val sourceLanguagePriority3: String = "hi",
    val skipIntro: Boolean = true,
    val autoSkipIntro: Boolean = true,
    val introSkipCountdownSeconds: Int = 5,
    val outroSkipCountdownSeconds: Int = 5,

    val preferredAudioLanguage: String = "en",
    val preferredAudioLanguageSecondary: String = "",
    val preferredSubtitleLanguage: String = "en",
    val preferredSubtitleLanguageSecondary: String = "",

    val subtitleSize: Int = 100,                     // 50-200%
    val subtitleOffset: Int = 0,                     // -20 to 20%
    val subtitleTextColor: Long = 0xFFFFFFFFL,       // White (ARGB)
    val subtitleBackgroundColor: Long = 0x00000000L, // Transparent
    val assRendererEnabled: Boolean = false,          // Styled ASS/SSA subtitles via libass

    // Watch thresholds
    val watchedThreshold: Int = 95,                  // 50-99%, marks as watched when exceeded

    // TMDB integration
    val tmdbEnabled: Boolean = false,
    val tmdbLanguage: String = ""                    // ISO-639-1, empty = device locale
)
