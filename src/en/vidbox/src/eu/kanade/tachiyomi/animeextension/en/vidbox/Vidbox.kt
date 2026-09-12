package eu.kanade.tachiyomi.animeextension.en.vidbox

import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.vidbox.extractors.VidboxExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class Vidbox :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "Vidbox"

    private val preferences: SharedPreferences by getPreferencesLazy {
        val currentApiUrl = getString(PREF_API_URL_KEY, null)
        if (currentApiUrl == null || currentApiUrl == "https://api.themoviedb.org/3") {
            edit().putString(PREF_API_URL_KEY, DEFAULT_API_URL).apply()
        }

        val currentKey = getString(PREF_TMDB_KEY, null)
        if (currentKey == null || currentKey == "4bbdaeed1e7eb5a9cb84e7235fe52422" || currentKey.isBlank()) {
            edit().putString(PREF_TMDB_KEY, DEFAULT_TMDB_KEY).apply()
        }
    }

    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL_KEY, DEFAULT_BASE_URL)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL

    override val lang = "en"
    override val supportsLatest = true

    private val tmdbApiKey: String
        get() {
            val key = preferences.getString(PREF_TMDB_KEY, DEFAULT_TMDB_KEY)?.trim().orEmpty()
            return if (key.isBlank() || key == "4bbdaeed1e7eb5a9cb84e7235fe52422") DEFAULT_TMDB_KEY else key
        }

    private val apiUrl: String
        get() = preferences.getString(PREF_API_URL_KEY, DEFAULT_API_URL)?.takeIf { it.isNotBlank() } ?: DEFAULT_API_URL

    private val imageUrl = "https://image.tmdb.org/t/p"

    private val extractor by lazy { VidboxExtractor(client, headers) }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")

    private fun buildApiUrl(endpoint: String, vararg queryParams: Pair<String, String>): String {
        val base = apiUrl.trimEnd('/')
        val isTmdbOfficial = base.contains("themoviedb.org", ignoreCase = true)

        val urlBuilder = "$base/$endpoint".toHttpUrl().newBuilder()
        if (isTmdbOfficial) {
            urlBuilder.addQueryParameter("api_key", tmdbApiKey)
        }
        for ((key, value) in queryParams) {
            urlBuilder.addQueryParameter(key, value)
        }
        return urlBuilder.build().toString()
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(buildApiUrl("trending/all/week", "page" to page.toString()), headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<PageDto<MediaItemDto>>()
        val animes = data.results.map { it.toSAnime() }
        return AnimesPage(animes, data.totalPages > data.page && data.results.isNotEmpty())
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request = GET(buildApiUrl("movie/now_playing", "page" to page.toString()), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            buildApiUrl("search/multi", "query" to query.trim(), "page" to page.toString())
        } else {
            buildApiUrl("trending/all/week", "page" to page.toString())
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Details ===============================

    private fun animeUrlToTypeAndId(url: String): Pair<String, String> {
        val clean = url.removePrefix("/")
        val parts = clean.split("/")
        val type = if (parts[0] == "movie" || parts[0] == "tv") parts[0] else "movie"
        val id = parts.getOrNull(1) ?: parts[0]
        return Pair(type, id)
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val (type, id) = animeUrlToTypeAndId(anime.url)
        return GET(buildApiUrl("$type/$id", "append_to_response" to "external_ids"), headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val url = response.request.url.toString()
        return if (url.contains("/movie/")) {
            val movie = response.parseAs<MovieDetailDto>()
            SAnime.create().apply {
                title = movie.title
                this.url = "movie/${movie.id}"
                thumbnail_url = movie.posterPath?.let { "$imageUrl/w500$it" }
                description = buildString {
                    movie.overview?.let { append(it + "\n\n") }
                    val details = listOfNotNull(
                        "**Type:** Movie",
                        movie.voteAverage.takeIf { it > 0 }?.let {
                            "**Score:** ★ ${String.format(Locale.US, "%.1f", it)}"
                        },
                        movie.releaseDate?.takeIf { it.isNotBlank() }?.let { "**Release Date:** $it" },
                        movie.runtime?.takeIf { it > 0 }?.let {
                            val hours = it / 60
                            val minutes = it % 60
                            "**Runtime:** ${if (hours > 0) "${hours}h " else ""}${minutes}m"
                        },
                    )
                    append(details.joinToString("\n"))
                }
                genre = movie.genres.joinToString(", ") { it.name }
                status = SAnime.COMPLETED
                author = movie.releaseDate?.take(4)
            }
        } else {
            val tv = response.parseAs<TvDetailDto>()
            SAnime.create().apply {
                title = tv.name
                this.url = "tv/${tv.id}"
                thumbnail_url = tv.posterPath?.let { "$imageUrl/w500$it" }
                description = buildString {
                    tv.overview?.let { append(it + "\n\n") }
                    val details = listOfNotNull(
                        "**Type:** TV Show",
                        tv.voteAverage.takeIf { it > 0 }?.let {
                            "**Score:** ★ ${String.format(Locale.US, "%.1f", it)}"
                        },
                        tv.firstAirDate?.takeIf { it.isNotBlank() }?.let { "**First Air Date:** $it" },
                    )
                    append(details.joinToString("\n"))
                }
                genre = tv.genres.joinToString(", ") { it.name }
                status = if (tv.status == "Ended") SAnime.COMPLETED else SAnime.ONGOING
                author = tv.firstAirDate?.take(4)
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val (type, id) = animeUrlToTypeAndId(anime.url)
        val detailsResponse = client.newCall(animeDetailsRequest(anime)).awaitSuccess()

        return if (type == "movie") {
            val movie = detailsResponse.parseAs<MovieDetailDto>()
            val extraData = Triple(
                movie.title,
                movie.releaseDate?.take(4) ?: "",
                movie.externalIds?.imdbId ?: "",
            )
            val extraDataEncoded = extraData.toJsonString()

            listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    url = "movie/${movie.id}#$extraDataEncoded"
                    episode_number = 1F
                    date_upload = parseDate(movie.releaseDate)
                },
            )
        } else {
            val tv = detailsResponse.parseAs<TvDetailDto>()
            val extraData = Triple(
                tv.name,
                tv.firstAirDate?.take(4) ?: "",
                tv.externalIds?.imdbId ?: "",
            )
            val extraDataEncoded = extraData.toJsonString()

            tv.seasons
                .filter { it.seasonNumber > 0 }
                .parallelCatchingFlatMap { season ->
                    val seasonResponse = client.newCall(
                        GET(buildApiUrl("tv/$id/season/${season.seasonNumber}"), headers),
                    ).awaitSuccess()
                    val seasonDetail = seasonResponse.parseAs<TvSeasonDetailDto>()
                    seasonDetail.episodes.map { ep ->
                        SEpisode.create().apply {
                            name = "S${season.seasonNumber.toString().padStart(2, '0')}E${ep.episodeNumber.toString().padStart(2, '0')} - ${ep.name}"
                            url = "tv/$id/${season.seasonNumber}/${ep.episodeNumber}#$extraDataEncoded"
                            episode_number = ep.episodeNumber.toFloat()
                            scanlator = "Season ${season.seasonNumber}"
                            date_upload = parseDate(ep.airDate)
                        }
                    }
                }
                .reversed()
        }
    }

    // ============================== Video List ============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val parts = episode.url.split("#", limit = 2)
        val path = parts[0]
        val (title, year, imdbId) = if (parts.size > 1) {
            parts[1].parseAs<Triple<String, String, String>>()
        } else {
            Triple("", "", "")
        }

        val enabledServers = preferences.getStringSet(PREF_SERVERS_KEY, PREF_SERVERS_DEFAULT) ?: PREF_SERVERS_DEFAULT
        val qualityPref = preferences.getString(PREF_QUALITY_KEY, "Auto") ?: "Auto"

        return extractor.videosFromUrl(
            path = path,
            title = title,
            year = year,
            imdbId = imdbId,
            baseUrl = baseUrl,
            enabledServers = enabledServers,
            qualityPref = qualityPref,
        )
    }

    override fun videoListParse(response: Response): List<Video> = emptyList()

    // ============================== Helpers ===============================

    private fun MediaItemDto.toSAnime(): SAnime {
        val resolvedType = mediaType ?: if (title != null) "movie" else "tv"
        val resolvedId = id
        val resolvedTitle = realTitle
        return SAnime.create().apply {
            this.title = resolvedTitle
            this.url = "$resolvedType/$resolvedId"
            this.thumbnail_url = posterPath?.let { "$imageUrl/w500$it" }
            this.description = overview
            this.status = SAnime.UNKNOWN
        }
    }

    private fun parseDate(dateStr: String?): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr ?: "")?.time ?: 0L
    }.getOrDefault(0L)

    // ============================== Preferences ===========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addEditTextPreference(
            key = PREF_BASE_URL_KEY,
            title = "Base URL / Domain",
            summary = "Default: $DEFAULT_BASE_URL. Current: ${preferences.getString(PREF_BASE_URL_KEY, DEFAULT_BASE_URL)}",
            getSummary = { "Default: $DEFAULT_BASE_URL. Current: $it" },
            default = DEFAULT_BASE_URL,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )

        screen.addEditTextPreference(
            key = PREF_API_URL_KEY,
            title = "API / TMDB Proxy URL",
            summary = "Default: $DEFAULT_API_URL. Current: ${preferences.getString(PREF_API_URL_KEY, DEFAULT_API_URL)}",
            getSummary = { "Default: $DEFAULT_API_URL. Current: $it" },
            default = DEFAULT_API_URL,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = listOf("Auto", "1080p", "720p", "480p", "360p"),
            entryValues = listOf("Auto", "1080", "720", "480", "360"),
            default = "Auto",
            summary = "%s",
        )

        screen.addSetPreference(
            key = PREF_SERVERS_KEY,
            title = "Enabled Video Servers",
            entries = VidboxExtractor.VIDEASY_SERVERS.map { "${it.displayName} (${it.audioLabel ?: "Original"})" },
            entryValues = VidboxExtractor.SERVER_DISPLAY_NAMES,
            default = PREF_SERVERS_DEFAULT,
            summary = "Select servers to enable for stream extraction",
        )

        screen.addEditTextPreference(
            key = PREF_TMDB_KEY,
            title = "TMDB API Key (Optional)",
            summary = "Used if official TMDB endpoint is configured",
            default = DEFAULT_TMDB_KEY,
            inputType = InputType.TYPE_CLASS_TEXT,
        )
    }

    companion object {
        private const val PREF_BASE_URL_KEY = "override_base_url"
        private const val DEFAULT_BASE_URL = "https://vidbox.vc/home"

        private const val PREF_API_URL_KEY = "override_api_url"
        private const val DEFAULT_API_URL = "https://db.speedracelight.com/3"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_SERVERS_KEY = "enabled_servers"
        private val PREF_SERVERS_DEFAULT = VidboxExtractor.SERVER_DISPLAY_NAMES.toSet()

        private const val PREF_TMDB_KEY = "tmdb_api_key"
        private const val DEFAULT_TMDB_KEY = "e9e9d8da18ae29fc430845952232787c"
    }
}
