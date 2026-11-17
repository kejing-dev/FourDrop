package io.embodit.fourdrop.ui.board

import io.embodit.fourdrop.domain.Aim
import io.embodit.fourdrop.domain.Box3
import io.embodit.fourdrop.domain.Drop
import io.embodit.fourdrop.domain.GameFlow
import io.embodit.fourdrop.domain.GameStore
import io.embodit.fourdrop.domain.Layout
import io.embodit.fourdrop.domain.Motion
import io.embodit.fourdrop.domain.Phase
import io.embodit.fourdrop.domain.PlayState
import io.embodit.fourdrop.domain.Rules
import io.embodit.fourdrop.domain.SavedGame
import io.embodit.fourdrop.domain.Seat
import io.embodit.fourdrop.domain.Settings
import io.embodit.fourdrop.domain.V3
import io.embodit.fourdrop.ui.board.input.Offset3M
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scene's pure parts: where every disc is drawn, and the hit replay (build.md 命中回放, board-input 必带单测). */
class SceneTest {
    private val volume = Box3(V3.ZERO, Layout.VOLUME)

    @Test fun aFreshRackHasAllDiscsInTheTrays() {
        val poses = DiscPoses.all(PlayState(phase = Phase.PLAY), 0, false)
        assertEquals(42, poses.size)
        for ((k, p) in poses.withIndex()) {
            val seat = if (k < 21) Seat.RED else Seat.YELLOW
            assertEquals(Layout.traySlot(seat, k % 21), p.pos)
            assertTrue(p.visible)
        }
    }

    @Test fun placedDiscsSitInTheirCells() {
        val st = PlayState(phase = Phase.PLAY, pos = Rules.fromCode("4435"))
        val poses = DiscPoses.all(st, 0, false)
        assertEquals(Layout.cell(3, 0), poses[DiscPoses.id(Seat.RED, 0)].pos)
        assertEquals(Layout.cell(3, 1), poses[DiscPoses.id(Seat.YELLOW, 0)].pos)
        assertEquals(Layout.cell(2, 0), poses[DiscPoses.id(Seat.RED, 1)].pos)
        assertEquals(Layout.cell(4, 0), poses[DiscPoses.id(Seat.YELLOW, 1)].pos)
        assertEquals(Layout.traySlot(Seat.RED, 2), poses[DiscPoses.id(Seat.RED, 2)].pos)
    }

    @Test fun theAimedDiscHoversOverItsColumnAndTheFallStaysInside() {
        val st = PlayState(phase = Phase.PLAY, aim = Aim(5, null, 0))
        val p = DiscPoses.all(st, 1000, false)[DiscPoses.id(Seat.RED, 0)]
        assertEquals(Layout.colX(5), p.pos.x, 1e-5f); assertEquals(Layout.LIFT_Y, p.pos.y, 1e-5f); assertEquals(0f, p.pos.z, 1e-5f)
        assertEquals(5, DiscPoses.ringColumn(st))
        // the fall: from the hover height down to the bottom row, never below it, ends exactly in the cell
        val pos = Rules.fromCode("6")
        val d = Drop(5, 0, Seat.RED, 0, Layout.colX(5), Layout.LIFT_Y, 0, Motion.dropMs(Layout.LIFT_Y, 0, false))
        val falling = PlayState(phase = Phase.DROPPING, pos = pos, drop = d)
        var last = Float.MAX_VALUE
        for (t in 0L..d.durationMs - Motion.BOUNCE_MS step 20) {
            val y = DiscPoses.all(falling, t, false)[0].pos.y
            assertTrue("falls monotonically", y <= last + 1e-6f); last = y
            assertTrue(volume.contains(Box3(V3(0f, y, 0f), V3(Layout.DISC_D, Layout.DISC_D, Layout.DISC_T))))
        }
        assertEquals(Layout.rowY(0), DiscPoses.all(falling, d.durationMs + 1, false)[0].pos.y, 1e-4f)
        assertNull("no ring while dropping", DiscPoses.ringColumn(falling))
    }

    @Test fun theFourGlowsAndTheRestDim() {
        val st = PlayState(phase = Phase.OVER, pos = Rules.fromCode("445566" + "3"))
        val poses = DiscPoses.all(st, 0, false)
        val glow = poses.count { it.look == DiscLook.GLOW }
        val dim = poses.count { it.look == DiscLook.DIM }
        assertEquals(4, glow)
        assertEquals(3, dim)
    }

