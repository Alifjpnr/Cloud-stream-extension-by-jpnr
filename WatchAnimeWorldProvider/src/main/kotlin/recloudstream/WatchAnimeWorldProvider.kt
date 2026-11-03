package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class WatchAnimeWorldProvider : MainAPI() {
    override var mainUrl = "https://watchanimeworld.one"
    override var name = "Watch Anime World"
    override val supportedTypes = setOf(TvType.Anime, TvType.Cartoon)
    override var lang = "hi"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homeCategories = ArrayList<HomePageList>()

        val items = document.select("ul#episode_by_temp li, section.episodes ul.post-lst li").mapNotNull { element ->
            element.toSearchResult()
        }

        if (items.isNotEmpty()) {
            homeCategories.add(HomePageList("Latest Anime & Cartoons", items))
        }

        return newHomePageResponse(homeCategories)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a.lnk-blk") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        val title = this.selectFirst("h2.entry-title")?.text() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.post-thumbnail img, figure img")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select("article.post").mapNotNull { article ->
            val title = article.selectFirst("h2.entry-title")?.text() ?: return@mapNotNull null
            val href = fixUrlNull(article.selectFirst("a.lnk-blk")?.attr("href")) ?: return@mapNotNull null
            val posterUrl = fixUrlNull(article.selectFirst("div.post-thumbnail img, figure img")?.attr("src"))

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("title")?.text()?.replace(" - Anime World India", "")?.trim() ?: "Anime"
        val posterUrl = fixUrlNull(document.selectFirst("div.bghd img, div.post-thumbnail img")?.attr("src"))
        val description = document.selectFirst("div.overviewCss, .entry-content")?.text()

        val episodes = document.select("ul#episode_by_temp li").mapNotNull { element ->
            val link = fixUrlNull(element.selectFirst("a.lnk-blk")?.attr("href")) ?: return@mapNotNull null
            val epTitle = element.selectFirst("h2.entry-title")?.text() ?: "Episode"
            val epNumText = element.selectFirst("span.num-epi")?.text()

            var seasonNum: Int? = null
            var epNum: Int? = null
            if (epNumText != null && epNumText.contains("x")) {
                val parts = epNumText.split("x")
                seasonNum = parts.getOrNull(0)?.toIntOrNull()
                epNum = parts.getOrNull(1)?.toIntOrNull()
            }

            newEpisode(link) {
                this.name = epTitle
                this.season = seasonNum
                this.episode = epNum
                this.posterUrl = fixUrlNull(element.selectFirst("img")?.attr("src"))
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = posterUrl
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
        val iframeElements = document.select("aside.video-player div.video iframe")

        for (iframe in iframeElements) {
            val rawSrc = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            val iframeUrl = fixUrlNull(rawSrc) ?: continue

            val loaded = loadExtractor(iframeUrl, data, subtitleCallback) { link ->
                callback(link)
            }

            if (!loaded) {
                try {
                    val iframeDoc = app.get(iframeUrl, headers = mapOf("Referer" to mainUrl)).document
                    val directVideoSrc = iframeDoc.selectFirst("video source, video")?.attr("src")

                    if (!directVideoSrc.isNullOrEmpty()) {
                        val serverName = if (iframeUrl.contains("zephyrix")) "Server 1 (Zephyrix)" else "Server 2 (Abyss)"

                        callback(
                            newExtractorLink(
                                source = serverName,
                                name = serverName,
                                url = fixUrl(directVideoSrc),
                                type = if (directVideoSrc.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = iframeUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return true
    }
}
