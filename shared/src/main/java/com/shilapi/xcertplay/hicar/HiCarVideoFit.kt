package com.shilapi.xcertplay.hicar

/**
 * Fits the phone's picture into whatever rectangle the window has, without changing its shape.
 *
 * A `TextureView` stretched to fill its parent is the default behaviour of every naive mirror, and
 * it is wrong twice: a 16:9 desktop drawn into a 5:3 panel is visibly squashed, and every touch the
 * driver aims at an icon lands somewhere else, because the mapping uses the view's size rather than
 * the picture's. So the window is sized to the largest rectangle *with the stream's aspect ratio*
 * that fits, and the rest of the parent stays black (letterbox bars).
 *
 * Pure arithmetic, kept out of the Compose layer so it can be asserted in a unit test rather than
 * eyeballed on a head unit that is not in reach.
 */
object HiCarVideoFit {
    /** Aspect ratio used when a stream has not reported one yet: the 16:9 of a 1080p phone. */
    const val DEFAULT_ASPECT: Float = 16f / 9f

    /** Nothing wider or taller than this is believed; a decoder glitch must not flatten the window. */
    private const val MIN_ASPECT = 0.5f
    private const val MAX_ASPECT = 3.5f

    /** A rectangle in the same units as the container it was fitted into. */
    data class Fit(val width: Float, val height: Float) {
        val isEmpty: Boolean get() = width <= 0f || height <= 0f
    }

    /**
     * The largest rectangle of [videoAspect] inside [containerWidth] × [containerHeight].
     *
     * Width-limited when the container is taller than the picture, height-limited when it is wider.
     * Degenerate containers return an empty fit instead of a negative size: Compose treats a
     * negative constraint as an error, and that would take the whole screen down.
     */
    fun fit(videoAspect: Float, containerWidth: Float, containerHeight: Float): Fit {
        if (!containerWidth.isFinite() || !containerHeight.isFinite()) return Fit(0f, 0f)
        if (containerWidth <= 0f || containerHeight <= 0f) return Fit(0f, 0f)
        val aspect = sanitize(videoAspect)
        val widthIfFullHeight = containerHeight * aspect
        return if (widthIfFullHeight <= containerWidth) {
            Fit(widthIfFullHeight, containerHeight)
        } else {
            Fit(containerWidth, containerWidth / aspect)
        }
    }

    /** The ratio a decoder's output should be shown at, with a fallback rather than a division by 0. */
    fun aspectOf(width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return DEFAULT_ASPECT
        return sanitize(width.toFloat() / height.toFloat())
    }

    private fun sanitize(aspect: Float): Float =
        if (!aspect.isFinite() || aspect < MIN_ASPECT || aspect > MAX_ASPECT) DEFAULT_ASPECT else aspect
}
