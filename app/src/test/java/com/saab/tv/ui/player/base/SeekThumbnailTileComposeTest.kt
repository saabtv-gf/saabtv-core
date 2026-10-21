package com.saab.tv.ui.player.base

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.tv.material3.MaterialTheme
import com.saab.tv.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
class SeekThumbnailTileComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cachedThumbnailIsShownImmediatelyWithoutASeekLoader() {
        val bitmap = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.MAGENTA)
        val frame = SeekPreviewFrame(positionMs = 30_000L, bitmap = bitmap, cacheKey = "cached-source")
        compose.setContent {
            MaterialTheme {
                SeekThumbnailTile(frame = frame, isSelected = true)
            }
        }

        val previewDescription = RuntimeEnvironment.getApplication().getString(
            R.string.player_scene_preview_at,
            "0:30"
        )
        compose.onNodeWithContentDescription(previewDescription).assertExists()
        compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        ).assertCountEquals(0)
    }
}
