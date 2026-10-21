package com.saab.tv.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saab.tv.data.account.AccountStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SecurePreferencesAtRestTest {
    @Test fun profileRuntimePayloadPersistsWithoutPlaintextCredentialMaterial() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previousAccount = AccountStorage.userId(context)
        AccountStorage.setUserId(context, UUID.randomUUID().toString())
        val storeName = "profile_runtime_snapshot_${UUID.randomUUID()}"
        val alias = "saabtv_profile_runtime_${UUID.randomUUID()}"
        val secret = "credential-bearing-addon-password-should-not-appear-in-xml"
        try {
            val store = SecurePreferences.create(context, storeName, alias)
            assertTrue("The API 36 test device must provide persistent Android Keystore storage", store.isPersistent)

            assertTrue(store.preferences.edit().putString("runtime_snapshot_json", "{\"transportUrl\":\"$secret\"}").commit())

            val file = File(File(context.applicationInfo.dataDir, "shared_prefs"),
                "${AccountStorage.name(context, storeName)}.xml")
            assertTrue(file.exists())
            val onDisk = file.readText()
            assertFalse("Encrypted preference file must not contain the credential", onDisk.contains(secret))
            assertEquals("{\"transportUrl\":\"$secret\"}", store.preferences.getString("runtime_snapshot_json", null))
        } finally {
            AccountStorage.setUserId(context, previousAccount)
        }
    }
}
