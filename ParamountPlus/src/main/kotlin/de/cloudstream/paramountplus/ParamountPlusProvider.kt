package de.cloudstream.paramountplus

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

internal class ParamountPlusProvider(private val api: ParamountApi) : MainAPI() {
    override var name = "Paramount+ International"
    override var mainUrl = ParamountApi.ORIGIN
    override var lang = "de"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val hasChromecastSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = mainPageOf("movies" to "Filme", "shows" to "Serien")

    private fun titleUrl(title: CatalogueTitle) =
        "$mainUrl/cloudstream/${if (title.movie) "movie" else "show"}/${title.id}"

    private fun CatalogueTitle.toSearchResponse(): SearchResponse = if (movie) {
        newMovieSearchResponse(name, titleUrl(this), TvType.Movie) { posterUrl = poster }
    } else {
        newTvSeriesSearchResponse(name, titleUrl(this), TvType.TvSeries) { posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val result = if (request.data == "movies") api.movies(page) else api.shows(page)
        return newHomePageResponse(request.name, result.titles.map { it.toSearchResponse() }, result.hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        api.search(query).map { it.toSearchResponse() }

    private fun LoadResponse.metadata(title: CatalogueTitle) {
        posterUrl = title.poster
        backgroundPosterUrl = title.background
        plot = title.description
        year = title.year
        duration = title.durationMinutes
        tags = title.genres
        addActors(title.cast)
    }

    override suspend fun load(url: String): LoadResponse {
        val parsed = url.toHttpUrlOrNull() ?: throw ParamountException("Ungültige Titeladresse.")
        val path = parsed.pathSegments
        if (parsed.host != mainUrl.toHttpUrlOrNull()?.host || path.size != 3 || path[0] != "cloudstream")
            throw ParamountException("Ungültige Paramount+-Titeladresse.")
        return when (path[1]) {
            "movie" -> {
                val title = api.movie(path[2])
                newMovieLoadResponse(title.name, url, TvType.Movie, title.id) { metadata(title) }
            }
            "show" -> {
                val (title, programmes) = api.show(path[2])
                val episodes = programmes.map { programme ->
                    newEpisode(programme.id, fix = false, initializer = {
                        name = programme.title
                        season = programme.season
                        episode = programme.number
                        posterUrl = programme.image
                        description = programme.description
                    })
                }
                newTvSeriesLoadResponse(title.name, url, TvType.TvSeries, episodes) { metadata(title) }
            }
            else -> throw ParamountException("Unbekannter Inhaltstyp.")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (isCasting) throw ParamountException("Die Wiedergabe erfolgt im internen Cloudstream-Player.")
        // Request a fresh authorized session at playback time. Never serialize its token into bookmarks.
        val stream = api.playback(data)
        callback(newDrmExtractorLink(
            source = name, name = "$name · Widevine", url = stream.manifest,
            type = if (stream.dash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8,
            uuid = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"),
        ) {
            licenseUrl = stream.license
            referer = "$mainUrl/"
            quality = 0 // Adaptive quality; no unverified HD/4K claim.
            headers = mapOf("User-Agent" to ParamountApi.USER_AGENT,
                "Authorization" to "Bearer ${stream.token}", "Origin" to mainUrl)
        })
        // DASH/HLS embedded audio and subtitle tracks are selected by the host player.
        return true
    }
}
