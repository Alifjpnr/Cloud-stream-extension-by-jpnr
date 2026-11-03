
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class WatchAnimeWorldProvider : MainAPI() {

    override var mainUrl = "https://watchanimeworld.one"
    override var name = "Watch Anime World"
    override var lang = "hi"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.Cartoon
    )

    override val mainPage = mainPageOf(
        "" to "Latest Anime & Cartoons"
    )

    // -----------------------------------------
    // HOMEPAGE
    // -----------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {

        val document = app.get(mainUrl).document

        val homeCategories = ArrayList<HomePageList>()

        val items = document.select(
            """
            ul#episode_by_temp li,
            section.episodes ul.post-lst li,
            ul.post-lst li,
            .post-lst li,
            article.post,
            .post-item,
            .anime-card
            """.trimIndent()
        ).mapNotNull { element ->
            element.toSearchResult()
        }

        if (items.isNotEmpty()) {
            homeCategories.add(
                HomePageList(
                    request.name.ifBlank { "Latest Anime & Cartoons" },
                    items
                )
            )
        }

        return newHomePageResponse(homeCategories, false)
    }

    // -----------------------------------------
    // SEARCH RESULT PARSER
    // -----------------------------------------

    private fun Element.toSearchResult(): SearchResponse? {

        val linkElement = selectFirst(
            "a.lnk-blk[href], a[href]"
        ) ?: return null

        val href = fixUrlNull(
            linkElement.attr("href")
        ) ?: return null

        val title = (
            selectFirst(
                "h2.entry-title, h2, h3, .entry-title, .post-title"
            )?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: linkElement.attr("title").trim().takeIf { it.isNotBlank() }
                ?: linkElement.text().trim().takeIf { it.isNotBlank() }
            ) ?: return null

        return newAnimeSearchResponse(
            title,
            href,
            TvType.Anime
        ) {
            posterUrl = getImageUrl()
        }
    }

    // -----------------------------------------
    // IMAGE URL PARSER
    // -----------------------------------------

    private fun Element.getImageUrl(): String? {

        val image = selectFirst("img") ?: return null

        val imageUrl = image.attr("data-src")
            .ifBlank { image.attr("data-lazy-src") }
            .ifBlank { image.attr("data-original") }
            .ifBlank { image.attr("src") }

        return fixUrlNull(imageUrl)
    }

    // -----------------------------------------
    // SEARCH
    // -----------------------------------------

    override suspend fun search(
        query: String
    ): List<SearchResponse>? {

        val document = app.get(
            "$mainUrl/",
            params = mapOf("s" to query)
        ).document

        return document.select(
            "article.post, article, .post-lst li, .post-item, .anime-card"
        ).mapNotNull { article ->
            article.toSearchResult()
        }
    }

    // -----------------------------------------
    // LOAD ANIME DETAILS
    // -----------------------------------------

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(url).document

        val rawTitle = document.selectFirst(
            "h1.entry-title, h1.post-title, main h1, h1"
        )?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("title")?.text()?.trim()
            ?: "Anime"

        val title = rawTitle.replace(
            Regex(
                """\s*[-|–]\s*Anime World India.*$""",
                RegexOption.IGNORE_CASE
            ),
            ""
        ).trim().ifBlank { "Anime" }

        val posterElement = document.selectFirst(
            "div.bghd img, div.post-thumbnail img, figure img, .anime-poster img"
        )

        val posterUrl = posterElement?.let {
            it.getImageUrl()
        } ?: document.selectFirst(
            "meta[property=og:image]"
        )?.attr("content")?.let {
            fixUrlNull(it)
        }

        val description = document.selectFirst(
            "div.overviewCss, .entry-content, .description, .overview"
        )?.text()?.trim()

        // Episode list

        val episodeElements = document.select(
            """
            ul#episode_by_temp li,
            section.episodes ul.post-lst li,
            .episodes .post-lst li,
            .episode-list li,
            .episodes-list li
            """.trimIndent()
        )

        val episodes = episodeElements.mapNotNull { element ->

            val episodeLink = element.selectFirst(
                "a.lnk-blk[href], a[href]"
            ) ?: return@mapNotNull null

            val episodeUrl = fixUrlNull(
                episodeLink.attr("href")
            ) ?: return@mapNotNull null

            val episodeTitle = element.selectFirst(
                "h2.entry-title, h3, .entry-title, .episode-title"
            )?.text()?.trim()?.takeIf {
                it.isNotBlank()
            } ?: episodeLink.text().trim().ifBlank {
                "Episode"
            }

            val episodeLabel = element.selectFirst(
                "span.num-epi, .num-epi, .episode-number, .ep-num"
            )?.text().orEmpty()

            val numberSource = episodeLabel.ifBlank {
                episodeTitle
            }

            // Handles formats such as 1x02 and S1E02.

            val seasonEpisodeMatch = Regex(
                """(?i)s(?:eason)?\s*(\d+)\s*[- ]*e(?:p(?:isode)?)?\s*(\d+)"""
            ).find(numberSource) ?: Regex(
                """(?i)(\d+)\s*[x×]\s*(\d+)"""
            ).find(numberSource)

            val seasonNumber = seasonEpisodeMatch
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            val episodeNumber = seasonEpisodeMatch
                ?.groupValues
                ?.getOrNull(2)
                ?.toIntOrNull()
                ?: Regex(
                    """(?i)(?:episode|ep)\s*[-:#.]?\s*(\d+)"""
                ).find(numberSource)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                ?: Regex("""\b(\d+)\b""")
                    .find(numberSource)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

            newEpisode(episodeUrl) {
                this.name = episodeTitle
                this.season = seasonNumber
                this.episode = episodeNumber
                this.posterUrl = element.getImageUrl()
            }
        }

        return newAnimeLoadResponse(
            title,
            url,
            TvType.Anime
        ) {
            this.posterUrl = posterUrl
            this.plot = description

            addEpisodes(
                DubStatus.Subbed,
                episodes
            )
        }
    }

    // -----------------------------------------
    // RESOLVE RELATIVE VIDEO URLS
    // -----------------------------------------

    private fun resolveMediaUrl(
        baseUrl: String,
        rawUrl: String
    ): String {

        val candidate = rawUrl.trim()

        if (
            candidate.startsWith("http://", true) ||
            candidate.startsWith("https://", true)
        ) {
            return candidate
        }

        if (candidate.startsWith("//")) {
            return "https:$candidate"
        }

        val origin = Regex(
            """^https?://[^/]+""",
            RegexOption.IGNORE_CASE
        ).find(baseUrl)?.value ?: mainUrl

        if (candidate.startsWith("/")) {
            return origin + candidate
        }

        val cleanBaseUrl = baseUrl
            .substringBefore("?")
            .substringBefore("#")

        val directory = when {
            cleanBaseUrl.endsWith("/") ->
                cleanBaseUrl.trimEnd('/')

            cleanBaseUrl.length > origin.length ->
                cleanBaseUrl.substringBeforeLast("/")

            else -> origin
        }

        return "$directory/$candidate"
    }

    // -----------------------------------------
    // LOAD VIDEO LINKS
    // -----------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        val iframeElements = document.select(
            """
            aside.video-player div.video iframe,
            aside.video-player iframe,
            .video-player iframe,
            div.video iframe
            """.trimIndent()
        )

        var found = false

        for (iframe in iframeElements) {

            val rawSrc = iframe.attr("data-src")
                .ifBlank { iframe.attr("data-lazy-src") }
                .ifBlank { iframe.attr("src") }

            val iframeUrl = fixUrlNull(rawSrc) ?: continue

            // Try CloudStream's built-in extractors first.

            val extractorLoaded = loadExtractor(
                url = iframeUrl,
                referer = data,
                subtitleCallback = subtitleCallback,
                callback = callback
            )

            if (extractorLoaded) {
                found = true
                continue
            }

            // Fallback for direct video sources.

            try {

                val iframeDocument = app.get(
                    iframeUrl,
                    headers = mapOf(
                        "Referer" to data
                    )
                ).document

                val mediaElement = iframeDocument.selectFirst(
                    "video source, video, source"
                )

                val directVideoSrc = mediaElement?.let {
                    it.attr("src").ifBlank {
                        it.attr("data-src")
                    }
                }

                if (directVideoSrc.isNullOrBlank()) {
                    continue
                }

                val videoUrl = resolveMediaUrl(
                    iframeUrl,
                    directVideoSrc
                )

                val serverHost = iframeUrl
                    .substringAfter("://")
                    .substringBefore("/")
                    .removePrefix("www.")

                val serverName = when {
                    iframeUrl.contains("zephyrix", true) ->
                        "Zephyrix"

                    iframeUrl.contains("abyss", true) ->
                        "Abyss"

                    else -> serverHost
                }

                val cleanVideoUrl = videoUrl
                    .substringBefore("?")
                    .substringBefore("#")
                    .lowercase()

                val videoType = when {
                    cleanVideoUrl.endsWith(".m3u8") ->
                        ExtractorLinkType.M3U8

                    cleanVideoUrl.endsWith(".mpd") ->
                        ExtractorLinkType.DASH

                    else ->
                        ExtractorLinkType.VIDEO
                }

                callback(
                    newExtractorLink(
                        source = serverName,
                        name = serverName,
                        url = videoUrl,
                        type = videoType
                    ) {
                        this.referer = iframeUrl
                        this.quality = Qualities.Unknown.value
                    }
                )

                found = true

            } catch (e: Exception) {
                logError(e)
            }
        }

        return found
    }
}
