package com.shilapi.xcertplay.update

import org.json.JSONException
import org.json.JSONObject

/**
 * The static `version.json` that mirrors can serve when `api.github.com` is unreachable.
 *
 * The file is published by the release workflow next to the APK, so it names exactly one version
 * instead of a list. Unknown fields are ignored and the three documented spellings of each field
 * are accepted, because the manifest is hand-edited by whoever cuts a release and a typo in an
 * optional alias should not silently turn into "no update".
 */
internal object UpdateManifest {
    fun parse(json: String): UpdateRelease? {
        val root = try {
            JSONObject(json)
        } catch (failure: JSONException) {
            return null
        }
        val tag = firstString(root, "version", "tag_name", "tag") ?: return null
        val apkName = firstString(root, "apk", "apk_name", "asset") ?: return null
        val apkUrl = firstString(root, "url", "apk_url", "browser_download_url") ?: return null
        if (!APK_NAME.matches(apkName)) return null
        if (!UpdateMirrors.isGitHubUrl(apkUrl)) return null
        val checksums = firstString(root, "checksums", "checksums_url", "SHA256SUMS")?.takeIf {
            UpdateMirrors.isGitHubUrl(it)
        }
        val sha256 = firstString(root, "sha256", "apk_sha256")?.lowercase()?.takeIf { SHA256.matches(it) }
        // A manifest without any digest cannot be verified, so it is not a usable manifest.
        if (sha256 == null && checksums == null) return null
        return UpdateRelease(tagName = tag, apkName = apkName, apkUrl = apkUrl, checksumsUrl = checksums.orEmpty(), sha256 = sha256)
    }

    private fun firstString(root: JSONObject, vararg names: String): String? {
        for (name in names) {
            val value = root.optString(name, "")
            if (value.isNotBlank()) return value.trim()
        }
        return null
    }

    private val APK_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")
    private val SHA256 = Regex("[0-9a-f]{64}")
}
