package io.embodit.fourdrop.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** A small pure 3-vector (metres, volume axes: +X right, +Y up, +Z toward the user). */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    val length: Float get() = sqrt(x * x + y * y + z * z)
    fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)

    companion object { val ZERO = V3(0f, 0f, 0f) }
}

/** An axis-aligned box (centre + full size), for colliders and the occlusion / bounds tests. */
data class Box3(val centre: V3, val size: V3) {
    val min: V3 get() = centre - size * 0.5f
    val max: V3 get() = centre + size * 0.5f
    fun contains(o: Box3, eps: Float = 1e-4f) = o.min.x >= min.x - eps && o.min.y >= min.y - eps && o.min.z >= min.z - eps &&
        o.max.x <= max.x + eps && o.max.y <= max.y + eps && o.max.z <= max.z + eps

    companion object {
        fun span(x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float) =
            Box3(V3((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2), V3(abs(x1 - x0), abs(y1 - y0), abs(z1 - z0)))
    }
}

/**
 * Every metre of the volume, from design spatialLayout.coordinates (all derived from the column pitch 8.0 cm and the
 * row pitch 7.6 cm). Origin = volume centre. The geometry unit tests (LayoutTest) check these against the design's
 * targets (≥ 2° at 1.75 m, gaps, discs fit, every box inside the volume) and the D35 plaque occlusion.
 */
object Layout {
    val VOLUME = V3(0.90f, 0.76f, 0.40f)
    val HALF = VOLUME * 0.5f

    const val COL_PITCH = 0.080f
    const val ROW_PITCH = 0.076f
    const val ROW0_Y = -0.194f
    fun colX(c: Int): Float = (c - 3) * COL_PITCH
    fun rowY(r: Int): Float = ROW0_Y + r * ROW_PITCH
    fun cell(c: Int, r: Int) = V3(colX(c), rowY(r), 0f)

    // ---- discs: Ø 7.6 × 1.3 cm, 2 mm chamfer
    const val DISC_D = 0.076f
    const val DISC_R = DISC_D / 2
    const val DISC_T = 0.013f
    const val DISC_CHAMFER = 0.002f

    // ---- base 0.84 × 0.05 × 0.26, top y −0.31, front face z +0.13
    const val BASE_W = 0.84f
    const val BASE_H = 0.05f
    const val BASE_D = 0.26f
    const val BASE_TOP = -0.31f
    val BASE = Box3.span(-BASE_W / 2, BASE_W / 2, BASE_TOP - BASE_H, BASE_TOP, -BASE_D / 2, BASE_D / 2)
    /** The slot in the base top under the columns (dumped discs fall through it). */
    const val BASE_SLOT_W = 0.58f
    const val BASE_SLOT_D = 0.036f

    // ---- posts x ±0.292, 0.024 × 0.034, from the base top to +0.248
    const val POST_X = 0.292f
    const val POST_W = 0.024f
    const val POST_D = 0.034f
    const val RACK_TOP = 0.248f
    fun post(side: Int) = Box3.span(side * POST_X - POST_W / 2, side * POST_X + POST_W / 2, BASE_TOP, RACK_TOP, -POST_D / 2, POST_D / 2)

    // ---- plates: 0.56 wide, 4 mm thick, y −0.232…+0.248; front z +0.0085…+0.0125; 42 holes Ø 6.4 cm
    const val PLATE_W = 0.56f
    const val PLATE_T = 0.004f
    const val PLATE_Y0 = -0.232f
    const val PLATE_Z0 = 0.0085f
    const val HOLE_D = 0.064f
    fun plate(front: Boolean): Box3 { val s = if (front) 1f else -1f; return Box3.span(-PLATE_W / 2, PLATE_W / 2, PLATE_Y0, RACK_TOP, s * PLATE_Z0, s * (PLATE_Z0 + PLATE_T)) }
    /** Clear gap between the plates (the disc is 1.3 cm thick). */
    const val PLATE_GAP = 2 * PLATE_Z0

    // ---- ribs: 8 × 2 mm, column clear width 7.8 cm
    const val RIB_T = 0.002f
    fun ribX(k: Int): Float = (k - 3.5f) * COL_PITCH
    const val COLUMN_CLEAR = COL_PITCH - RIB_T

