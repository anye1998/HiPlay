package com.shilapi.xcertplay.update

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UpdateManifestTest {
    private lateinit var server: HttpServer
    private lateinit var root: String
    private val manifestHits = AtomicInteger()

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = Executors.newSingleThreadExecutor()
        root = "http://${server.address.hostString}:${server.address.port}"
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun aManifestWithAnInlineDigestIsARelease() {
        val release = UpdateManifest.parse(
            """
            {
              "version": "v0.3.0",
              "apk": "DiPlay-0.3.0.apk",
              "url": "https://github.com/o/r/releases/download/v0.3.0/DiPlay-0.3.0.apk",
              "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            }
            """.trimIndent(),
        )
        assertEquals("v0.3.0", release?.tagName)
        assertEquals("DiPlay-0.3.0.apk", release?.apkName)
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", release?.sha256)
        assertEquals("", release?.checksumsUrl)
    }

    @Test
    fun aManifestMayPointAtAChecksumsFileInstead() {
        val release = UpdateManifest.parse(
            """
            {"tag_name":"0.3.0","apk_name":"DiPlay-0.3.0.apk",
             "browser_download_url":"https://github.com/o/r/releases/download/0.3.0/DiPlay-0.3.0.apk",
             "checksums_url":"https://github.com/o/r/releases/download/0.3.0/SHA256SUMS.txt"}
            """.trimIndent(),
        )
        assertEquals("https://github.com/o/r/releases/download/0.3.0/SHA256SUMS.txt", release?.checksumsUrl)
        assertNull(release?.sha256)
    }

    @Test
    fun aManifestWithoutAnyDigestOrWithAForeignHostIsRejected() {
        assertNull(
            UpdateManifest.parse(
                """{"version":"1.0.0","apk":"a.apk","url":"https://github.com/o/r/a.apk"}""",
            ),
        )
        assertNull(
            UpdateManifest.parse(
                """{"version":"1.0.0","apk":"a.apk","url":"https://evil.example/a.apk",
                    "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}""",
            ),
        )
        assertNull(UpdateManifest.parse("not json"))
    }

    @Test
    fun theLookupUsesTheApiWhenItAnswers() {
        serveJson("/api", RELEASES)
        val attempts = ArrayList<UpdateAttempt>()
        val release = UpdateReleaseLookup.latest(
            installedVersion = "0.2.22",
            userAgent = "DiPlay/test",
            openConnection = ::localConnection,
            onAttempt = { attempts.add(it) },
        )
        assertEquals("v0.3.0", release?.tagName)
        assertEquals(0, manifestHits.get())
        assertEquals("api: found v0.3.0", attempts.single().toString())
    }

    @Test
    fun theLookupFallsBackToTheManifestWhenTheApiIsUnreachable() {
        serveStatus("/api", 403)
        serveStatus("/raw", 503)
        val manifest = """
            {"version":"v0.3.0","apk":"DiPlay-0.3.0.apk",
             "url":"https://github.com/o/r/releases/download/v0.3.0/DiPlay-0.3.0.apk",
             "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}
        """.trimIndent()
        serveText("/ghfast", manifest)
        val attempts = ArrayList<UpdateAttempt>()

        val release = UpdateReleaseLookup.latest(
            installedVersion = "0.2.22",
            userAgent = "DiPlay/test",
            openConnection = ::localConnection,
            onAttempt = { attempts.add(it) },
        )

        assertEquals("v0.3.0", release?.tagName)
        val recorded = attempts.map { it.toString() }
        assertTrue(recorded.toString(), recorded.first().startsWith("api: unavailable"))
        assertTrue(recorded[1], recorded[1].startsWith("direct: manifest unavailable: Request to https://raw.githubusercontent.com"))
        assertEquals("ghfast.top: found v0.3.0", recorded[2])
    }

    @Test
    fun anUnreachableApiAndAnUnreachableManifestReportNoUpdate() {
        serveStatus("/api", 403)
        serveStatus("/raw", 503)
        serveStatus("/ghfast", 503)
        serveStatus("/ghproxy", 503)
        val attempts = ArrayList<UpdateAttempt>()
        val release = UpdateReleaseLookup.latest(
            installedVersion = "0.2.22",
            userAgent = "DiPlay/test",
            openConnection = ::localConnection,
            onAttempt = { attempts.add(it) },
        )
        assertNull(release)
        assertEquals(4, attempts.size)
    }

    private fun localConnection(url: URL): URLConnection {
        val path = when (url.host.lowercase()) {
            "api.github.com" -> "api"
            "raw.githubusercontent.com" -> "raw"
            "ghfast.top" -> "ghfast"
            "gh-proxy.com" -> "ghproxy"
            else -> "raw"
        }
        if (path != "api") manifestHits.incrementAndGet()
        return URL("$root/$path").openConnection()
    }

    private fun serveJson(path: String, json: String) = serveText(path, json)

    private fun serveText(path: String, text: String) {
        val bytes = text.toByteArray()
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun serveStatus(path: String, status: Int) {
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
    }

    private companion object {
        val RELEASES = """
            [
              {
                "tag_name": "v0.3.0",
                "draft": false,
                "assets": [
                  {"name": "DiPlay-0.3.0.apk",
                   "browser_download_url": "https://github.com/o/r/releases/download/v0.3.0/DiPlay-0.3.0.apk"},
                  {"name": "SHA256SUMS.txt",
                   "browser_download_url": "https://github.com/o/r/releases/download/v0.3.0/SHA256SUMS.txt"}
                ]
              }
            ]
        """.trimIndent()
    }
}
