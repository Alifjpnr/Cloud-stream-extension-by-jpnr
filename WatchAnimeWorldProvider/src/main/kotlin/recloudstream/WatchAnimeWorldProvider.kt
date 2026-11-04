package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class WatchAnimeWorldProvider : MainAPI() {
    override var mainUrl = "https://watchanimeworld.one"
    override var name = "Anime World India"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.TvSeries,
        TvType.Movie
    )

    
    // Define home page tabs matching the site structure
    override val mainPage = mainPageOf(
        "$mainUrl/trending/" to "Trending Anime",
        "$mainUrl/movies/" to "Movies",
        "$mainUrl/tvshows/" to "TV Series",
        "$mainUrl/genre/hindi-dubbed/" to "Hindi Dubbed"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data}page/$page/"
        }

        val document = app.get(url).document
        val home = document.select("div.item, article.item, div.result-item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            listOf(HomePageList(request.name, home)),
            true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.title h3, h3, .title")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.let { img ->
            img.attr("data-src").takeIf { it.isNotBlank() } ?: img.attr("src")
        }
        
        val tvType = if (href.contains("/movie")) TvType.Movie else TvType.Anime

        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val searchUrl = "$mainUrl/page/$page/?s=$query"
        val document = app.get(searchUrl).document
        val results = document.select("div.item, article.item, div.result-item").mapNotNull { it.toSearchResult() }
        
        return newSearchResponseList(results, true)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("div.sheader div.data h1, h1.title")?.text() ?: "Unknown"
        val poster = document.selectFirst("div.poster img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        val description = document.selectFirst("div.wp-content p, div.description")?.text()
        val genres = document.select("div.sgeneros a").map { it.text() }
        
        val episodes = mutableListOf<Episode>()
        
        // Parse episode lists from Torofilm theme layout
        document.select("ul.episodios li, div.les-content a").forEach { element ->
            val epHref = element.attr("href")
            val epName = element.selectFirst(".episodiotitle, span")?.text() ?: element.text()
            val epNumText = element.selectFirst(".num")?.text()
            val episodeNum = epNumText?.filter { it.isDigit() }?.toIntOrNull()

            episodes.add(
                newEpisode(epHref) {
                    name = epName
                    episode = episodeNum
                }
            )
        }

        val isMovie = url.contains("/movie/")
        
        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Extract embedded player links or iframes standard to Torofilm sites
        document.select("iframe, .mobius iframe, .play-box-iframe iframe").forEach { iframe ->
            val iframeUrl = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (iframeUrl.isNotBlank()) {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
