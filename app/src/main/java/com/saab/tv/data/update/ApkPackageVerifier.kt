package com.saab.tv.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

internal data class ApkVerificationResult(val valid: Boolean, val message: String)

internal object ApkPackageVerifier {
    fun verify(context: Context, apkFile: File): ApkVerificationResult {
        val packageManager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
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

        val candidate = currentSignerDigests(archive)
        val trusted = signingHistoryDigests(installed)
        val installedHasMultipleSigners = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && installed.signingInfo?.hasMultipleSigners() == true
        val signersMatch = if (installedHasMultipleSigners) candidate == currentSignerDigests(installed)
            else candidate.size == 1 && trusted.containsAll(candidate)
        if (candidate.isEmpty() || trusted.isEmpty() || !signersMatch) {
            return ApkVerificationResult(false, "Downloaded APK is not signed by the Saab TV release key")
        }
        return ApkVerificationResult(true, "Package name, version and signing certificate verified")
    }

    @Suppress("DEPRECATION")
    private fun currentSignerDigests(info: PackageInfo): Set<String> {
        val signatures: Array<out Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        return signatures.mapTo(hashSetOf(), ::digest)
    }

    @Suppress("DEPRECATION")
    private fun signingHistoryDigests(info: PackageInfo): Set<String> {
        val signatures: Array<out Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        return signatures.mapTo(hashSetOf(), ::digest)
    }

    private fun digest(signature: Signature): String = MessageDigest.getInstance("SHA-256")
        .digest(signature.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
