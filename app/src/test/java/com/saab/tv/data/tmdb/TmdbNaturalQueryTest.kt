package com.saab.tv.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbNaturalQueryTest {
    @Test fun popularThrillerMoviesMapsToMovieThrillerPopularity() {
        assertEquals(
            TmdbNaturalQuery("movie", "53", null, "popularity.desc"),
            TmdbNaturalQuery.parse("popular thriller movies")
        )
    }

    @Test fun languageOnlyMovieQueryUsesOriginalLanguage() {
        assertEquals(
            TmdbNaturalQuery("movie", null, "te", "vote_average.desc"),
            TmdbNaturalQuery.parse("telugu movies")
        )
    }

    @Test fun englishThrillerSeriesMapsToTvThrillerAndLanguage() {
        assertEquals(
            TmdbNaturalQuery("tv", "9648|80", "en", "vote_average.desc"),
            TmdbNaturalQuery.parse("english thriller series")
        )
    }

    @Test fun unqualifiedThrillerQueryUsesTheCorrectGenreIdsForBothMediaTypes() {
        val query = TmdbNaturalQuery.parse("popular thriller")
        assertEquals("53", query?.genreIds)
        assertEquals("9648|80", query?.tvGenreIds)
    }

    @Test fun ordinaryTitleSearchIsNotTreatedAsDiscoverIntent() {
        assertNull(TmdbNaturalQuery.parse("The Good Place"))
    }

    @Test fun supportsAllConfiguredProfileLanguagesCaseInsensitively() {
        mapOf(
            "Kannada movies" to "kn",
            "MALAYALAM series" to "ml",
            "Hindi films" to "hi",
            "Tamil movies" to "ta"
        ).forEach { (query, language) ->
            assertEquals(language, TmdbNaturalQuery.parse(query)?.originalLanguage)
        }
    }
}
