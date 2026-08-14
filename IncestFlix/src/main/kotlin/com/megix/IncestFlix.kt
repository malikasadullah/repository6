package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

class IncestFlix : MainAPI() {
    override var mainUrl = "https://incestflix.com.co"
    override var name = "IncestFlix"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/search?q=popular" to "Popular",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else request.data + if (request.data.contains("?")) "&page=$page" else "?page=$page"
        val res = app.get(url, headers = mapOf("User-Agent" to ua))
        val document = res.document
        
        val videos = mutableListOf<SearchResponse>()
        
        // Try multiple selectors for video items
        val videoElements = document.select(
            "div.video-item, article.video, div.post, div.entry, div.content-item, a[href*=watch], a[href*=view]"
        ).take(30)
        
        videoElements.forEach { element ->
            try {
                val video = parseVideoElement(element, document) ?: return@forEach
                videos.add(video)
            } catch (e: Exception) {
                // Skip malformed entries
            }
        }
        
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = videos,
                isHorizontalImages = true
            ),
            hasNext = videos.size >= 20
        )
    }

    private suspend fun parseVideoElement(element: Element, document: Document): SearchResponse? {
        // Extract URL - multiple fallbacks
        val url = element.attr("href").takeIf { it.isNotEmpty() }?.let { normalizeUrl(it) }
            ?: element.selectFirst("a[href]")?.attr("href")?.let { normalizeUrl(it) }
            ?: element.attr("data-url")?.let { normalizeUrl(it) }
            ?: return null

        if (url.isBlank() || !isValidUrl(url)) return null

        // Extract title - multiple fallbacks
        val title = element.attr("title").takeIf { it.isNotEmpty() }
            ?: element.selectFirst("h2, h3, .title, [class*=title]")?.text()
            ?: element.selectFirst("a")?.text()
            ?: element.attr("data-name")
            ?: url.substringAfterLast("/").replace(Regex("[^a-zA-Z0-9 ]"), " ").trim()
            ?: return null

        if (title.isBlank()) return null

        // Extract poster - multiple fallbacks
        val poster = getPosterUrl(element, url)

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
            if (poster != null) {
                this.posterHeaders = mapOf(
                    "User-Agent" to ua
                )
            }
        }
    }

    private suspend fun getPosterUrl(element: Element, videoUrl: String): String? {
        // Try 1: img tag with src/data-src
        val imgSrc = element.selectFirst("img")?.let { img ->
            img.attr("src").takeIf { it.isNotEmpty() }
                ?: img.attr("data-src").takeIf { it.isNotEmpty() }
                ?: img.attr("data-lazy-src").takeIf { it.isNotEmpty() }
        }?.let { normalizeUrl(it) }

        if (imgSrc != null && imgSrc.startsWith("http")) return imgSrc

        // Try 2: background-image style
        val bgImage = element.attr("style").let { style ->
            Regex("""background-image\s*:\s*url\(['"]?([^'")]+)['"]?\)""").find(style)?.groupValues?.get(1)
        }?.let { normalizeUrl(it) }

        if (bgImage != null && bgImage.startsWith("http")) return bgImage

        // Try 3: data attributes
        val dataPoster = element.attr("data-poster")?.takeIf { it.isNotEmpty() }
            ?: element.attr("data-thumbnail")?.takeIf { it.isNotEmpty() }
            ?: element.attr("data-image")?.takeIf { it.isNotEmpty() }

        if (dataPoster != null) {
            val normalized = normalizeUrl(dataPoster)
            if (normalized.startsWith("http")) return normalized
        }

        // Try 4: og:image from video page (risky but last resort)
        return try {
            if (videoUrl.isNotEmpty()) {
                val videoDoc = app.get(videoUrl, headers = mapOf("User-Agent" to ua), timeout = 5000).document
                videoDoc.selectFirst("meta[property=og:image]")?.attr("content")?.let { normalizeUrl(it) }
                    ?: videoDoc.selectFirst("meta[name=twitter:image]")?.attr("content")?.let { normalizeUrl(it) }
                    ?: videoDoc.selectFirst("img[class*=poster], img[class*=thumb]")?.attr("src")?.let { normalizeUrl(it) }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${query.trim()}"
        val res = app.get(url, headers = mapOf("User-Agent" to ua))
        val document = res.document

        return document.select(
            "div.video-item, article.video, div.post, div.entry, div.content-item, a[href*=watch]"
        ).mapNotNull { element ->
            try {
                parseVideoElement(element, document)
            } catch (e: Exception) {
                null
            }
        }.distinctBy { it.url }.take(20)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = mapOf("User-Agent" to ua)).document

        // Extract title - multiple fallbacks
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("meta[name=title]")?.attr("content")
            ?: doc.selectFirst("h1, .title, [class*=title]")?.text()
            ?: doc.title()
            ?: "Video"

        // Extract poster - multiple fallbacks
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("meta[name=twitter:image]")?.attr("content")
            ?: doc.selectFirst("img.poster, img[class*=poster], img[class*=thumb]")?.attr("src")
            ?: doc.selectFirst("img")?.attr("src")
            ?: null

        val normalizedPoster = poster?.let { normalizeUrl(it) }

        // Extract description
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("p, .description, [class*=desc]")?.text()
            ?: ""

        // Get recommendations
        val recommendations = doc.select(
            "div.related-item, div.similar, a[href*=watch], a[href*=view]"
        ).mapNotNull { element ->
            try {
                parseVideoElement(element, doc)
            } catch (e: Exception) {
                null
            }
        }.filter { it.url != url }.distinctBy { it.url }.take(15)

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = normalizedPoster
            if (normalizedPoster != null) {
                this.posterHeaders = mapOf(
                    "User-Agent" to ua
                )
            }
            this.plot = description
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = mapOf("User-Agent" to ua)).document
        var foundLinks = false

        // Method 1: Direct video/source tags
        doc.select("video source[src], video source[data-src]").forEach { source ->
            val src = source.attr("src").takeIf { it.isNotEmpty() }
                ?: source.attr("data-src").takeIf { it.isNotEmpty() }
                ?: return@forEach

            val normalized = normalizeUrl(src)
            if (normalized.isNotEmpty() && isValidMediaUrl(normalized)) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = normalized,
                        type = ExtractorLinkType.VIDEO
                    )
                )
                foundLinks = true
            }
        }

        // Method 2: Video tag src attribute
        doc.selectFirst("video[src]")?.attr("src")?.let { src ->
            val normalized = normalizeUrl(src)
            if (normalized.isNotEmpty() && isValidMediaUrl(normalized)) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = normalized,
                        type = ExtractorLinkType.VIDEO
                    )
                )
                foundLinks = true
            }
        }

        // Method 3: Iframe embeds
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotEmpty() } ?: return@forEach
            val normalized = normalizeUrl(src)
            
            if (normalized.isNotEmpty()) {
                // Try to extract from iframe
                try {
                    val iframeDoc = app.get(normalized, headers = mapOf("User-Agent" to ua), timeout = 3000).document
                    iframeDoc.select("video source[src], source[src]").forEach { source ->
                        val videoSrc = source.attr("src")
                        val normalizedVideo = normalizeUrl(videoSrc)
                        if (normalizedVideo.isNotEmpty() && isValidMediaUrl(normalizedVideo)) {
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = name,
                                    url = normalizedVideo,
                                    type = ExtractorLinkType.VIDEO
                                )
                            )
                            foundLinks = true
                        }
                    }
                } catch (e: Exception) {
                    // Skip iframe if it fails
                }
            }
        }

        // Method 4: Parse script tags for direct URLs
        doc.select("script").forEach { script ->
            val content = script.data()
            // Look for .m3u8 or .mp4 URLs
            Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)""").findAll(content).forEach { match ->
                val url = match.value
                if (url.isNotEmpty() && isValidMediaUrl(url)) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = url,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    foundLinks = true
                }
            }
        }

        // Method 5: HLS playlist patterns
        Regex("""https?://[^\s"'<>]+\.m3u8""").find(doc.toString())?.value?.let { url ->
            if (isValidMediaUrl(url)) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name (HLS)",
                        url = url,
                        isM3u8 = true,
                        type = ExtractorLinkType.M3U8
                    )
                )
                foundLinks = true
            }
        }

        return foundLinks
    }

    private fun isValidUrl(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("/")
    }

    private fun isValidMediaUrl(url: String): Boolean {
        return url.contains(".mp4") || url.contains(".m3u8") || url.contains(".webm") || url.contains(".mkv")
    }

    private fun normalizeUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            url.startsWith("/") -> mainUrl + url
            else -> try {
                fixUrl(url)
            } catch (e: Exception) {
                ""
            }
        }
    }
}
