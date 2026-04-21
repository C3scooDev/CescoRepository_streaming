package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder.capitalize
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class StreamingCommunity(
    override var lang: String = "it",
) : MainAPI() {
    private var baseUrl = Companion.baseUrls.first()
    override var mainUrl = Companion.mainUrlFor(baseUrl, lang)
    override var name = Companion.name
    override var supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon, TvType.Documentary)
    override val hasMainPage = true
    override val mainPage = mainPageOf(
        "${Companion.tradeBaseUrl}archivio/?sorting=popserie" to "Popolari serie",
        "${Companion.tradeBaseUrl}archivio/?tipo=1" to "Film recenti",
        "${Companion.tradeBaseUrl}archivio/?sorting=soon" to "Prossimamente"
    )

    companion object {
        private var inertiaVersion = ""
        private var decodedXsrfToken = ""
        private val headers = mapOf(
            "Cookie" to "",
            "X-Inertia" to true.toString(),
            "X-Inertia-Version" to inertiaVersion,
            "X-Requested-With" to "XMLHttpRequest",
        ).toMutableMap()
        const val tradeBaseUrl = "https://streaming-community.surf/"
        /** Il sito `.surf` è DLE/HTML: non ha API Laravel; va provato per primo. */
        val baseUrls = listOf(
            tradeBaseUrl,
            "https://streamingcommunityz.ooo/",
            "https://streamingunity.biz/",
            "https://streamingcommunity.biz/",
        )

        fun isDleTradeHost(url: String): Boolean =
            url.contains("streaming-community.surf", ignoreCase = true) ||
            url.contains("streamingcommunityz.ooo", ignoreCase = true) ||
                url.contains("streaming-community.trade", ignoreCase = true)

        fun mainUrlFor(base: String, langSegment: String): String =
            if (isDleTradeHost(base)) base.removeSuffix("/") else base + langSegment

        var name = "StreamingCommunity"
        val TAG = "SCommunity"
    }
    private val tradeBaseUrl = Companion.tradeBaseUrl

    private val sliderFetchRequestBody = SliderFetchRequestBody(
        sliders = listOf(
            SliderFetchRequestSlider(name = "top10", genre = null),
            SliderFetchRequestSlider(name = "trending", genre = null),
            SliderFetchRequestSlider(name = "latest", genre = null),
            SliderFetchRequestSlider(name = "upcoming", genre = null),
            SliderFetchRequestSlider(name = "genre", genre = "Animation"),
            SliderFetchRequestSlider(name = "genre", genre = "Adventure"),
            SliderFetchRequestSlider(name = "genre", genre = "Action"),
            SliderFetchRequestSlider(name = "genre", genre = "Comedy"),
            SliderFetchRequestSlider(name = "genre", genre = "Crime"),
            SliderFetchRequestSlider(name = "genre", genre = "Documentary"),
            SliderFetchRequestSlider(name = "genre", genre = "Drama"),
            SliderFetchRequestSlider(name = "genre", genre = "Family"),
            SliderFetchRequestSlider(name = "genre", genre = "Science Fiction"),
            SliderFetchRequestSlider(name = "genre", genre = "Fantasy"),
            SliderFetchRequestSlider(name = "genre", genre = "Horror"),
            SliderFetchRequestSlider(name = "genre", genre = "Reality"),
            SliderFetchRequestSlider(name = "genre", genre = "Romance"),
            SliderFetchRequestSlider(name = "genre", genre = "Thriller")
        )
    )

    private suspend fun fetchSliderSectionsInBatches(): List<HomePageList> {
        val maxSlidersPerRequest = 6
        val allSections = mutableListOf<HomePageList>()

        sliderFetchRequestBody.sliders
            .chunked(maxSlidersPerRequest)
            .forEachIndexed { index, sliderBatch ->
                val response = app.post(
                    "${baseUrl}api/sliders/fetch?lang=$lang",
                    requestBody = SliderFetchRequestBody(sliderBatch).toRequestBody(),
                    headers = getSliderFetchHeaders()
                )

                val payload = response.body.string()
//                Log.d(TAG, "Slider fetch batch=${index + 1} status=${response.code} size=${sliderBatch.size}")
//                Log.d(TAG, "Slider fetch batch=${index + 1} preview=${payload.take(500)}")

                allSections += parseSliderFetchSections(payload)
            }

        return allSections
    }

    private fun isHtmlPayload(payload: String): Boolean {
        val trimmed = payload.trimStart()
        return trimmed.startsWith("<") || trimmed.contains("<!DOCTYPE", ignoreCase = true)
    }

    private fun extractInertiaPageJson(html: String): String? {
        val dataPageRaw = org.jsoup.Jsoup.parse(html).selectFirst("#app")?.attr("data-page")
        if (dataPageRaw.isNullOrBlank()) return null
        return Parser.unescapeEntities(dataPageRaw, true)
    }

    private fun parseInertiaPayload(payload: String, logContext: String): InertiaResponse? {
        if (payload.isBlank()) {
            Log.e(TAG, "$logContext: empty payload")
            return null
        }
        if (isHtmlPayload(payload)) {
            Log.e(TAG, "$logContext: expected JSON but received HTML payload")
            return null
        }
        return runCatching { parseJson<InertiaResponse>(payload) }
            .onFailure { Log.e(TAG, "$logContext: invalid JSON payload - ${it.message}") }
            .getOrNull()
    }

    private fun parseBrowseTitles(payload: String, logContext: String): List<Title> {
        val jsonPayload = if (isHtmlPayload(payload)) {
            Log.e(TAG, "$logContext: received HTML payload, attempting embedded data-page fallback")
            extractInertiaPageJson(payload) ?: return emptyList()
        } else {
            payload
        }

        val result = parseInertiaPayload(jsonPayload, logContext) ?: return emptyList()
        return result.props.titles ?: emptyList()
    }

    private fun parseSliderFetchSections(payload: String): List<HomePageList> {
        if (payload.isBlank()) return emptyList()
        val trimmedPayload = payload.trimStart()
        if (trimmedPayload.startsWith("{") || trimmedPayload.contains("\"message\"")) {
            Log.e(
                TAG,
                "Sliders fetch: received error object instead of slider array: ${payload.take(300)}"
            )
            return emptyList()
        }
        if (isHtmlPayload(payload)) {
            Log.e(TAG, "Sliders fetch: expected JSON array but received HTML payload")
            return emptyList()
        }

        val sliders = runCatching { parseJson<List<Slider>>(payload) }
            .onFailure { Log.e(TAG, "Sliders fetch: invalid JSON payload - ${it.message}") }
            .getOrNull()
            ?: return emptyList()

        return sliders.mapNotNull { slider ->
            val items = searchResponseBuilder(slider.titles)
            if (items.isEmpty()) return@mapNotNull null
            HomePageList(
                name = slider.label.ifBlank { slider.name },
                list = items,
                isHorizontalImages = false
            )
        }
    }

    private suspend fun setupHeaders() {
        for (candidateBaseUrl in Companion.baseUrls.distinct()) {
            if (Companion.isDleTradeHost(candidateBaseUrl)) {
                baseUrl = candidateBaseUrl
                mainUrl = Companion.mainUrlFor(candidateBaseUrl, lang)
                headers["Cookie"] = "dle=1"
                decodedXsrfToken = ""
                inertiaVersion = ""
                headers["X-Inertia-Version"] = inertiaVersion
                Log.d(TAG, "Bootstrap DLE trade host; Laravel session skipped")
                return
            }

            val candidateMainUrl = candidateBaseUrl + lang
            val archiveResponse = runCatching { app.get("$candidateMainUrl/archive") }
                .onFailure { Log.e(TAG, "Headers setup failed for $candidateBaseUrl archive: ${it.message}") }
                .getOrNull()
                ?: continue

            val inertiaPageObject = archiveResponse.document.select("#app").attr("data-page")
            if (inertiaPageObject.isBlank()) {
                Log.e(TAG, "Headers setup failed for $candidateBaseUrl: missing inertia page data")
                continue
            }

            val cookieJar = linkedMapOf<String, String>()
            archiveResponse.cookies.forEach { cookieJar[it.key] = it.value }

            val csrfResponse = runCatching {
                app.get(
                    "${candidateBaseUrl}sanctum/csrf-cookie",
                    headers = mapOf(
                        "Referer" to "$candidateMainUrl/",
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                )
            }.onFailure {
                Log.e(TAG, "Headers setup failed for $candidateBaseUrl csrf-cookie: ${it.message}")
            }.getOrNull() ?: continue
            csrfResponse.cookies.forEach { cookieJar[it.key] = it.value }

            val xsrfToken = cookieJar["XSRF-TOKEN"]
                ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
            if (xsrfToken.isNullOrBlank()) {
                Log.e(TAG, "Headers setup failed for $candidateBaseUrl: missing XSRF token")
                continue
            }

            baseUrl = candidateBaseUrl
            mainUrl = candidateMainUrl
            headers["Cookie"] = cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" }
            decodedXsrfToken = xsrfToken
            inertiaVersion = inertiaPageObject
                .substringAfter("\"version\":\"")
                .substringBefore("\"")
            headers["X-Inertia-Version"] = inertiaVersion
            Log.d(TAG, "Headers initialized using $baseUrl")
            return
        }
    }

    private fun getSliderFetchHeaders(): Map<String, String> {
        return mapOf(
            "Cookie" to (headers["Cookie"] ?: ""),
            "X-Requested-With" to "XMLHttpRequest",
            "X-XSRF-TOKEN" to decodedXsrfToken,
            "Referer" to "$mainUrl/",
            "Accept" to "application/json, text/plain, */*",
            "Content-Type" to "application/json",
            "Origin" to baseUrl.removeSuffix("/")
        )
    }

    private fun searchResponseBuilder(listJson: List<Title>): List<SearchResponse> {
        val domain = mainUrl.substringAfter("://").substringBeforeLast("/")
        val list: List<SearchResponse> =
            listJson.filter { it.type == "movie" || it.type == "tv" }.map { title ->
                val url = "$mainUrl/titles/${title.id}-${title.slug}"

                if (title.type == "tv") {
                    newTvSeriesSearchResponse(title.name, url) {
                        posterUrl = "https://cdn.${domain}/images/" + title.getPoster()
                    }
                } else {
                    newMovieSearchResponse(title.name, url) {
                        posterUrl = "https://cdn.$domain/images/" + title.getPoster()
                    }
                }
            }
        return list
    }

    private fun parseTradeSearchResponses(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select("a.tile-image[href*=\"/titles/\"]")
            .mapNotNull { anchor ->
                val href = fixTradeUrl(anchor.attr("href")) ?: return@mapNotNull null
                val title = href.substringAfterLast("/titles/")
                    .substringBefore(".html")
                    .substringAfter("-guarda-")
                    .replace('-', ' ')
                    .trim()
                    .replaceFirstChar { c -> c.uppercase() }
                val poster = anchor.selectFirst("img.img-desktop, img.img-mobile")
                    ?.attr("data-src")
                    ?.let { fixTradeUrl(it) }
                val category = anchor.attr("data-category")
                val type = if (category.contains("Serie", ignoreCase = true)) {
                    TvType.TvSeries
                } else {
                    TvType.Movie
                }

                when (type) {
                    TvType.TvSeries -> newTvSeriesSearchResponse(title, href) {
                        posterUrl = poster
                    }
                    else -> newMovieSearchResponse(title, href) {
                        posterUrl = poster
                    }
                }
            }
            .distinctBy { it.url }
    }

    private fun fixTradeUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> tradeBaseUrl.removeSuffix("/") + url
            else -> "${tradeBaseUrl}$url"
        }
    }

    private suspend fun getTradeMainPage(request: MainPageRequest): HomePageResponse {
        val homepagePayload = runCatching { app.get(tradeBaseUrl).body.string() }
            .getOrNull()
        val sliders = homepagePayload
            ?.let { extractInertiaPageJson(it) }
            ?.let { parseInertiaPayload(it, "Trade main page") }
            ?.props
            ?.sliders
            .orEmpty()

        val sliderSections = sliders.mapNotNull { slider ->
            val items = searchResponseBuilder(slider.titles)
            if (items.isEmpty()) return@mapNotNull null
            HomePageList(
                name = slider.label.ifBlank { slider.name },
                list = items,
                isHorizontalImages = false
            )
        }
        if (sliderSections.isNotEmpty()) {
            return newHomePageResponse(sliderSections, hasNext = false)
        }

        // Fallback: parse static cards when slider payload is not available.
        val document = app.get(request.data).document
        val items = parseTradeSearchResponses(document)
        val list = HomePageList(name = request.name, list = items, isHorizontalImages = false)
        return newHomePageResponse(listOf(list), hasNext = false)
    }

    private suspend fun tradeLoad(url: String): LoadResponse {
        val detailDocument = app.get(url).document
        val watchingUrl = detailDocument.selectFirst("a.play2, a[href*=\"/watching.html\"]")
            ?.attr("href")
            ?.let { fixTradeUrl(it) }
            ?: "$url/watching.html"
        val watchingDocument = app.get(watchingUrl).document

        val title = detailDocument.selectFirst("meta[property=og:title]")?.attr("content")
            ?.ifBlank { null }
            ?: detailDocument.selectFirst("h1.title")?.text()?.trim()
            ?: "StreamingCommunity"
        val poster = detailDocument.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let { fixTradeUrl(it) }
        val plot = detailDocument.selectFirst("meta[name=description]")?.attr("content")
            ?.substringAfter(": ", missingDelimiterValue = "")
            ?.trim()
            ?.ifBlank { null }
        val year = detailDocument.select("div.info-span span.desc")
            .firstOrNull()
            ?.text()
            ?.trim()
            ?.toIntOrNull()
        val tags = detailDocument.select("div.extra:contains(Genere) a")
            .mapNotNull { it.text().trim().takeIf { text -> text.isNotBlank() } }
        val actors = detailDocument.select("div.extra:contains(Attori) a")
            .mapNotNull { it.text().trim().takeIf { text -> text.isNotBlank() } }
        val imdbId = Regex("tt\\d+").find(detailDocument.html())?.value

        val episodeData = extractTradeEpisodes(watchingDocument)
        if (episodeData.isNotEmpty()) {
            val episodes = episodeData.map { ep ->
                newEpisode(
                    TradeLoadData(
                        urls = ep.urls,
                        isSeries = true,
                        imdbId = ep.imdbId
                    ).toJson()
                ) {
                    this.name = ep.name
                    this.season = ep.season
                    this.episode = ep.episode
                }
            }

            return newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.addActors(actors)
                imdbId?.let { this.addImdbId(it) }
            }
        }

        val movieUrls = extractTradeMovieUrls(watchingDocument)
        val movieData = TradeLoadData(
            urls = movieUrls,
            isSeries = false,
            imdbId = imdbId
        )
        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.Movie,
            dataUrl = movieData.toJson()
        ) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.addActors(actors)
            imdbId?.let { this.addImdbId(it) }
        }
    }

    private fun extractTradeEpisodes(document: org.jsoup.nodes.Document): List<TradeEpisodeData> {
        return document.select("div.tab-pane[id^=season-]").flatMap { seasonPane ->
            val season = seasonPane.id().substringAfter("season-").toIntOrNull()
            seasonPane.select("li").mapNotNull { li ->
                val episodeAnchor = li.selectFirst("a[id^=serie-]") ?: return@mapNotNull null
                val dataNum = episodeAnchor.attr("data-num")
                val seasonFromNum = dataNum.substringBefore('x').toIntOrNull()
                val episodeNumber = dataNum.substringAfter('x', "").toIntOrNull()
                    ?: episodeAnchor.text().trim().toIntOrNull()
                    ?: return@mapNotNull null
                val finalSeason = seasonFromNum ?: season ?: 1
                val urls = li.select("select.smirrors option")
                    .mapNotNull { opt -> fixTradeUrl(opt.attr("value")) }
                    .filter { it.isNotBlank() }
                    .distinct()
                if (urls.isEmpty()) return@mapNotNull null
                val imdbId = urls.firstNotNullOfOrNull { url ->
                    Regex("tt\\d+").find(url)?.value
                }
                val title = episodeAnchor.attr("data-title").trim().ifBlank {
                    "Episodio $episodeNumber"
                }
                TradeEpisodeData(
                    season = finalSeason,
                    episode = episodeNumber,
                    name = title,
                    urls = urls,
                    imdbId = imdbId
                )
            }
        }
    }

    private fun extractTradeMovieUrls(document: org.jsoup.nodes.Document): List<String> {
        val iframeUrls = document.select("iframe[src]")
            .mapNotNull { iframe -> fixTradeUrl(iframe.attr("src")) }
            .filter { it.isNotBlank() }
        val mirrorUrls = document.select(".mirrors span[data-link], .mirrors [data-link]")
            .mapNotNull { mirror -> fixTradeUrl(mirror.attr("data-link")) }
            .filter { it.isNotBlank() }
        val optionUrls = document.select("select.smirrors option[value]")
            .mapNotNull { option -> fixTradeUrl(option.attr("value")) }
            .filter { it.isNotBlank() }
        val scriptUrls = Regex("https?://[^\"'\\s<>]+")
            .findAll(document.html())
            .map { it.value }
            .filter { url ->
                url.contains("guardahd.stream", ignoreCase = true) ||
                    url.contains("supervideo.", ignoreCase = true) ||
                    url.contains("vixsrc.to", ignoreCase = true) ||
                    url.contains("vixcloud.", ignoreCase = true)
            }
        return (iframeUrls + mirrorUrls + optionUrls + scriptUrls).distinct()
    }

    private suspend fun resolveTradeMovieUrls(loadData: TradeLoadData): List<String> {
        val resolved = mutableListOf<String>()
        loadData.urls.forEach { sourceUrl ->
            if (sourceUrl.contains("guardahd.stream", ignoreCase = true)) {
                val guardahdUrl = buildGuardahdUrl(loadData.imdbId, sourceUrl)
                if (guardahdUrl != null) resolved += guardahdUrl
            } else {
                resolved += sourceUrl
            }
        }
        if (resolved.isEmpty() && loadData.imdbId != null) {
            buildGuardahdUrl(loadData.imdbId, null)?.let { resolved += it }
        }
        return resolved.distinct()
    }

    private suspend fun extractGuardahdSourceUrls(url: String): List<String> {
        val document = runCatching { app.get(url).document }.getOrNull() ?: return emptyList()
        val iframeUrls = document.select("iframe[src]")
            .mapNotNull { iframe -> fixTradeUrl(iframe.attr("src")) }
        val mirrorUrls = document.select(".mirrors span[data-link], .mirrors [data-link]")
            .mapNotNull { mirror -> fixTradeUrl(mirror.attr("data-link")) }
        val optionUrls = document.select("select.smirrors option[value]")
            .mapNotNull { option -> fixTradeUrl(option.attr("value")) }
        val scriptUrls = Regex("https?://[^\"'\\s<>]+")
            .findAll(document.html())
            .map { it.value }
            .filter { sourceUrl ->
                sourceUrl.contains("supervideo.", ignoreCase = true) ||
                    sourceUrl.contains("vixsrc.to", ignoreCase = true) ||
                    sourceUrl.contains("vixcloud.", ignoreCase = true)
            }
        return (iframeUrls + mirrorUrls + optionUrls + scriptUrls)
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.contains("{") }
            .distinct()
    }

    /** Normalizza URL candidati (come nel provider storico + protocol-relative). */
    private fun normalizeTradeCandidateUrl(raw: String): String? {
        val t = raw.trim()
        if (t.isBlank() || t.contains("{")) return null
        val fixed = when {
            t.startsWith("//") -> "https:$t"
            else -> t
        }
        return fixed.takeIf { it.startsWith("http", ignoreCase = true) }
    }

    /**
     * Combina il flusso storico (referer vixsrc.to, loadExtractor con tradeBaseUrl) con fallback
     * sul dominio embedder quando il primo tentativo fallisce.
     */
    private suspend fun tryVixSrcExtract(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        /** Secondo referer: su DLE è il sito embedder; su API Laravel è [baseUrl]. */
        secondaryReferer: String = tradeBaseUrl.trimEnd('/') + "/",
    ) {
        val referers = listOf(
            "https://vixsrc.to/",
            secondaryReferer.trimEnd('/') + "/",
        )
        var lastError: Throwable? = null
        for (ref in referers) {
            runCatching {
                VixSrcExtractor().getUrl(
                    url = url,
                    referer = ref,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }.onSuccess { return }
                .onFailure { lastError = it }
        }
        Log.e(TAG, "VixSrc fallito per url=$url — ${lastError?.message}")
    }

    private suspend fun tryVixCloudExtract(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val referers = listOf(
            baseUrl.trimEnd('/') + "/",
            tradeBaseUrl.trimEnd('/') + "/",
        ).distinct()
        var lastError: Throwable? = null
        for (ref in referers) {
            runCatching {
                VixCloudExtractor().getUrl(
                    url = url,
                    referer = ref,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }.onSuccess { return }
                .onFailure { lastError = it }
        }
        Log.e(TAG, "VixCloud fallito per url=$url — ${lastError?.message}")
    }

    private suspend fun tryLoadExtractorDual(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val referers = listOf(
            tradeBaseUrl,
            baseUrl.trimEnd('/') + "/",
        ).distinct()
        var lastError: Throwable? = null
        for (ref in referers) {
            runCatching {
                loadExtractor(url, ref, subtitleCallback, callback)
            }.onSuccess { return }
                .onFailure { lastError = it }
        }
        Log.e(TAG, "loadExtractor fallito per url=$url — ${lastError?.message}")
    }

    /** Fonti guardahd: link estratti dalla pagina + URL guardahd originale (comportamento storico). */
    private suspend fun collectGuardahdUrls(candidate: String): List<String> {
        val expanded = extractGuardahdSourceUrls(candidate)
        return buildList {
            addAll(expanded)
            add(candidate)
        }.mapNotNull { normalizeTradeCandidateUrl(it) }
            .distinct()
    }

    private suspend fun dispatchTradeStreamSource(
        sourceUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        runCatching {
            when {
                sourceUrl.contains("vixsrc.to", ignoreCase = true) ->
                    tryVixSrcExtract(sourceUrl, subtitleCallback, callback)
                sourceUrl.contains("vixcloud", ignoreCase = true) ->
                    tryVixCloudExtract(sourceUrl, subtitleCallback, callback)
                else ->
                    tryLoadExtractorDual(sourceUrl, subtitleCallback, callback)
            }
        }.onFailure {
            Log.e(TAG, "dispatchTradeStreamSource url=$sourceUrl: ${it.message}")
        }
    }

    private fun buildGuardahdUrl(imdbId: String?, fallback: String?): String? {
        val normalizedImdb = imdbId ?: Regex("id_imdb=(tt\\d+)").find(fallback ?: "")
            ?.groupValues
            ?.getOrNull(1)
        return normalizedImdb?.let {
            "https://guardahd.stream/index.php?task=set-movie-u&id_imdb=$it"
        } ?: fallback
    }

    private fun normalizeInputUrl(url: String): String {
        return url
            .trim()
            .replace(" -", "-")
            .replace("- ", "-")
            .replace("%20-", "-")
            .replace(Regex("\\s+"), "")
            .replace("%20", "")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (Companion.isDleTradeHost(request.data)) {
            if (page > 1) return newHomePageResponse(emptyList(), hasNext = false)
            return getTradeMainPage(request)
        }

        if (page > 1) {
            return newHomePageResponse(emptyList(), hasNext = false)
        }

        if (headers["Cookie"].isNullOrEmpty()) {
            setupHeaders()
        }

        val lazySections = fetchSliderSectionsInBatches()
        if (lazySections.isEmpty()) {
            Log.d(TAG, "Lazy slider fetch returned no sections")
        }

        return newHomePageResponse(lazySections, hasNext = false)
    }

    /** Evita `.results` su [SearchResponseList]: nella dipendenza `pre-release` il tipo può differire. */
    private suspend fun searchItems(
        query: String,
        page: Int,
    ): Pair<List<SearchResponse>, Boolean> {
        if (headers["Cookie"].isNullOrEmpty()) {
            setupHeaders()
        }
        val legacyItems = if (Companion.isDleTradeHost(mainUrl)) {
            searchTradeArchive(query, page)
        } else {
            runCatching {
                val response = app.get("$mainUrl/search", params = mapOf("q" to query)).body.string()
                val titles = parseBrowseTitles(response, "Search")
                searchResponseBuilder(titles)
            }.getOrElse { emptyList() }
        }

        if (legacyItems.isNotEmpty()) {
            val hasNext = legacyItems.size >= 60
            return Pair(legacyItems, hasNext)
        }

        val tradeDoc = app.get(
            tradeBaseUrl,
            params = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to query,
                "titleonly" to "3"
            )
        ).document
        val tradeItems = parseTradeSearchResponses(tradeDoc)
        return Pair(tradeItems, false)
    }

    private suspend fun searchTradeArchive(query: String, page: Int): List<SearchResponse> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return emptyList()

        val archivePayload = runCatching {
            app.get(
                "${tradeBaseUrl}${lang}/archive",
                params = mapOf(
                    "search" to normalizedQuery,
                    "page" to page.toString()
                )
            ).body.string()
        }.getOrNull()
            ?: runCatching {
                app.get(
                    "${tradeBaseUrl}archivio/",
                    params = mapOf(
                        "story" to normalizedQuery,
                        "do" to "search",
                        "subaction" to "search",
                        "titleonly" to "3",
                        "page" to page.toString()
                    )
                ).body.string()
            }.getOrNull()
        val titles = archivePayload
            ?.let { parseBrowseTitles(it, "Trade archive search page=$page query=$normalizedQuery") }
            .orEmpty()
        if (titles.isNotEmpty()) {
            return searchResponseBuilder(titles)
        }

        // Secondary fallback when archive is temporarily unavailable.
        return searchTradeFromHomeSliders(normalizedQuery)
    }

    private suspend fun searchTradeFromHomeSliders(query: String): List<SearchResponse> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return emptyList()

        val homepagePayload = runCatching { app.get(tradeBaseUrl).body.string() }
            .getOrNull()
            ?: return emptyList()
        val sliders = extractInertiaPageJson(homepagePayload)
            ?.let { parseInertiaPayload(it, "Trade search fallback") }
            ?.props
            ?.sliders
            .orEmpty()
        if (sliders.isEmpty()) return emptyList()

        val allTitles = sliders.flatMap { it.titles }
        val matchedTitles = allTitles.filter {
            it.name.contains(normalizedQuery, ignoreCase = true)
        }
        return searchResponseBuilder(matchedTitles).distinctBy { it.url }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return searchItems(query, 1).first
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (items, hasNext) = searchItems(query, page)
        return newSearchResponseList(items, hasNext = hasNext)
    }

    private suspend fun getPoster(title: TitleProp): String? {
        if (title.tmdbId != null) {
            val tmdbUrl = "https://www.themoviedb.org/${title.type}/${title.tmdbId}"
            val resp = app.get(tmdbUrl).document
            val img = resp.select("img.poster.w-full").attr("srcset").split(", ").last()
            return img
        } else {
            val domain = mainUrl.substringAfter("://").substringBeforeLast("/")
            return title.getBackgroundImageId().let { "https://cdn.$domain/images/$it" }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val normalizedUrl = normalizeInputUrl(url)
        if (Companion.isDleTradeHost(normalizedUrl)) {
            return tradeLoad(normalizedUrl)
        }

        if (headers["Cookie"].isNullOrEmpty()) {
            setupHeaders()
        }
        val actualUrl = getActualUrl(normalizedUrl)
        val response = app.get(actualUrl, headers = headers)
        val responseBody = response.body.string()

        val props = parseInertiaPayload(responseBody, "Load actualUrl=$actualUrl")?.props
        if (props?.title == null) {
            val tradeCandidateUrl = when {
                actualUrl.contains("/titles/") -> {
                    val slugPart = actualUrl.substringAfter("/titles/")
                        .substringBefore("?")
                        .substringBefore("#")
                        .substringAfter("it/")
                        .substringBefore('/')
                        .substringBefore(".html")
                    if (slugPart.isNotBlank()) "${tradeBaseUrl}titles/$slugPart.html" else null
                }
                else -> null
            }
            if (tradeCandidateUrl != null) {
                return tradeLoad(tradeCandidateUrl)
            }
            throw ErrorLoadingException("Unable to parse title payload from provider response")
        }

        val domain = mainUrl.substringAfter("://").substringBeforeLast("/")
        val title = props.title
        val genres = title.genres.map { it.name.capitalize() }
        val year = title.releaseDate?.substringBefore('-')?.toIntOrNull()
        val related = props.sliders?.getOrNull(0)
        val trailers = title.trailers?.mapNotNull { it.getYoutubeUrl() }
        val poster = getPoster(title)

        if (title.type == "tv") {
            val episodes: List<Episode> = getEpisodes(props)

            val tvShow = newTvSeriesLoadResponse(
                title.name,
                actualUrl,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = poster
                title.getBackgroundImageId()
                    .let { this.backgroundPosterUrl = "https://cdn.$domain/images/$it" }

                this.tags = genres
                this.episodes = episodes
                this.year = year
                this.plot = title.plot
                title.age?.let { this.contentRating = "$it+" }
                this.recommendations = related?.titles?.let { searchResponseBuilder(it) }
                title.imdbId?.let { this.addImdbId(it) }
                title.tmdbId?.let { this.addTMDbId(it.toString()) }
                this.addActors(title.mainActors?.map { it.name })
                this.addScore(title.score)
                if (trailers != null) {
                    if (trailers.isNotEmpty()) {
                        addTrailer(trailers)
                    }
                }

            }
            return tvShow
        } else {
            val data = LoadData(
                "$mainUrl/iframe/${title.id}&canPlayFHD=1",
                "movie",
                title.tmdbId
            )
            val movie = newMovieLoadResponse(
                title.name,
                actualUrl,
                TvType.Movie,
                dataUrl = data.toJson()
            ) {
                this.posterUrl = poster
                title.getBackgroundImageId()
                    .let { this.backgroundPosterUrl = "https://cdn.$domain/images/$it" }

                this.tags = genres
                this.year = year
                this.plot = title.plot
                title.age?.let { this.contentRating = "$it+" }
                this.recommendations = related?.titles?.let { searchResponseBuilder(it) }
                this.addActors(title.mainActors?.map { it.name })
                this.addScore(title.score)

                title.imdbId?.let { this.addImdbId(it) }
                title.tmdbId?.let { this.addTMDbId(it.toString()) }

                title.runtime?.let { this.duration = it }
                if (trailers != null) {
                    if (trailers.isNotEmpty()) {
                        addTrailer(trailers)
                    }
                }
            }
            return movie
        }
    }

    private fun getActualUrl(url: String) =
        if (!url.contains(mainUrl)) {
            val replacingValue =
                if (url.contains("/it/") || url.contains("/en/")) mainUrl.toHttpUrl().host else mainUrl.toHttpUrl().host + "/$lang"
            val actualUrl = url.replace(url.toHttpUrl().host, replacingValue)

//            Log.d("$TAG:UrlFix", "Old: $url\nNew: $actualUrl")
            actualUrl
        } else {
            url
        }

    private suspend fun getEpisodes(props: Props): List<Episode> {
        val episodeList = mutableListOf<Episode>()
        val title = props.title

        title?.seasons?.forEach { season ->
            val responseEpisodes = emptyList<it.dogior.hadEnough.Episode>().toMutableList()
            if (season.id == props.loadedSeason!!.id) {
                responseEpisodes.addAll(props.loadedSeason.episodes!!)
            } else {
                if (inertiaVersion == "") {
                    setupHeaders()
                }
                val url = "$mainUrl/titles/${title.id}-${title.slug}/season-${season.number}"
                val obj =
                    parseJson<InertiaResponse>(app.get(url, headers = headers).body.string())
                responseEpisodes.addAll(obj.props.loadedSeason?.episodes!!)
            }
            responseEpisodes.forEach { ep ->

                val loadData = LoadData(
                    "$mainUrl/iframe/${title.id}?episode_id=${ep.id}&canPlayFHD=1",
                    type = "tv",
                    tmdbId = title.tmdbId,
                    seasonNumber = season.number,
                    episodeNumber = ep.number)
                episodeList.add(
                    newEpisode(loadData.toJson()) {
                        this.name = ep.name
                        this.posterUrl = props.cdnUrl + "/images/" + ep.getCover()
                        this.description = ep.plot
                        this.episode = ep.number
                        this.season = season.number
                        this.runTime = ep.duration
                    }
                )
            }
        }

        return episodeList
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
//        Log.d(TAG, "Load Data : $data")
        if (data.isEmpty()) return false
        val tradeLoadData = tryParseJson<TradeLoadData>(data)
        if (tradeLoadData != null) {
            val rawCandidateUrls = if (tradeLoadData.isSeries) {
                tradeLoadData.urls
            } else {
                resolveTradeMovieUrls(tradeLoadData)
            }
            val candidateUrls = rawCandidateUrls
                .mapNotNull { normalizeTradeCandidateUrl(it) }
                .distinct()

            candidateUrls.forEach { candidate ->
                val urlsToResolve = if (candidate.contains("guardahd.stream", ignoreCase = true)) {
                    collectGuardahdUrls(candidate)
                } else {
                    listOf(candidate)
                }
                urlsToResolve.forEach { sourceUrl ->
                    dispatchTradeStreamSource(sourceUrl, subtitleCallback, callback)
                }
            }
            return candidateUrls.isNotEmpty()
        }

        val loadData = parseJson<LoadData>(data)

        val response = app.get(loadData.url).document
        val iframeSrc = response.select("iframe").attr("src")

        VixCloudExtractor().getUrl(
            url = iframeSrc,
            referer = baseUrl,
            subtitleCallback = subtitleCallback,
            callback = callback
        )

        val vixsrcUrl = if(loadData.type == "movie"){
            "https://vixsrc.to/movie/${loadData.tmdbId}"
        } else{
            "https://vixsrc.to/tv/${loadData.tmdbId}/${loadData.seasonNumber}/${loadData.episodeNumber}"
        }

        tryVixSrcExtract(
            url = vixsrcUrl,
            subtitleCallback = subtitleCallback,
            callback = callback,
            secondaryReferer = baseUrl.trimEnd('/') + "/",
        )

        return true
    }
}