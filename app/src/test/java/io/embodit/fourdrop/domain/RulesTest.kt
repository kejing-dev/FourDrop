package io.embodit.fourdrop.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * design mustHave.rulesEnforced / acceptance M1: four directions ≥ 3 each, full column refused, 42-move draw, no drop
 * after the end or while the computer thinks, undo rolls the bitboards back. The game strings were found by playing
 * random games until the last move made exactly one four (no earlier four).
 */
class RulesTest {
    private fun p(code: String) = Rules.fromCode(code)

    private enum class Dir { V, H, U, D }

    private fun dirOf(line: List<Cell>): Dir = when {
        line[0].col == line[1].col -> Dir.V
        line[0].row == line[1].row -> Dir.H
        (line[1].row - line[0].row) * (line[1].col - line[0].col) > 0 -> Dir.U
        else -> Dir.D
    }

    private fun assertFour(code: String, seat: Seat, dir: Dir) {
        val pos = p(code)
        assertEquals("all moves of $code kept", code.length, pos.moves.size)
        assertEquals("winner of $code", seat, pos.winner)
        val line = Rules.winLine(pos)!!
        assertEquals(4, line.size)
        assertTrue("line goes through the last disc", pos.lastCell in line)
        assertTrue("every cell is the winner's", line.all { pos.seatOf(it) == seat })
        assertEquals("direction of $code", dir, dirOf(line))
        // nothing earlier had a four
        assertNull(p(code.dropLast(1)).winner)
    }

    @Test fun verticalFours() {
        assertFour("7366416322131713", Seat.YELLOW, Dir.V)
        assertFour("466362456523516", Seat.RED, Dir.V)
        assertFour("45461253454", Seat.RED, Dir.V)
    }

    @Test fun horizontalFours() {
        assertFour("25452572361", Seat.RED, Dir.H)
        assertFour("74164365", Seat.YELLOW, Dir.H)
        assertFour("76443416772", Seat.RED, Dir.H)
    }

    @Test fun risingDiagonalFours() {
        assertFour("7453456121677667", Seat.YELLOW, Dir.U)
        assertFour("6665571743757", Seat.RED, Dir.U)
        assertFour("43737552247455", Seat.YELLOW, Dir.U)
    }

    @Test fun fallingDiagonalFours() {
        assertFour("3744422553727213", Seat.YELLOW, Dir.D)
        assertFour("257436444752536", Seat.RED, Dir.D)
        assertFour("35364324652622", Seat.YELLOW, Dir.D)
    }

    @Test fun straightViewMissesDiagonals() {
        val diag = (0..3).fold(0L) { a, k -> a or Rules.bit(k, 3 - k) }
        assertTrue(Rules.hasFour(diag)); assertFalse(Rules.hasFourStraight(diag))
        val row = (2..5).fold(0L) { a, k -> a or Rules.bit(k, 2) }
        assertTrue(Rules.hasFourStraight(row))
        // no wrap-around between columns (the sentinel bit)
        assertFalse(Rules.hasFour(Rules.bit(0, 4) or Rules.bit(0, 5) or Rules.bit(1, 0) or Rules.bit(1, 1)))
    }

    @Test fun twoLinesInOneDropCountOnce() {
        val code = "7517625351432164344"
        val pos = p(code)
        assertEquals(Seat.RED, pos.winner)
        assertEquals(4, Rules.winLine(pos)!!.size)
        assertNull(p(code.dropLast(1)).winner)
    }

    @Test fun fullColumnRefused() {
        val pos = p("444444")
        assertTrue(pos.isFull(3))
        assertFalse(Rules.canPlay(pos, 3))
        assertEquals(Refusal.FULL, Rules.refusal(pos, 3, Seat.RED))
        assertEquals("fromCode stops at the illegal 7th disc", "444444", p("4444444").code())
    }

    @Test fun drawAfter42() {
        val pos = p("757341132773575647262416323134652254654611")
        assertEquals(42, pos.moves.size)
        assertNull(pos.winner)
        assertTrue(pos.isDraw); assertTrue(pos.isOver)
        assertEquals(Rules.DISCS_PER_SEAT, pos.used(Seat.RED)); assertEquals(Rules.DISCS_PER_SEAT, pos.used(Seat.YELLOW))
        assertTrue(Rules.playable(pos).isEmpty())
    }

    @Test fun noDropAfterTheEnd() {
        val pos = p("25452572361")
        assertTrue(pos.isOver)
        for (c in 0 until Rules.COLS) { assertFalse(Rules.canPlay(pos, c)); assertEquals(Refusal.OVER, Rules.refusal(pos, c, Seat.YELLOW)) }
    }

    @Test fun noDropWhileThinkingOrOutOfTurn() {
        val pos = p("4")
        assertEquals(Refusal.THINKING, Rules.refusal(pos, 3, Seat.YELLOW, thinking = true))
        assertEquals(Refusal.NOT_YOUR_TURN, Rules.refusal(pos, 3, Seat.RED))
        assertEquals(Refusal.BAD_COLUMN, Rules.refusal(pos, 7, Seat.YELLOW))
        assertNull(Rules.refusal(pos, 3, Seat.YELLOW))
    }

    @Test fun undoRollsBackTheBitboards() {
        val code = "44353216677"
        var q = p(code)
        for (n in code.length downTo 1) {
            val back = q.copy(moves = q.moves.dropLast(1))
            val fresh = p(code.substring(0, n - 1))
            assertEquals(fresh.boards.toList(), back.boards.toList())
            assertEquals(fresh.heights.toList(), back.heights.toList())
            assertEquals(fresh.toMove, back.toMove)
            q = back
        }
    }

    @Test fun giveawayCheck() {
        val pos = p("1111273473")
        assertEquals(Seat.RED, pos.toMove)
        assertTrue(Rules.givesAway(pos, 1, Seat.RED))
        assertFalse(Rules.givesAway(pos, 3, Seat.RED))
    }

    @Test fun saveCodecRoundTrip() {
        val g = SavedGame(Rules.fromCode("4435", Seat.YELLOW), Opponent.HARD, Opponent.TWO)
        val back = SaveCodec.decode(SaveCodec.encode(g))!!
        assertEquals(g.position.code(), back.position.code())
        assertEquals(Seat.YELLOW, back.position.firstSeat)
        assertEquals(Opponent.HARD, back.opponent); assertEquals(Opponent.TWO, back.pending)
        assertNull(SaveCodec.decode("fd1|R|4444444|EASY|-"))
        assertNull(SaveCodec.decode("garbage"))
    }
}