    // ---- slide (floor of the rack): 0.56 × 0.014 × 0.030, y −0.246…−0.232, pulls +Z up to 0.10
    const val SLIDE_W = 0.56f
    const val SLIDE_Y0 = -0.246f
    const val SLIDE_Y1 = -0.232f
    const val SLIDE_D = 0.030f
    const val SLIDE_MAX = 0.10f
    const val SLIDE_DUMP_EMPTY = 0.05f
    const val SLIDE_DUMP_GAME = 0.098f
    const val SLIDE_DETENT = 0.05f
    /** The detent holds the slide at 5 cm until the hand pulls 1.5 cm further (design input.slide 「一格卡位」). */
    const val SLIDE_DETENT_HOLD = 0.015f
    fun slide(pull: Float) = Box3.span(-SLIDE_W / 2, SLIDE_W / 2, SLIDE_Y0, SLIDE_Y1, -SLIDE_D / 2 + pull, SLIDE_D / 2 + pull)

    // ---- handle under the slide's front edge: 0.14 × 0.05 × 0.012, y −0.296…−0.246, z +0.015…+0.027; collider 0.14 × 0.062 × 0.05
    const val HANDLE_W = 0.14f
    const val HANDLE_Y0 = -0.296f
    const val HANDLE_Y1 = -0.246f
    const val HANDLE_Z0 = 0.015f
    const val HANDLE_Z1 = 0.027f
    fun handle(pull: Float) = Box3.span(-HANDLE_W / 2, HANDLE_W / 2, HANDLE_Y0, HANDLE_Y1, HANDLE_Z0 + pull, HANDLE_Z1 + pull)
    val HANDLE_TARGET = V3(0.14f, 0.062f, 0.05f)
    fun handleTarget(pull: Float) = Box3(V3(0f, (HANDLE_Y0 + HANDLE_Y1) / 2, (HANDLE_Z0 + HANDLE_Z1) / 2 + pull), HANDLE_TARGET)

    // ---- column-mouth band +0.224…+0.248, a 6.15 × 2 cm plate per column (visible marker with the number)
    const val BAND_Y0 = 0.224f
    const val MOUTH_W = 0.0615f
    const val MOUTH_H = 0.020f
    val MOUTH_Y = (BAND_Y0 + RACK_TOP) / 2
    const val MOUTH_Z = PLATE_Z0 + PLATE_T + 0.0008f
    /** Column targets: 6.15 × 56 × 4 cm, y −0.214…+0.346. */
    val COLUMN_TARGET = V3(MOUTH_W, 0.56f, 0.04f)
    const val COLUMN_Y0 = -0.214f
    const val COLUMN_Y1 = 0.346f
    fun columnTarget(c: Int) = Box3(V3(colX(c), (COLUMN_Y0 + COLUMN_Y1) / 2, 0f), COLUMN_TARGET)

    // ---- trays: 0.09 × 0.012 × 0.25 at x ±0.365 (red left, yellow right), on the base top; 3 stacks × 7 flat discs
    const val TRAY_X = 0.365f
    const val TRAY_W = 0.09f
    const val TRAY_H = 0.012f
    const val TRAY_D = 0.25f
    val STACK_Z = floatArrayOf(0.082f, 0f, -0.082f)
    const val STACK = 7
    fun traySide(seat: Seat): Float = if (seat == Seat.RED) -1f else 1f
    fun tray(seat: Seat) = Box3.span(traySide(seat) * TRAY_X - TRAY_W / 2, traySide(seat) * TRAY_X + TRAY_W / 2, BASE_TOP, BASE_TOP + TRAY_H, -TRAY_D / 2, TRAY_D / 2)
    /** The whole own tray is the tap / grab target: 9 × 10.5 × 25 cm. */
    fun trayTarget(seat: Seat) = Box3.span(traySide(seat) * TRAY_X - TRAY_W / 2, traySide(seat) * TRAY_X + TRAY_W / 2, BASE_TOP, BASE_TOP + 0.105f, -TRAY_D / 2, TRAY_D / 2)

    /**
     * Where disc [i] (0..20) of [seat] lies in its tray: slot 20 − i, so the first disc used comes off the top of the back
     * stack and a tray empties back-to-front, top-down (a disc never moves when another one is taken).
     */
    fun traySlot(seat: Seat, i: Int): V3 {
        val slot = Rules.DISCS_PER_SEAT - 1 - i
        val stack = slot / STACK
        val level = slot % STACK
        return V3(traySide(seat) * TRAY_X, BASE_TOP + TRAY_H + DISC_T / 2 + level * DISC_T, STACK_Z[stack])
    }

