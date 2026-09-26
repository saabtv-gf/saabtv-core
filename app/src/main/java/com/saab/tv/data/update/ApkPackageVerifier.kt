package com.saab.tv.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest
import java.util.jar.JarFile

internal data class ApkVerificationResult(val valid: Boolean, val message: String)

internal object ApkPackageVerifier {
    fun verify(context: Context, apkFile: File): ApkVerificationResult {
        val packageManager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: return ApkVerificationResult(false, "Downloaded file is not a valid Android package")
        if (archive.packageName != context.packageName) {
            return ApkVerificationResult(false, "Downloaded package identity does not match Saab TV")
        }
        if ((archive.applicationInfo?.minSdkVersion ?: 0) > Build.VERSION.SDK_INT) {
            return ApkVerificationResult(false, "This update requires a newer Android version than this TV supports")
        }
        val nativeAbis = java.util.zip.ZipFile(apkFile).use { zip ->
            zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }
                .map { it.split('/')[1] }.toSet()
        }
        if (nativeAbis.isNotEmpty() && nativeAbis.none { it in Build.SUPPORTED_ABIS }) {
            return ApkVerificationResult(false, "Downloaded APK does not support this TV’s CPU architecture")
        }
        val installed = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(context.packageName, flags)
        }.getOrNull() ?: return ApkVerificationResult(false, "Installed package identity is unavailable")

        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) {
            return ApkVerificationResult(false, "Downloaded package is not newer than the installed version")
        }

        // Handle vendor PackageManager implementations that omit SigningInfo.
        // Retain the legacy result, then cryptographically verify v1 JAR signatures
        // only if both platform certificate APIs failed to return certificates.
        val candidate = currentSignerDigests(archive).ifEmpty { verifiedJarSigners(apkFile) }
        val trusted = signingHistoryDigests(installed).ifEmpty {
            verifiedJarSigners(File(context.applicationInfo.sourceDir))
        }
        val installedHasMultipleSigners = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && installed.signingInfo?.hasMultipleSigners() == true
        if (candidate.isEmpty() || trusted.isEmpty()) {
            return ApkVerificationResult(false, "This TV could not read APK signing certificates. Install the official update manually once; signature checks were not bypassed.")
        }
        if (!ApkSignerPolicy.matches(candidate, trusted, installedHasMultipleSigners)) {
            return ApkVerificationResult(false, "APK signing key mismatch (Android ${Build.VERSION.SDK_INT}; downloaded ${candidate.joinToString { it.take(12) }}; installed ${trusted.joinToString { it.take(12) }}). Use the official APK; local data has not been changed.")
        }
        return ApkVerificationResult(true, "Package name, version and signing certificate verified")
    }

    @Suppress("DEPRECATION")
    private fun currentSignerDigests(info: PackageInfo): Set<String> {
        val signatures: Array<out Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() } ?: info.signatures.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        return signatures.mapTo(hashSetOf(), ::digest)
    }

    @Suppress("DEPRECATION")
    private fun signingHistoryDigests(info: PackageInfo): Set<String> {
        val signatures: Array<out Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
                ?.takeIf { it.isNotEmpty() } ?: info.signatures.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        return signatures.mapTo(hashSetOf(), ::digest)
    }

    private fun digest(signature: Signature): String = MessageDigest.getInstance("SHA-256")
        .digest(signature.toByteArray())
        .joinToString("") { "%02x".format(it) }

    /** Official builds contain v1 and v2 signatures; never infer trust from a manifest. */
    internal fun verifiedJarSigners(file: File): Set<String> = runCatching {
        JarFile(file, true).use { jar ->
            var signerSet: Set<String>? = null
            var readTotal = 0L
            val buffer = ByteArray(64 * 1024)
            val entryNames = HashSet<String>()
            for (entry in jar.entries()) {
                require(entryNames.add(entry.name)) { "Duplicate APK entry" }
                if (entry.isDirectory || entry.name.startsWith("META-INF/", ignoreCase = true)) continue
                jar.getInputStream(entry).use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        readTotal += count
                        require(readTotal <= 512L * 1024 * 1024)
                    }
                }
                val entrySigners = entry.codeSigners.orEmpty().mapTo(mutableSetOf()) {
                    MessageDigest.getInstance("SHA-256").digest(it.signerCertPath.certificates.first().encoded)
                        .joinToString("") { b -> "%02x".format(b) }
                }
                require(entrySigners.isNotEmpty()) { "Unsigned APK entry" }
                if (signerSet == null) signerSet = entrySigners else require(signerSet == entrySigners)
            }
            signerSet.orEmpty()
        }
    }.getOrDefault(emptySet())
}
