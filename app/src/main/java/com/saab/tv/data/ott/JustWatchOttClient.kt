package com.saab.tv.data.ott

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal enum class OttMediaKind(val justWatchType: String, val cinemetaType: String) {
    MOVIE("MOVIE", "movie"),
    SERIES("SHOW", "series")
}

internal data class OttProvider(val name: String, val shortName: String)

internal data class JustWatchCandidate(
    val imdbId: String,
    val kind: OttMediaKind,
    val title: String,
    val year: Int?,
    val platforms: List<String>,
    val genres: Set<String>,
    val ageCertification: String?,
    val description: String?,
    val addedDate: String? = null
)

/** Endpoint-specific client for JustWatch's undocumented GraphQL API. */
internal class JustWatchOttClient(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val ENDPOINT = "https://apis.justwatch.com/graphql"
        private const val POPULAR_CANDIDATE_COUNT = 70
        private const val NEW_DATE_BUCKET_COUNT = 14
        private const val NEW_TITLES_PER_DATE = 50
        private const val MAX_NEW_CANDIDATES = 40
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val IMDB_ID = Regex("^tt[0-9]+$")
        private val excludedGenreCodes = setOf("ani", "rly")
        private val excludedTextTerms = setOf(
            "adult film", "erotic", "porn", "sexploitation", "reality show",
            "talk show", "game show", "talent show", "cooking show",
            "daily soap", "daily serial"
        )
        private val supportedAudioLanguages = setOf(
            "en", "eng", "english", "hi", "hin", "hindi", "te", "tel", "telugu"
        )
    }

    suspend fun popular(
        provider: OttProvider?,
        kind: OttMediaKind,
        trending: Boolean
    ): List<JustWatchCandidate> {
        val packageFilter = provider?.let { "packages: [\"${it.shortName}\"], " }.orEmpty()
        val offerFilter = provider?.let { "packages: [\"${it.shortName}\"]" }.orEmpty()
        val sort = if (trending) "TRENDING" else "POPULAR"
        val query = """
            query {
              popularTitles(
                country: IN
                first: $POPULAR_CANDIDATE_COUNT
                sortBy: $sort
                filter: { ${packageFilter}objectTypes: [${kind.justWatchType}] }
              ) {
                edges {
                  node {
                    __typename
                    ... on Movie {
                      content(country: IN, language: en) { ${contentFields()} }
                      offers(country: IN, platform: WEB, filter: { $offerFilter }) { ${offerFields()} }
                    }
                    ... on Show {
                      content(country: IN, language: en) { ${contentFields()} }
                      offers(country: IN, platform: WEB, filter: { $offerFilter }) { ${offerFields()} }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val edges = postGraphQl(query)
            .objectOrNull("data")
            ?.objectOrNull("popularTitles")
            ?.arrayOrNull("edges")
            ?: throw IOException("JustWatch returned no popularity results")
        return parseEdges(edges, kind, provider, addedDate = null)
    }

    suspend fun newlyAdded(
        provider: OttProvider,
        kind: OttMediaKind
    ): List<JustWatchCandidate> {
        val bucketQuery = """
            query {
              newTitleBuckets(
                country: IN
                first: $NEW_DATE_BUCKET_COUNT
                bucketSize: $NEW_TITLES_PER_DATE
                priceDrops: false
                pageType: NEW
                groupBy: DATE_PACKAGE
                filter: { packages: [\"${provider.shortName}\"], objectTypes: [${kind.justWatchType}] }
              ) {
                edges {
                  key { ... on DatePackageAggregationKey { date package { shortName } } }
                }
              }
            }
        """.trimIndent()

        val dates = postGraphQl(bucketQuery)
            .objectOrNull("data")
            ?.objectOrNull("newTitleBuckets")
            ?.arrayOrNull("edges")
            ?.mapNotNull { edge -> edge.asJsonObject.objectOrNull("key")?.stringOrNull("date") }
            ?.distinct()
            .orEmpty()
        if (dates.isEmpty()) return emptyList()

        val candidates = mutableListOf<JustWatchCandidate>()
        val seen = mutableSetOf<String>()
        for (date in dates) {
            val edges = postGraphQl(newTitlesQuery(provider, kind, date))
                .objectOrNull("data")
                ?.objectOrNull("newTitles")
                ?.arrayOrNull("edges")
                ?: continue
            parseEdges(edges, kind, provider, date).forEach { candidate ->
                if (seen.add("${candidate.kind}:${candidate.imdbId}")) candidates += candidate
            }
            if (candidates.size >= MAX_NEW_CANDIDATES) break
        }
        return candidates.take(MAX_NEW_CANDIDATES)
    }

    private fun newTitlesQuery(provider: OttProvider, kind: OttMediaKind, date: String): String = """
        query {
          newTitles(
            country: IN
            date: \"$date\"
            first: $NEW_TITLES_PER_DATE
            priceDrops: false
            pageType: NEW
            filter: { packages: [\"${provider.shortName}\"], objectTypes: [${kind.justWatchType}] }
          ) {
            edges {
              node {
                __typename
                ... on Movie {
                  content(country: IN, language: en) { ${contentFields()} }
                  offers(country: IN, platform: WEB, filter: { packages: [\"${provider.shortName}\"] }) {
                    ${offerFields()}
                  }
                }
                ... on Season {
                  content(country: IN, language: en) { genres { shortName } }
                  show { content(country: IN, language: en) { ${contentFields()} } }
                  offers(country: IN, platform: WEB, filter: { packages: [\"${provider.shortName}\"] }) {
                    ${offerFields()}
                  }
                }
              }
            }
          }
        }
    """.trimIndent()

    internal fun parseEdges(
        edges: JsonArray,
        expectedKind: OttMediaKind,
        provider: OttProvider?,
        addedDate: String?
    ): List<JustWatchCandidate> {
        val seen = mutableSetOf<String>()
        return edges.mapNotNull { edgeElement ->
            val node = edgeElement.asJsonObject.objectOrNull("node") ?: return@mapNotNull null
            val nodeType = node.stringOrNull("__typename")
            val content = when {
                expectedKind == OttMediaKind.SERIES && nodeType == "Season" ->
                    node.objectOrNull("show")?.objectOrNull("content")
                else -> node.objectOrNull("content")
            } ?: return@mapNotNull null

            val imdbId = content.objectOrNull("externalIds")
                ?.stringOrNull("imdbId")
                ?.lowercase(Locale.US)
                ?.takeIf(IMDB_ID::matches)
                ?: return@mapNotNull null
            if (!seen.add("$expectedKind:$imdbId")) return@mapNotNull null

            val offers = node.arrayOrNull("offers") ?: JsonArray()
            if (!hasEligibleAudioOrMissingMetadata(offers)) return@mapNotNull null

            val genres = buildSet {
                content.arrayOrNull("genres")?.forEach { genre ->
                    genre.asJsonObject.stringOrNull("shortName")?.lowercase(Locale.US)?.let(::add)
                }
                if (nodeType == "Season") {
                    node.objectOrNull("content")?.arrayOrNull("genres")?.forEach { genre ->
                        genre.asJsonObject.stringOrNull("shortName")?.lowercase(Locale.US)?.let(::add)
                    }
                }
            }
            if (genres.any(excludedGenreCodes::contains)) return@mapNotNull null

            val title = content.stringOrNull("title")?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val description = content.stringOrNull("shortDescription")
            if (containsExcludedText(title, description)) return@mapNotNull null

            val platforms = offers.mapNotNull { offer ->
                offer.asJsonObject.objectOrNull("package")?.stringOrNull("clearName")
            }.distinct().ifEmpty { provider?.let { listOf(it.name) }.orEmpty() }

            JustWatchCandidate(
                imdbId = imdbId,
                kind = expectedKind,
                title = title,
                year = content.intOrNull("originalReleaseYear"),
                platforms = platforms,
                genres = genres,
                ageCertification = content.stringOrNull("ageCertification"),
                description = description,
                addedDate = addedDate
            )
        }
    }

    /**
     * India offers frequently return an empty audioLanguages array. Enforce the requested
     * languages when metadata exists, but do not treat missing metadata as proof of exclusion.
     */
    private fun hasEligibleAudioOrMissingMetadata(offers: JsonArray): Boolean {
        val audio = offers.flatMap { offer ->
            offer.asJsonObject.arrayOrNull("audioLanguages")
                ?.mapNotNull { language -> runCatching { language.asString.trim().lowercase(Locale.US) }.getOrNull() }
                .orEmpty()
        }
        return audio.isEmpty() || audio.any(supportedAudioLanguages::contains)
    }

    private fun containsExcludedText(title: String, description: String?): Boolean {
        val searchable = "$title ${description.orEmpty()}".lowercase(Locale.US)
        return excludedTextTerms.any(searchable::contains)
    }

    private suspend fun postGraphQl(query: String): JsonObject {
        val payload = JsonObject().apply { addProperty("query", query) }.toString()
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val responseText = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url(ENDPOINT)
                        .header("Accept", "application/json")
                        .header("Origin", "https://www.justwatch.com")
                        .header("User-Agent", "Saab-TV/Android")
                        .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    okHttpClient.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) throw IOException("JustWatch returned HTTP ${response.code}")
                        if (body.isBlank()) throw IOException("JustWatch returned an empty response")
                        body
                    }
                }
                val root = JsonParser.parseString(responseText).asJsonObject
                val errors = root.arrayOrNull("errors")
                if (errors != null && errors.size() > 0) {
                    val message = errors.first().asJsonObject.stringOrNull("message")
                    throw IOException("JustWatch GraphQL error: ${message ?: "unknown error"}")
                }
                return root
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < 2) delay(500L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("JustWatch request failed")
    }

    private fun contentFields(): String = """
        title
        originalTitle
        ageCertification
        shortDescription
        originalReleaseYear
        genres { shortName }
        externalIds { imdbId }
    """.trimIndent()

    private fun offerFields(): String = """
        monetizationType
        audioLanguages(language: en)
        package { shortName clearName }
    """.trimIndent()
}

private fun JsonObject.objectOrNull(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.arrayOrNull(name: String): JsonArray? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.stringOrNull(name: String): String? =
    get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asString

private fun JsonObject.intOrNull(name: String): Int? {
    val value = get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive } ?: return null
    return runCatching { value.asInt }.getOrNull()
}
