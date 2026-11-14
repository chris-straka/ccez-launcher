package uk.ccez.launcher

/** Sizes worked out without Android, so unit tests cover them. */
object Sizes {
    /**
     * Each hosted widget's height in dp: its own minimum ([minPx], which
     * Android gives in pixels) as dp, kept between 72 and 220, then all of
     * them scaled down together when they'd take more than [capDp], so the
     * pinned row under them always fits on screen (review 2026-11-14:
     * three tall widgets pushed the dock off the bottom).
     */
    fun widgetHeights(minPx: List<Int>, density: Float, capDp: Float): List<Float> {
        val own = minPx.map { (it / density.coerceAtLeast(0.1f)).coerceIn(72f, 220f) }
        val total = own.sum()
        if (total <= capDp || total <= 0f) return own
        val k = capDp / total
        return own.map { it * k }
    }
}
