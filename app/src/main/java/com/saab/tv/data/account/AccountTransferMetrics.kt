package com.saab.tv.data.account

import android.content.Context

/** Local aggregates only: no URL, account ID, key, content, or snapshot is recorded. */
internal object AccountTransferMetrics {
    @Synchronized fun record(context: Context, sent: Long, received: Long, success: Boolean) {
        val prefs = AccountStorage.preferences(context, "cloud_transfer_metrics")
        val day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
        val editor = prefs.edit()
        listOf("sent" to sent, "received" to received, "requests" to 1L,
            "failures" to if (success) 0L else 1L).forEach { (name, amount) ->
            val key = "$day:$name"
            editor.putLong(key, prefs.getLong(key, 0) + amount.coerceAtLeast(0))
        }
        val cutoff = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(6).toString()
        prefs.all.keys.filter { ':' in it && it.substringBefore(':') < cutoff }.forEach { editor.remove(it) }
        editor.apply()
    }
    fun snapshot(context: Context, original: Long, changedBytes: Long, changedCount: Int, totalCount: Int) {
        AccountStorage.preferences(context, "cloud_transfer_metrics").edit()
            .putLong("last_snapshot_plain", original).putLong("last_changed_payload", changedBytes)
            .putInt("last_changed_count", changedCount).putInt("last_total_count", totalCount).apply()
    }
    fun summary(context: Context): String {
        val values = AccountStorage.preferences(context, "cloud_transfer_metrics").all
        fun total(name: String) = values.filterKeys { it.endsWith(":$name") }.values.filterIsInstance<Long>().sum()
        return "Cloud Data API (last 7 UTC days, application payload bytes—not billed wire bytes): " +
            "sent=${total("sent")} received=${total("received")} requests=${total("requests")} failures=${total("failures")}. " +
            "Excludes auth, phone relay and other devices. Last incremental save: " +
            "snapshot_plain=${values["last_snapshot_plain"] ?: 0} changed_payload=${values["last_changed_payload"] ?: 0} " +
            "changed_components=${values["last_changed_count"] ?: 0}/${values["last_total_count"] ?: 0}."
    }
}
