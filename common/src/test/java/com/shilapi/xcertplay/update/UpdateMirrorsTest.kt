package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateMirrorsTest {
    @Test
    fun downloadOrderStartsDirectlyAndEndsWithTheFourthMirror() {
        assertEquals(
            listOf(
                UpdateMirror.DIRECT,
                UpdateMirror.GHFAST,
                UpdateMirror.GH_PROXY,
                UpdateMirror.GH_PROXY_V4,
            ),
            UpdateMirrors.DOWNLOAD_ORDER,
        )
    }

    @Test
    fun metadataFallsBackToTheManifestAndNeverToAProxiedApi() {
        // api.github.com is not forwarded by the public mirrors, so no entry may point at it.
        for (mirror in UpdateMirrors.MANIFEST_ORDER) {
            val url = UpdateMirrors.manifestUrl(UpdateRepository(), mirror) ?: continue
            assertTrue(mirror.id, url.contains("raw.githubusercontent.com"))
            assertFalse(mirror.id, url.contains("api.github.com"))
        }
    }

    @Test
    fun noMirrorUsesARetiredDomain() {
        val retired = listOf("homeboyc")
        for (mirror in UpdateMirror.entries) {
            val prefix = mirror.prefix ?: continue
            for (dead in retired) {
                assertFalse("${mirror.id} still uses a retired mirror", prefix.contains(dead))
            }
        }
    }

    @Test
    fun aMirrorPrefixesOnlyGitHubUrls() {
        val release = "https://github.com/o/r/releases/download/v1.0.0/app.apk"
        assertEquals("https://ghfast.top/$release", UpdateMirror.GHFAST.rewrite(release))
        assertEquals(release, UpdateMirror.DIRECT.rewrite(release))
        assertNull(UpdateMirror.GHFAST.rewrite("https://example.com/app.apk"))
        assertNull(UpdateMirror.GHFAST.rewrite("http://github.com/o/r/app.apk"))
        assertNull(UpdateMirror.GHFAST.rewrite("https://user:pw@github.com/o/r/app.apk"))
    }

    @Test
    fun theManifestUrlFollowsTheRepositoryAndBranch() {
        val fork = UpdateRepository(owner = "someone", name = "DiPlay-HiCar", branch = "hicar")
        assertEquals(
            "https://raw.githubusercontent.com/someone/DiPlay-HiCar/hicar/version.json",
            fork.manifestUrl,
        )
        assertEquals(
            "https://api.github.com/repos/someone/DiPlay-HiCar/releases?per_page=3",
            fork.apiUrl,
        )
    }
}
