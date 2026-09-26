package com.saab.tv.domain

import androidx.compose.runtime.Immutable

@Immutable
data class AddonSubtitle(
    val id: String,
    val url: String,
    val lang: String?,
    val addonName: String,
    val releaseName: String? = null
)
