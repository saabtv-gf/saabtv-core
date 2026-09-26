package com.saab.tv.data.update

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class ApkJarSignerTest {
    @Test fun officialSignedArchiveMatchesPinnedKey() {
        val path = System.getenv("SAAB_SIGNED_APK_TEST")
        assumeTrue("Provide an official signed APK for the cryptographic fixture", !path.isNullOrBlank())
        assertEquals(ApkSignerPolicy.releaseKeys, ApkPackageVerifier.verifiedJarSigners(File(path!!)))
    }
    @Test fun unsignedArchiveIsNeverTrusted() {
        val file = File.createTempFile("unsigned-package", ".apk")
        try {
            JarOutputStream(file.outputStream()).use {
                it.putNextEntry(JarEntry("AndroidManifest.xml")); it.write(byteArrayOf(1, 2, 3)); it.closeEntry()
            }
            assertTrue(ApkPackageVerifier.verifiedJarSigners(file).isEmpty())
        } finally { file.delete() }
    }
    @Test fun emptyArchiveIsNeverTrusted() {
        val file = File.createTempFile("empty-package", ".apk")
        try {
            JarOutputStream(file.outputStream()).close()
            assertTrue(ApkPackageVerifier.verifiedJarSigners(file).isEmpty())
        } finally { file.delete() }
    }
}
