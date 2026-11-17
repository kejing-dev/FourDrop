package io.embodit.fourdrop.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Geometry (acceptance M6, design spatialLayout.targets + occlusionCheck): every box inside the volume, column / tray /
 * handle targets ≥ 2° at 1.75 m with gaps ≥ max(1.5 cm, 0.3 × size), the disc fits its column and the plate gap, the
 * floor opens fully, the plaques are never hidden from the six seated eyes (D35), plaque text ≥ 2 cm.
 */
class LayoutTest {
    private val volume = Box3(V3.ZERO, Layout.VOLUME)
    private val report = StringBuilder()

    @Test fun everyBoxIsInsideTheVolume() {
        for (pull in listOf(0f, 0.05f, Layout.SLIDE_MAX)) for ((name, b) in Layout.solids(pull)) assertTrue("$name @ pull $pull: ${b.min} … ${b.max}", volume.contains(b))
        for (c in 0 until Rules.COLS) assertTrue("column $c target", volume.contains(Layout.columnTarget(c)))
        for (s in Seat.entries) { assertTrue(volume.contains(Layout.trayTarget(s))); assertTrue(volume.contains(Layout.winsPlaque(s))) }
        assertTrue(volume.contains(Layout.SEAT_PLAQUE))
        assertTrue(volume.contains(Layout.handleTarget(Layout.SLIDE_MAX)))
        // a carried disc stays inside too
        for (x in listOf(-1f, 0f, 1f)) for (y in listOf(-1f, 1f)) {
            val (gx, gy) = Layout.clampGrab(x, y)
            assertTrue("grab ($gx, $gy)", volume.contains(Box3(V3(gx, gy, 0f), V3(Layout.DISC_D, Layout.DISC_D, Layout.DISC_T))))
        }
        // every tray slot of both seats
        for (s in Seat.entries) for (i in 0 until Rules.DISCS_PER_SEAT) assertTrue(volume.contains(Box3(Layout.traySlot(s, i), V3(Layout.DISC_D, Layout.DISC_T, Layout.DISC_D))))
    }

    @Test fun targetsAreAtLeastTwoDegreesWithRoomBetween() {
        val col = Layout.COLUMN_TARGET.x
        val colDeg = Layout.degrees(col)
        val gap = Layout.COL_PITCH - col
        report.append("column target ${"%.4f".format(col)} m = ${"%.2f".format(colDeg)}°, gap ${"%.4f".format(gap)} m\n")
        assertTrue("column ≥ 4 cm", col >= 0.04f)
        assertTrue("column ≥ 2° (got $colDeg)", colDeg >= 2.0)
        assertTrue("gap ≥ 1.5 cm and ≥ 0.3 × target", gap >= 0.015f && gap >= 0.3f * col - 1e-4f)
        val tray = Layout.trayTarget(Seat.RED).size
        val trayDeg = Layout.degrees(minOf(tray.x, tray.y))
        report.append("tray target ${tray.x} × ${tray.y} × ${tray.z} m, short side ${"%.2f".format(trayDeg)}°\n")
        assertTrue(trayDeg >= 2.0)
        val handle = Layout.HANDLE_TARGET
        val hDeg = Layout.degrees(minOf(handle.x, handle.y))
        report.append("handle target short side ${"%.2f".format(hDeg)}°\n")
        assertTrue(hDeg >= 2.0)
        // tray ↔ column 7 and handle ↔ columns
        val trayGap = (Layout.trayTarget(Seat.YELLOW).min.x - Layout.columnTarget(6).max.x)
        val handleGap = Layout.columnTarget(3).min.y - Layout.handleTarget(0f).max.y
        report.append("tray–column gap ${"%.3f".format(trayGap)} m, handle–column gap ${"%.3f".format(handleGap)} m\n")
        assertTrue(trayGap >= 0.015f && handleGap >= 0.015f)
        File("build/reports").mkdirs(); File("build/reports/targets.txt").writeText(report.toString())
    }

    @Test fun theDiscFitsItsColumnAndShowsThroughTheHoles() {
        assertTrue("column clear width ${Layout.COLUMN_CLEAR} > disc", Layout.COLUMN_CLEAR > Layout.DISC_D)
        assertTrue("plate gap ${Layout.PLATE_GAP} > disc thickness", Layout.PLATE_GAP > Layout.DISC_T)
        assertTrue("the hole edge holds the disc", Layout.HOLE_D < Layout.DISC_D)
        assertTrue("rows stack without overlap", Layout.ROW_PITCH >= Layout.DISC_D - 1e-4f)
        assertEquals("the bottom row sits on the slide", Layout.SLIDE_Y1, Layout.rowY(0) - Layout.DISC_R, 1e-3f)
        assertTrue("the top row stays under the band", Layout.rowY(Rules.ROWS - 1) + Layout.DISC_R <= Layout.BAND_Y0 + 1e-4f)
        assertTrue("the hovering disc clears the band", Layout.LIFT_Y - Layout.DISC_R > Layout.RACK_TOP)
        // pulled 10 cm, the slide's back edge is in front of the front plate: every column bottom is open
        assertTrue(Layout.slide(Layout.SLIDE_MAX).min.z > Layout.PLATE_Z0 + Layout.PLATE_T)
        // the gravity curve: bottom row 0.55 s, top row 0.26 s (design mustHave.spatialOnly)
        assertEquals(550.0, Motion.fallMs(Layout.LIFT_Y - Layout.rowY(0)).toDouble(), 15.0)
        assertEquals(260.0, Motion.fallMs(Layout.LIFT_Y - Layout.rowY(5)).toDouble(), 15.0)
    }

