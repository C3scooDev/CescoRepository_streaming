package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.HttpUrl.Companion.toHttpUrl

private const val BRANDING = "https://moviesnchill.net"
private const val VSRC_REFERER = "$BRANDING/"
private const val TMDB_IMG = "https://image.tmdb.org/t/p"

@JsonIgnoreProperties(ignoreUnknown = true)
data class MncPlayData(
    @JsonProperty("embedUrl") val embedUrl: String,
    @JsonProperty("referer") val referer: String = VSRC_REFERER,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class TmdbPaged<T>(
    @JsonProperty("results") val results: List<T>? = null,
    @JsonProperty("total_pages") val totalPages: Int = 1,
    @JsonProperty("page") val page: Int = 1,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class TmdbMediaItem(
    @JsonProperty("id") val id: Long,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class TmdbGenre(
    @JsonProperty("name") val name: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class TmdbMovieDetail(
    @JsonProperty("id") val id: Long,
    @JsonProperty("title") val title: String,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("tagline") val tagline: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("imdb_id") val imdbId: String? = null,
    @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class TmdbTvDetail(
    @JsonProperty("id") val id: Long,
    @JsonProperty("name") val name: String,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("number_of_seasons") val numberOfSeasons: Int? = null,
    @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("episode_run_time") val episodeRunTime: List<Int>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class TmdbSeasonResponse(
    @JsonProperty("season_number") val seasonNumber: Int,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("episodes") val episodes: List<TmdbEpisodeItem>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class TmdbEpisodeItem(
    @JsonProperty("episode_number") val episodeNumber: Int,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("still_path") val stillPath: String? = null,
)

class MoviesNChill : MainAPI() {
    override var mainUrl = BRANDING
    override var name = "MoviesNChill"
    override var lang = "en"
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "trending/all/week" to "Trending",
        "movie/popular" to "Popular movies",
        "tv/popular" to "Popular TV",
    )

    private val loadPathRegex = Regex("""/m/(movie|tv)/(\d+)""")

    private fun apiKey(): String = BuildConfig.TMDB_API.trim()

    private fun tmdbUrl(path: String, params: Map<String, String> = emptyMap()): String {
        val key = apiKey()
        val b = "https://api.themoviedb.org/3/${path.trimStart('/')}"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("api_key", key)
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build().toString()
    }

    private suspend fun tmdbGet(path: String, params: Map<String, String> = emptyMap()): String {
        if (apiKey().isBlank()) {
            throw ErrorLoadingException("Chiave TMDB mancante: imposta TMDB_API in secrets.properties (come in CI).")
        }
        return app.get(tmdbUrl(path, params)).text
    }

    private fun posterUrl(path: String?, size: String = "w500"): String? {
        if (path.isNullOrBlank()) return null
        return "$TMDB_IMG/$size$path"
    }

    private fun absolutizeIframe(src: String, basePage: String): String {
        val s = src.trim()
        if (s.startsWith("//")) return "https:$s"
        if (s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)) {
            return s
        }
        return basePage.toHttpUrl().resolve(s)?.toString() ?: s
    }

    private fun buildItemUrl(tmdbType: String, id: Long): String =
        "${mainUrl.trimEnd('/')}/m/$tmdbType/$id"

    private fun itemToSearch(item: TmdbMediaItem, forcedType: String? = null): SearchResponse? {
        val type = forcedType ?: item.mediaType ?: return null
        if (type != "movie" && type != "tv") return null
        val displayTitle = item.title
            ?: item.name
            ?: item.originalTitle
            ?: return null
        val tvType = if (type == "movie") TvType.Movie else TvType.TvSeries
        val yearStr = item.releaseDate ?: item.firstAirDate
        val year = yearStr?.substringBefore('-')?.toIntOrNull()
        val url = buildItemUrl(type, item.id)
        return if (tvType == TvType.Movie) {
            newMovieSearchResponse(displayTitle, url, TvType.Movie) {
                this.posterUrl = posterUrl(item.posterPath)
                this.year = year
                item.voteAverage?.let { va -> this.score = Score.from10(va) }
            }
        } else {
            newTvSeriesSearchResponse(displayTitle, url, TvType.TvSeries) {
                this.posterUrl = posterUrl(item.posterPath)
                this.year = year
                item.voteAverage?.let { va -> this.score = Score.from10(va) }
            }
        }
    }

    private suspend fun parseTmdbPage(
        path: String,
        page: Int,
        forcedType: String? = null,
    ): Pair<List<SearchResponse>, Boolean> {
        if (apiKey().isBlank()) return emptyList<SearchResponse>() to false
        val body = runCatching {
            tmdbGet(path, mapOf("page" to page.toString(), "language" to "en-US"))
        }.getOrElse { return emptyList<SearchResponse>() to false }
        val parsed = runCatching { parseJson<TmdbPaged<TmdbMediaItem>>(body) }.getOrNull()
            ?: return emptyList<SearchResponse>() to false
        val items = parsed.results.orEmpty().mapNotNull { itemToSearch(it, forcedType) }
        val hasNext = page < parsed.totalPages
        return items to hasNext
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.substringBefore("#")
        val forcedType = when {
            path.startsWith("movie/") -> "movie"
            path.startsWith("tv/") -> "tv"
            else -> null
        }
        val (list, hasNext) = parseTmdbPage(path, page, forcedType = forcedType)
        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = list,
                isHorizontalImages = false,
            ),
            hasNext,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (apiKey().isBlank() || query.isBlank()) return emptyList()
        val body = runCatching {
            tmdbGet(
                "search/multi",
                mapOf(
                    "query" to query,
                    "page" to "1",
                    "include_adult" to "false",
                    "language" to "en-US",
                ),
            )
        }.getOrElse { return emptyList() }
        val parsed = runCatching { parseJson<TmdbPaged<TmdbMediaItem>>(body) }.getOrNull()
            ?: return emptyList()
        return parsed.results.orEmpty().mapNotNull { itemToSearch(it, null) }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (apiKey().isBlank() || query.isBlank()) {
            return newSearchResponseList(emptyList(), hasNext = false)
        }
        val body = runCatching {
            tmdbGet(
                "search/multi",
                mapOf(
                    "query" to query,
                    "page" to page.toString(),
                    "include_adult" to "false",
                    "language" to "en-US",
                ),
            )
        }.getOrElse { return newSearchResponseList(emptyList(), hasNext = false) }
        val parsed = runCatching { parseJson<TmdbPaged<TmdbMediaItem>>(body) }.getOrNull()
            ?: return newSearchResponseList(emptyList(), hasNext = false)
        val items = parsed.results.orEmpty().mapNotNull { itemToSearch(it, null) }
        val hasNext = page < parsed.totalPages
        return newSearchResponseList(items, hasNext = hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        val match = loadPathRegex.find(url) ?: throw ErrorLoadingException("URL non riconosciuta: $url")
        val kind = match.groupValues[1]
        val id = match.groupValues[2].toLongOrNull()
            ?: throw ErrorLoadingException("ID TMDB non valido")

        if (kind == "movie") {
            val raw = tmdbGet("movie/$id", mapOf("language" to "en-US"))
            val detail = parseJson<TmdbMovieDetail>(raw)
            val embedUrl = "https://vsrc.su/embed/$id"
            val recBody = runCatching {
                tmdbGet("movie/$id/recommendations", mapOf("page" to "1", "language" to "en-US"))
            }.getOrNull()
            val recParsed = recBody?.let { runCatching { parseJson<TmdbPaged<TmdbMediaItem>>(it) }.getOrNull() }
            val recommendations = recParsed?.results.orEmpty().mapNotNull {
                itemToSearch(it, "movie")
            }
            return newMovieLoadResponse(
                name = detail.title,
                url = url,
                type = TvType.Movie,
                dataUrl = MncPlayData(embedUrl = embedUrl).toJson(),
            ) {
                this.posterUrl = posterUrl(detail.posterPath, "w780")
                this.backgroundPosterUrl = posterUrl(detail.backdropPath, "w1280")
                this.plot = detail.overview?.ifBlank { null } ?: detail.tagline
                this.year = detail.releaseDate?.substringBefore('-')?.toIntOrNull()
                this.tags = detail.genres.orEmpty().map { it.name }
                this.duration = detail.runtime
                this.recommendations = recommendations
                detail.imdbId?.let { addImdbId(it) }
                addTMDbId(id.toString())
                detail.voteAverage?.let { addScore(Score.from10(it)) }
            }
        }

        val raw = tmdbGet("tv/$id", mapOf("language" to "en-US"))
        val detail = parseJson<TmdbTvDetail>(raw)
        val seasonCount = detail.numberOfSeasons ?: 0
        val episodes = mutableListOf<Episode>()
        for (s in 1..seasonCount) {
            val seasonJson = runCatching {
                tmdbGet("tv/$id/season/$s", mapOf("language" to "en-US"))
            }.getOrNull() ?: continue
            val season = runCatching { parseJson<TmdbSeasonResponse>(seasonJson) }.getOrNull() ?: continue
            season.episodes.orEmpty().forEach { ep ->
                val embedUrl =
                    "https://vsrc.su/embed/tv?tmdb=$id&season=$s&episode=${ep.episodeNumber}"
                episodes.add(
                    newEpisode(MncPlayData(embedUrl = embedUrl).toJson()) {
                        this.name = ep.name?.ifBlank { null } ?: "Episode ${ep.episodeNumber}"
                        this.season = s
                        this.episode = ep.episodeNumber
                        this.description = ep.overview
                        this.posterUrl = posterUrl(ep.stillPath, "w300")
                    },
                )
            }
        }

        return newTvSeriesLoadResponse(
            name = detail.name,
            url = url,
            type = TvType.TvSeries,
            episodes = episodes,
        ) {
            this.posterUrl = posterUrl(detail.posterPath, "w780")
            this.backgroundPosterUrl = posterUrl(detail.backdropPath, "w1280")
            this.plot = detail.overview
            this.year = detail.firstAirDate?.substringBefore('-')?.toIntOrNull()
            this.tags = detail.genres.orEmpty().map { it.name }
            addTMDbId(id.toString())
            detail.voteAverage?.let { addScore(Score.from10(it)) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val payload = tryParseJson<MncPlayData>(data) ?: return false
        val referer = payload.referer.ifBlank { VSRC_REFERER }
        val headers = mapOf("Referer" to referer)

        var emitted = 0
        val wrap: (ExtractorLink) -> Unit = {
            emitted++
            callback(it)
        }

        runCatching {
            val doc = app.get(payload.embedUrl, headers = headers).document
            val iframeSrc = doc.selectFirst("iframe#player_iframe")?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?: doc.select("iframe[src]").firstOrNull()?.attr("src").orEmpty()
            val resolved = iframeSrc.takeIf { it.isNotBlank() }?.let { absolutizeIframe(it, payload.embedUrl) }
            if (!resolved.isNullOrBlank()) {
                loadExtractor(resolved, payload.embedUrl, subtitleCallback, wrap)
            }
        }

        if (emitted == 0) {
            loadExtractor(payload.embedUrl, referer, subtitleCallback, wrap)
        }

        return emitted > 0
    }
}
