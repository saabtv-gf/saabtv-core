package com.saab.tv.domain.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalValueResolverTest {
    private fun candidate(
        value: String,
        source: MetadataSource,
        at: Long = 10,
        identity: String = value,
        precision: Int = 0,
        verified: Boolean = true
    ) = CanonicalValueCandidate(value, source, at, identity, precision, verified)

    @Test fun sourceAuthorityBeatsFreshnessAndLegacyFactsRemainFallbackOnly() {
        val selection = CanonicalValueResolver.select(
            listOf(
                candidate("legacy", MetadataSource.LEGACY, at = 100),
                candidate("addon", MetadataSource.ADDON, at = 90),
                candidate("cinemeta", MetadataSource.CINEMETA, at = 80),
                candidate("tmdb", MetadataSource.TMDB, at = 70)
            ),
            nowMillis = 100
        )

        assertEquals("cinemeta", selection?.value)
        assertEquals(MetadataSource.CINEMETA, selection?.source)
        assertEquals(80L, selection?.observedAtMillis)
    }

    @Test fun userOverrideAndPrecisionWinOnlyAtTheirDefinedPrecedence() {
        val precise = CanonicalValueResolver.select(
            listOf(
                candidate("year", MetadataSource.TMDB, at = 90, precision = 0),
                candidate("date", MetadataSource.TMDB, at = 10, precision = 1),
                candidate("override", MetadataSource.USER_OVERRIDE, at = 1)
            ),
            nowMillis = 100
        )
        assertEquals("override", precise?.value)

        val sameProvider = CanonicalValueResolver.select(
            listOf(
                candidate("year", MetadataSource.TMDB, at = 90, precision = 0),
                candidate("date", MetadataSource.TMDB, at = 10, precision = 1)
            ),
            nowMillis = 100
        )
        assertEquals("date", sameProvider?.value)
    }

    @Test fun invalidObservationsAreIgnoredAndTiesAreDeterministic() {
        assertNull(CanonicalValueResolver.select(emptyList<CanonicalValueCandidate<String>>(), nowMillis = 0))
        assertNull(CanonicalValueResolver.select(
            listOf(candidate("future", MetadataSource.TMDB, at = 11)), nowMillis = 10
        ))
        assertNull(CanonicalValueResolver.select(
            listOf(candidate("unverified", MetadataSource.TMDB, at = 10, verified = false)), nowMillis = 10
        ))

        val tied = CanonicalValueResolver.select(
            listOf(
                candidate("b", MetadataSource.TMDB, at = 10, identity = "b"),
                candidate("a", MetadataSource.TMDB, at = 10, identity = "a")
            ),
            nowMillis = 10
        )
        assertEquals("b", tied?.value)
    }
}
