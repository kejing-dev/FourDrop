package io.embodit.fourdrop.domain

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The analytic motion curves (design motion[], budget.perf: no physics engine; y(t) = y0 − ½ g t², every frame only
 * sets transforms). Times are milliseconds since the motion started; the scene reads them each frame and the flow uses
 * the same numbers to time sounds and phase changes, so what you hear lands on the frame you see.
 */
object Motion {
    /** Slowed gravity so the fall is readable: bottom row 0.55 s, top row 0.26 s (design mustHave.spatialOnly). */
    const val G = 3.3f
    const val BOUNCE_M = 0.003f
    const val BOUNCE_MS = 60L
    const val LIFT_MS = 220L
    const val MOVE_MS = 150L
    const val AI_HOVER_MS = 300L
    const val SHAKE_MS = 240L
    const val SHAKE_M = 0.006f
    const val SLIDE_BACK_MS = 180L
    const val WINBAR_MS = 350L
    const val TO_TRAY_MS = 300L
    const val REDUCED_DROP_MS = 120L
    const val DUMP_COLUMN_STAGGER = 30L
    const val DUMP_ROW_STAGGER = 15L
    const val DUMP_RETURN_MS = 120L
    const val DUMP_RETURN_SPAN = 400L
    /** Where a dumped disc disappears: inside the base, under its top slot. */
    val DUMP_FLOOR_Y = Layout.BASE_TOP - 0.03f

    fun fallMs(distance: Float): Long = (sqrt(2 * distance.coerceAtLeast(0f) / G) * 1000).toLong()

    /** Full drop duration from height [y0] into row [row] (fall + 3 mm bounce), or the reduced-motion 120 ms. */
    fun dropMs(y0: Float, row: Int, reduced: Boolean): Long = if (reduced) REDUCED_DROP_MS else fallMs(y0 - Layout.rowY(row)) + BOUNCE_MS

    /** Height of a dropping disc [t] ms after release from [y0] into [row]. */
    fun dropY(y0: Float, row: Int, t: Long, reduced: Boolean): Float {
        val yEnd = Layout.rowY(row)
        if (reduced) return if (t >= REDUCED_DROP_MS) yEnd else y0 + (yEnd - y0) * (t.toFloat() / REDUCED_DROP_MS)
        val fall = fallMs(y0 - yEnd)
        if (t < fall) { val s = t / 1000f; return y0 - 0.5f * G * s * s }
        val b = (t - fall).coerceAtMost(BOUNCE_MS)
        return yEnd + BOUNCE_M * sin(PI.toFloat() * b / BOUNCE_MS)
    }

    /**
     * When the falling disc's centre passes each row above its landing row ("哒" per row), as (ms, row) from the top
     * down; the landing sound is at [fallMs]. A row only counts when it is below the release height.
     */
    fun tickTimes(y0: Float, row: Int): List<Pair<Long, Int>> =
        (Rules.ROWS - 1 downTo row + 1).filter { Layout.rowY(it) < y0 }.map { r -> fallMs(y0 - Layout.rowY(r)) to r }

    /** The undo rise: the drop played backwards, then [TO_TRAY_MS] back to the tray. */
    fun undoMs(row: Int, reduced: Boolean): Long = if (reduced) REDUCED_DROP_MS else fallMs(Layout.LIFT_Y - Layout.rowY(row)) + TO_TRAY_MS

    /** Full-column shake offset (2 sine periods, 6 mm). */
    fun shake(t: Long, reduced: Boolean): Float = if (reduced || t >= SHAKE_MS) 0f else SHAKE_M * sin(2 * PI.toFloat() * 2 * t / SHAKE_MS)

    fun easeOutCubic(t: Float): Float { val u = 1f - t.coerceIn(0f, 1f); return 1f - u * u * u }
    fun easeInOutCubic(t: Float): Float { val u = t.coerceIn(0f, 1f); return if (u < 0.5f) 4 * u * u * u else 1 - (-2 * u + 2).let { it * it * it } / 2 }
    fun easeOutBack(t: Float): Float { val u = t.coerceIn(0f, 1f) - 1f; val c = 1.70158f; return 1 + (c + 1) * u * u * u + c * u * u }

    /** One disc of the dump: when it starts falling, when it is gone, when it reappears in its tray. */
    data class DumpDisc(val cell: Cell, val seat: Seat, val trayIndex: Int, val fallStart: Long, val fallEnd: Long, val returnStart: Long)

    /**
     * The dump (design motion 倒子, 1.2 s): the columns fall one after another 30 ms apart, bottom disc first, through the
     * open floor into the base; then the discs reappear in the trays bottom-up, staggered over 400 ms.
     */
    fun dumpPlan(p: Position): List<DumpDisc> {
        val used = IntArray(2)
        val placed = p.moves.mapIndexed { i, c -> Triple(c, p.seatAt(i), used[p.seatAt(i).ordinal]++) }
        val rowOf = IntArray(Rules.COLS)
        val discs = placed.map { (c, seat, k) -> Triple(Cell(c, rowOf[c]++), seat, k) }
        val falls = discs.map { (cell, _, _) ->
            val start = cell.col * DUMP_COLUMN_STAGGER + cell.row * DUMP_ROW_STAGGER
            start to start + fallMs(Layout.rowY(cell.row) - DUMP_FLOOR_Y)
        }
        val returnAt = (falls.maxOfOrNull { it.second } ?: 0L)
        val n = discs.size
        val stagger = if (n == 0) 0L else minOf(40L, DUMP_RETURN_SPAN / n)
        // reappear bottom-up: the last disc used (lowest tray slot) first
        val order = discs.indices.sortedByDescending { discs[it].third }
        val back = LongArray(n)
        order.forEachIndexed { k, i -> back[i] = returnAt + k * stagger }
        return discs.mapIndexed { i, (cell, seat, k) -> DumpDisc(cell, seat, k, falls[i].first, falls[i].second, back[i]) }
    }

    fun dumpMs(plan: List<DumpDisc>, reduced: Boolean): Long =
        if (reduced || plan.isEmpty()) 0L else plan.maxOf { it.returnStart } + DUMP_RETURN_MS + SLIDE_BACK_MS
}
