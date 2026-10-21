package com.saab.tv.data.localization

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.activity.ComponentActivity
import java.util.Locale

/** Small app-locale adapter: platform-managed on Android 13+, persisted context locale before it. */
object AppLanguage {
    private const val PREFERENCES = "ui_preferences"
    private const val LANGUAGE_KEY = "app_language"

    fun currentTag(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            if (locales != null) return locales.toLanguageTags().ifBlank { SYSTEM }
        }
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(LANGUAGE_KEY, SYSTEM)
            .orEmpty()
            .ifBlank { SYSTEM }
    }

    fun setLanguage(context: Context, languageTag: String) {
        val normalized = languageTag.takeIf { it in SUPPORTED_TAGS } ?: SYSTEM
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(LANGUAGE_KEY, normalized)
            .apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales = LocaleList.forLanguageTags(normalized.takeUnless { it == SYSTEM }.orEmpty())
        } else {
            context.findActivity()?.recreate()
        }
    }

    /** Applies the stored locale to application/activity contexts on Android versions below 13. */
    fun localizedContext(base: Context, languageTag: String = currentTag(base)): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || languageTag == SYSTEM) return base
        val locale = Locale.forLanguageTag(languageTag)
        if (locale.language.isBlank()) return base
        val configuration = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(configuration)
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private const val SYSTEM = "system"
    private val SUPPORTED_TAGS = setOf(SYSTEM, "en", "ml", "kn")
}

/** Applies the saved pre-Android-13 locale whenever an app screen is created or recreated. */
abstract class LocalizedComponentActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.localizedContext(newBase))
    }
}
