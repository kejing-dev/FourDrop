package io.embodit.fourdrop.ui.board

import io.embodit.fourdrop.domain.Seat
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Disc lacquer per seat and [DiscLook] — pure, so the unit test checks the same numbers the materials are built from.
 * D44 「高亮时棋子本色不变」 / 2026-11-16 「高亮不盖本色」 (QA #1 q1-1): after a four every disc keeps its own colour.
 * The four is marked by the light bar and a warm halo ring round each hole, never by repainting the discs; the rest
 * only lose the faint self-light and drop a hair of brightness, so red still reads red and cream still reads cream.
 */
object DiscFinish {
    const val RED = 0xC8412E
    const val YELLOW = 0xEBD9A8

    /** Brightness of the discs outside the four (CIE76 ΔE vs own colour stays ≤ 5, DiscFinishTest). */
    const val DIM_K = 0.96f
    /** Self-light as a share of the base colour: the four keeps the normal amount (more washed red out to salmon). */
    const val GLOW_NORMAL = 0.05f

    fun own(seat: Seat): Int = if (seat == Seat.RED) RED else YELLOW

    fun base(seat: Seat, look: DiscLook): Int = if (look == DiscLook.DIM) scale(own(seat), DIM_K) else own(seat)

    fun glow(look: DiscLook): Float = if (look == DiscLook.DIM) 0f else GLOW_NORMAL

    fun scale(c: Int, k: Float): Int = listOf(16, 8, 0).fold(0) { acc, sh -> acc or (((((c shr sh) and 255) * k).toInt().coerceIn(0, 255)) shl sh) }

    /** sRGB int → CIE L*a*b* (D65). */
    fun lab(c: Int): DoubleArray {
        fun lin(v: Int): Double { val u = v / 255.0; return if (u <= 0.04045) u / 12.92 else ((u + 0.055) / 1.055).pow(2.4) }
        val r = lin((c shr 16) and 255); val g = lin((c shr 8) and 255); val b = lin(c and 255)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        fun f(t: Double) = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116
        return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }

    /** CIE76 colour difference. */
    fun deltaE(a: Int, b: Int): Double {
        val p = lab(a); val q = lab(b)
        return sqrt((p[0] - q[0]).pow(2) + (p[1] - q[1]).pow(2) + (p[2] - q[2]).pow(2))
    }
}
