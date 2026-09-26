package com.saab.tv.data.model

/**
 * Represents a Stremio addon fetched from the user's Stremio account.
 * Used for the import selection UI.
 */
data class StremioAddonItem(
    val name: String,
    val transportUrl: String,
    val description: String?,
    var isSelected: Boolean,
    val isAlreadyInstalled: Boolean
)
