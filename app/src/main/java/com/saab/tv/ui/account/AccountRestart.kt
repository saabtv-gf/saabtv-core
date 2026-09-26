package com.saab.tv.ui.account

import android.app.Activity
import android.content.Intent
import android.os.Process

object AccountRestart {
    /** Discard all account-bound Room instances and integration caches on logout/restore. */
    fun restart(activity: Activity) {
        val intent = Intent(activity, AccountRestartActivity::class.java)
            .putExtra("previous_pid", Process.myPid())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(intent)
    }
}
