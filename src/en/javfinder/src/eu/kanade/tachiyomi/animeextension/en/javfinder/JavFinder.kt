package eu.kanade.tachiyomi.animeextension.en.javfinder

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Locale

class JavFinder : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "JavFinder"

    override val baseUrl = "https://javfinder.sb"

    override val lang = "en"

    override val supportsLatest = false

    private val dateFormat: SimpleDateFormat = SimpleDateFormat("MMMM dd,yyyy", Locale.ENGLISH)

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private fun animeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))

        val imgList = element.select("div.post-thumbnail img,video")
        val thumbUrl = if (imgList.hasAttr("data-src")) {
            imgList.attr("data-src")
        } else {
            imgList.attr("poster")
        }
        anime.thumbnail_url = thumbUrl.substringAfter("url=")
        anime.title = element.attr("title")
        return anime
    }

    // Popular Anime

    override fun popularAnimeSelector(): String = "div.videos-list article a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/movies/hot/page-$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        return animeFromElement(element)
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination ul > li"

    // Episodes

    override fun episodeListSelector() = "html"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.select("head link").attr("href"))
        episode.name = element.select("h1.entry-title").text()
        val epNum = element.select("div.name").text().substringAfter("Episode ")
        episode.episode_number = when {
            (epNum.isNotEmpty()) -> epNum.toFloat()
            else -> 1F
        }
        val dateText = element.select("div#video-date").text().substringAfter("Date: ")
        episode.date_upload = dateFormat.parse(dateText)!!.time
        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    // Video urls

    override fun videoListRequest(episode: SEpisode): Request {
        val document = client.newCall(GET(baseUrl + episode.url)).execute().asJsoup()
        val script = document.select("div.responsive-player script").html()
        val playerUrl = script.substring(script.indexOf("player#") + 7, script.indexOf("id=") - 2)
        return GET("$baseUrl/stream/$playerUrl")
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    override fun videoListSelector() = throw Exception("not used")

    private fun videosFromElement(document: Document): List<Video> {
        val jsonObj = JSONTokener(document.body().text()).nextValue() as JSONObject
        val url = jsonObj.getJSONArray("list").getJSONObject(0).getString("url")
        val videoList = mutableListOf<Video>()
        val videos = StreamSBExtractor(client).videosFromUrl(url, headers, common = false)
        videoList.addAll(videos)
        return videoList
    }

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", null)
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    // search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))

        val imgList = element.select("div.post-thumbnail img,video")
        val thumbUrl = if (imgList.hasAttr("data-src")) {
            imgList.attr("data-src")
        } else {
            imgList.attr("poster")
        }
        anime.thumbnail_url = thumbUrl.substringAfter("url=")
        anime.title = element.attr("title")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "div.pagination ul > li"

    override fun searchAnimeSelector(): String = "div.videos-list article a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/search/movie/$query/$page-$page"
        } else {
            var catLink = String()
            var sortType = String()
            (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                when (filter) {
                    is TypeList -> {
                        catLink = getTypeList()[filter.state].query
                    }
                    is SortFilter -> {
                        sortType = getSortList()[filter.state].query
                    }
                }
            }
            if (catLink.isNotEmpty()) {
                val catUrl = ("$baseUrl$catLink$sortType/page-$page").toHttpUrlOrNull()!!.newBuilder()
                return GET(catUrl.toString(), headers)
            } else {
                throw Exception("Choose Filter")
            }
        }
        return GET(url, headers)
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("h1.entry-title").text()
        anime.description = document.select("h1.entry-title").text()
        anime.author = document.select("div#video-actors a").text()
        return anime
    }

    // Latest

    override fun latestUpdatesSelector(): String = "div.videos-list article a"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/movies/page-$page")

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return animeFromElement(element)
    }

    override fun latestUpdatesNextPageSelector(): String = "div.pagination ul > li"

    // Filter

    override fun getFilterList() = AnimeFilterList(
        TypeList(typesName),
        SortFilter(getSortList().map { it.name }.toTypedArray())
    )

    private class TypeList(types: Array<String>) : AnimeFilter.Select<String>("Jav Type", types)
    private data class Type(val name: String, val query: String)
    private val typesName = getTypeList().map {
        it.name
    }.toTypedArray()

    private fun getTypeList(): List<Type> {
        val document = client.newCall(GET("$baseUrl/category")).execute().asJsoup()
        val articles = document.select("div.videos-list article")

        val catList = mutableListOf<Type>(
            Type("Latest", "/movies"),
        )
        for (a in articles) {
            val el = a.select("a")
            val href = el.attr("href")
            val catName = el.attr("title")
            catList.add(Type(catName, href))
        }
        return catList
    }

    private class SortFilter(types: Array<String>) : AnimeFilter.Select<String>("Sort", types)
    private fun getSortList() = listOf(
        Type("New", ""),
        Type("Hot", "/hot"),
    )

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }
}
