package com.saab.tv.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.saab.tv.data.update.AppUpdateManager
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class UpdateRecoveryComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun updateCheckFailureOffersCheckAgainAndRepeatsTheSameSafeOperation() {
        val requests = AtomicInteger()
        val context = RuntimeEnvironment.getApplication()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message("Unavailable")
                .body("temporarily unavailable".toResponseBody("text/plain".toMediaType()))
                .build()
        }.build()
        val updateManager = AppUpdateManager(client, context)

        compose.setContent {
            MaterialTheme { AboutSettings(onGoBack = {}, updateManager = updateManager) }
        }
        compose.onNodeWithText("Check For Updates").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Retry Update").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Couldn’t Check For Updates").assertExists()
        compose.onNodeWithText("Retry Update").assertHasClickAction().performClick()
        compose.waitUntil(5_000) { requests.get() == 2 }
        assertEquals(2, requests.get())
    }
}