    /** Segment from [a] to [b] passes through [box] (slab test), ignoring the last [skipEnd] of the way (the plaque itself). */
    private fun hits(a: V3, b: V3, box: Box3, skipEnd: Float = 1e-3f): Boolean {
        var t0 = 0f; var t1 = 1f - skipEnd
        val d = b - a
        val mins = floatArrayOf(box.min.x, box.min.y, box.min.z); val maxs = floatArrayOf(box.max.x, box.max.y, box.max.z)
        val o = floatArrayOf(a.x, a.y, a.z); val dv = floatArrayOf(d.x, d.y, d.z)
        for (k in 0..2) {
            if (abs(dv[k]) < 1e-9f) { if (o[k] < mins[k] || o[k] > maxs[k]) return false; continue }
            var ta = (mins[k] - o[k]) / dv[k]; var tb = (maxs[k] - o[k]) / dv[k]
            if (ta > tb) { val t = ta; ta = tb; tb = t }
            t0 = maxOf(t0, ta); t1 = minOf(t1, tb)
            if (t0 > t1) return false
        }
        return true
    }

    @Test fun plaquesAreNeverHidden() {
        val plaques = listOf("seat" to Layout.SEAT_PLAQUE) + Seat.entries.map { "count$it" to Layout.winsPlaque(it) }
        val out = StringBuilder("D35 plaques × boxes × eyes (slide 0 / 5 / 10 cm): ")
        var checks = 0
        for (pull in listOf(0f, 0.05f, Layout.SLIDE_MAX)) {
            val boxes = Layout.solids(pull).filter { it.first != "base" } + ("base" to Layout.BASE)
            for ((pn, p) in plaques) {
                val pts = listOf(p.centre, V3(p.min.x, p.min.y, p.centre.z), V3(p.max.x, p.min.y, p.centre.z), V3(p.min.x, p.max.y, p.centre.z), V3(p.max.x, p.max.y, p.centre.z))
                for (eye in Layout.EYES) for (pt in pts) for ((bn, b) in boxes) {
                    checks++
                    assertTrue("$pn hidden by $bn from $eye at pull $pull (point $pt)", !hits(eye, pt, b))
                }
            }
        }
        out.append("$checks rays, 0 blocked\n")
        File("build/reports").mkdirs(); File("build/reports/d35.txt").writeText(out.toString())
    }

    @Test fun plaqueTextIsTallEnough() {
        assertTrue(Layout.PLAQUE_TEXT_ZH >= 0.02f && Layout.PLAQUE_TEXT_EN >= 0.02f)
        // 1.75 m: 2.0 cm ≈ 0.65°, above the 0.5° floor for 3D text (D13)
        assertTrue(Layout.degrees(Layout.PLAQUE_TEXT_EN) >= 0.5)
        assertTrue("plaque text fits the plaque", Layout.PLAQUE_TEXT_ZH < Layout.SEAT_PLAQUE.size.y)
    }

    @Test fun carryingSnapsWithHysteresis() {
        // over the rack the disc never dips into it
        assertEquals(Layout.LIFT_Y, Layout.clampGrab(0f, -0.2f).second, 1e-6f)
        // beside the rack it can go down to the tray
        assertTrue(Layout.clampGrab(Layout.GRAB_X_MAX, -0.2f).second < -0.19f)
        val x2 = Layout.colX(2)
        assertEquals(2, Layout.snapColumn(x2 + 0.02f, 0.30f, null))
        assertNull("too far sideways", Layout.snapColumn(x2 + 0.035f, 0.30f, null))
        assertNull("too low", Layout.snapColumn(x2, 0.20f, null))
        assertEquals("held until 4.8 cm away", 2, Layout.snapColumn(x2 + 0.045f, 0.30f, 2))
        assertEquals("held down to 18 cm", 2, Layout.snapColumn(x2, 0.19f, 2))
        assertNull(Layout.snapColumn(x2, 0.17f, 2))
    }

    @Test fun traySlotsEmptyFromTheBackTop() {
        val first = Layout.traySlot(Seat.RED, 0)
        val last = Layout.traySlot(Seat.RED, 20)
        assertEquals(Layout.STACK_Z[2], first.z, 1e-6f)
        assertTrue(first.y > last.y)
        assertEquals(Layout.STACK_Z[0], last.z, 1e-6f)
        assertTrue(Layout.traySlot(Seat.RED, 0).x < 0 && Layout.traySlot(Seat.YELLOW, 0).x > 0)
        assertEquals(42, Seat.entries.flatMap { s -> (0 until 21).map { Layout.traySlot(s, it) } }.toSet().size)
    }

    @Test fun volumeScaleFollowsTheResize() {
        for (k in listOf(0.5f, 1f, 3f)) {
            val measured = Layout.VOLUME.x * k
            val got = VolumeScale.fromWidth(measured)
            assertEquals(k, got, 1e-4f)
            // a hand that moves 6 cm × k on screen moves the disc 6 cm of content: under the hand at every scale
            assertEquals(0.06f, VolumeScale.toContent(0.06f * k, got), 1e-5f)
        }
        assertEquals("not measured yet", 1f, VolumeScale.fromWidth(0f), 0f)
        assertEquals("a few mm off at 1×", 1f, VolumeScale.fromWidth(0.903f), 0f)
    }
}
