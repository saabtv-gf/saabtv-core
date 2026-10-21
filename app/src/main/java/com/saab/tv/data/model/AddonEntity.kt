package com.saab.tv.data.model

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "addons", primaryKeys = ["profileOwnerId", "transportUrl"], indices = [Index("profileOwnerId")])
data class AddonEntity(
    val transportUrl: String,

    val id: String,
    val name: String,
    val version: String,
    val description: String?,
    val iconUrl: String?,
    val isTrusted: Boolean = false,
    val isEnabled: Boolean = true,
    val nickname: String? = null,

    val catalogsJson: String = "[]",

    val supportsMeta: Boolean = false,
    val supportsStream: Boolean = true,
    val typesJson: String = "[]",
    val idPrefixesJson: String = "[]",

    val sortOrder: Int = 999,

    /** Stable profile ownership; unlike the Room profile row ID this survives cloud restore. */
    val profileOwnerId: String = LEGACY_PROFILE_OWNER_ID
)

const val LEGACY_PROFILE_OWNER_ID = "legacy-default"
