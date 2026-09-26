package com.saab.tv.data.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap

data class SecurePreferencesResult(
    val preferences: SharedPreferences,
    val isPersistent: Boolean
)

/** Creates an isolated encrypted store and fails closed to process-memory only. */
object SecurePreferences {
    fun create(context: Context, prefsName: String, keyAlias: String): SecurePreferencesResult {
        fun encrypted(): SharedPreferences {
            val masterKey = MasterKey.Builder(context, keyAlias)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                prefsName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        runCatching { encrypted() }.getOrNull()?.let {
            return SecurePreferencesResult(it, true)
        }

        // Reset only this integration's file and key. Never delete a shared alias.
        runCatching {
            File(context.applicationInfo.dataDir, "shared_prefs/$prefsName.xml").delete()
            File(context.applicationInfo.dataDir, "shared_prefs/$prefsName.xml.bak").delete()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(keyAlias)
            }
        }

        runCatching { encrypted() }.getOrNull()?.let {
            return SecurePreferencesResult(it, true)
        }

        Log.e("SecurePreferences", "Encrypted storage unavailable for $prefsName; using non-persistent memory")
        return SecurePreferencesResult(MemorySharedPreferences(), false)
    }
}

private class MemorySharedPreferences : SharedPreferences {
    private val values = ConcurrentHashMap<String, Any>()
    private val listeners = ConcurrentHashMap.newKeySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> = values.toMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        ((values[key] as? Set<String>) ?: defValues)?.toMutableSet()
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = key != null && values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) listeners += listener
    }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) listeners -= listener
    }

    private inner class Editor : SharedPreferences.Editor {
        private val changes = linkedMapOf<String, Any?>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = set(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            set(key, values?.toSet())
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = set(key, value)
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = set(key, value)
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = set(key, value)
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = set(key, value)
        override fun remove(key: String?): SharedPreferences.Editor = set(key, null)
        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
        override fun commit(): Boolean {
            val changedKeys = linkedSetOf<String>()
            if (clearAll) {
                changedKeys += values.keys
                values.clear()
            }
            changes.forEach { (key, value) ->
                changedKeys += key
                if (value == null) values.remove(key) else values[key] = value
            }
            changedKeys.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this@MemorySharedPreferences, key) } }
            return true
        }
        override fun apply() { commit() }

        private fun set(key: String?, value: Any?): SharedPreferences.Editor = apply {
            if (key != null) changes[key] = value
        }
    }
}
