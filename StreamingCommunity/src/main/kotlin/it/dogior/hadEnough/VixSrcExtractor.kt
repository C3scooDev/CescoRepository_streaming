package it.dogior.hadEnough

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

class VixSrcExtractor : ExtractorApi() {
    override val mainUrl = "vixsrc.to"
    override val name = "VixCloud"
    override val requiresReferer = false
    val TAG = "VixSrcExtractor"
    private var referer: String? = null

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        this.referer = referer
        Log.d(TAG, "REFERER: $referer  URL: $url")
        val playlistUrl = getPlaylistLink(url)
        Log.w(TAG, "FINAL URL: $playlistUrl")

        callback.invoke(
            newExtractorLink(
                source = "VixSrc",
                name = "Streaming Community - VixSrc",
                url = playlistUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer!!
            }
        )


    }

    /**
     * Le URL "watch" (/tv/..., /movie/...) sono una SPA Next.js senza masterPlaylist nell'HTML.
     * L'API ufficiale restituisce `src` verso `/embed/...` dove è ancora presente window.masterPlaylist.
     */
    private suspend fun resolveToEmbedPageUrl(watchOrEmbedUrl: String): String {
        if (watchOrEmbedUrl.contains("/embed/", ignoreCase = true)) {
            return watchOrEmbedUrl
        }
        val httpUrl = watchOrEmbedUrl.toHttpUrl()
        if (!httpUrl.host.equals("vixsrc.to", ignoreCase = true)) {
            return watchOrEmbedUrl
        }
        val segments = httpUrl.encodedPath.trim('/').split('/').filter { it.isNotBlank() }
        if (segments.size < 2) return watchOrEmbedUrl
        val kind = segments.first()
        if (kind != "tv" && kind != "movie") return watchOrEmbedUrl

        val apiPath = "/api/" + segments.joinToString("/")
        val query = httpUrl.query
        val apiUrl = "${httpUrl.scheme}://${httpUrl.host}$apiPath" +
            if (query.isNullOrBlank()) "" else "?$query"

        val headers = mutableMapOf(
            "Accept" to "application/json",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/133.0",
            "Referer" to (referer ?: "https://vixsrc.to/"),
        )
        val jsonText = runCatching { app.get(apiUrl, headers = headers).text }
            .getOrElse {
                Log.e(TAG, "API vixsrc fallita apiUrl=$apiUrl err=${it.message}")
                return watchOrEmbedUrl
            }
        val src = runCatching { JSONObject(jsonText).optString("src", "") }.getOrElse { "" }
        if (src.isBlank()) return watchOrEmbedUrl

        return when {
            src.startsWith("http://") || src.startsWith("https://") -> src
            src.startsWith("//") -> "https:$src"
            else -> "${httpUrl.scheme}://${httpUrl.host}$src"
        }
    }

    private suspend fun getPlaylistLink(url: String): String {
        Log.d(TAG, "Item url: $url")
        val embedPageUrl = resolveToEmbedPageUrl(url)
        if (embedPageUrl != url) {
            Log.d(TAG, "Risolto embed vixsrc: $embedPageUrl")
        }

        val script = getScript(embedPageUrl)
        val masterPlaylist = script.getJSONObject("masterPlaylist")
        val masterPlaylistParams = masterPlaylist.getJSONObject("params")
        val token = masterPlaylistParams.getString("token")
        val expires = masterPlaylistParams.getString("expires")
        val playlistUrl = masterPlaylist.getString("url")

        var masterPlaylistUrl: String
        val params = "token=${token}&expires=${expires}"
        masterPlaylistUrl = if ("?b" in playlistUrl) {
            "${playlistUrl.replace("?b:1", "?b=1")}&$params"
        } else {
            "${playlistUrl}?$params"
        }
        Log.d(TAG, "masterPlaylistUrl: $masterPlaylistUrl")

        if (script.getBoolean("canPlayFHD")) {
            masterPlaylistUrl += "&h=1"
        }

        Log.d(TAG, "Master Playlist URL: $masterPlaylistUrl")
        return masterPlaylistUrl
    }

    private suspend fun getScript(url: String): JSONObject {
        Log.d(TAG, "Item url: $url")
        val headers = mutableMapOf(
            "Accept" to "*/*",
            "Alt-Used" to url.toHttpUrl().host,
            "Connection" to "keep-alive",
            "Host" to url.toHttpUrl().host,
            "Referer" to referer!!,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/133.0",
        )

        val resp = app.get(url, headers = headers).document
//        Log.d(TAG, resp.toString())

//        Log.d(TAG, iframe.document.toString())
        val scripts = resp.select("script")
        val script = scripts.find { it.data().contains("masterPlaylist") }
            ?.data()
            ?.replace("\n", "\t")
            ?: error("masterPlaylist assente nella pagina embed url=$url (vixsrc ha cambiato markup?)")

        val scriptJson = getSanitisedScript(script)
        Log.d(TAG, "Script Json: $scriptJson")
        return JSONObject(scriptJson)
    }

    private fun getSanitisedScript(script: String): String {
        // Split by top-level assignments like window.xxx =
        val parts = Regex("""window\.(\w+)\s*=""")
            .split(script)
            .drop(1) // first split part is empty before first assignment

        val keys = Regex("""window\.(\w+)\s*=""")
            .findAll(script)
            .map { it.groupValues[1] }
            .toList()

        val jsonObjects = keys.zip(parts).map { (key, value) ->
            // Clean up the value
            val cleaned = value
                .replace(";", "")
                // Quote keys only inside objects
                .replace(Regex("""(\{|\[|,)\s*(\w+)\s*:"""), "$1 \"$2\":")
                // Remove trailing commas before } or ]
                .replace(Regex(""",(\s*[}\]])"""), "$1")
                .trim()

            "\"$key\": $cleaned"
        }
        val finalObject =
            "{\n${jsonObjects.joinToString(",\n")}\n}"
                .replace("'", "\"")

        return finalObject
    }
}
