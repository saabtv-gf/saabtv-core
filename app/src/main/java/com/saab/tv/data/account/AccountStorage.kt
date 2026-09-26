package com.saab.tv.data.account

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.security.MessageDigest

/** An account is immutable while the main application process is running. */
object AccountStorage {
    private const val IDENTITY_PREFS = "saabtv_account_identity"
    @Volatile private var runningScope: String? = null

    fun bindRunningAccount(context: Context) {
        val selected = scopeFor(requireNotNull(userId(context)))
        check(runningScope == null || runningScope == selected) { "Account switching requires a fresh process." }
        runningScope = selected
    }

    fun userId(context: Context): String? = context.getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE)
        .getString("user_id", null)?.takeIf { it.isNotBlank() }

    fun setUserId(context: Context, userId: String?) {
        check(context.getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE)
            .edit().putString("user_id", userId).commit())
    }

    fun scopeFor(userId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(userId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun scope(context: Context): String = runningScope ?: userId(context)?.let(::scopeFor) ?: "signed_out"
    fun name(context: Context, name: String): String = "${name}_account_${scope(context)}"
    fun preferences(context: Context, name: String): SharedPreferences =
        context.getSharedPreferences(name(context, name), Context.MODE_PRIVATE)
    fun files(context: Context): File = File(context.filesDir, "accounts/${scope(context)}").apply { mkdirs() }
    fun databaseName(context: Context): String = name(context, "saabtv_db")
}
