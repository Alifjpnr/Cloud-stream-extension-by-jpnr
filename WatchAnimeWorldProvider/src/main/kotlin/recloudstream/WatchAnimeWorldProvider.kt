package com.watchanimeworld

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class WatchAnimeWorldProvider : MainAPI() {
    override var mainUrl = "https://watchanimeworld.one"
    override var name = "WatchAnimeWorld"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.Movie
    )

    override val mainPage = mainPageOf("$mainUrl/" to "Home")

    // ------------------------------------------------------------------
    // Home page
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Every section lives on the front page, there is nothing to paginate.
        if (page > 1) return newHomePageResponse(emptyList<HomePageList>(), false)

        val doc = app.get(mainUrl).document
        val lists = ArrayList<HomePageList>()

        for (section in doc.select("section.widget_list_episodes, section.widget_list_movies_series")) {
            val title = section.selectFirst("h3.section-title")?.text()?.trim().orEmpty()
            if (title.isBlank()) continue
            val items = section.select("article.post")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
            if (items.isNotEmpty()) lists.add(HomePageList(title, items))
        }

        for (section in doc.select("section.widget_top")) {
            val title = section.selectFirst("h3.widget-title")?.text()?.trim().orEmpty()
            if (title.isBlank()) continue
            val items = section.select("a.item__card")
                .mapNotNull { it.toTopPickResult() }
                .distinctBy { it.url }
            if (items.isNotEmpty()) lists.add(HomePageList(title, items))
        }

        if (lists.isEmpty()) throw ErrorLoadingException("No sections found on the home page")
        return newHomePageResponse(lists, false)
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val results = ArrayList<SearchResponse>()
        var nextUrl: String? = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        var pages = 0

        while (nextUrl != null && pages < 5) {
            val doc = app.get(nextUrl).document
            val items = doc.select("ul.post-lst article.post").mapNotNull { it.toSearchResult() }
            if (items.isEmpty()) break
            results.addAll(items)
            nextUrl = doc.selectFirst("nav.pagination a.next")
                ?.attr("href")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
            pages++
        }

        return results.distinctBy { it.url }
    }

    // ------------------------------------------------------------------
    // Details (series / movie)
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val info: Element = doc.selectFirst("article.post.single") ?: doc

        val title = info.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        val poster = fixUrlNull(
            info.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        )?.replace("/w185/", "/w500/")

        val plot = info.selectFirst("div.description")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val tags = (info.select("p.genres a") + info.select("p.loadactor a"))
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val year = info.selectFirst("span.year .overviewCss")?.text()?.trim()?.toIntOrNull()
        val duration = info.selectFirst("span.duration .overviewCss")?.text()
            ?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

        val recommendations = doc.select("section.related article.post")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        if (url.contains("/movies/")) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.duration = duration
                this.recommendations = recommendations
            }
        }

        val episodes = loadEpisodes(doc)

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            addEpisodes(DubStatus.Dubbed, episodes)
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
            this.duration = duration
            this.recommendations = recommendations
        }
    }

    /**
     * The series page only prints the episodes of one season. The other
     * seasons are fetched with the theme's season-select AJAX call.
     */
    private suspend fun loadEpisodes(doc: Element): List<Episode> {
        val all = LinkedHashMap<String, Episode>()

        val first = parseEpisodes(doc)
        first.forEach { all.putIfAbsent(it.data, it) }
        val loadedSeasons = first.mapNotNull { it.season }.toSet()

        for (link in doc.select("ul.aa-cnt li.sel-temp a[data-season]")) {
            val season = link.attr("data-season").toIntOrNull() ?: continue
            val post = link.attr("data-post")
            if (post.isBlank() || season in loadedSeasons) continue
            try {
                val html = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "action_select_season",
                        "season" to season.toString(),
                        "post" to post
                    ),
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    referer = "$mainUrl/"
                ).text
                parseEpisodes(Jsoup.parse(html)).forEach { all.putIfAbsent(it.data, it) }
            } catch (e: Exception) {
                logError(e)
            }
        }

        return all.values.sortedWith(
            compareBy<Episode>({ it.season ?: 0 }, { it.episode ?: 0 })
        )
    }

    private fun parseEpisodes(root: Element): List<Episode> {
        return root.select("article.post.episodes").mapNotNull { art ->
            val href = fixUrlNull(art.selectFirst("a.lnk-blk")?.attr("href"))
                ?: return@mapNotNull null
            // "14x43" -> season 14, episode 43
            val parts = art.selectFirst("span.num-epi")?.text()?.trim().orEmpty().split("x")
            val seasonNumber = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val episodeNumber = parts.getOrNull(1)?.trim()?.toIntOrNull()
            val thumb = fixUrlNull(art.selectFirst("img")?.attr("src"))

            newEpisode(href) {
                this.season = seasonNumber
                this.episode = episodeNumber
                this.posterUrl = thumb
            }
        }
    }

    // ------------------------------------------------------------------
    // Video links
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = "$mainUrl/").document
        val seen = HashSet<String>()
        var found = false

        for (frame in doc.select("div.video iframe")) {
            val src = frame.attr("data-src").ifBlank { frame.attr("src") }.trim()
            if (src.isBlank()) continue
            val full = fixUrl(src)
            if (!seen.add(full)) continue
            if (resolveEmbed(full, data, subtitleCallback, callback, 0)) found = true
        }

        return found
    }

    private suspend fun resolveEmbed(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int
    ): Boolean {
        // External hosts (zephyrix, abyss, ...) go to CloudStream's extractor system.
        if (!url.startsWith(mainUrl)) {
            return loadExtractor(url, referer, subtitleCallback, callback)
        }
        if (depth > 2) return false

        val res = app.get(url, referer = referer)
        val page = res.document
        val text = res.text.replace("\\/", "/")
        var found = false

        for (track in page.select("track[src]")) {
            val trackUrl = fixUrlNull(track.attr("src")) ?: continue
            val label = track.attr("label").ifBlank { "Subtitle" }
            subtitleCallback(newSubtitleFile(label, trackUrl))
        }

        for (frame in page.select("iframe")) {
            val src = frame.attr("data-src").ifBlank { frame.attr("src") }.trim()
            if (src.isBlank()) continue
            if (resolveEmbed(fixUrl(src), url, subtitleCallback, callback, depth + 1)) found = true
        }

        for (source in page.select("source[src], video[src]")) {
            val link = fixUrlNull(source.attr("src")) ?: continue
            emitDirect(link, url, callback)
            found = true
        }

        val direct = Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*""")
        for (match in direct.findAll(text)) {
            emitDirect(match.value, url, callback)
            found = true
        }

        val abyss = Regex("""https?://player\.abyssplayer\.com/[A-Za-z0-9_-]+""")
        for (match in abyss.findAll(text)) {
            if (loadExtractor(match.value, url, subtitleCallback, callback)) found = true
        }

        return found
    }

    private suspend fun emitDirect(link: String, referer: String, callback: (ExtractorLink) -> Unit) {
        if (link.contains(".m3u8")) {
            M3u8Helper.generateM3u8(name, link, referer).forEach(callback)
        } else {
            callback(
                newExtractorLink(name, name, link, ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }

    // ------------------------------------------------------------------
    // Card parsing helpers
    // ------------------------------------------------------------------

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(selectFirst("a.lnk-blk")?.attr("href")) ?: return null
        val title = selectFirst("h2.entry-title")?.text()?.trim()
        if (title.isNullOrBlank()) return null
        val img = selectFirst("img")
        val poster = fixUrlNull(
            img?.attr("src")?.takeIf { it.isNotBlank() } ?: img?.attr("data-src")
        )
        val categories = parent()?.className().orEmpty()
        return buildResult(title, href, poster, categories)
    }

    private fun Element.toTopPickResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val img = selectFirst("img")
        val title = img?.attr("alt")?.removePrefix("Image ")?.trim()
        if (title.isNullOrBlank()) return null
        val poster = fixUrlNull(img.attr("src"))?.replace("/original/", "/w500/")
        return buildResult(title, href, poster, "")
    }

    private fun buildResult(
        title: String,
        href: String,
        poster: String?,
        categories: String
    ): SearchResponse {
        val isMovie = href.contains("/movies/")
        // Cards without category info (home widgets) are treated as anime.
        val isAnime = categories.isBlank() || categories.contains("category-anime")

        return when {
            isMovie -> newMovieSearchResponse(
                title,
                href,
                if (isAnime) TvType.AnimeMovie else TvType.Movie
            ) {
                this.posterUrl = poster
            }
            isAnime -> newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                addDubStatus(DubStatus.Dubbed)
            }
            else -> newTvSeriesSearchResponse(title, href, TvType.Cartoon) {
                this.posterUrl = poster
            }
        }
    }
}
