package com.saab.tv.ui.account

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process

/** Tiny private helper process: restart without delayed/inexact alarm delivery. */
class AccountRestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previousPid = intent.getIntExtra("previous_pid", -1)
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        manager.runningAppProcesses?.filter { process ->
            process.uid == Process.myUid() && process.pid != Process.myPid() &&
                ((process.pid == previousPid && process.processName == packageName) ||
                    process.processName == "$packageName:thumbnail_worker")
        }?.forEach { Process.killProcess(it.pid) }
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, AccountEntryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
        }, 250)
    }
}
