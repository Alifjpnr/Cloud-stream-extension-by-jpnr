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

    // IMAGE URL HELPER
    private fun Element.imageUrl(): String? {
        val img = selectFirst("img") ?: return null

        val src = img.attr("data-src")
            .ifBlank { img.attr("data-lazy-src") }
            .ifBlank { img.attr("data-original") }
            .ifBlank { img.attr("src") }

        return fixUrlNull(src)
    }

    // SEARCH RESULT HELPER
    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a.lnk-blk[href], a[href]")
            ?: return null

        val href = fixUrlNull(link.attr("href"))
            ?: return null

        val title = selectFirst(
            "h2.entry-title, h2, h3, .entry-title"
        )?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: link.text().trim().takeIf { it.isNotBlank() }
            ?: return null

        return newAnimeSearchResponse(
            title,
            href,
            TvType.Anime
        ) {
            this.posterUrl = imageUrl()
        }
    }

    // HOMEPAGE
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {

        val document = app.get(mainUrl).document

        val items = document.select(
            "ul#episode_by_temp li, section.episodes ul.post-lst li, ul.post-lst li, article.post"
        ).mapNotNull {
            it.toSearchResult()
        }.distinctBy {
            it.url
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    request.name,
                    items
                )
            ),
            false
        )
    }

    // SEARCH
    override suspend fun search(
        query: String
    ): List<SearchResponse>? {

        val document = app.get(
            "$mainUrl/?s=$query"
        ).document

        return document.select(
            "article.post, .post-lst li, article"
        ).mapNotNull {
            it.toSearchResult()
        }.distinctBy {
            it.url
        }
    }

    // LOAD DETAILS
    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(url).document

        val title = (
            document.selectFirst(
                "h1.entry-title, h1.post-title, h1"
            )?.text()
                ?: document.selectFirst("title")?.text()
                ?: "Anime"
            ).replace(
                Regex(
                    """\s*[-|–]\s*Anime World India.*$""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            ).trim()

        val poster = document.selectFirst(
            "div.bghd img, div.post-thumbnail img, figure img"
        )?.imageUrl()
            ?: document.selectFirst(
                "meta[property=og:image]"
            )?.attr("content")?.let {
                fixUrlNull(it)
            }

        val description = document.selectFirst(
            "div.overviewCss, .entry-content"
        )?.text()?.trim()

        val episodeElements = document.select(
            "ul#episode_by_temp li"
        )

        val episodes = episodeElements.mapNotNull { element ->

            val link = element.selectFirst(
                "a.lnk-blk[href]"
            ) ?: return@mapNotNull null

            val episodeUrl = fixUrlNull(
                link.attr("href")
            ) ?: return@mapNotNull null

            val episodeTitle = element.selectFirst(
                "h2.entry-title"
            )?.text()?.trim() ?: "Episode"

            val episodeText = element.selectFirst(
                "span.num-epi"
            )?.text().orEmpty().ifBlank {
                episodeTitle
            }

            val seasonEpisode = Regex(
                """(?i)(\d+)\s*[x×]\s*(\d+)"""
            ).find(episodeText)

            val season = seasonEpisode
                ?.groupValues?.getOrNull(1)?.toIntOrNull()

            val episode = seasonEpisode
                ?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: Regex(
                    """(?i)(?:episode|ep)\s*[-:#.]?\s*(\d+)"""
                ).find(episodeText)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()

            newEpisode(episodeUrl) {
                this.name = episodeTitle
                this.season = season
                this.episode = episode
                this.posterUrl = element.imageUrl()
            }
        }

        return newAnimeLoadResponse(
            title.ifBlank { "Anime" },
            url,
            TvType.Anime
        ) {
            this.posterUrl = poster
            this.plot = description

            addEpisodes(
                DubStatus.Subbed,
                episodes
            )
        }
    }

    // MEDIA URL RESOLVER
    private fun resolveUrl(
        base: String,
        path: String
    ): String {

        val value = path.trim()

        if (
            value.startsWith("http://") ||
            value.startsWith("https://")
        ) return value

        if (value.startsWith("//")) {
            return "https:$value"
        }

        val origin = Regex(
            """^https?://[^/]+"""
        ).find(base)?.value ?: mainUrl

        if (value.startsWith("/")) {
            return origin + value
        }

        return base.substringBeforeLast("/") + "/" + value
    }

    // LOAD LINKS
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        val iframes = document.select(
            "aside.video-player div.video iframe, aside.video-player iframe"
        )

        var found = false

        for (iframe in iframes) {

            val rawUrl = iframe.attr("data-src")
                .ifBlank { iframe.attr("src") }

            val iframeUrl = fixUrlNull(rawUrl)
                ?: continue

            try {

                val loaded = loadExtractor(
                    url = iframeUrl,
                    referer = data,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )

                if (loaded) {
                    found = true
                    continue
                }

                val iframeDocument = app.get(
                    iframeUrl,
                    headers = mapOf(
                        "Referer" to data
                    )
                ).document

                val video = iframeDocument.selectFirst(
                    "video source, video"
                ) ?: continue

                val rawVideoUrl = video.attr("src")
                    .ifBlank { video.attr("data-src") }

                if (rawVideoUrl.isBlank()) continue

                val videoUrl = resolveUrl(
                    iframeUrl,
                    rawVideoUrl
                )

                val host = iframeUrl
                    .substringAfter("://")
                    .substringBefore("/")
                    .removePrefix("www.")

                val server = when {
                    iframeUrl.contains("zephyrix", true) ->
                        "Zephyrix"

                    iframeUrl.contains("abyss", true) ->
                        "Abyss"

                    else -> host
                }

                val cleanUrl = videoUrl
                    .substringBefore("?")
                    .substringBefore("#")
                    .lowercase()

                val type = when {
                    cleanUrl.endsWith(".m3u8") ->
                        ExtractorLinkType.M3U8

                    cleanUrl.endsWith(".mpd") ->
                        ExtractorLinkType.DASH

                    else ->
                        ExtractorLinkType.VIDEO
                }

                callback(
                    newExtractorLink(
                        source = server,
                        name = server,
                        url = videoUrl,
                        type = type
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
