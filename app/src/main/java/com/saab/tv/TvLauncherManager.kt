package com.saab.tv

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Fire OS consumes launcher icons as tiles; keep Android's adaptive icon elsewhere. */
internal object TvLauncherManager {
    fun configure(context: Context) {
        val pm = context.packageManager
        val fire = pm.hasSystemFeature("com.hardware.amazon.fire_tv")
        val wanted = if (fire) ".FireTvLauncher" else ".StandardLauncher"
        val unwanted = if (fire) ".StandardLauncher" else ".FireTvLauncher"
        // Enable first, so there is never an interval without a launcher entry.
        for ((name, state) in listOf(wanted to PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            unwanted to PackageManager.COMPONENT_ENABLED_STATE_DISABLED)) {
            // Activity aliases are qualified with the manifest namespace, while the
            // installed package can differ (for example, the debug `.test` suffix).
            val component = ComponentName(
                context.packageName,
                BuildConfig::class.java.getPackage().name + name
            )
            if (pm.getComponentEnabledSetting(component) != state) {
                pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
