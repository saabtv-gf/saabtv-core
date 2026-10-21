package com.saab.tv.benchmark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.saab.tv.data.model.stremio.MetaItem
import com.saab.tv.ui.components.FocusShelf
import com.saab.tv.ui.components.SaabTvCard
import com.saab.tv.ui.theme.DefaultThemes
import com.saab.tv.ui.theme.SaabTvTheme
import org.json.JSONObject

/**
 * Deterministic, offline-only content surface for U02 release-mode profiling.
 * This source set is compiled only into the app's `benchmark` build type.
 */
@OptIn(ExperimentalFoundationApi::class)
class U02BaselineFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val fixture = assets.open("u02-tv-fixture-v1.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        val fixtureId = fixture.getString("fixtureId")
        val rowCount = fixture.getInt("rowCount")
        val cardsPerRow = fixture.getInt("cardsPerRow")
        val artworkUri = fixture.getString("artworkUri")
        val rowTypes = fixture.getJSONArray("rowTypes")
        val rows = (0 until rowCount).map { rowIndex ->
            val kind = rowTypes.getString(rowIndex)
            val rowCode = if (kind == "series") "S" else "M"
            FixtureRow(
                title = "U02 ${kind.replaceFirstChar(Char::uppercase)} ${rowIndex + 1}",
                cards = (0 until cardsPerRow).map { cardIndex ->
                    val title = "U02-$rowCode${rowIndex + 1}-${(cardIndex + 1).toString().padStart(2, '0')}"
                    MetaItem(
                        id = title,
                        type = kind,
                        name = title,
                        poster = artworkUri,
                        background = artworkUri,
                        releaseInfo = "2024",
                        genres = listOf(if (kind == "series") "Drama" else "Adventure")
                    )
                }
            )
        }

        setContent {
            SaabTvTheme(DefaultThemes.VOID) {
                val initialFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    runCatching { initialFocus.requestFocus() }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF081525))
                        .semantics { contentDescription = "U02 Fixed Fixture $fixtureId" }
                        .testTag("u02-fixed-fixture")
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        itemsIndexed(rows, key = { _, row -> row.title }) { rowIndex, row ->
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    row.title,
                                    modifier = Modifier.padding(start = 48.dp, top = 18.dp, bottom = 8.dp)
                                )
                                val listState = rememberLazyListState()
                                val shelfFocused = remember { mutableStateOf(false) }
                                val focusedIndex = remember { mutableIntStateOf(-1) }
                                FocusShelf(
                                    state = listState,
                                    focused = shelfFocused,
                                    width = 140.dp,
                                    height = 210.dp,
                                    startPadding = 48.dp,
                                    focusedItemIndex = focusedIndex.intValue.takeIf { shelfFocused.value },
                                    tag = "u02-shelf-${rowIndex + 1}",
                                    viewportHeight = 228.dp,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    itemsIndexed(row.cards, key = { _, card -> card.id }) { cardIndex, card ->
                                        SaabTvCard(
                                            title = card.name,
                                            posterUrl = card.poster,
                                            previewItem = card,
                                            onClick = {},
                                            modifier = Modifier.onFocusChanged { state ->
                                                if (state.isFocused) {
                                                    focusedIndex.intValue = cardIndex
                                                    shelfFocused.value = true
                                                }
                                            }.let { base ->
                                                if (rowIndex == 0 && cardIndex == 0) {
                                                    base.focusRequester(initialFocus)
                                                } else base
                                            },
                                            normalWidth = 140.dp,
                                            normalHeight = 210.dp,
                                            showFocusOutline = true
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        "U02 • fixed offline dataset • 4 shelves × 24 cards",
                        modifier = Modifier.align(Alignment.TopEnd).padding(20.dp)
                    )
                }
            }
        }
    }

    private data class FixtureRow(val title: String, val cards: List<MetaItem>)
}
