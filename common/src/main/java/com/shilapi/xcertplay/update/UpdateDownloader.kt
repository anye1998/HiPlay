package com.shilapi.xcertplay.update

import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLConnection

/**
 * Downloads a release APK, moving to the next mirror whenever a source fails or serves bad bytes.
 *
 * Order is fixed in [UpdateMirrors.DOWNLOAD_ORDER]: direct GitHub first, because it is the only
 * source that cannot be stale, then the proxies. Each failure is recorded with its reason, and a
 * digest mismatch is never retried against the same source: it means the bytes are wrong, not that
 * the attempt was unlucky.
 *
 * A release without any digest (inline or a `SHA256SUMS.txt` asset) is refused. The alternative —
 * installing an APK that nothing vouches for — is exactly what the verification step exists to
 * prevent.
 */
internal object UpdateDownloader {
    class DownloadFailure(val attempts: List<UpdateAttempt>) : IOException(
        attempts.joinToString(prefix = "Update download failed: ", separator = "; ") { it.toString() },
    )

    fun download(
        release: UpdateRelease,
        directory: File,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
        openConnection: (URL) -> URLConnection = { it.openConnection() },
        onAttempt: (UpdateAttempt) -> Unit = {},
    ): File {
        val attempts = ArrayList<UpdateAttempt>()
        fun record(attempt: UpdateAttempt) {
            attempts.add(attempt)
            onAttempt(attempt)
        }

        if (release.sha256 == null && release.checksumsUrl.isEmpty()) {
            record(UpdateAttempt("checksums", "release publishes no digest"))
            throw DownloadFailure(attempts)
        }
        for (mirror in UpdateMirrors.DOWNLOAD_ORDER) {
            val url = mirror.rewrite(release.apkUrl)
            if (url == null) {
                record(UpdateAttempt(mirror.id, "not a GitHub asset URL"))
                continue
            }
            val expected = try {
                expectedDigest(release, mirror, openConnection)
            } catch (failure: IOException) {
                record(UpdateAttempt(mirror.id, "checksums unavailable: ${failure.message}"))
                continue
            }
            if (expected == null) {
                record(UpdateAttempt(mirror.id, "no digest for ${release.apkName}"))
                continue
            }
            val target = File(directory, release.apkName)
            try {
                UpdateClient.download(url, target, openConnection, onProgress)
                val actual = UpdateChecksums.sha256Hex(target)
                if (!actual.equals(expected, ignoreCase = true)) {
                    target.delete()
                    record(UpdateAttempt(mirror.id, "SHA-256 mismatch, rejected"))
                    continue
                }
                record(UpdateAttempt(mirror.id, "ok"))
                return target
            } catch (failure: IOException) {
                target.delete()
                record(UpdateAttempt(mirror.id, "download failed: ${failure.message}"))
            } catch (failure: RuntimeException) {
                target.delete()
                record(UpdateAttempt(mirror.id, "download failed: ${failure.javaClass.simpleName}"))
            }
        }
        throw DownloadFailure(attempts)
    }

    private fun expectedDigest(
        release: UpdateRelease,
        mirror: UpdateMirror,
        openConnection: (URL) -> URLConnection,
    ): String? {
        release.sha256?.let { return it }
        val checksumsUrl = mirror.rewrite(release.checksumsUrl) ?: return null
        val text = UpdateClient.fetchText(checksumsUrl, "text/plain", "DiPlay/update", openConnection)
        return UpdateChecksums.parse(text)[release.apkName]
    }
}
