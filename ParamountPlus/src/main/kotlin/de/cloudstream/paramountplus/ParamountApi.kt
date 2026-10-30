package de.cloudstream.paramountplus

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

internal interface Vault {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

internal class ParamountException(message: String) : IOException(message)

internal fun JsonNode.text(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    get(key)?.takeUnless { it.isNull }?.asText()?.takeIf { it.isNotBlank() }
}
internal fun JsonNode.items(): List<JsonNode> = if (isArray) toList() else emptyList()

/** Only sends matching, unexpired cookies; never installs cookies in the host app's client. */
internal class SessionCookies(private val vault: Vault, origin: HttpUrl) : CookieJar {
    private val mapper = ObjectMapper()
    private val cookies = mutableListOf<Cookie>()

    init {
        runCatching { mapper.readTree(vault.read("cookies") ?: "[]").items() }
            .getOrDefault(emptyList()).mapNotNull { Cookie.parse(origin, it.asText()) }
            .filterTo(cookies) { it.expiresAt > System.currentTimeMillis() }
    }

    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            this.cookies.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            if (cookie.expiresAt > System.currentTimeMillis()) this.cookies.add(cookie)
        }
        vault.write("cookies", mapper.writeValueAsString(this.cookies.map { it.toString() }))
    }

    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
        cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }

    @Synchronized fun clear() {
        cookies.clear()
        vault.write("cookies", null)
    }
}

internal data class AppConfig(val country: String, val locale: String)
internal data class Profile(val id: String, val name: String)
internal data class CataloguePage(val titles: List<CatalogueTitle>, val hasNext: Boolean)
internal data class CatalogueTitle(
    val id: String,
    val name: String,
    val movie: Boolean,
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val year: Int? = null,
    val durationMinutes: Int? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
)
internal data class ProgrammeEpisode(
    val id: String, val title: String?, val season: Int?, val number: Int?,
    val image: String?, val description: String?,
)
internal data class Playback(val manifest: String, val license: String, val token: String, val dash: Boolean)

