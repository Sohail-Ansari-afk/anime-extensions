package eu.kanade.tachiyomi.animeextension.en.vidbox.extractors

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.en.vidbox.SeedDto
import eu.kanade.tachiyomi.animeextension.en.vidbox.VideasyDecryptedResult
import eu.kanade.tachiyomi.animeextension.en.vidbox.VideasyDecryptionDto
import eu.kanade.tachiyomi.animeextension.en.vidbox.VideasyServer
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class VidboxExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // FIX-3: preserve exact origin string — don't strip www or modify the host.
    // Some stream CDNs validate the Referer against an exact-match allowlist;
    // stripping www causes a 403 on the m3u8 fetch even when seed/sources succeed.
    private fun getOrigin(url: String): String = url.toHttpUrl().run { "$scheme://$host" }

    suspend fun videosFromUrl(
        path: String,
        title: String,
        year: String,
        imdbId: String,
        baseUrl: String,
        enabledServers: Set<String>,
        qualityPref: String = "Auto",
    ): List<Video> {
        val pathParts = path.split("/")
        val isMovie = pathParts.first() == "movie"
        val tmdbId = pathParts[1]

        val eligibleServers = VIDEASY_SERVERS.filter { server ->
            (!server.movieOnly || isMovie) &&
                (enabledServers.isEmpty() || server.displayName in enabledServers)
        }

        if (eligibleServers.isEmpty()) return emptyList()

        // Use the exact baseUrl origin — do not manipulate it further.
        val apiOrigin = getOrigin(baseUrl)
        val backendHeaders = headers.newBuilder()
            .set("Referer", "$apiOrigin/")
            .set("Origin", apiOrigin)
            .build()

        val seed = runCatching {
            client.newCall(
                GET("$VIDEASY_API_BASE/seed?mediaId=$tmdbId", backendHeaders),
            ).awaitSuccess().parseAs<SeedDto>().seed
        }.getOrNull() ?: return emptyList()

        val videoList = eligibleServers.parallelCatchingFlatMap { server ->
            try {
                val seasonId = if (isMovie) "1" else pathParts.getOrElse(2) { "1" }
                val episodeId = if (isMovie) "1" else pathParts.getOrElse(3) { "1" }

                val serverUrl = server.apiBase.toHttpUrl().newBuilder().apply {
                    addPathSegments(server.path)
                    addPathSegment("sources-with-title")
                    addEncodedQueryParameter("title", doubleEncode(title))
                    addQueryParameter("mediaType", if (isMovie) "movie" else "tv")
                    addQueryParameter("year", year)
                    addQueryParameter("episodeId", episodeId)
                    addQueryParameter("seasonId", seasonId)
                    addQueryParameter("tmdbId", tmdbId)
                    if (imdbId.isNotBlank()) addQueryParameter("imdbId", imdbId)
                    if (server.language != null) addQueryParameter("language", server.language)
                    addQueryParameter("enc", "2")
                    addQueryParameter("seed", seed)
                }.build()

                val encryptedText = client.newCall(
                    GET(serverUrl.toString(), backendHeaders),
                ).awaitSuccess().bodyString()

                val requestBody = mapOf(
                    "text" to encryptedText,
                    "id" to tmdbId,
                    "seed" to seed,
                ).toJsonRequestBody()

                val decrypted = client.newCall(POST(DECRYPTION_API_URL, body = requestBody))
                    .awaitSuccess()
                    .parseAs<VideasyDecryptionDto>()
                    .result

                buildVideos(server, decrypted, baseUrl)
            } catch (_: Throwable) {
                emptyList()
            }
        }

        return videoList.sortedWith(
            compareByDescending<Video> {
                it.videoTitle.contains(qualityPref, ignoreCase = true) ||
                    (qualityPref == "2160" && it.videoTitle.contains("4k", ignoreCase = true))
            }.thenByDescending {
                extractQualityValue(it.videoTitle)
            },
        )
    }

    // ─── Encoding ────────────────────────────────────────────────────────────

    private fun pctEncode(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size * 3)
        for (raw in bytes) {
            val c = raw.toInt() and 0xFF
            val unreserved =
                (c in 0x30..0x39) ||
                    (c in 0x41..0x5A) ||
                    (c in 0x61..0x7A) ||
                    c == 0x2D || c == 0x2E || c == 0x5F || c == 0x7E
            if (unreserved) {
                out.append(c.toChar())
            } else {
                out.append('%')
                out.append(HEX[(c ushr 4) and 0x0F])
                out.append(HEX[c and 0x0F])
            }
        }
        return out.toString()
    }

    private fun doubleEncode(s: String): String = pctEncode(pctEncode(s))

    // ─── Quality helpers ─────────────────────────────────────────────────────

    /**
     * Returns true when the quality string is a real resolution label
     * (e.g. "1080p", "720", "4K", "2160p"). These sources do NOT need
     * HLS expansion — the Videasy server already labelled each variant.
     */
    private fun isRealResolution(quality: String): Boolean = quality.isNotBlank() && (
        qualityRegex.containsMatchIn(quality) ||
            quality.contains("4k", ignoreCase = true) ||
            quality.all { it.isDigit() }
        )

    /**
     * FIX-1 (PRIMARY BUG): Returns true for generic placeholder quality strings
     * that Videasy uses when it returns a master playlist without committing to
     * a specific bitrate. The original code only checked for "auto" — but servers
     * also return "original", "video", "hls", "full video", etc.
     * Without this check those sources skip HLS expansion and are handed to
     * the player as unresolved CDN URLs, which fail silently.
     */
    private fun isGenericQuality(quality: String): Boolean {
        val normalized = quality.trim().lowercase()
        return normalized.isBlank() ||
            normalized in GENERIC_QUALITY_PLACEHOLDERS ||
            GENERIC_QUALITY_REGEX.matches(normalized)
    }

    /**
     * Returns true when the quality label is actually a language name
     * masquerading as a resolution (e.g. "German" from Killjoy, "English"
     * from Vyse, "Hindi" from Fade). These are always HLS master playlists
     * even when the URL has no .m3u8 extension.
     */
    private fun isLanguageQuality(server: VideasyServer, quality: String): Boolean {
        if (isRealResolution(quality)) return false
        return quality.equals(server.audioLabel, ignoreCase = true) ||
            (server.qualityFilter != null && quality.equals(server.qualityFilter, ignoreCase = true))
    }

    private fun extractQualityValue(quality: String): Int {
        val match = qualityRegex.find(quality)
        if (match != null) return match.groupValues[1].toIntOrNull() ?: 0
        if (quality.contains("4k", ignoreCase = true)) return 2160
        return 0
    }

    // ─── Video construction ───────────────────────────────────────────────────

    private fun buildVideos(
        server: VideasyServer,
        decrypted: VideasyDecryptedResult,
        baseUrl: String,
    ): List<Video> {
        val subtitles = decrypted.subtitles.mapNotNull { sub ->
            val u = sub.url ?: return@mapNotNull null
            val l = sub.language ?: return@mapNotNull null
            Track(u, l)
        }

        val videoHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
            .build()

        // FIX-2: When qualityFilter produces zero results (Videasy returned
        // "Auto" or blank quality instead of the expected language label),
        // fall back to the full source list rather than silently returning [].
        val filteredSources = decrypted.sources?.let { sources ->
            server.qualityFilter?.let { filter ->
                sources
                    .filter { it.quality.equals(filter, ignoreCase = true) }
                    .ifEmpty { sources } // ← THE FIX: never discard all sources
            } ?: sources
        }

        val videos = when {
            !filteredSources.isNullOrEmpty() -> {
                // FIX-4: skip blank URLs before dedup — a blank url creates a
                // Video() with empty videoUrl that the player can't load.
                filteredSources
                    .filter { it.url.isNotBlank() }
                    .distinctBy { it.url }
                    .flatMap { source ->
                        val rawQuality = source.quality?.takeIf { it.isNotBlank() } ?: "Auto"
                        val lower = source.url.lowercase()
                        val isHls = ".m3u8" in lower
                        val isDash = ".mpd" in lower

                        // FIX-1: full expansion decision tree, matching Cineby reference.
                        // A source needs HLS expansion when:
                        //   - URL extension is .m3u8 or .mpd, OR
                        //   - quality is a generic placeholder ("original","video","hls"…), OR
                        //   - quality is a language name (always a master playlist), OR
                        //   - server is Breach (m4uhd returns master playlists without extension), OR
                        //   - quality is NOT a real resolution AND any of the above
                        // A source does NOT need expansion when quality is "1080p","720p","480" etc.
                        val isGeneric = isGenericQuality(rawQuality)
                        val isLang = isLanguageQuality(server, rawQuality)
                        val needsExpansion = (
                            !isRealResolution(rawQuality) &&
                                (isHls || isDash || isGeneric || isLang)
                            ) || server.displayName == "Breach"

                        if (needsExpansion) {
                            val expanded = runCatching {
                                playlistUtils.extractFromHls(
                                    playlistUrl = source.url,
                                    referer = "",
                                    videoNameGen = { q ->
                                        buildVideoLabel(server, q, source.url, subtitles.size)
                                    },
                                    subtitleList = subtitles,
                                    masterHeaders = videoHeaders,
                                    videoHeaders = videoHeaders,
                                )
                            }.getOrDefault(emptyList())

                            // Fallback: if expansion returned nothing (CDN error, redirect, etc.)
                            // still surface the raw URL so the user can try it manually.
                            expanded.ifEmpty {
                                listOf(
                                    Video(
                                        url = source.url,
                                        quality = buildVideoLabel(server, rawQuality, source.url, subtitles.size),
                                        videoUrl = source.url,
                                        headers = videoHeaders,
                                        subtitleTracks = subtitles,
                                    ),
                                )
                            }
                        } else {
                            listOf(
                                Video(
                                    url = source.url,
                                    quality = buildVideoLabel(server, rawQuality, source.url, subtitles.size),
                                    videoUrl = source.url,
                                    headers = videoHeaders,
                                    subtitleTracks = subtitles,
                                ),
                            )
                        }
                    }
            }

            decrypted.streams != null -> {
                decrypted.streams.map { (quality, url) ->
                    Video(
                        url = url,
                        quality = buildVideoLabel(server, quality, url, subtitles.size),
                        videoUrl = url,
                        headers = videoHeaders,
                        subtitleTracks = subtitles,
                    )
                }
            }

            decrypted.url != null -> {
                playlistUtils.extractFromHls(
                    playlistUrl = decrypted.url,
                    referer = "",
                    videoNameGen = { q ->
                        buildVideoLabel(server, q, decrypted.url, subtitles.size)
                    },
                    subtitleList = subtitles,
                    masterHeaders = videoHeaders,
                    videoHeaders = videoHeaders,
                )
            }

            else -> emptyList()
        }

        return videos.distinctBy { it.videoUrl }
    }

    // ─── Label builder ────────────────────────────────────────────────────────

    private fun buildVideoLabel(
        server: VideasyServer,
        quality: String,
        url: String,
        subCount: Int,
    ): String {
        val parts = mutableListOf(server.displayName)

        // FIX-5: skip quality in the label when it IS the language name to
        // avoid "Killjoy · German · HLS · German audio" duplication.
        if (!isLanguageQuality(server, quality) && !isGenericQuality(quality)) {
            parts += quality
        }

        val lower = url.lowercase()
        val container = when {
            ".m3u8" in lower -> "HLS"
            ".mpd" in lower -> "DASH"
            ".mkv" in lower -> "MKV"
            ".mp4" in lower -> "MP4"
            else -> null
        }
        container?.let { parts += it }

        server.audioLabel?.let { parts += "$it audio" }
        if (subCount > 0) parts += "$subCount subs"

        return parts.joinToString(" · ")
    }

    // ─── Companions ───────────────────────────────────────────────────────────

    companion object {
        private const val VIDEASY_API_BASE = "https://api.speedracelight.com"
        private const val DECRYPTION_API_URL = "https://enc-dec.app/api/dec-videasy"
        private const val HEX = "0123456789ABCDEF"

        private val qualityRegex = Regex("""(\d{3,4})[pP]?""")

        // FIX-1 supporting sets: quality strings that are placeholders,
        // not real resolution labels, and therefore signal a master playlist.
        private val GENERIC_QUALITY_PLACEHOLDERS = setOf(
            "original",
            "auto",
            "video",
            "full video",
            "watch video",
            "play video",
            "hls",
            "dash",
        )
        private val GENERIC_QUALITY_REGEX = Regex("""^(video|stream|hls|dash)(\s+.*)?$""")

        val VIDEASY_SERVERS = listOf(
            VideasyServer(
                displayName = "Yoru",
                apiBase = VIDEASY_API_BASE,
                path = "cdn",
                mayHave4K = true,
                audioLabel = "Original",
            ),
            VideasyServer(
                displayName = "Cypher",
                apiBase = VIDEASY_API_BASE,
                path = "downloader2",
                audioLabel = "Original",
            ),
            VideasyServer(
                displayName = "Breach",
                apiBase = VIDEASY_API_BASE,
                path = "m4uhd",
                audioLabel = "Original",
            ),
            VideasyServer(
                displayName = "Neon",
                apiBase = VIDEASY_API_BASE,
                path = "vsrc",
                audioLabel = "Original",
            ),
            VideasyServer(
                displayName = "Vyse",
                apiBase = VIDEASY_API_BASE,
                path = "hdmovie",
                qualityFilter = "English",
                audioLabel = "Original",
            ),
            VideasyServer(
                displayName = "Killjoy",
                apiBase = VIDEASY_API_BASE,
                path = "meine",
                language = "german",
                audioLabel = "German",
            ),
            VideasyServer(
                displayName = "Fade",
                apiBase = VIDEASY_API_BASE,
                path = "hdmovie",
                qualityFilter = "Hindi",
                audioLabel = "Hindi",
            ),
            VideasyServer(
                displayName = "Omen",
                apiBase = VIDEASY_API_BASE,
                path = "lamovie",
                audioLabel = "Spanish",
            ),
            VideasyServer(
                displayName = "Raze",
                apiBase = VIDEASY_API_BASE,
                path = "superflix",
                audioLabel = "Portuguese",
            ),
        )

        val SERVER_DISPLAY_NAMES: List<String> = VIDEASY_SERVERS.map { it.displayName }
    }
}
