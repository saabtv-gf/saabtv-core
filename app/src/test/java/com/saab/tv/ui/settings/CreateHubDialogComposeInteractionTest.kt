package com.saab.tv.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.saab.tv.data.model.CatalogConfigEntity
import com.saab.tv.data.model.HubRowItemEntity
import com.saab.tv.domain.HubShape
import com.saab.tv.ui.home.CreateHubDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class CreateHubDialogComposeInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedCategoriesAndShapeAreReturnedWhenCreatingHub() {
        val category = CatalogConfigEntity(
            uniqueId = "cinemeta:movie:top",
            transportUrl = "https://cinemeta.example/manifest.json",
            addonName = "Cinemeta",
            catalogType = "movie",
            catalogId = "top",
            catalogName = "Popular"
        )
        var created: Triple<String, HubShape, List<HubRowItemEntity>>? = null

        compose.setContent {
            MaterialTheme {
                CreateHubDialog(
                    categories = listOf(category),
                    onDismiss = {},
                    onCreate = { name, shape, items -> created = Triple(name, shape, items) }
                )
            }
        }

        compose.onNode(hasSetTextAction()).performTextInput("Favorites")
        compose.onNodeWithText("SQUARE").performClick()
        compose.onNodeWithText("Add Category").performClick()
        compose.onNodeWithText("Popular - Movie").performClick()
        compose.onNodeWithText("Confirm").performClick()
        compose.onNodeWithText("Save").performClick()

        assertEquals("Favorites", created?.first)
        assertEquals(HubShape.SQUARE, created?.second)
        assertEquals("Popular - Movie", created?.third?.single()?.title)
        assertEquals(0, created?.third?.single()?.itemOrder)
    }
}