/** International API; country and language come from Paramount's regional availability response. */
internal class ParamountApi(
    private val vault: Vault,
    private val origin: String = ORIGIN,
    httpClient: OkHttpClient = OkHttpClient(),
) {
    companion object {
        const val ORIGIN = "https://www.intl.paramountplus.com"
        // Public application identifier, not a subscriber credential or a DRM key.
        const val APPLICATION_TOKEN = "ABAS/G30Pp6tJuNOlZ1OEE6Rf5goS0KjICkGkBVIapVuxemiiASyWVfW4v7SUeAkogc="
        const val USER_AGENT = "Paramount+/15.5.0 (com.cbs.ott; build:520000178; Android SDK 30; androidtv; SHIELD Android TV) okhttp/5.1.0"
        const val PAGE_SIZE = 40
    }

    private val mapper = ObjectMapper()
    private val cookies = SessionCookies(vault, origin.toHttpUrl())
    private val client = httpClient.newBuilder().cookieJar(cookies)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val configMutex = Mutex()
    private val authMutex = Mutex()
    private var appConfig: AppConfig? = null
    private var showsCache: Pair<Long, List<CatalogueTitle>>? = null

    val signedIn: Boolean get() = vault.read("profileId") != null &&
        cookies.loadForRequest((origin + "/apps-api/").toHttpUrl()).isNotEmpty()
    val profileName: String? get() = if (signedIn) vault.read("profileName") else null

    private suspend fun request(
        path: String,
        query: Map<String, String> = emptyMap(),
        form: Map<String, String>? = null,
        locale: String? = appConfig?.locale,
    ): JsonNode = withContext(Dispatchers.IO) {
        val url = (origin + "/apps-api" + path).toHttpUrl().newBuilder()
            .addQueryParameter("at", APPLICATION_TOKEN)
        locale?.let { url.addQueryParameter("locale", it) }
        query.forEach { (key, value) -> url.addQueryParameter(key, value) }
        val builder = Request.Builder().url(url.build()).header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
        form?.let { fields ->
            val body = FormBody.Builder()
            fields.forEach { (key, value) -> body.add(key, value) }
            builder.post(body.build())
        }
        try {
            client.newCall(builder.build()).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    throw ParamountException("Anmeldung oder Zugriffsberechtigung abgelaufen. Bitte erneut anmelden und Abo prüfen.")
                }
                if (!response.isSuccessful) throw ParamountException("Paramount+ antwortet mit HTTP ${response.code}.")
                val data = try { mapper.readTree(response.body?.string() ?: "") }
                    catch (_: IOException) { throw ParamountException("Unerwartete Antwort von Paramount+.") }
                    ?: throw ParamountException("Leere Antwort von Paramount+.")
                if (data.path("success").isBoolean && !data.path("success").asBoolean()) {
                    throw ParamountException("Paramount+ hat die Anfrage abgelehnt. Anmeldung, Region und Abo prüfen.")
                }
                data
            }
        } catch (e: ParamountException) {
            throw e
        } catch (_: IOException) {
            // Do not expose URLs, query tokens, cookies, response bodies or subscriber data.
            throw ParamountException("Paramount+ ist nicht erreichbar. Verbindung prüfen und erneut versuchen.")
        }
    }

    suspend fun config(): AppConfig = configMutex.withLock {
        appConfig ?: request("/v2.0/androidphone/app/status.json").let { data ->
            if (!data.path("appVersion").path("availableInRegion").asBoolean()) {
                throw ParamountException("Paramount+ International ist in dieser Region nicht verfügbar.")
            }
            val country = data.path("appVersion").text("clientRegion")
                ?: throw ParamountException("Paramount+ hat keine Region geliefert.")
            val language = data.path("localesSupport").items()
                .firstOrNull { it.path("isDefaultLanguage").asBoolean() }?.text("lang") ?: "de-de"
            AppConfig(country, language).also { appConfig = it }
        }
    }

    private fun deviceId(): String = vault.read("deviceId") ?: UUID.randomUUID().toString()
        .replace("-", "").take(16).also { vault.write("deviceId", it) }

    suspend fun login(email: String, password: String) = authMutex.withLock {
        require(email.isNotBlank() && password.isNotEmpty()) { "E-Mail und Passwort eingeben." }
        config()
        clearSession()
        try {
            request("/v2.0/androidtv/auth/login.json", form = mapOf(
                "j_username" to email.trim(), "j_password" to password, "deviceId" to deviceId(),
            ))
            val user = request("/v3.0/androidtv/login/status.json")
            saveProfile(user.path("activeProfile"))
            if (!signedIn) throw ParamountException("Paramount+ hat keine gültige Sitzung geliefert.")
        } catch (e: Exception) {
            clearSession()
            throw e
        }
    }

    private fun saveProfile(data: JsonNode) {
        val id = data.text("id") ?: throw ParamountException("Das Konto hat kein aktives Profil.")
        vault.write("profileId", id)
        vault.write("profileName", data.text("name") ?: "Paramount+")
        vault.write("refreshedAt", System.currentTimeMillis().toString())
        showsCache = null
    }

    private fun clearSession() {
        cookies.clear()
        listOf("profileId", "profileName", "refreshedAt").forEach { vault.write(it, null) }
        showsCache = null
    }

    suspend fun logout() = authMutex.withLock { clearSession() }

    suspend fun profiles(): List<Profile> = authMutex.withLock {
        config()
        requireLogin()
        request("/v3.0/androidtv/login/status.json").path("accountProfiles").items().mapNotNull {
            val id = it.text("id") ?: return@mapNotNull null
            Profile(id, it.text("name") ?: "Profil")
        }
    }

    suspend fun selectProfile(id: String) = authMutex.withLock {
        config()
        requireLogin()
        switchProfile(id)
    }

    private suspend fun switchProfile(id: String) {
        if (!id.matches(Regex("[A-Za-z0-9_-]+"))) throw ParamountException("Ungültige Profil-ID.")
        val data = request("/v2.0/androidtv/user/account/profile/switch/$id.json", form = emptyMap())
        saveProfile(data.path("profile"))
    }

    private fun requireLogin() {
        if (!signedIn) throw ParamountException("Bitte unter Erweiterungen → ParamountPlus → Einstellungen anmelden.")
    }

    private suspend fun prepare() {
        config()
        authMutex.withLock {
            val last = vault.read("refreshedAt")?.toLongOrNull() ?: 0L
            if (signedIn && System.currentTimeMillis() - last > TimeUnit.HOURS.toMillis(12)) {
                switchProfile(vault.read("profileId")!!)
            }
        }
    }

    suspend fun movies(page: Int): CataloguePage {
        prepare()
        val start = (page.coerceAtLeast(1) - 1) * PAGE_SIZE
        val data = request("/v3.0/androidphone/movies.json", mapOf(
            "start" to "$start", "rows" to "$PAGE_SIZE", "includeContentInfo" to "true",
            "includeTrailerInfo" to "false", "platformType" to "androidphone",
        ))
        return CataloguePage(data.path("movies").items().mapNotNull(::movieTitle),
            data.path("numFound").asInt() > start + PAGE_SIZE)
    }

    suspend fun shows(page: Int): CataloguePage {
        prepare()
        val cached = showsCache
        val all = if (cached != null && System.currentTimeMillis() - cached.first < 300_000) cached.second
        else {
            val data = request("/v2.0/androidphone/shows/groups.json", mapOf("includeAllShowGroups" to "true"))
            data.path("all").items().mapNotNull(::showTitle).distinctBy { it.id }
                .also { showsCache = System.currentTimeMillis() to it }
        }
        val start = (page.coerceAtLeast(1) - 1) * PAGE_SIZE
        return CataloguePage(all.drop(start).take(PAGE_SIZE), all.size > start + PAGE_SIZE)
    }

    suspend fun search(query: String): List<CatalogueTitle> {
        if (query.isBlank()) return emptyList()
        prepare()
        return request("/v3.0/androidphone/contentsearch/search.json", mapOf(
            "term" to query.trim(), "termCount" to "50", "showCanVids" to "true",
        )).path("terms").items().mapNotNull { item ->
            when (item.text("term_type")) {
                "show" -> showTitle(item)
                "movie" -> movieTitle(item)
                else -> null
            }
        }.distinctBy { (if (it.movie) "m" else "s") + it.id }
    }

    suspend fun movie(id: String): CatalogueTitle {
        prepare()
        return movieTitle(request("/v3.0/androidphone/movies/${validId(id)}.json",
            mapOf("includeContentInfo" to "true")).path("movie"))
            ?: throw ParamountException("Dieser Film ist nicht verfügbar.")
    }

    suspend fun show(id: String): Pair<CatalogueTitle, List<ProgrammeEpisode>> {
        prepare()
        val safeId = validId(id)
        val data = request("/v3.0/androidphone/shows/$safeId.json")
        val showData = data.path("show").path("results").firstOrNull()
            ?: throw ParamountException("Diese Serie ist nicht verfügbar.")
        val title = showTitle(showData, data.path("showAssets"))
            ?: throw ParamountException("Die Seriendaten sind unvollständig.")
        val links = request("/v3.0/androidphone/shows/$safeId/menu.json")
            .path("showMenu").firstOrNull()?.path("links").itemsOrEmpty()
        val sections = linkedSetOf<String>()
        for (link in links) {
            val configName = link.text("videoConfigUniqueName") ?: continue
            val metadata = request("/v2.0/androidphone/shows/$safeId/videos/config/${validId(configName)}.json",
                mapOf("platformType" to "apps", "rows" to "1", "begin" to "0"))
            metadata.path("videoSectionMetadata").items()
                .filter { it.text("section_type").equals("Full Episodes", ignoreCase = true) }
                .mapNotNullTo(sections) { it.text("sectionId") }
        }
        val episodes = mutableListOf<ProgrammeEpisode>()
        for (section in sections) {
            // Page each section: a fixed rows=999 silently truncates long-running series.
            var begin = 0
            val seen = mutableSetOf<String>()
            while (true) {
                val batch = request("/v2.0/androidphone/videos/section/${validId(section)}.json",
                    mapOf("begin" to "$begin", "rows" to "100"))
                    .path("sectionItems").path("itemList").items()
                val parsed = batch.mapNotNull(::episode)
                val fresh = parsed.filter { seen.add(it.id) }
                episodes.addAll(fresh)
                if (batch.size < 100 || fresh.isEmpty()) break
                begin += batch.size
            }
        }
        return title to episodes.distinctBy { it.id }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.number ?: 0 }, { it.title ?: "" }))
    }

    suspend fun playback(id: String): Playback = authMutex.withLock {
        config()
        requireLogin()
        val last = vault.read("refreshedAt")?.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - last > TimeUnit.HOURS.toMillis(12))
            switchProfile(vault.read("profileId")!!)
        val data = request("/v3.1/androidphone/irdeto-control/session-token.json",
            mapOf("contentId" to validId(id)))
        val manifest = data.text("streamingUrl")?.let(::httpsUrl)
            ?: throw ParamountException("Paramount+ hat keinen abspielbaren Stream freigegeben. Abo und Verfügbarkeit prüfen.")
        val license = data.text("url")?.let(::httpsUrl)
            ?: throw ParamountException("Paramount+ hat keine Widevine-Lizenzadresse geliefert.")
        val token = data.text("ls_session")
            ?: throw ParamountException("Paramount+ hat keine Wiedergabeberechtigung geliefert.")
        if (!license.contains("widevine", ignoreCase = true))
            throw ParamountException("Diese DRM-Variante wird vom Plugin nicht unterstützt.")
        Playback(manifest, license, token, manifest.toHttpUrl().encodedPath.endsWith(".mpd", true))
    }

    private fun validId(id: String): String {
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) throw ParamountException("Ungültige Inhalts-ID.")
        return id
    }

    private fun httpsUrl(url: String): String? = runCatching { url.toHttpUrl() }.getOrNull()
        ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString()

    private fun image(assets: JsonNode, vararg fields: String): String? {
        val file = assets.text(*fields) ?: return null
        return if (file.startsWith("files/")) "$origin/thumbnails/photos/w600/${file.removePrefix("files/")}"
        else httpsUrl(file)
    }

    private fun posterFromVideo(video: JsonNode): String? = video.path("thumbnailSet").items()
        .firstOrNull { it.text("assetType") == "PosterArt" }?.text("url")?.let(::httpsUrl)
        ?: video.text("thumbnail")?.let(::httpsUrl)

    private fun movieTitle(item: JsonNode): CatalogueTitle? {
        val video = item.get("movieContent")?.takeUnless { it.isNull }
            ?: item.path("videoList").path("itemList").firstOrNull() ?: item
        val id = video.text("contentId", "content_id") ?: item.text("contentId", "content_id") ?: return null
        val name = video.text("label", "title") ?: item.text("title") ?: return null
        val assets = item.path("movieAssets")
        return CatalogueTitle(id, name, true,
            image(assets, "filepath_movie_poster", "filepath_movie_poster_sky") ?: posterFromVideo(video),
            image(assets, "filepath_movie_hero", "filepath_movie_hero_sky"),
            video.text("description", "shortDescription"),
            video.text("_airDateISO")?.take(4)?.toIntOrNull(),
            video.text("duration")?.toIntOrNull()?.div(60),
            item.path("unifiedGenre").items().map { it.asText() },
            item.path("castNames").items().map { it.asText() },
        )
    }

    private fun showTitle(item: JsonNode, assets: JsonNode = item.path("showAssets")): CatalogueTitle? {
        val id = item.text("showId", "show_id", "id") ?: return null
        val name = item.text("title") ?: return null
        return CatalogueTitle(id, name, false,
            image(assets, "filepath_show_browse_poster", "filepath_show_poster"),
            image(assets, "filepath_show_hero_landscape", "filepath_show_hero_regular"),
            item.text("about"), item.text("showPremiereDateStr")?.take(4)?.toIntOrNull(),
            genres = item.path("unifiedGenre").items().map { it.asText() },
        )
    }

    private fun episode(item: JsonNode): ProgrammeEpisode? {
        if (item.path("fullEpisode").isBoolean && !item.path("fullEpisode").asBoolean()) return null
        val id = item.text("contentId") ?: return null
        return ProgrammeEpisode(id, item.text("label", "title"),
            item.text("seasonNum")?.toIntOrNull(), item.text("episodeNum")?.toIntOrNull(),
            item.text("thumbnail")?.let(::httpsUrl), item.text("description", "shortDescription"))
    }
}

private fun JsonNode?.itemsOrEmpty(): List<JsonNode> = this?.items() ?: emptyList()
