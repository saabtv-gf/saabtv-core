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
        val installed = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(context.packageName, flags)
        }.getOrNull() ?: return ApkVerificationResult(false, "Installed package identity is unavailable")

        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) {
            return ApkVerificationResult(false, "Downloaded package is not newer than the installed version")
        }

        val candidate = currentSignerDigests(archive)
        val trusted = signingHistoryDigests(installed)
        if (candidate.isEmpty() || trusted.isEmpty() || candidate.none(trusted::contains)) {
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
            info.signingInfo?.signingCertificateHistory.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        return signatures.mapTo(hashSetOf(), ::digest)
    }

    private fun digest(signature: Signature): String = MessageDigest.getInstance("SHA-256")
        .digest(signature.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
