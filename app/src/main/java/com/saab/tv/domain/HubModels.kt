package com.saab.tv.domain

import androidx.compose.runtime.Immutable
import com.saab.tv.data.model.stremio.MetaItem

/**
 * ============================================================================
 * HUB MODELS - Omni-Style Category Hub System
 * ============================================================================
 * 
 * Defines the data structures for Hub Rows on the Home Screen.
 * Hub Rows display a collection of clickable "Hub Cards" representing
 * categories (e.g., "Action", "Netflix", "Comedy").
 * ============================================================================
 */

/**
 * Shape variants for Hub Cards.
 * Each shape has a corresponding aspect ratio for rendering.
 */
enum class HubShape(val aspectRatio: Float) {
    HORIZONTAL(16f / 9f),  // 1.77f - Wide landscape cards
    VERTICAL(2f / 3f),      // 0.67f - Tall portrait cards (poster-like)
    SQUARE(1f)              // 1.0f  - Square cards
}

/**
 * Represents a single Hub item (category) within a Hub Row.
 * 
 * @param id Unique identifier for this hub item
 * @param title Display name (e.g., "Action", "Netflix")
 * @param categoryId The category ID to navigate to when clicked
 * @param customImageUrl Optional custom image URL (uploaded via QR code)
 */
@Immutable
data class HubItem(
    val id: String,
    val title: String,
    val categoryId: String,
    val customImageUrl: String? = null
)

/**
 * Sealed interface for Home Screen row types.
 * Allows the HomeScreen to render different row types polymorphically.
 */
interface HomeRowItem {
    val id: String
    val title: String
    val order: Int
}

/**
 * A standard movie/content row showing items from a single category.
 * Wraps the existing HomeRow data class for backward compatibility.
 */
@Immutable
data class CategoryRow(
    override val id: String,
    override val title: String,
    override val order: Int,
    val items: List<MetaItem>,
    val isInfiniteLoopEnabled: Boolean = false,
    val visibleItemCount: Int = 15,
    val isInfiniteScrollingEnabled: Boolean = true
) : HomeRowItem {
    
    companion object {
        /**
         * Create a CategoryRow from an existing HomeRow.
         */
        fun fromHomeRow(homeRow: HomeRow): CategoryRow {
            return CategoryRow(
                id = homeRow.configId,
                title = homeRow.title,
                order = homeRow.order,
                items = homeRow.items,
                isInfiniteLoopEnabled = homeRow.isInfiniteLoopEnabled,
                visibleItemCount = homeRow.visibleItemCount,
                isInfiniteScrollingEnabled = homeRow.isInfiniteScrollingEnabled
            )
        }
    }
}

/**
 * A hub row displaying a collection of category cards.
 * Each card represents a category that opens a Grid View when clicked.
 * 
 * @param id Unique identifier for this hub row
 * @param title Row title displayed above the cards (e.g., "Streaming Services")
 * @param items List of hub items (categories) to display
 * @param shape The shape/aspect ratio for all cards in this row
 */
@Immutable
data class HubGroupRow(
    override val id: String,
    override val title: String,
    override val order: Int,
    val items: List<HubItem>,
    val shape: HubShape = HubShape.HORIZONTAL
) : HomeRowItem
