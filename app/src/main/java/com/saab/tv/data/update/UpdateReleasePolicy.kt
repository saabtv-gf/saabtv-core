package com.saab.tv.data.update

internal data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val digest: String? = null
)

/** Pure release policy so update safety is covered by unit tests. */
internal object UpdateReleasePolicy {
    private val SHA256 = Regex("(?i)\\b[a-f0-9]{64}\\b")

    fun selectApk(assets: List<ReleaseAsset>, supportedAbis: List<String>): ReleaseAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null

        supportedAbis.forEach { abi ->
            apks.firstOrNull { assetAbi(it.name) == normalizeAbi(abi) }?.let { return it }
        }
        apks.firstOrNull { assetAbi(it.name) == "universal" }?.let { return it }
        return apks.singleOrNull()?.takeIf { asset ->
            val abi = assetAbi(asset.name)
            abi == null || supportedAbis.map(::normalizeAbi).contains(abi)
        }
    }

    fun expectedSha256(asset: ReleaseAsset, releaseBody: String?, apkCount: Int): String? {
        asset.digest
            ?.removePrefix("sha256:")
            ?.takeIf { it.matches(Regex("(?i)[a-f0-9]{64}")) }
            ?.let { return it.lowercase() }

        val lines = releaseBody.orEmpty().lineSequence().toList()
        lines.firstOrNull { line ->
            line.contains(asset.name, ignoreCase = true) && SHA256.containsMatchIn(line)
        }?.let { return SHA256.find(it)?.value?.lowercase() }

        // A generic checksum is unambiguous only when a release has one APK.
        if (apkCount == 1) return SHA256.find(releaseBody.orEmpty())?.value?.lowercase()
        return null
    }

    private fun assetAbi(name: String): String? {
        val value = name.lowercase()
        return when {
            "arm64-v8a" in value || "aarch64" in value -> "arm64-v8a"
            "armeabi-v7a" in value || "armv7" in value || "32-bit" in value -> "armeabi-v7a"
            "x86_64" in value -> "x86_64"
            Regex("(?:^|[-_.])x86(?:[-_.]|$)").containsMatchIn(value) -> "x86"
            "universal" in value || "all-abi" in value -> "universal"
            else -> null
        }
    }

    private fun normalizeAbi(abi: String): String = when (abi.lowercase()) {
        "aarch64" -> "arm64-v8a"
        "armv7", "armeabi" -> "armeabi-v7a"
        else -> abi.lowercase()
    }
}
