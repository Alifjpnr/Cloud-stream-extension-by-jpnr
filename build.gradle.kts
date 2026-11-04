package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup
import java.net.URLEncoder

class WatchAnimeWorldProvider : MainAPI() {
    override var mainUrl = "https://watchanimeworld.one"
    override var name = "Anime World India - JPNR"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.TvSeries,
        TvType.Movie,
        TvType.Cartoon
    )

    @Volatile
    private var isUrlLoaded = false

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FirebaseConfig(
        @JsonProperty("animeworldindia_url") val animeworldindia_url: String? = null,
        @JsonProperty("animeworld_url") val animeworld_url: String? = null,
        @JsonProperty("awi_url") val awi_url: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ZephyrixVideo(
        @JsonProperty("videoSource") val videoSource: String? = null,
        @JsonProperty("securedLink") val securedLink: String? = null
    )

    private suspend fun loadFirebaseUrl() {
        if (isUrlLoaded) return
        try {
            val response = app.get("https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json", timeout = 10_000L).text
            val config = parseJson<FirebaseConfig>(response)
            val url = config.animeworldindia_url ?: config.animeworld_url ?: config.awi_url
            if (!url.isNullOrBlank()) mainUrl = url.removeSuffix("/")
            isUrlLoaded = true
        } catch (_: Exception) {
            isUrlLoaded = true
        }
    }

    override val mainPage = mainPageOf(
        "home" to "Latest Releases",
        "trending" to "Trending Anime",
        "popular" to "Popular Series",
        "movies" to "Anime Movies",
        "tvshows" to "TV Series",
        "hindi-dubbed" to "Hindi Dubbed",
        "franchise" to "Franchises"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        loadFirebaseUrl()
        return try {
            val endpoint = when (request.data) {
                "home" -> if (page == 1) mainUrl else "$mainUrl/page/$page/"
                "movies" -> "$mainUrl/movies/" + if (page > 1) "page/$page/" else ""
                "tvshows" -> "$mainUrl/tvshows/" + if (page > 1) "page/$page/" else ""
                "hindi-dubbed" -> "$mainUrl/genre/hindi-dubbed/" + if (page > 1) "page/$page/" else ""
                "franchise" -> "$mainUrl/category/franchise/" + if (page > 1) "page/$page/" else ""
                else -> "$mainUrl/page/$page/"
            }

            val response = app.get(endpoint)
            val doc = Jsoup.parse(response.text)

            val items = doc.select("li.post, article.post, div.item, div.result-item").mapNotNull { el ->
                val link = el.selectFirst("a[href*=/series/], a[href*=/movies/], a[href*=/episode/]")?.attr("href") ?: return@mapNotNull null
                if (link.endsWith("/series/") || link.endsWith("/movies/")) return@mapNotNull null
                val title = el.selectFirst("h2, h3, .title")?.text()?.trim() ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.let { img ->
                    val src = img.attr("data-src").ifBlank { img.attr("src") }
                    if (src.startsWith("//")) "https:$src" else src
                }
                val tvType = if (link.contains("/movies/")) TvType.Movie else TvType.Anime
                newAnimeSearchResponse(title, link, tvType) {
                    this.posterUrl = poster
                }
            }.distinctBy { it.url }

            val customizedItems = when (request.data) {
                "trending" -> items.shuffled()
                "popular" -> items.reversed()
                else -> items
            }

            newHomePageResponse(request.name, customizedItems, hasNext = true)
        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), false)
        loadFirebaseUrl()
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$mainUrl/page/$page/?s=$encoded"
            val response = app.get(searchUrl)
            val doc = Jsoup.parse(response.text)

            val results = doc.select("li.post, article.post, div.item").mapNotNull { el ->
                val seriesLink = el.selectFirst("a[href*=/series/]")?.attr("href")
                val movieLink = el.selectFirst("a[href*=/movies/]")?.attr("href")
                val link = seriesLink ?: movieLink ?: return@mapNotNull null
                if (link.endsWith("/series/") || link.endsWith("/movies/")) return@mapNotNull null
                val title = el.selectFirst("h2, h3, .title")?.text()?.trim() ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.let { img ->
                    val src = img.attr("data-src").ifBlank { img.attr("src") }
                    if (src.startsWith("//")) "https:$src" else src
                }
                val tvType = if (link.contains("/movies/")) TvType.Movie else TvType.Anime
                newAnimeSearchResponse(title, link, tvType) {
                    this.posterUrl = poster
                }
            }.distinctBy { it.url }

            newSearchResponseList(results, true)
        } catch (_: Exception) {
            newSearchResponseList(emptyList(), false)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        loadFirebaseUrl()
        return try {
            val response = app.get(url)
            val doc = Jsoup.parse(response.text)

            val title = doc.selectFirst("h1, div.sheader h1")?.text()?.trim() ?: return null
            val poster = doc.selectFirst(".poster img, .post-thumbnail img")?.let { img ->
                val src = img.attr("data-src").ifBlank { img.attr("src") }
                if (src.startsWith("//")) "https:$src" else src
            }
            val backdrop = doc.selectFirst(".backdrop img, .bg img")?.let { img ->
                val src = img.attr("data-src").ifBlank { img.attr("src") }
                if (src.startsWith("//")) "https:$src" else src
            }
            val plot = doc.selectFirst(".description, .synopsis, .plot, p")?.text()?.trim()
            val genres = doc.select("a[href*=genre], div.sgeneros a").map { it.text().trim() }.filter { it.isNotBlank() }

            val isMovie = url.contains("/movies/")

            if (isMovie) {
                return newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop ?: poster
                    this.plot = plot
                    this.tags = genres
                }
            }

            val postId = doc.select("[data-post]").firstOrNull()?.attr("data-post") ?: ""
            val seasonTabs = doc.select("[data-season]").mapNotNull { el ->
                val seasonNum = el.attr("data-season").toIntOrNull() ?: return@mapNotNull null
                val seasonName = el.text()?.trim()?.ifBlank { "Season $seasonNum" } ?: "Season $seasonNum"
                Pair(seasonNum, seasonName)
            }.distinctBy { it.first }.sortedBy { it.first }

            val initialEpLinks = doc.select("a[href*=/episode/]").map { it.attr("href") }.distinct()
            val initialSeasonNum = initialEpLinks.firstOrNull()?.let {
                Regex("""(\d+)x(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull()
            } ?: (seasonTabs.firstOrNull()?.first ?: 1)

            val episodes = mutableListOf<Episode>()
            val allSeasons = seasonTabs.map { it.first }.ifEmpty { listOf(initialSeasonNum) }
            val fullSeasons = (1..(allSeasons.maxOrNull() ?: initialSeasonNum)).toList()

            for (season in fullSeasons) {
                val epLinks = if (season == initialSeasonNum && initialEpLinks.isNotEmpty()) {
                    initialEpLinks
                } else {
                    fetchSeasonEpisodes(postId, season)
                }
                for (epUrl in epLinks) {
                    val match = Regex("""(\d+)x(\d+)""").find(epUrl)
                    val epNum = match?.groupValues?.get(2)?.toIntOrNull() ?: (episodes.count { it.season == season } + 1)
                    val seasonNum = match?.groupValues?.get(1)?.toIntOrNull() ?: season
                    val cleanName = cleanEpisodeName(epUrl)
                    episodes.add(newEpisode(epUrl) {
                        this.season = seasonNum
                        this.episode = epNum
                        this.name = cleanName
                    })
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop ?: poster
                this.plot = plot
                this.tags = genres
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanEpisodeName(epUrl: String): String {
        val raw = epUrl.substringAfter("/episode/").trimEnd('/')
        val match = Regex("""(\d+)x(\d+)""").find(raw)
        val epNum = match?.groupValues?.get(2)?.toIntOrNull()
        val withoutNum = raw.replace(Regex("""\d+x\d+"""), "").trim('-')
        val parts = withoutNum.split("-").filter { it.isNotBlank() && it != "ep" && it != "episode" }
        val name = parts.joinToString(" ")
            .replace(Regex("\\s+/\\s*"), " ")
            .replace("/", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        return if (name.isNotBlank()) name else "Episode ${epNum ?: ""}".trim()
    }

    private suspend fun fetchSeasonEpisodes(postId: String, season: Int): List<String> {
        if (postId.isBlank()) return emptyList()
        return try {
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php?action=action_select_season&season=$season&post=$postId"
            val response = app.get(ajaxUrl, headers = mapOf(
                "Referer" to mainUrl,
                "X-Requested-With" to "XMLHttpRequest"
            ))
            val doc = Jsoup.parse(response.text)
            doc.select("a[href*=/episode/]").map { it.attr("href") }.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadFirebaseUrl()
        return try {
            val response = app.get(data)
            val doc = Jsoup.parse(response.text)

            val iframes = doc.select("iframe, .mobius iframe, .play-box-iframe iframe").mapNotNull { el ->
                val src = el.attr("data-src").ifBlank { el.attr("src") }
                if (src.isNotBlank()) src else null
            }.distinct()

            var found = false
            for (iframe in iframes) {
                if (iframe.contains("zephyrix") || iframe.contains("zephyrflick")) {
                    if (resolveZephyrix(iframe, subtitleCallback, callback)) found = true
                } else {
                    if (loadExtractor(iframe, data, subtitleCallback, callback)) found = true
                }
            }
            found
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun resolveZephyrix(
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val videoId = Regex("/video/([a-f0-9]+)").find(iframeUrl)?.groupValues?.get(1) ?: return false
            val playerBase = Regex("(https?://[^/]+)").find(iframeUrl)?.groupValues?.get(1) ?: return false

            val response = app.post(
                "$playerBase/player/index.php?data=$videoId&do=getVideo",
                data = mapOf("hash" to videoId, "r" to "$mainUrl/"),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to playerBase,
                    "Referer" to "$playerBase/video/$videoId",
                    "Accept" to "application/json, text/javascript, */*; q=0.01"
                ),
                timeout = 30_000L
            )
            val video = parseJson<ZephyrixVideo>(response.text)
            val streamUrl = video.videoSource ?: video.securedLink ?: return false
            val absoluteUrl = if (streamUrl.startsWith("http")) streamUrl else playerBase + streamUrl

            val link = newExtractorLink(
                name,
                "Anime World India - JPNR",
                absoluteUrl,
                ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = "$playerBase/"
                this.headers = mapOf(
                    "Accept" to "*/*",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
            }
            callback.invoke(link)
            true
        } catch (_: Exception) {
            false
        }
    }
}
