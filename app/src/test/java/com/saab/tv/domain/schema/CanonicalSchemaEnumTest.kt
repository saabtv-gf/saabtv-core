package com.saab.tv.domain.schema

import org.junit.Assert.*
import org.junit.Test

class CanonicalSchemaEnumTest {
    @Test fun serializedKindsAndFieldLocalizationHaveOneStableContract() {
        assertEquals(listOf("movie", "series", "episode", "person"), ContentKind.entries.map { it.keyPart })
        ContentKind.values().forEach { assertEquals(it, ContentKind.valueOf(it.name)) }
        assertEquals(setOf(CanonicalTextField.TITLE, CanonicalTextField.SYNOPSIS, CanonicalTextField.LOGO),
            CanonicalTextField.entries.filter { it.isLocalized }.toSet())
        CanonicalTextField.values().forEach { assertEquals(it, CanonicalTextField.valueOf(it.name)) }
        IdentityNamespace.entries.forEach { assertEquals(it, IdentityNamespace.valueOf(it.name)) }
        MetadataSource.entries.forEach { assertEquals(it, MetadataSource.valueOf(it.name)) }
    }
}