    // ---- the lifted disc: centre y +0.298 over its column; drag reaches up to +0.335
    const val LIFT_Y = 0.298f
    const val GRAB_Y_MAX = 0.335f
    const val GRAB_Y_MIN = -0.25f
    const val GRAB_X_MAX = 0.41f
    /** Over the rack (|x| ≤ 0.345) a carried disc stays at or above the hover height, so it never cuts into the rack. */
    const val RACK_GUARD_X = 0.345f
    const val SNAP_Y = 0.215f
    const val SNAP_DX = 0.030f
    const val UNSNAP_DX = 0.048f
    const val UNSNAP_Y = 0.18f

    // ---- light bar on the front plate, plaques on the base front face
    const val WINBAR_Z = 0.017f
    const val WINBAR_W = 0.008f
    const val RING_Z = PLATE_Z0 + PLATE_T + 0.0015f
    const val PLAQUE_Z = BASE_D / 2 + 0.001f
    val SEAT_PLAQUE = Box3(V3(0f, -0.335f, PLAQUE_Z), V3(0.44f, 0.04f, 0.001f))
    fun winsPlaque(seat: Seat) = Box3(V3(traySide(seat) * 0.34f, -0.335f, PLAQUE_Z), V3(0.10f, 0.04f, 0.001f))
    /** Plaque text height: 2.2 cm (zh) / 2.0 cm (en); the column numbers 1.6 cm (a scale mark, design deviations). */
    const val PLAQUE_TEXT_ZH = 0.022f
    const val PLAQUE_TEXT_EN = 0.020f
    const val MOUTH_TEXT = 0.016f

    // ---- the system's default placement and the seated eye positions (design occlusionCheck.eye)
    const val VIEW_DISTANCE = 1.75f

    /** 6 eyes: above the volume centre by 0.15 / 0.45 m, 1.2 / 2.0 m ahead, straight and ±30°. */
    val EYES: List<V3> = buildList {
        for (h in listOf(0.15f, 0.45f)) for (d in listOf(1.2f, 2.0f)) for (a in listOf(-30.0, 0.0, 30.0)) {
            val r = Math.toRadians(a)
            add(V3((d * kotlin.math.sin(r)).toFloat(), h, (d * kotlin.math.cos(r)).toFloat()))
        }
    }

    /** Angle (degrees) a width [w] subtends at [d] metres. */
    fun degrees(w: Float, d: Float = VIEW_DISTANCE): Double = Math.toDegrees(2 * atan2(w / 2.0, d.toDouble()))

    /** All the solid boxes inside the volume with the slide at [pull] (bounds + occlusion tests). */
    fun solids(pull: Float = 0f): List<Pair<String, Box3>> = buildList {
        add("base" to BASE)
        add("postL" to post(-1)); add("postR" to post(1))
        add("plateF" to plate(true)); add("plateB" to plate(false))
        add("slide" to slide(pull)); add("handle" to handle(pull))
        for (s in Seat.entries) {
            add("tray$s" to tray(s))
            add("stacks$s" to Box3.span(traySide(s) * TRAY_X - DISC_R, traySide(s) * TRAY_X + DISC_R, BASE_TOP + TRAY_H, BASE_TOP + TRAY_H + STACK * DISC_T, STACK_Z[2] - DISC_R, STACK_Z[0] + DISC_R))
        }
        add("lifted" to Box3(V3(colX(6), LIFT_Y, 0f), V3(DISC_D, DISC_D, DISC_T)))
    }

    /** The disc carried by hand at ([x], [y]) on the rack plane, clamped by the guard rails (design input.grab). */
    fun clampGrab(x: Float, y: Float): Pair<Float, Float> {
        val cx = x.coerceIn(-GRAB_X_MAX, GRAB_X_MAX)
        val ax = abs(cx)
        val floor = when {
            ax <= RACK_GUARD_X -> LIFT_Y
            else -> { val t = (ax - RACK_GUARD_X) / (GRAB_X_MAX - RACK_GUARD_X); val s = t * t * (3 - 2 * t); LIFT_Y + (GRAB_Y_MIN - LIFT_Y) * s }
        }
        return cx to y.coerceIn(floor, GRAB_Y_MAX)
    }

    /** Snap with hysteresis (design input.grab): the column the carried disc is held over, or null. */
    fun snapColumn(x: Float, y: Float, current: Int?): Int? {
        if (current != null) {
            if (abs(x - colX(current)) <= UNSNAP_DX && y >= UNSNAP_Y) return current
        }
        if (y <= SNAP_Y) return null
        val c = (0 until Rules.COLS).minByOrNull { abs(x - colX(it)) }!!
        return if (abs(x - colX(c)) <= SNAP_DX) c else null
    }
}
