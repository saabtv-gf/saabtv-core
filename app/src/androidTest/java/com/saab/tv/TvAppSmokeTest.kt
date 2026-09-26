package com.saab.tv

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvAppSmokeTest {
    @Test
    fun coldLaunchKeepsAccountGateResponsive() {
        ActivityScenario.launch(com.saab.tv.ui.account.AccountEntryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                check(!activity.isFinishing)
                check(!activity.isDestroyed)
            }
        }
    }
}
