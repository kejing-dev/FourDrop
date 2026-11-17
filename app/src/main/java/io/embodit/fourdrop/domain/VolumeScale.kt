package io.embodit.fourdrop.domain

/**
 * The volume follows the system's uniform resize (manifest resizerestriction = 1; build.md 「体积可缩放就要跟着缩放」,
 * wind-chime / zen-orb pattern): the content root is scaled by k = measured width / 0.90 m, and every hand movement is
 * divided by k before it moves a disc or the slide, so a disc stays under the hand at 0.5×, 1× and 3×.
 */
object VolumeScale {
    const val MIN = 0.25f
    const val MAX = 4f

    /** k from the SpatialView's measured width in metres (≤ 0 or NaN = not measured yet → 1). */
    fun fromWidth(widthM: Float): Float {
        if (!widthM.isFinite() || widthM <= 0f) return 1f
        val k = widthM / Layout.VOLUME.x
        // the first measurement can be a few mm off the manifest size: treat ±1 % as exactly 1
        return if (kotlin.math.abs(k - 1f) < 0.01f) 1f else k.coerceIn(MIN, MAX)
    }

    /** Physical metres moved by the hand → content metres. */
    fun toContent(physicalM: Float, k: Float): Float = physicalM / k.coerceIn(MIN, MAX)
}
