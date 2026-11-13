package com.shilapi.xcertplay.update

import java.net.URI

/**
 * Where an update can be fetched from, in the order it is tried.
 *
 * Two different problems need two different answers:
 *
 * - **The API is unreachable.** Public GitHub proxies forward `github.com` file downloads but not
 *   `api.github.com`, so falling back to a proxy for the API would only add a slow failure. The
 *   fallback is therefore a static manifest (`version.json`) that the repository publishes, read
 *   through a mirror of `raw.githubusercontent.com`.
 * - **The download is slow or blocked.** A release asset is a plain file, so the same URL can be
 *   prefixed by each mirror in turn.
 *
 * A dead mirror is worse than no mirror: it turns a fast direct failure into a timeout. The list is
 * therefore explicit and ordered, and every skipped entry records why it was skipped.
 */
internal enum class UpdateMirror(val id: String, val prefix: String?) {
    /** No mirror: the release asset URL as published by GitHub. */
    DIRECT("direct", null),
    GHFAST("ghfast.top", "https://ghfast.top/"),
    GH_PROXY("gh-proxy.com", "https://gh-proxy.com/"),
    GH_PROXY_V4("v4.gh-proxy.org", "https://v4.gh-proxy.org/"),
    ;

    /** Applies this mirror to an absolute GitHub URL; null when the URL is not one we may rewrite. */
    fun rewrite(url: String): String? {
        if (prefix == null) return url
        if (!UpdateMirrors.isGitHubUrl(url)) return null
        return prefix + url
    }
}

/** One attempt at reaching the update metadata or an asset. */
internal data class UpdateAttempt(val source: String, val outcome: String) {
    override fun toString(): String = "$source: $outcome"
}

/** The published repository, so a fork only changes these three values. */
internal data class UpdateRepository(
    val owner: String = UpdateMirrors.DEFAULT_OWNER,
    val name: String = UpdateMirrors.DEFAULT_REPOSITORY,
    val branch: String = UpdateMirrors.DEFAULT_BRANCH,
) {
    val apiUrl: String get() = "https://api.github.com/repos/$owner/$name/releases?per_page=3"
    val manifestUrl: String get() = "https://raw.githubusercontent.com/$owner/$name/$branch/${UpdateMirrors.MANIFEST_FILE}"
}

internal object UpdateMirrors {
    // The fork that publishes the builds this app is cut from. Pointing at upstream DiPlay would
    // offer releases that carry no HiCar code and are signed with a different key.
    const val DEFAULT_OWNER = "anye1998"
    const val DEFAULT_REPOSITORY = "HiPlay"

    /** The branch that holds the static manifest; it must be a branch, not a tag. */
    const val DEFAULT_BRANCH = "main"
    const val MANIFEST_FILE = "version.json"

    /** Download order: direct first, then the mirrors that actually proxy release assets. */
    val DOWNLOAD_ORDER: List<UpdateMirror> = listOf(
        UpdateMirror.DIRECT,
        UpdateMirror.GHFAST,
        UpdateMirror.GH_PROXY,
        UpdateMirror.GH_PROXY_V4,
    )

    /**
     * Metadata order: the API once, then the static manifest through each mirror.
     *
     * `raw.githubusercontent.com` is tried before its mirrors because it is often reachable where
     * `api.github.com` is not, and it costs one request.
     */
    val MANIFEST_ORDER: List<UpdateMirror> = listOf(
        UpdateMirror.DIRECT,
        UpdateMirror.GHFAST,
        UpdateMirror.GH_PROXY,
    )

    fun manifestUrl(repository: UpdateRepository, mirror: UpdateMirror): String? =
        mirror.rewrite(repository.manifestUrl)

    fun isGitHubUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.rawUserInfo != null || uri.port != -1) return false
        val host = uri.host ?: return false
        return host.equals("github.com", ignoreCase = true) ||
            host.equals("objects.githubusercontent.com", ignoreCase = true) ||
            host.equals("raw.githubusercontent.com", ignoreCase = true)
    }
}
