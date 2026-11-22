package com.shilapi.xcertplay.hicar

/**
 * The picture geometry a session runs at.
 *
 * Two sizes matter and they are not the same thing:
 *
 * - **offered** — what this receiver tells the phone it can show, in `his_video_formats`. It is
 *   chosen once per session from the area the picture will actually occupy — the panel minus the
 *   status and navigation bars the chosen display mode leaves visible — and 1080p is the ceiling.
 *   Because that area is not necessarily 16:9, the offer follows its shape rather than a fixed step;
 *   a phone that honours it sends a picture that fills the window exactly.
 * - **actual** — what the phone really encodes. It arrives in the decoder's output format and is
 *   authoritative: a phone is free to ignore the offer and send its own shape (many send the
 *   handset's aspect with letterboxing). The window ratio and the touch mapping both follow this
 *   one, which is what makes the picture adaptive rather than stretched.
 *
 * Touch coordinates and `TextureView` pixels are both expressed in the **actual** size, so the only
 * conversion needed is a straight scale between the view and [HiCarDisplaySize]; there are no
 * letterbox offsets to subtract because the window is laid out at the picture's own ratio.
 */
data class HiCarDisplaySize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "display size" }
    }

    val aspect: Float get() = width.toFloat() / height.toFloat()

    companion object {
        const val FULL_HD_WIDTH = 1920
        const val FULL_HD_HEIGHT = 1080

        val FULL_HD = HiCarDisplaySize(FULL_HD_WIDTH, FULL_HD_HEIGHT)

        /**
         * The size every build before 1080p support used.
         *
         * Kept as the last step of [best] and as the default of a sink built without a screen, so a
         * device that reports nothing still lands on a geometry this protocol has been proven at.
         */
        val BASE = HiCarDisplaySize(800, 480)

        /** 16:9 descending, so the offer is the largest one the screen can really show. */
        private val STEPS = listOf(
            FULL_HD,
            HiCarDisplaySize(1600, 900),
            HiCarDisplaySize(1280, 720),
            HiCarDisplaySize(960, 540),
            BASE,
        )

        /**
         * The largest step that fits [screenWidth]×[screenHeight], or [BASE] when none does.
         *
         * Judged against the screen rather than the window: the window is laid out from this ratio
         * afterwards, so a screen with a smaller inset still shows the full picture.
         */
        fun best(screenWidth: Int, screenHeight: Int): HiCarDisplaySize =
            STEPS.firstOrNull { it.width <= screenWidth && it.height <= screenHeight } ?: BASE

        /**
         * The area itself, scaled down to fit [maxWidth]×[maxHeight] while keeping its shape.
         *
         * Used when the display mode leaves a status or navigation bar on screen: the area that is
         * really available is not 16:9 any more, and offering a 16:9 step would make the phone encode
         * a picture that has to be letterboxed back into a slightly different rectangle. A phone that
         * honours this offer sends exactly the window's shape, so nothing is wasted on black bars.
         *
         * Dimensions are rounded down to even numbers because H.264 encoders reject an odd width or
         * height, and clamped so a degenerate inset (a bar taller than the screen) still yields a
         * usable size rather than an exception.
         */
        fun fitTo(width: Int, height: Int, maxWidth: Int, maxHeight: Int): HiCarDisplaySize {
            if (width <= 0 || height <= 0 || maxWidth <= 0 || maxHeight <= 0) return BASE
            val limit = minOf(
                1.0,
                maxWidth.toDouble() / width,
                maxHeight.toDouble() / height,
            )
            val scaledWidth = (width * limit).toInt().coerceAtLeast(2) and 1.inv()
            val scaledHeight = (height * limit).toInt().coerceAtLeast(2) and 1.inv()
            return HiCarDisplaySize(scaledWidth, scaledHeight)
        }

        /**
         * The render density that keeps UI elements the size they were at 800×480.
         *
         * Android derives dp from px and dpi, so handing the phone 1920 pixels at 160 dpi describes
         * a 1920 dp wide screen - every icon and line of text would come back 2.4 times smaller.
         * Scaling dpi with the width keeps the layout identical and only sharpens it.
         */
        fun densityFor(width: Int): Int = (160L * width / BASE.width).toInt().coerceIn(160, 640)
    }
}
