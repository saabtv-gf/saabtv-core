package com.saab.tv.ui.components

internal fun initialsForPerson(name: String): String =
    name.trim().split(Regex("\\s+")).filter(String::isNotBlank).take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("").ifBlank { "?" }
