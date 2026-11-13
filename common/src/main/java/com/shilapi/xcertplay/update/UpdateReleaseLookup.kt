package com.shilapi.xcertplay.update

import java.io.IOException
import java.net.URL
import java.net.URLConnection

/**
 * Finds the newest published release, using the API first and a static manifest second.
 *
 * The API is the only place that lists several releases with their assets, so it stays first. It is
 * also the endpoint that public GitHub proxies do not forward, which is why the fallback is a file
 * on `raw.githubusercontent.com` rather than a proxied API call: a proxied API request would return
 * a proxy error page and look like "no update".
 */
internal object UpdateReleaseLookup {
    fun latest(
        installedVersion: String,
        userAgent: String,
        openConnection: (URL) -> URLConnection = { it.openConnection() },
        repository: UpdateRepository = UpdateRepository(),
        onAttempt: (UpdateAttempt) -> Unit = {},
    ): UpdateRelease? {
        apiRelease(installedVersion, userAgent, openConnection, repository, onAttempt)?.let { return it }
        return manifestRelease(installedVersion, userAgent, openConnection, repository, onAttempt)
    }

    private fun apiRelease(
        installedVersion: String,
        userAgent: String,
        openConnection: (URL) -> URLConnection,
        repository: UpdateRepository,
        onAttempt: (UpdateAttempt) -> Unit,
    ): UpdateRelease? {
        val json = try {
            UpdateClient.fetchText(repository.apiUrl, "application/vnd.github+json", userAgent, openConnection)
        } catch (failure: IOException) {
            onAttempt(UpdateAttempt("api", "unavailable: ${failure.message}"))
            return null
        } catch (failure: RuntimeException) {
            onAttempt(UpdateAttempt("api", "unavailable: ${failure.javaClass.simpleName}"))
            return null
        }
        val release = try {
            UpdateCatalog.parse(json)
        } catch (failure: RuntimeException) {
            onAttempt(UpdateAttempt("api", "malformed: ${failure.javaClass.simpleName}"))
            return null
        }
        if (release == null) {
            onAttempt(UpdateAttempt("api", "no usable release"))
            return null
        }
        if (!UpdateVersion.isNewer(release.tagName, installedVersion)) {
            onAttempt(UpdateAttempt("api", "up to date at ${release.tagName}"))
            return null
        }
        onAttempt(UpdateAttempt("api", "found ${release.tagName}"))
        return release
    }

    private fun manifestRelease(
        installedVersion: String,
        userAgent: String,
        openConnection: (URL) -> URLConnection,
        repository: UpdateRepository,
        onAttempt: (UpdateAttempt) -> Unit,
    ): UpdateRelease? {
        for (mirror in UpdateMirrors.MANIFEST_ORDER) {
            val url = UpdateMirrors.manifestUrl(repository, mirror) ?: continue
            val json = try {
                UpdateClient.fetchText(url, "application/json", userAgent, openConnection)
            } catch (failure: IOException) {
                onAttempt(UpdateAttempt(mirror.id, "manifest unavailable: ${failure.message}"))
                continue
            } catch (failure: RuntimeException) {
                onAttempt(UpdateAttempt(mirror.id, "manifest unavailable: ${failure.javaClass.simpleName}"))
                continue
            }
            val release = UpdateManifest.parse(json)
            if (release == null) {
                onAttempt(UpdateAttempt(mirror.id, "manifest unreadable"))
                continue
            }
            if (!UpdateVersion.isNewer(release.tagName, installedVersion)) {
                onAttempt(UpdateAttempt(mirror.id, "up to date at ${release.tagName}"))
                return null
            }
            onAttempt(UpdateAttempt(mirror.id, "found ${release.tagName}"))
            return release
        }
        return null
    }
}
