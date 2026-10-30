package de.cloudstream.paramountplus

import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ParamountApiTest {
    private lateinit var server: MockWebServer
    private lateinit var vault: MemoryVault
    private lateinit var api: ParamountApi

    private class MemoryVault : Vault {
        val data = mutableMapOf<String, String>()
        override fun read(key: String) = data[key]
        override fun write(key: String, value: String?) { if (value == null) data.remove(key) else data[key] = value }
    }

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        vault = MemoryVault()
        api = ParamountApi(vault, server.url("/").toString().trimEnd('/'))
    }

    @After fun teardown() { server.shutdown() }

    private fun json(body: String) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body)) }

    private fun config() { json("""{"success":true,"appVersion":{"availableInRegion":true,"clientRegion":"DE"},"localesSupport":[{"lang":"de-de","isDefaultLanguage":true}]}""") }

    private suspend fun login() {
        config()
        server.enqueue(MockResponse().setHeader("Set-Cookie", "subscriber=session-secret; Path=/; HttpOnly")
            .setBody("""{"success":true}"""))
        json("""{"success":true,"isLoggedIn":true,"activeProfile":{"id":"42","name":"Testprofil"}}""")
        api.login("someone@example.org", "test-password")
    }

    @Test fun `search maps shows and movies with encoded query and German locale`() = runBlocking {
        config()
        json("""{"success":true,"terms":[
          {"term_type":"show","show_id":123,"title":"Serie","showAssets":{"filepath_show_browse_poster":"files/show/poster.jpg"}},
          {"term_type":"movie","title":"Film","videoList":{"itemList":[{"contentId":"abc_123","label":"Film"}]}},
          {"term_type":"unknown","title":"Werbung"}
        ]}""")
        val results = api.search("A&B + C")
        assertEquals(listOf("Serie", "Film"), results.map { it.name })
        assertFalse(results[0].movie)
        assertTrue(results[1].movie)
        server.takeRequest()
        val url = server.takeRequest().requestUrl!!
        assertEquals("A&B + C", url.queryParameter("term"))
        assertEquals("de-de", url.queryParameter("locale"))
        assertEquals(ParamountApi.APPLICATION_TOKEN, url.queryParameter("at"))
    }

    @Test fun `movies preserve server pagination despite missing entries`() = runBlocking {
        config()
        json("""{"success":true,"numFound":81,"movies":[{"movieContent":{"contentId":"m1","label":"Film","duration":7200,"_airDateISO":"2022-01-01"}},{}]}""")
        val page = api.movies(2)
        assertTrue(page.hasNext)
        assertEquals(120, page.titles.single().durationMinutes)
        assertEquals(2022, page.titles.single().year)
        server.takeRequest()
        assertEquals("40", server.takeRequest().requestUrl!!.queryParameter("start"))
    }

    @Test fun `series episode pagination loads past one hundred and sorts season numbers`() = runBlocking {
        config()
        json("""{"success":true,"show":{"results":[{"show_id":123,"title":"Serie"}]},"showAssets":{}}""")
        json("""{"success":true,"showMenu":[{"links":[{"videoConfigUniqueName":"EPISODES"}]}]}""")
        json("""{"success":true,"videoSectionMetadata":[{"section_type":"Full Episodes","sectionId":8}]}""")
        val hundred = (1..100).joinToString(",") { """{"contentId":"ep$it","seasonNum":"2","episodeNum":"$it","fullEpisode":true}""" }
        json("""{"success":true,"sectionItems":{"itemList":[$hundred]}}""")
        json("""{"success":true,"sectionItems":{"itemList":[{"contentId":"first","seasonNum":"1","episodeNum":"1","fullEpisode":true}]}}""")
        val (_, episodes) = api.show("123")
        assertEquals(101, episodes.size)
        assertEquals("first", episodes.first().id)
        repeat(5) { server.takeRequest() }
        assertEquals("100", server.takeRequest().requestUrl!!.queryParameter("begin"))
    }

    @Test fun `login persists cookies but never email or password and restores a session`() = runBlocking {
        login()
        assertTrue(api.signedIn)
        assertEquals("Testprofil", api.profileName)
        assertFalse(vault.data.values.any { it.contains("test-password") || it.contains("someone@example.org") })
        server.takeRequest()
        assertTrue(server.takeRequest().body.readUtf8().contains("j_password=test-password"))
        assertTrue(server.takeRequest().getHeader("Cookie")!!.contains("subscriber=session-secret"))
        val restored = ParamountApi(vault, server.url("/").toString().trimEnd('/'))
        assertTrue(restored.signedIn)
        restored.logout()
        assertFalse(restored.signedIn)
        assertFalse(vault.data.containsKey("cookies"))
    }

    @Test fun `failed login clears all partially received credentials`() = runBlocking {
        config()
        server.enqueue(MockResponse().setHeader("Set-Cookie", "partial=secret; Path=/")
            .setBody("""{"success":false,"message":"private account information"}"""))
        try { api.login("a@b.test", "private-password"); fail("Expected failure") }
        catch (e: ParamountException) { assertFalse(e.message!!.contains("private")) }
        assertFalse(api.signedIn)
        assertFalse(vault.data.containsKey("cookies"))
    }

    @Test fun `playback requires login before requesting an entitlement`() = runBlocking {
        config()
        try { api.playback("video1"); fail("Expected login requirement") }
        catch (e: ParamountException) { assertTrue(e.message!!.contains("anmelden")) }
        assertEquals(1, server.requestCount)
    }

    @Test fun `authorized playback uses Android session endpoint and preserves license token`() = runBlocking {
        login()
        json("""{"success":true,"streamingUrl":"https://cdn.example.org/manifest.mpd?x=1","url":"https://license.example.org/widevine","ls_session":"playback-token"}""")
        val result = api.playback("video_1")
        assertTrue(result.dash)
        assertEquals("playback-token", result.token)
        repeat(3) { server.takeRequest() }
        val request = server.takeRequest()
        assertEquals("/apps-api/v3.1/androidphone/irdeto-control/session-token.json", request.requestUrl!!.encodedPath)
        assertEquals("video_1", request.requestUrl!!.queryParameter("contentId"))
        assertTrue(request.getHeader("Cookie")!!.contains("subscriber="))
    }

    @Test fun `denied entitlement never becomes a playable stream`() = runBlocking {
        login()
        json("""{"success":false,"message":"No valid entitlement policy found","ls_session":"do-not-leak"}""")
        try { api.playback("video1"); fail("Expected denial") }
        catch (e: ParamountException) { assertFalse(e.message!!.contains("do-not-leak")) }
    }

    @Test fun `older sessions refresh the selected profile before playback`() = runBlocking {
        login()
        vault.write("refreshedAt", "0")
        json("""{"success":true,"profile":{"id":"42","name":"Erneuert"}}""")
        json("""{"success":true,"streamingUrl":"https://cdn.example.org/master.mpd","url":"https://license.example.org/widevine","ls_session":"fresh-token"}""")
        assertEquals("fresh-token", api.playback("video1").token)
        repeat(3) { server.takeRequest() }
        val refresh = server.takeRequest()
        assertEquals("POST", refresh.method)
        assertEquals("/apps-api/v2.0/androidtv/user/account/profile/switch/42.json", refresh.requestUrl!!.encodedPath)
        assertEquals("Erneuert", api.profileName)
        assertTrue(vault.read("refreshedAt")!!.toLong() > 0)
    }

    @Test fun `expired cookies cannot restore an authenticated session`() {
        vault.write("cookies", """["subscriber=expired; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"]""")
        vault.write("profileId", "42")
        val restored = ParamountApi(vault, server.url("/").toString().trimEnd('/'))
        assertFalse(restored.signedIn)
    }

    @Test fun `profile selection uses server-confirmed profile data`() = runBlocking {
        login()
        json("""{"success":true,"accountProfiles":[{"id":42,"name":"Eins"},{"id":43,"name":"Zwei"}]}""")
        assertEquals(listOf("42", "43"), api.profiles().map { it.id })
        json("""{"success":true,"profile":{"id":43,"name":"Zwei"}}""")
        api.selectProfile("43")
        assertEquals("Zwei", api.profileName)
        assertEquals("43", vault.read("profileId"))
    }

    @Test fun `playback rejects cleartext license addresses`() = runBlocking {
        login()
        json("""{"streamingUrl":"https://cdn.example.org/master.mpd","url":"http://license.example.org/widevine","ls_session":"secret"}""")
        try { api.playback("video1"); fail("Expected HTTPS requirement") }
        catch (e: ParamountException) { assertTrue(e.message!!.contains("Lizenzadresse")) }
    }

    @Test fun `unavailable regions stop catalogue requests`() = runBlocking {
        json("""{"success":true,"appVersion":{"availableInRegion":false}}""")
        try { api.movies(1); fail("Expected region error") }
        catch (e: ParamountException) { assertTrue(e.message!!.contains("Region")) }
        assertEquals(1, server.requestCount)
    }

    @Test fun `cookie matching prevents leaking sessions to other hosts or paths`() {
        val jar = SessionCookies(vault, "https://www.intl.paramountplus.com".toHttpUrl())
        val cookie = Cookie.parse("https://www.intl.paramountplus.com".toHttpUrl(),
            "subscriber=secret; Path=/apps-api; Secure; HttpOnly")!!
        jar.saveFromResponse("https://www.intl.paramountplus.com".toHttpUrl(), listOf(cookie))
        assertEquals(1, jar.loadForRequest("https://www.intl.paramountplus.com/apps-api/test".toHttpUrl()).size)
        assertTrue(jar.loadForRequest("https://other.example.org/apps-api/test".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://www.intl.paramountplus.com/thumbnails/a.jpg".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://www.intl.paramountplus.com/apps-api/test".toHttpUrl()).isEmpty())
    }
}
