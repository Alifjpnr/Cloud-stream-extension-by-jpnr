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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val items = document.select("ul#episode_by_temp li, section.episodes ul.post-lst li, article.post").mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(listOf(HomePageList(request.name, items)))
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = selectFirst("h2.entry-title, h2, h3") ?: return null
        val linkElement = selectFirst("a.lnk-blk[href], a[href]") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        val title = titleElement.text().trim()
        
        val imgElement = selectFirst("img")
        val poster = imgElement?.let { 
            fixUrlNull(it.attr("data-src").ifBlank { it.attr("src") }) 
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.post, .post-lst li").mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Anime"
        
        val imgElement = document.selectFirst("div.bghd img, div.post-thumbnail img, figure img")
        val poster = imgElement?.let { 
            fixUrlNull(it.attr("data-src").ifBlank { it.attr("src") }) 
        }

        val description = document.selectFirst("div.overviewCss, .entry-content")?.text()?.trim()

        val episodes = document.select("ul#episode_by_temp li").mapNotNull { element ->
            val link = element.selectFirst("a.lnk-blk[href]") ?: return@mapNotNull null
            val epUrl = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
            val epTitle = element.selectFirst("h2.entry-title")?.text()?.trim() ?: "Episode"
            val numText = element.selectFirst("span.num-epi")?.text().orEmpty()

            val regex = Regex("""(\d+)\s*[x×]\s*(\d+)""").find(numText)
            val season = regex?.groupValues?.getOrNull(1)?.toIntOrNull()
            val episode = regex?.groupValues?.getOrNull(2)?.toIntOrNull() 
                ?: Regex("""(?:ep|episode)\s*(\d+)""", RegexOption.IGNORE_CASE).find(numText)?.groupValues?.getOrNull(1)?.toIntOrNull()

            newEpisode(epUrl) {
                this.name = epTitle
                this.season = season
                this.episode = episode
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframes = document.select("aside.video-player iframe")
        var success = false

        for (iframe in iframes) {
            val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }
            val iframeUrl = fixUrlNull(src) ?: continue

            val loaded = loadExtractor(iframeUrl, data, subtitleCallback, callback)
            if (loaded) {
                success = true
                continue
            }

            try {
                val iframeDoc = app.get(iframeUrl, headers = mapOf("Referer" to data)).document
                val videoSource = iframeDoc.selectFirst("video source, video")?.attr("src") ?: continue
                val videoUrl = fixUrl(videoSource)

                val serverName = if (iframeUrl.contains("zephyrix", true)) "Zephyrix" else "Abyss"
                val isM3u8 = videoUrl.contains(".m3u8", true)

                callback(
                    newExtractorLink(
                        source = serverName,
                        name = serverName,
                        url = videoUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = iframeUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                success = true
            } catch (e: Exception) {
                // Ignore individual frame errors and continue loop
            }
        }

        return success
    }
}
