package eu.kanade.tachiyomi.animeextension.en.gogoanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.lang.Exception

class GogoAnime : ParsedAnimeHttpSource() {

    override val name = "Gogoanime"

    override val baseUrl = "https://gogoanime.pe"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder() = Headers.Builder().apply {
        add("User-Agent", "Aniyomi")
        add("Referer", "https://streamani.io/")
    }

    override fun popularAnimeSelector(): String = "div.img a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/popular.html?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.thumbnail_url = element.select("img").first().attr("src")
        anime.title = element.attr("title")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination-list li:last-child:not(.selected)"

    override fun episodeListSelector() = "ul#episode_page li a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val totalEpisodes = document.select(episodeListSelector()).last().attr("ep_end")
        val id = document.select("input#movie_id").attr("value")
        return episodesRequest(totalEpisodes, id)
    }

    private fun episodesRequest(totalEpisodes: String, id: String): List<SEpisode> {
        val request = GET("https://ajax.gogo-load.com/ajax/load-list-episode?ep_start=0&ep_end=$totalEpisodes&id=$id", headers)
        val epResponse = client.newCall(request).execute()
        val document = epResponse.asJsoup()
        return document.select("a").map { episodeFromElement(it) }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(baseUrl + element.attr("href").substringAfter(" "))
        val ep = element.selectFirst("div.name").ownText().substringAfter(" ")
        episode.episode_number = ep.toFloat()
        episode.name = "Episode $ep"
        episode.date_upload = System.currentTimeMillis()
        return episode
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val document = client.newCall(GET(baseUrl + episode.url)).execute().asJsoup()
        val link = document.selectFirst("li.dowloads a").attr("href")
        return GET(link)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select(videoListSelector()).ordered().map { videoFromElement(it) }
    }

    private fun Elements.ordered(): Elements {
        val newElements = Elements()
        var googleElements = 0
        for (element in this) {
            newElements.add(googleElements, element)
            if (element.attr("href").startsWith("https://storage.googleapis.com")) {
                googleElements++
            }
        }
        return newElements
    }

    override fun videoListSelector() = "div.mirror_link a[download]"

    override fun videoFromElement(element: Element): Video {
        val quality = element.text().substringAfter("Download (").replace("P - mp4)", "p")
        val url = element.attr("href")
        return if (url.startsWith("https://storage.googleapis.com")) {
            Video(url, quality, url, null)
        } else {
            Video(url, quality, videoUrlParse(url), null)
        }
    }

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    private fun videoUrlParse(url: String): String {
        val noRedirectClient = client.newBuilder().followRedirects(false).build()
        val response = noRedirectClient.newCall(GET(url)).execute()
        val videoUrl = response.header("location")
        response.close()
        return videoUrl ?: url
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.thumbnail_url = element.select("img").first().attr("src")
        anime.title = element.attr("title")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination-list li:last-child:not(.selected)"

    override fun searchAnimeSelector(): String = "div.img a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/search.html?keyword=$query&page=$page", headers)

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.anime_info_body_bg h1").text()
        anime.genre = document.select("p.type:eq(5) a").joinToString("") { it.text() }
        anime.description = document.select("p.type:eq(4)").first().ownText()
        anime.status = parseStatus(document.select("p.type:eq(7) a").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector(): String = "ul.pagination-list li:last-child:not(.selected)"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(baseUrl + element.attr("href"))
        val style = element.select("div.thumbnail-popular").attr("style")
        anime.thumbnail_url = style.substringAfter("background: url('").substringBefore("');")
        anime.title = element.attr("title")
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET("https://ajax.gogo-load.com/ajax/page-recent-release-ongoing.html?page=$page&type=1", headers)

    override fun latestUpdatesSelector(): String = "div.added_series_body.popular li a:has(div)"
}