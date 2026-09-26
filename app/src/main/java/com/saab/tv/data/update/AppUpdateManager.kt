package com.saab.tv.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.content.ClipData
import kotlinx.coroutines.CancellationException
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.saab.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val versionName: String,
    val changelog: String,
    val apkUrl: String,
    val sha256: String
)

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class UpdateAvailable(val info: UpdateInfo) : UpdateState()
    data object UpToDate : UpdateState()
    data class Downloading(val progress: Float, val downloadedMb: Float = 0f, val totalMb: Float = 0f) : UpdateState()
    data object AwaitingInstallPermission : UpdateState()
    data object Verifying : UpdateState()
    data class ReadyToInstall(val file: File) : UpdateState()
    data class InstallError(val file: File, val message: String) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

// GitHub Releases API response (only fields we need)
private data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String,
    @SerializedName("body") val body: String?,
    @SerializedName("assets") val assets: List<GitHubAsset>
)

private data class GitHubAsset(
    @SerializedName("browser_download_url") val downloadUrl: String,
    @SerializedName("name") val name: String,
    @SerializedName("digest") val digest: String? = null
)

@Singleton
class AppUpdateManager @Inject constructor(
    private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = _state.asStateFlow()

    private val gson = Gson()
    private val operation = Mutex()
    private var offeredUpdate: UpdateInfo? = null
    private var pendingDownload: Pair<String, String>? = null
    private var pendingInstall: File? = null
    // Large APKs must not consume the metadata disk cache or its short timeout.
    private val downloadClient = okHttpClient.newBuilder().cache(null)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES)
        .addNetworkInterceptor { chain ->
            if (!UpdateDownloadPolicy.trustedUrl(chain.request().url.toString())) {
                throw IOException("Update redirected to an untrusted download host.")
            }
            chain.proceed(chain.request())
        }.build()

    suspend fun checkForUpdate() {
        if (!operation.tryLock()) return
        offeredUpdate = null
        _state.value = UpdateState.Checking
        try {
            val release = withContext(Dispatchers.IO) { fetchLatestRelease() }

            val remoteVersion = release.tagName.removePrefix("v")
            val currentVersion = BuildConfig.VERSION_NAME

            if (compareVersions(remoteVersion, currentVersion) > 0) {
                val releaseAssets = release.assets.map { ReleaseAsset(it.name, it.downloadUrl, it.digest) }
                val apkCount = releaseAssets.count { it.name.endsWith(".apk", ignoreCase = true) }
                val apkAsset = UpdateReleasePolicy.selectApk(releaseAssets, Build.SUPPORTED_ABIS.toList())
                if (apkAsset != null) {
                    if (!isAllowedDownloadUrl(apkAsset.downloadUrl)) {
                        _state.value = UpdateState.Error("Update URL is not from a trusted source.")
                        return
                    }
                    val expectedHash = UpdateReleasePolicy.expectedSha256(apkAsset, release.body, apkCount)
                    if (expectedHash == null) {
                        _state.value = UpdateState.Error(
                            "Release is missing a mandatory SHA-256 checksum for ${apkAsset.name}."
                        )
                        return
                    }
                    offeredUpdate = UpdateInfo(
                            versionName = remoteVersion,
                            changelog = stripHashFromChangelog(release.body),
                            apkUrl = apkAsset.downloadUrl,
                            sha256 = expectedHash
                        )
                    _state.value = UpdateState.UpdateAvailable(requireNotNull(offeredUpdate))
                } else {
                    _state.value = UpdateState.Error("Release has no APK compatible with this device.")
                }
            } else {
                _state.value = UpdateState.UpToDate
            }
        } catch (e: CancellationException) {
            _state.value = UpdateState.Idle
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Error(e.message ?: "Failed to check for updates.")
        } finally { operation.unlock() }
    }

    suspend fun downloadAndInstall(apkUrl: String, expectedSha256: String) {
        if (!isAllowedDownloadUrl(apkUrl) || !UpdateDownloadPolicy.validHash(expectedSha256)) {
            _state.value = UpdateState.Error("Download URL is not from a trusted source.")
            return
        }
        if (!operation.tryLock()) return
        try {
            pendingDownload = apkUrl to expectedSha256
            if (!canInstallUpdates()) {
                _state.value = UpdateState.AwaitingInstallPermission
                openInstallPermissionSettings()
                return
            }
            pendingDownload = null
            var apkFile: File? = null
            repeat(3) { attempt ->
                if (apkFile == null) {
                    _state.value = UpdateState.Downloading(0f)
                    try {
                        val jobContext = coroutineContext
                        apkFile = runInterruptible(Dispatchers.IO) { downloadApk(apkUrl, expectedSha256) { jobContext.ensureActive() } }
                    } catch (e: IOException) {
                        coroutineContext.ensureActive()
                        if (attempt == 2 || e is PermanentDownloadException) throw e
                        delay((attempt + 1) * 1_000L)
                    }
                }
            }
            val verifiedFile = requireNotNull(apkFile)
            _state.value = UpdateState.Verifying
            val verification = withContext(Dispatchers.IO) {
                ApkPackageVerifier.verify(context, verifiedFile)
            }
            if (!verification.valid) {
                verifiedFile.delete()
                throw SecurityException(verification.message)
            }
            launchInstaller(verifiedFile)
        } catch (e: CancellationException) {
            _state.value = offeredUpdate?.let { UpdateState.UpdateAvailable(it) } ?: UpdateState.Idle
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Error("Download failed: ${e.message}")
        } finally { operation.unlock() }
    }

    fun canInstallUpdates(): Boolean = runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) {
            try { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { _state.value = UpdateState.Error("Open TV Settings and allow Saab TV to install unknown apps, then retry.") }
        } catch (_: Exception) { _state.value = UpdateState.Error("Installation permission is blocked. Allow Saab TV in your TV’s security settings.") }
    }

    /** Called on resume; permission denial never starts a network download. */
    suspend fun resumePendingUpdate() {
        if (!canInstallUpdates()) return
        pendingInstall?.let { installDownloaded(it); return }
        pendingDownload?.let { downloadAndInstall(it.first, it.second) }
    }

    fun cancelPendingUpdate() {
        pendingDownload = null; pendingInstall = null
        _state.value = offeredUpdate?.let { UpdateState.UpdateAvailable(it) } ?: UpdateState.Idle
    }

    suspend fun retryDownload() { offeredUpdate?.let { downloadAndInstall(it.apkUrl, it.sha256) } ?: checkForUpdate() }

    suspend fun installDownloaded(file: File) {
        if (!operation.tryLock()) return
        try {
            val verification = withContext(Dispatchers.IO) { ApkPackageVerifier.verify(context, file) }
            if (!verification.valid) { _state.value = UpdateState.Error(verification.message); return }
            launchInstaller(file)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _state.value = UpdateState.InstallError(file, "The downloaded APK could not be opened. Try downloading it again.") }
        finally { operation.unlock() }
    }

    private fun launchInstaller(file: File) {
        if (!canInstallUpdates()) {
            pendingInstall = file
            _state.value = UpdateState.AwaitingInstallPermission
            openInstallPermissionSettings()
            return
        }
        pendingInstall = null
        _state.value = UpdateState.ReadyToInstall(file)
        try { installApk(file) }
        catch (_: Exception) {
            _state.value = UpdateState.InstallError(file, "The TV could not open its installer. The verified APK is saved; retry installation or check your TV’s security settings.")
        }
    }

    fun resetState() {
        if (operation.isLocked) return
        pendingDownload = null; pendingInstall = null
        _state.value = UpdateState.Idle
    }

    private fun fetchLatestRelease(): GitHubRelease {
        val url = "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .build()

        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Update server returned HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw IOException("Update server returned an empty response")
            gson.fromJson(body, GitHubRelease::class.java)
                ?: throw IOException("Update server returned an invalid response")
        }
    }

    private class PermanentDownloadException(message: String) : IOException(message)

    private fun downloadApk(apkUrl: String, expectedSha256: String, checkCancellation: () -> Unit): File {
        val request = Request.Builder().url(apkUrl).build()
        val updateDir = File(context.cacheDir, "updates")
        if (!updateDir.isDirectory && !updateDir.mkdirs()) throw PermanentDownloadException("Cannot create update storage.")
        val partial = File(updateDir, "saabtv-update.part")
        val apkFile = File(updateDir, "saabtv-update.apk")
        try {
            return downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 408 || response.code == 429 || response.code >= 500) throw IOException("Update server HTTP ${response.code}.")
                    throw PermanentDownloadException("Update server HTTP ${response.code}. Check for updates again.")
                }
                val body = response.body ?: throw IOException("Update download was empty.")
                val totalBytes = body.contentLength()
                if (totalBytes > UpdateDownloadPolicy.MAX_APK_BYTES) throw PermanentDownloadException("Update exceeds the download size limit.")
                if (updateDir.usableSpace < maxOf(totalBytes, 64L * 1024 * 1024) + 16L * 1024 * 1024) {
                    throw PermanentDownloadException("Not enough storage. Free space on your TV and retry.")
                }
                var lastProgressAt = 0L
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        UpdateDownloadPolicy.copyVerified(input, output, expectedSha256, totalBytes,
                            checkCancellation = checkCancellation) { bytes, total ->
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastProgressAt >= 150 || bytes == total) {
                                lastProgressAt = now
                                _state.value = UpdateState.Downloading(if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else -1f,
                                    bytes / (1024f * 1024f), if (total > 0) total / (1024f * 1024f) else 0f)
                            }
                        }
                        output.fd.sync()
                    }
                }
                checkCancellation()
                if (!partial.renameTo(apkFile)) throw PermanentDownloadException("Cannot save verified update. Free storage and retry.")
                apkFile
            }
        } finally {
            partial.delete()
        }
    }

    private fun installApk(apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri("Saab TV Update", uri)
        }
        context.startActivity(intent)
    }

    companion object {
        /** Initial APK URL must be an asset of our configured repository. */
        private fun isAllowedDownloadUrl(url: String): Boolean {
            return UpdateDownloadPolicy.releaseUrl(url, BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO)
        }

        /** Compare common semantic-style versions without ever offering a downgrade. */
        private fun compareVersions(left: String, right: String): Int {
            data class Version(val numbers: List<Int>, val prerelease: String?)
            fun parse(raw: String): Version {
                val clean = raw.trim().removePrefix("v")
                val parts = clean.split('-', limit = 2)
                val numbers = parts.first().split('.').map { token ->
                    token.takeWhile(Char::isDigit).toIntOrNull() ?: 0
                }
                return Version(numbers, parts.getOrNull(1)?.lowercase())
            }

            val a = parse(left)
            val b = parse(right)
            repeat(maxOf(a.numbers.size, b.numbers.size)) { index ->
                val comparison = (a.numbers.getOrNull(index) ?: 0)
                    .compareTo(b.numbers.getOrNull(index) ?: 0)
                if (comparison != 0) return comparison
            }
            return when {
                a.prerelease == null && b.prerelease != null -> 1
                a.prerelease != null && b.prerelease == null -> -1
                else -> a.prerelease.orEmpty().compareTo(b.prerelease.orEmpty())
            }
        }

        /** Strip checksum inventory lines from the changelog shown to users. */
        private fun stripHashFromChangelog(body: String?): String {
            if (body == null) return "No changelog provided."
            return body.replace(
                Regex("""(?im)^.*SHA-256.*[a-f0-9]{64}.*$"""),
                ""
            ).trim()
                .ifEmpty { "No changelog provided." }
        }
    }
}
