package io.embodit.fourdrop.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The game flow (design interactions I01–I19) on virtual time: the same calls the volume, the panel and the debug broadcast make. */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowTest {
    class MemStore : GameStore {
        var saved: SavedGame? = null
        var saves = 0
        override fun load() = saved
        override fun save(game: SavedGame) { saved = game; saves++ }
        override fun clear() { saved = null }
    }

    private class Rig(val scope: TestScope, val store: MemStore = MemStore(), var settings: Settings = Settings(firstRunDone = true)) {
        val sounds = ArrayList<Sound>()
        val flow = GameFlow(
            scope = scope, clock = { scope.testScheduler.currentTime }, store = store, settings = { settings },
            onSettings = { settings = it }, onSound = { s, _ -> sounds += s }, aiDispatcher = StandardTestDispatcher(scope.testScheduler), seed = 1,
        )
        val st: PlayState get() = flow.state.value
    }

    private fun TestScope.rig(opponent: Opponent = Opponent.EASY, store: MemStore = MemStore()): Rig =
        Rig(this, store, Settings(firstRunDone = true, opponent = opponent)).also { it.flow.start() }

    @Test fun tapTwiceDropsAndTheComputerAnswers() = runTest {
        val r = rig()
        r.flow.tapColumn(3)
        assertEquals(3, r.st.aim?.col)
        assertTrue(r.st.pos.moves.isEmpty())
        assertEquals("S04", r.st.stateId(0))
        r.flow.tapColumn(3)
        assertEquals(listOf(3), r.st.pos.moves)
        assertEquals(Phase.DROPPING, r.st.phase)
        assertEquals("committed before the animation", "4", r.store.saved!!.position.code())
        advanceTimeBy(Motion.dropMs(Layout.LIFT_Y, 0, false) + 1)
        assertEquals(Phase.THINKING, r.st.phase)
        advanceUntilIdle()
        assertEquals(2, r.st.pos.moves.size)
        assertEquals(Phase.PLAY, r.st.phase)
        assertEquals(Seat.RED, r.st.pos.toMove)
        assertTrue(Sound.TICK in r.sounds && Sound.LAND in r.sounds)
    }

    @Test fun aimMovesAndGoesBackToTheTray() = runTest {
        val r = rig()
        r.flow.tapColumn(1)
        r.flow.tapColumn(5)
        assertEquals(5, r.st.aim?.col); assertEquals(1, r.st.aim?.from)
        assertTrue(r.st.pos.moves.isEmpty())
        r.flow.tapTray(Seat.YELLOW) // the other tray does nothing
        assertNotNull(r.st.aim)
        r.flow.tapTray(Seat.RED)
        assertNull(r.st.aim)
        assertNotNull(r.st.back)
        assertTrue(r.st.pos.moves.isEmpty())
    }

    @Test fun fullColumnIsRefused() = runTest {
        val r = rig(); r.flow.debugImport("444444")
        r.flow.tapColumn(3)
        assertEquals(Notice.FULL, r.st.notice)
        assertEquals("S08", r.st.stateId(testScheduler.currentTime))
        r.flow.tapColumn(3)
        assertEquals("444444", r.st.pos.code())
        assertEquals(Phase.PLAY, r.st.phase)
        assertTrue(Sound.FULL in r.sounds)
    }

    @Test fun noDropWhileTheComputerThinks() = runTest {
        val r = rig()
        r.flow.tapColumn(3); r.flow.tapColumn(3)
        advanceTimeBy(Motion.dropMs(Layout.LIFT_Y, 0, false) + 1)
        assertEquals(Phase.THINKING, r.st.phase)
        r.flow.tapColumn(0)
        assertEquals(listOf(3), r.st.pos.moves)
        assertEquals(Notice.NOT_NOW, r.st.notice)
        assertFalse(r.flow.grabStart(Seat.RED))
    }

    @Test fun winEndsTheGameAndCountsOnce() = runTest {
        val r = rig(); r.flow.debugImport("445566")
        r.flow.tapColumn(6); r.flow.tapColumn(6)
        assertEquals(Seat.RED, r.st.pos.winner)
        assertEquals("counted at the commit", 1, r.settings.winsRed)
        advanceUntilIdle()
        assertEquals(Phase.OVER, r.st.phase)
        assertEquals(4, r.st.winLine!!.size)
        assertTrue(Sound.WIN in r.sounds)
        r.flow.tapColumn(0)
        assertEquals(7, r.st.pos.moves.size)
        assertEquals(1, r.settings.winsRed)
    }

    @Test fun undoAgainstTheComputerGoesBackToYourTurn() = runTest {
        val r = rig()
        r.flow.tapColumn(3); r.flow.tapColumn(3)
        advanceUntilIdle()
        assertEquals(2, r.st.pos.moves.size)
        r.flow.undo()
        assertTrue(r.st.pos.moves.isEmpty())
        assertEquals(Seat.RED, r.st.pos.toMove)
        assertEquals(2, r.st.rises.size)
        assertEquals("", r.store.saved!!.position.code())
    }

    @Test fun undoWhileThinkingCancelsItsMove() = runTest {
        val r = rig()
        r.flow.tapColumn(3); r.flow.tapColumn(3)
        advanceTimeBy(Motion.dropMs(Layout.LIFT_Y, 0, false) + 1)
        assertEquals(Phase.THINKING, r.st.phase)
        r.flow.undo()
        assertTrue(r.st.pos.moves.isEmpty())
        advanceUntilIdle()
        assertTrue("the cancelled search never drops", r.st.pos.moves.isEmpty())
        assertEquals(Phase.PLAY, r.st.phase)
    }

    @Test fun undoAfterAWinTakesTheWinBack() = runTest {
        val r = rig(); r.flow.debugImport("445566")
        r.flow.tapColumn(2); r.flow.tapColumn(2)
        advanceUntilIdle()
        assertEquals(1, r.settings.winsRed)
        r.flow.undo()
        assertEquals(0, r.settings.winsRed)
        assertEquals("445566", r.st.pos.code())
        assertEquals(Phase.PLAY, r.st.phase)
    }

    @Test fun twoPlayersConfirmUndoAndAlternateTheFirstSeat() = runTest {
        val r = rig(Opponent.TWO)
        r.flow.tapColumn(3); r.flow.tapColumn(3)
        advanceUntilIdle()
        assertEquals(Seat.YELLOW, r.st.handSeat)
        r.flow.tapColumn(2); r.flow.tapColumn(2)
        advanceUntilIdle()
        assertEquals(2, r.st.pos.moves.size)
        r.flow.undo()
        assertEquals(Confirm.UNDO, r.st.confirm)
        assertEquals(2, r.st.pos.moves.size)
        r.flow.confirm()
        assertEquals(listOf(3), r.st.pos.moves)
        // the next game starts with yellow
        r.flow.requestRestart()
        assertEquals(Confirm.RESTART, r.st.confirm)
        r.flow.confirm()
        assertEquals(Phase.CLEARING, r.st.phase)
        assertEquals(Seat.YELLOW, r.st.pos.firstSeat)
        advanceUntilIdle()
        assertEquals(Phase.PLAY, r.st.phase)
        assertEquals(Seat.YELLOW, r.st.handSeat)
    }

    @Test fun againstTheComputerRedAlwaysStarts() = runTest {
        val r = rig(); r.flow.debugImport("445566")
        r.flow.tapColumn(2); r.flow.tapColumn(2)
        advanceUntilIdle()
        r.flow.requestRestart() // game over: [再来一局] needs no confirmation
        assertNull(r.st.confirm)
        assertEquals(Seat.RED, r.st.pos.firstSeat)
        advanceUntilIdle()
        assertEquals(Seat.RED, r.st.pos.toMove)
        assertTrue(r.st.pos.moves.isEmpty())
    }

    @Test fun theSlideStopsAtFiveCentimetresMidGame() = runTest {
        val r = rig(); r.flow.debugImport("4435")
        assertTrue(r.flow.slideStart())
        r.flow.slideMove(0.058f)
        assertEquals(Layout.SLIDE_DETENT, r.st.slide, 1e-6f)
        assertEquals(Notice.PULL_MORE, r.st.notice)
        r.flow.slideEnd()
        assertEquals("released at the detent: springs back", "4435", r.st.pos.code())
        assertNotNull(r.st.slideBack)
        assertTrue(r.flow.slideStart())
        r.flow.slideMove(0.10f)
        assertEquals(Layout.SLIDE_MAX, r.st.slide, 1e-6f)
        r.flow.slideEnd()
        assertEquals(Phase.CLEARING, r.st.phase)
        assertEquals(4, r.st.dump!!.plan.size)
        assertTrue(r.st.pos.moves.isEmpty())
    }

    @Test fun anEmptyOrFinishedRackDumpsAtFiveCentimetres() = runTest {
        val r = rig(); r.flow.debugImport("445566")
        r.flow.tapColumn(2); r.flow.tapColumn(2)
        advanceUntilIdle()
        assertTrue(r.flow.slideStart())
        r.flow.slideMove(0.052f)
        r.flow.slideEnd()
        assertEquals(Phase.CLEARING, r.st.phase)
        assertEquals(7, r.st.dump!!.plan.size)
    }

    @Test fun carryADiscToAColumnAndLetGo() = runTest {
        val r = rig()
        assertTrue(r.flow.grabStart(Seat.RED))
        val g = r.st.grab!!
        // up and over column 2 (index 1)
        r.flow.grabMove(Layout.colX(1) - g.startX, Layout.LIFT_Y - g.startY)
        assertEquals(1, r.st.grab!!.snap)
        assertEquals("S04", r.st.stateId(testScheduler.currentTime))
        r.flow.grabEnd()
        assertEquals(listOf(1), r.st.pos.moves)
        assertEquals(Phase.DROPPING, r.st.phase)
    }

    @Test fun letGoOffTheColumnsGoesBackToTheTray() = runTest {
        val r = rig()
        assertTrue(r.flow.grabStart(Seat.RED))
        val g = r.st.grab!!
        r.flow.grabMove(0.02f, 0.05f) // still beside the rack, low
        assertNull(r.st.grab!!.snap)
        r.flow.grabEnd()
        assertTrue(r.st.pos.moves.isEmpty())
        assertNotNull(r.st.back)
        assertEquals(Phase.PLAY, r.st.phase)
        assertTrue(g.startX < 0) // red tray is on the left
    }

    @Test fun opponentChangesNowOnAnEmptyRackAndNextGameOtherwise() = runTest {
        val r = rig()
        r.flow.chooseOpponent(Opponent.SERIOUS)
        assertEquals(Opponent.SERIOUS, r.st.opponent)
        r.flow.debugImport("4435")
        r.flow.chooseOpponent(Opponent.TWO)
        assertEquals(Opponent.SERIOUS, r.st.opponent)
        assertEquals(Opponent.TWO, r.st.pending)
        assertEquals(Notice.NEXT_GAME, r.st.notice)
        r.flow.requestRestart(); r.flow.confirm()
        assertEquals(Opponent.TWO, r.st.opponent)
        assertNull(r.st.pending)
    }

    @Test fun aKillDuringTheFallKeepsTheMove() = runTest {
        val store = MemStore()
        val r = rig(store = store)
        r.flow.tapColumn(4); r.flow.tapColumn(4)
        assertEquals(Phase.DROPPING, r.st.phase)
        r.flow.stopJobs() // the process dies mid-fall
        val again = Rig(this, store, Settings(firstRunDone = true)).also { it.flow.start() }
        assertEquals(listOf(4), again.st.pos.moves)
        assertEquals(Notice.RESUMED, again.st.notice)
        // the computer is to move, so it is thinking (S06); the plaque still says 「接着上次」 for 1.5 s (plaqueLine order)
        assertEquals("S06", again.st.stateId(testScheduler.currentTime))
        assertTrue(again.st.noticeFresh(testScheduler.currentTime, GameFlow.RESUMED_MS))
        advanceUntilIdle() // the computer's turn resumes
        assertEquals(2, again.st.pos.moves.size)
    }

    @Test fun aResumedGameOnYourTurnIsS16() = runTest {
        val store = MemStore().apply { save(SavedGame(Rules.fromCode("4435"), Opponent.EASY, null)) }
        val r = Rig(this, store, Settings(firstRunDone = true)).also { it.flow.start() }
        assertEquals("4435", r.st.pos.code())
        assertEquals("S16", r.st.stateId(testScheduler.currentTime))
        assertEquals("S03", r.st.stateId(testScheduler.currentTime + GameFlow.RESUMED_MS + 1))
    }

    @Test fun easyWarnsOfAGiveawayButStillLetsYouDrop() = runTest {
        val r = rig(); r.flow.debugImport("1111273473")
        r.flow.tapColumn(1)
        assertEquals(Notice.GIVEAWAY, r.st.notice)
        r.flow.tapColumn(1)
        assertEquals(11, r.st.pos.moves.size)
        val s2 = rig(Opponent.SERIOUS); s2.flow.debugImport("1111273473", Opponent.SERIOUS)
        s2.flow.tapColumn(1)
        assertEquals("only Easy warns", Notice.NONE, s2.st.notice)
    }

    @Test fun theMouthsGlowForTheFirstThreeDropsOfTheFirstGame() = runTest {
        val r = Rig(this, MemStore(), Settings(firstRunDone = true, firstGameLit = false)).also { it.flow.start() }
        assertEquals(3, r.st.litLeft)
        repeat(3) { r.flow.tapColumn(it); r.flow.tapColumn(it); advanceUntilIdle() }
        assertEquals(0, r.st.litLeft)
        assertTrue(r.settings.firstGameLit)
    }

    @Test fun firstLaunchShowsTheRulesUntilGotIt() = runTest {
        val r = Rig(this, MemStore(), Settings(firstRunDone = false)).also { it.flow.start() }
        assertTrue(r.st.welcome)
        assertEquals("S01", r.st.stateId(0))
        r.flow.welcomeDone()
        assertFalse(r.st.welcome)
        assertTrue(r.settings.firstRunDone)
    }

    @Test fun reducedMotionDropsIn120ms() = runTest {
        val r = Rig(this, MemStore(), Settings(firstRunDone = true, reducedMotion = true)).also { it.flow.start() }
        r.flow.tapColumn(3); r.flow.tapColumn(3)
        assertEquals(Motion.REDUCED_DROP_MS, r.st.drop!!.durationMs)
    }

    @Test fun backgroundSettlesTheFallAndForegroundResumesThinking() = runTest {
        val r = rig()
        r.flow.tapColumn(3); r.flow.tapColumn(3)
        r.flow.onBackground()
        assertNull(r.st.drop)
        assertEquals(Phase.THINKING, r.st.phase)
        advanceUntilIdle()
        assertEquals("no move while in the background", 1, r.st.pos.moves.size)
        r.flow.onForeground()
        advanceUntilIdle()
        assertEquals(2, r.st.pos.moves.size)
    }
}