    @Test fun theDumpEmptiesTheRackAndRefillsTheTrays() {
        val old = Rules.fromCode("4435")
        val plan = Motion.dumpPlan(old)
        val dur = Motion.dumpMs(plan, false)
        assertTrue("about 1.2 s, got $dur", dur in 700L..1600L)
        val st = PlayState(phase = Phase.CLEARING, dump = io.embodit.fourdrop.domain.Dump(plan, 0, dur))
        val mid = DiscPoses.all(st, plan.maxOf { it.fallEnd } + 1, false)
        assertTrue("all dumped discs gone mid-way", mid.count { !it.visible } >= 1)
        val end = DiscPoses.all(st, dur, false)
        for ((k, p) in end.withIndex()) { val want = Layout.traySlot(if (k < 21) Seat.RED else Seat.YELLOW, k % 21); assertEquals(want.y, p.pos.y, 1e-4f); assertEquals(want.x, p.pos.x, 1e-5f) }
        assertEquals(0f, DiscPoses.slidePull(st, dur + 1, false), 0.01f)
    }

    // ---------------------------------------------------------------- hit replay

    @Test fun everyHolderResolvesFromItsMeshChild() {
        for (c in 0 until Rules.COLS) assertEquals("col:$c", BoardTargets.resolvePath(listOf("mouth:$c", "col:$c", "root")))
        for (s in Seat.entries) assertEquals("tray:$s", BoardTargets.resolvePath(listOf("trayBlock:$s", "tray:$s", "root")))
        assertEquals("handle", BoardTargets.resolvePath(listOf("handleBrass", "handle", "root")))
        assertNull(BoardTargets.resolvePath(listOf("disc:RED:0", "root")))
        assertNull(BoardTargets.resolvePath(listOf("plateFront", "root")))
        for (c in 0 until Rules.COLS) assertEquals("col${c + 1}", BoardTargets.squareOf(BoardTargets.resolvePath(listOf("mouth:$c", "col:$c"))))
    }

    private class NoStore : GameStore { override fun load(): SavedGame? = null; override fun save(game: SavedGame) {}; override fun clear() {} }

    private fun flow(): GameFlow = GameFlow(CoroutineScope(Dispatchers.Unconfined), { 0L }, NoStore(), { Settings(firstRunDone = true) }).also { it.start() }

    @Test fun replayedHitsDriveTheGame() {
        val f = flow()
        routeFlow(f, BoardTargets.resolvePath(listOf("mouth:2", "col:2")))
        assertEquals(2, f.state.value.aim?.col)
        routeFlow(f, BoardTargets.resolvePath(listOf("trayBlock:RED", "tray:RED")))
        assertNull(f.state.value.aim)
        routeFlow(f, BoardTargets.resolvePath(listOf("handleBrass", "handle")))
        assertTrue(f.state.value.pos.moves.isEmpty())
    }

    @Test fun dragsThroughTheTemplatePathFollowTheHandAtEveryScale() {
        for (k in listOf(0.5f, 1f, 3f)) {
            val f = flow()
            val hit = routeDragStart(f, "tray:RED")
            assertTrue(hit is BoardNames.TrayHit)
            val g = f.state.value.grab!!
            val dx = Layout.colX(1) - g.startX; val dy = Layout.LIFT_Y - g.startY
            // the template reports physical metres: the content distance times the volume scale
            routeDragMove(f, hit, Offset3M(dx * k, dy * k, 0f), k)
            assertEquals("snapped at scale $k", 1, f.state.value.grab!!.snap)
            routeDragEnd(f, hit)
            assertEquals(listOf(1), f.state.value.pos.moves)
        }
        val f = flow()
        val h = routeDragStart(f, "handle")
        assertEquals(BoardNames.HandleHit, h)
        routeDragMove(f, h, Offset3M(0f, 0f, 0.03f * 3f), 3f)
        assertEquals(0.03f, f.state.value.slide, 1e-5f)
        assertEquals("a column cannot be dragged", BoardNames.NoHit, routeDragStart(f, "col:3"))
    }
}
