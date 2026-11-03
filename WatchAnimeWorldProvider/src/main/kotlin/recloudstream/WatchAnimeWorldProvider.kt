
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
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

    // HOMEPAGE

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
            article.post,
            .post-item,
            .anime-card
            """.trimIndent()
        ).mapNotNull { it.toSearchResult() }

        if (items.isNotEmpty()) {
            homeCategories.add(
                HomePageList(request.name, items)
            )
        }

        return newHomePageResponse(homeCategories, false)
    }

    // SEARCH RESULT PARSER

    private fun Element.toSearchResult(): SearchResponse? {

        val link = selectFirst("a.lnk-blk[href], a[href]")
            ?: return null

        val href = fixUrlNull(link.attr("href"))
            ?: return null

        val title = selectFirst(
            "h2.entry-title, h2, h3, .entry-title"
        )?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: link.text().trim().takeIf { it.isNotBlank() }
            ?: return null

        return newAnimeSearchResponse(
            title,
            href,
            TvType.Anime
        ) {
            posterUrl = getImageUrl()
        }
    }

    // IMAGE PARSER

    private fun Element.getImageUrl(): String? {

        val image = selectFirst("img") ?: return null

        val imageUrl = image.attr("data-src")
            .ifBlank { image.attr("data-lazy-src") }
            .ifBlank { image.attr("data-original") }
            .ifBlank { image.attr("src") }

        return fixUrlNull(imageUrl)
    }

    // SEARCH

    override suspend fun search(
        query: String
    ): List<SearchResponse>? {

        val document = app.get(
            "$mainUrl/",
            params = mapOf("s" to query)
        ).document

        return document.select(
            "article.post, article, .post-lst li, .post-item, .anime-card"
        ).mapNotNull { it.toSearchResult() }
    }

    // LOAD ANIME DETAILS

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(url).document

        val rawTitle = document.selectFirst(
            "h1.entry-title, h1.post-title, main h1, h1"
        )?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("title")?.text()?.trim()
            ?: "Anime"

        val title = rawTitle.replace(
            Regex(
                """\s*[-|–]\s*Anime World India.*$""",
                RegexOption.IGNORE_CASE
            ),
            ""
        ).trim().ifBlank { "Anime" }

        val posterUrl = document.selectFirst(
            "div.bghd img, div.post-thumbnail img, figure img"
        )?.getImageUrl()
            ?: document.selectFirst(
                "meta[property=og:image]"
            )?.attr("content")?.let { fixUrlNull(it) }

        val description = document.selectFirst(
            "div.overviewCss, .entry-content, .description, .overview"
        )?.text()?.trim()

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
            )?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: episodeLink.text().trim().ifBlank {
                    "Episode"
                }

            val episodeText = element.selectFirst(
                "span.num-epi, .num-epi, .episode-number, .ep-num"
            )?.text().orEmpty().ifBlank {
                episodeTitle
            }

            val match = Regex(
                """(?i)s(?:eason)?\s*(\d+)\s*[- ]*e(?:p(?:isode)?)?\s*(\d+)"""
            ).find(episodeText) ?: Regex(
                """(?i)(\d+)\s*[x×]\s*(\d+)"""
            ).find(episodeText)

            val seasonNumber = match
                ?.groupValues?.getOrNull(1)?.toIntOrNull()

            val episodeNumber = match
                ?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: Regex(
                    """(?i)(?:episode|ep)\s*[-:#.]?\s*(\d+)"""
                ).find(episodeText)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""\b(\d+)\b""")
                    .find(episodeText)
                    ?.groupValues?.getOrNull(1)
                    ?.toIntOrNull()

            newEpisode(episodeUrl) {
                name = episodeTitle
                season = seasonNumber
                episode = episodeNumber
                posterUrl = element.getImageUrl()
            }
        }

        return newAnimeLoadResponse(
            title,
            url,
            TvType.Anime
        ) {
            posterUrl = posterUrl
            plot = description

            addEpisodes(
                DubStatus.Subbed,
                episodes
            )
        }
    }

    // RESOLVE RELATIVE MEDIA URLS

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

        val cleanBase = baseUrl
            .substringBefore("?")
            .substringBefore("#")

        val directory = when {
            cleanBase.endsWith("/") ->
                cleanBase.trimEnd('/')

            cleanBase.length > origin.length ->
                cleanBase.substringBeforeLast("/")

            else -> origin
        }

        return "$directory/$candidate"
    }

    // LOAD VIDEO LINKS

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

            // Built-in extractor support

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

            // Direct video fallback

            try {

                val iframeDocument = app.get(
                    iframeUrl,
                    headers = mapOf(
                        "Referer" to data
                    )
                ).document

                val videoElement = iframeDocument.selectFirst(
                    "video source, video, source"
                ) ?: continue

                val directVideoSrc = videoElement.attr("src")
                    .ifBlank { videoElement.attr("data-src") }

                if (directVideoSrc.isBlank()) continue

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

                val videoPath = videoUrl
                    .substringBefore("?")
                    .substringBefore("#")
                    .lowercase()

                val videoType = when {
                    videoPath.endsWith(".m3u8") ->
                        ExtractorLinkType.M3U8

                    videoPath.endsWith(".mpd") ->
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
                        referer = iframeUrl
                        quality = Qualities.Unknown.value
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
pOf(
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
