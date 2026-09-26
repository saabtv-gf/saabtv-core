package com.saab.tv.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.saab.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
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
    data class ReadyToInstall(val file: File) : UpdateState()
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

    suspend fun checkForUpdate() {
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
                    _state.value = UpdateState.UpdateAvailable(
                        UpdateInfo(
                            versionName = remoteVersion,
                            changelog = stripHashFromChangelog(release.body),
                            apkUrl = apkAsset.downloadUrl,
                            sha256 = expectedHash
                        )
                    )
                } else {
                    _state.value = UpdateState.Error("Release has no APK compatible with this device.")
                }
            } else {
                _state.value = UpdateState.UpToDate
            }
        } catch (e: Exception) {
            _state.value = UpdateState.Error(e.message ?: "Failed to check for updates.")
        }
    }

    suspend fun downloadAndInstall(apkUrl: String, expectedSha256: String) {
        if (!isAllowedDownloadUrl(apkUrl)) {
            _state.value = UpdateState.Error("Download URL is not from a trusted source.")
            return
        }
        _state.value = UpdateState.Downloading(0f)
        try {
            val apkFile = withContext(Dispatchers.IO) { downloadApk(apkUrl, expectedSha256) }
            val verification = withContext(Dispatchers.IO) {
                ApkPackageVerifier.verify(context, apkFile)
            }
            if (!verification.valid) {
                apkFile.delete()
                throw SecurityException(verification.message)
            }
            _state.value = UpdateState.ReadyToInstall(apkFile)
            installApk(apkFile)
        } catch (e: Exception) {
            _state.value = UpdateState.Error("Download failed: ${e.message}")
        }
    }

    fun resetState() {
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

    private fun downloadApk(apkUrl: String, expectedSha256: String): File {
        val request = Request.Builder().url(apkUrl).build()
        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")

            val updateDir = File(context.cacheDir, "updates")
            updateDir.mkdirs()
            val apkFile = File(updateDir, "saabtv-update.apk")

            val body = response.body ?: throw IOException("Update download was empty")
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L
            val digest = MessageDigest.getInstance("SHA-256")

            body.byteStream().use { input ->
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            _state.value = UpdateState.Downloading(
                                progress = downloadedBytes.toFloat() / totalBytes,
                                downloadedMb = downloadedBytes / (1024f * 1024f),
                                totalMb = totalBytes / (1024f * 1024f)
                            )
                        }
                    }
                }
            }

            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualHash.equals(expectedSha256, ignoreCase = true)) {
                apkFile.delete()
                throw SecurityException("APK checksum mismatch — file may be tampered")
            }
            apkFile
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
        }
        context.startActivity(intent)
    }

    companion object {
        /** Trusted domains for APK downloads. */
        private val ALLOWED_DOWNLOAD_HOSTS = setOf(
            "github.com",
            "objects.githubusercontent.com"
        )

        /** Validate that the download URL is from a trusted GitHub domain. */
        private fun isAllowedDownloadUrl(url: String): Boolean {
            val uri = try {
                Uri.parse(url)
            } catch (_: Exception) {
                null
            } ?: return false
            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            val host = uri.host?.lowercase() ?: return false
            return ALLOWED_DOWNLOAD_HOSTS.any { host == it || host.endsWith(".$it") }
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
