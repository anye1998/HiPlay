package com.shilapi.xcertplay.update

/**
 * Semantic version comparison, independent of how a release happens to be named.
 *
 * A tag such as `v0.2.15`, `0.2.15` or `DiPlay 0.2.15` all parse to the same version, because the
 * number is looked up inside the string instead of the whole string being matched. Pre-release
 * identifiers follow SemVer 2.0: `0.2.15-beta.1` sorts before `0.2.15`, and identifiers compare
 * numerically when both sides are numbers, otherwise lexically.
 */
internal object UpdateVersion {
    internal data class SemanticVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val preRelease: List<String> = emptyList(),
    ) {
        val isPreRelease: Boolean get() = preRelease.isNotEmpty()
    }

    // A version token, not a name pattern: text around it is allowed, a longer number is not.
    private val VERSION = Regex(
        "(?<![0-9A-Za-z])v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z][0-9A-Za-z.-]*))?(?:\\+[0-9A-Za-z.-]+)?",
    )

    internal fun parse(value: String): SemanticVersion? {
        val match = VERSION.find(value) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        val patch = match.groupValues[3].toIntOrNull() ?: return null
        val preRelease = match.groupValues[4]
            .takeIf { it.isNotEmpty() }
            ?.split('.')
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        return SemanticVersion(major, minor, patch, preRelease)
    }

    internal fun isNewer(remote: String, installed: String): Boolean {
        val target = parse(remote) ?: return false
        val current = parse(installed) ?: return true
        return compare(target, current) > 0
    }

    internal fun compare(left: SemanticVersion, right: SemanticVersion): Int {
        if (left.major != right.major) return left.major.compareTo(right.major)
        if (left.minor != right.minor) return left.minor.compareTo(right.minor)
        if (left.patch != right.patch) return left.patch.compareTo(right.patch)
        // A release outranks any pre-release of the same numbers (SemVer 2.0 section 11.3).
        if (left.preRelease.isEmpty() && right.preRelease.isEmpty()) return 0
        if (left.preRelease.isEmpty()) return 1
        if (right.preRelease.isEmpty()) return -1
        for (index in 0 until minOf(left.preRelease.size, right.preRelease.size)) {
            val result = compareIdentifier(left.preRelease[index], right.preRelease[index])
            if (result != 0) return result
        }
        return left.preRelease.size.compareTo(right.preRelease.size)
    }

    private fun compareIdentifier(left: String, right: String): Int {
        val leftNumber = left.toIntOrNull()
        val rightNumber = right.toIntOrNull()
        return when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            // Numeric identifiers always have lower precedence than alphanumeric ones.
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> left.compareTo(right)
        }
    }
}
