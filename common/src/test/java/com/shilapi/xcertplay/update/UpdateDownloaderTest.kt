package com.shilapi.xcertplay.update

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateDownloaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var root: String
    private val attempts = ArrayList<UpdateAttempt>()

    private val apkName = "DiPlay-9.9.9.apk"
    private val good = "real apk bytes".toByteArray()
    private val bad = "exfiltrated bytes".toByteArray()
    private val goodDigest = UpdateChecksums.sha256Hex(good.inputStream())

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
    fun aFailedMirrorFallsThroughToTheNextAndAChecksumMismatchIsRejected() {
        serve("/direct/$apkName", status = 500)
        serveText("/direct/${UpdateCatalog.CHECKSUMS_FILE}", sums(goodDigest))
        serve("/ghfast/$apkName", body = bad)
        serveText("/ghfast/${UpdateCatalog.CHECKSUMS_FILE}", sums(goodDigest))
        serveText("/ghproxy/${UpdateCatalog.CHECKSUMS_FILE}", sums(goodDigest))
        serve("/ghproxy/$apkName", body = good)

        val directory = temporaryFolder.newFolder("update")
        val file = UpdateDownloader.download(
            release = UpdateRelease(
                tagName = "v9.9.9",
                apkName = apkName,
                apkUrl = "https://github.com/o/r/releases/download/v9.9.9/$apkName",
                checksumsUrl = "https://github.com/o/r/releases/download/v9.9.9/${UpdateCatalog.CHECKSUMS_FILE}",
            ),
            directory = directory,
            openConnection = ::localConnection,
            onAttempt = { attempts.add(it) },
        )

        assertEquals(good.toList(), file.readBytes().toList())
        val reasons = attempts.map { "${it.source}: ${it.outcome}" }
        assertTrue(reasons.toString(), reasons.any { it.startsWith("direct: download failed") })
        assertTrue(reasons.toString(), reasons.contains("ghfast.top: SHA-256 mismatch, rejected"))
        assertTrue(reasons.toString(), reasons.contains("gh-proxy.com: ok"))
        // The rejected copy must not survive next to the verified one.
        assertEquals(1, directory.listFiles()!!.count { it.name.endsWith(".apk") })
    }

    @Test
    fun anInlineDigestSkipsTheChecksumsFile() {
        serve("/direct/$apkName", body = good)
        val directory = temporaryFolder.newFolder("inline")
        val file = UpdateDownloader.download(
            release = UpdateRelease(
                tagName = "v9.9.9",
                apkName = apkName,
                apkUrl = "https://github.com/o/r/releases/download/v9.9.9/$apkName",
                checksumsUrl = "",
                sha256 = goodDigest,
            ),
            directory = directory,
            openConnection = ::localConnection,
            onAttempt = { attempts.add(it) },
        )
        assertEquals(good.toList(), file.readBytes().toList())
        assertEquals(listOf("direct: ok"), attempts.map { "${it.source}: ${it.outcome}" })
    }

    @Test
    fun anUnverifiableReleaseIsRefusedBeforeAnyDownload() {
        val directory = temporaryFolder.newFolder("unverifiable")
        try {
            UpdateDownloader.download(
                release = UpdateRelease(
                    tagName = "v9.9.9",
                    apkName = apkName,
                    apkUrl = "https://github.com/o/r/releases/download/v9.9.9/$apkName",
                    checksumsUrl = "",
                ),
                directory = directory,
                openConnection = ::localConnection,
                onAttempt = { attempts.add(it) },
            )
            fail("no digest means the APK cannot be verified")
        } catch (expected: UpdateDownloader.DownloadFailure) {
            assertTrue(expected.message!!, expected.message!!.contains("publishes no digest"))
        }
        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    private fun localConnection(url: URL): URLConnection {
        val mirror = when (url.host.lowercase()) {
            "github.com" -> "direct"
            "ghfast.top" -> "ghfast"
            "gh-proxy.com" -> "ghproxy"
            "v4.gh-proxy.org" -> "v4"
            else -> "direct"
        }
        val name = url.path.substringAfterLast('/')
        return URL("$root/$mirror/$name").openConnection()
    }

    private fun sums(digest: String) = "$digest  $apkName\n"

    private fun serve(path: String, status: Int = 200, body: ByteArray = ByteArray(0)) {
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(status, if (status == 200) body.size.toLong() else -1)
            if (status == 200) exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
    }

    private fun serveText(path: String, text: String) {
        val bytes = text.toByteArray()
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
