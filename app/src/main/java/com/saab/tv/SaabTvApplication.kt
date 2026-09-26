package com.saab.tv

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.saab.tv.data.cache.SeekThumbnailWorkerService
import dagger.hilt.android.HiltAndroidApp
import org.acra.config.httpSender
import org.acra.config.toast
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import org.acra.sender.HttpSender
import javax.inject.Inject

@HiltAndroidApp
class SaabTvApplication : Application(), ImageLoaderFactory {

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var startupOptimizer: StartupOptimizer

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        // Do not claim that crash reports are sent when this self-built APK has no
        // configured, authenticated crash endpoint.
        if (BuildConfig.ACRA_URL.isBlank() || BuildConfig.ACRA_TOKEN.isBlank()) return

        initAcra {
            buildConfigClass = BuildConfig::class.java
            reportFormat = StringFormat.JSON

            httpSender {
                uri = BuildConfig.ACRA_URL
                httpMethod = HttpSender.Method.POST
                basicAuthLogin = "acra"
                basicAuthPassword = BuildConfig.ACRA_TOKEN
            }

            toast {
                text = getString(R.string.acra_toast_text)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (currentProcessName().endsWith(THUMBNAIL_WORKER_PROCESS)) return
        AppHealthMonitor.install(this)
        startupOptimizer.warmup()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!currentProcessName().endsWith(THUMBNAIL_WORKER_PROCESS) &&
            level >= TRIM_MEMORY_RUNNING_LOW
        ) {
            AppHealthMonitor.recordMemoryPressure(this, level)
            SeekThumbnailWorkerService.cancelAll(this)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (!currentProcessName().endsWith(THUMBNAIL_WORKER_PROCESS)) {
            AppHealthMonitor.recordMemoryPressure(this, TRIM_MEMORY_COMPLETE)
            SeekThumbnailWorkerService.cancelAll(this)
        }
    }

    private fun currentProcessName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return manager?.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }
            ?.processName
            .orEmpty()
    }

    override fun newImageLoader(): ImageLoader = imageLoader

    private companion object {
        const val THUMBNAIL_WORKER_PROCESS = ":thumbnail_worker"
    }
}
