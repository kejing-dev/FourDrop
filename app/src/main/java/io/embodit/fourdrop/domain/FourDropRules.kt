package io.embodit.fourdrop.domain

/** The two sides. Red is the human in a game against the computer and always moves first there. */
enum class Seat {
    RED, YELLOW;

    val other: Seat get() = if (this == RED) YELLOW else RED
}

/** One cell of the rack: column 0..6 left to right, row 0..5 bottom to top. */
data class Cell(val col: Int, val row: Int)

/**
 * A position = the first seat + the columns played so far (design dataModel.game). Everything else (heights, who is to
 * move, the bitboards, the winner) is derived, so undo is moves.dropLast and the save is the move string.
 *
 * Bitboards use the usual 7 bits per column (6 rows + a sentinel): cell (c, r) is bit c * 7 + r.
 */
data class Position(val firstSeat: Seat = Seat.RED, val moves: List<Int> = emptyList()) {
    val heights: IntArray = IntArray(Rules.COLS).also { h -> moves.forEach { h[it]++ } }
    val boards: LongArray = LongArray(2).also { b ->
        val h = IntArray(Rules.COLS)
        moves.forEachIndexed { i, c -> b[seatAt(i).ordinal] = b[seatAt(i).ordinal] or Rules.bit(c, h[c]); h[c]++ }
    }

    /** Who played move [i] (0-based). */
    fun seatAt(i: Int): Seat = if (i % 2 == 0) firstSeat else firstSeat.other

    val toMove: Seat get() = seatAt(moves.size)
    val lastSeat: Seat? get() = if (moves.isEmpty()) null else seatAt(moves.size - 1)
    val lastCell: Cell? get() = moves.lastOrNull()?.let { Cell(it, heights[it] - 1) }

    fun board(seat: Seat): Long = boards[seat.ordinal]
    fun seatOf(cell: Cell): Seat? = Seat.entries.firstOrNull { board(it) and Rules.bit(cell.col, cell.row) != 0L }

    /** Discs [seat] has put in the rack (its tray holds 21 minus this). */
    fun used(seat: Seat): Int = moves.indices.count { seatAt(it) == seat }

    val winner: Seat? get() = lastSeat?.takeIf { Rules.hasFour(board(it)) }
    val isDraw: Boolean get() = moves.size == Rules.CELLS && winner == null
    val isOver: Boolean get() = winner != null || moves.size == Rules.CELLS

    fun isFull(col: Int): Boolean = heights[col] >= Rules.ROWS

    fun play(col: Int): Position {
        require(Rules.canPlay(this, col)) { "column $col is not playable" }
        return copy(moves = moves + col)
    }

    /** The column string QA uses with the debug `import` (1-based digits). */
    fun code(): String = moves.joinToString("") { (it + 1).toString() }
}

/** Why a drop is refused (design mustHave.rulesEnforced). */
enum class Refusal { BAD_COLUMN, FULL, OVER, NOT_YOUR_TURN, THINKING }

/** Classic four-in-a-row rules on the vertical 7 × 6 rack (design gameSpec; domain/rules/FourDropRules). */
object Rules {
    const val COLS = 7
    const val ROWS = 6
    const val CELLS = COLS * ROWS
    const val H1 = ROWS + 1
    const val DISCS_PER_SEAT = CELLS / 2

    fun bit(col: Int, row: Int): Long = 1L shl (col * H1 + row)

    /** Four in a line in any of the four directions (vertical 1, horizontal 7, diagonals 6 and 8). */
    fun hasFour(b: Long): Boolean = shiftFour(b, 1) || shiftFour(b, H1) || shiftFour(b, H1 - 1) || shiftFour(b, H1 + 1)

    /** Only the vertical and horizontal lines: the Easy level's "missed the diagonal" view (design gameSpec.ai). */
    fun hasFourStraight(b: Long): Boolean = shiftFour(b, 1) || shiftFour(b, H1)

    private fun shiftFour(b: Long, s: Int): Boolean { val m = b and (b shr s); return m and (m shr (2 * s)) != 0L }

    fun canPlay(p: Position, col: Int): Boolean = col in 0 until COLS && !p.isFull(col) && !p.isOver

    /** The refusal for a drop by [seat] into [col], or null when it is legal. */
    fun refusal(p: Position, col: Int, seat: Seat, thinking: Boolean = false): Refusal? = when {
        col !in 0 until COLS -> Refusal.BAD_COLUMN
        p.isOver -> Refusal.OVER
        thinking -> Refusal.THINKING
        p.toMove != seat -> Refusal.NOT_YOUR_TURN
        p.isFull(col) -> Refusal.FULL
        else -> null
    }

    fun playable(p: Position): List<Int> = (0 until COLS).filter { canPlay(p, it) }

    /**
     * The four winning cells of the last mover (the first line found, in direction order). When one drop makes two lines
     * it still counts as one win (design gameSpec.specialRules); the light bar takes the line through the last disc.
     */
    fun winLine(p: Position): List<Cell>? {
        val seat = p.winner ?: return null
        val b = p.board(seat)
        val last = p.lastCell ?: return null
        fun has(c: Int, r: Int) = c in 0 until COLS && r in 0 until ROWS && b and bit(c, r) != 0L
        for ((dc, dr) in listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)) {
            // walk back from the last disc, then take four forward
            var c = last.col; var r = last.row
            while (has(c - dc, r - dr)) { c -= dc; r -= dr }
            val run = ArrayList<Cell>()
            while (has(c, r)) { run += Cell(c, r); c += dc; r += dr }
            if (run.size >= 4) {
                // four in a row that contain the last disc (a run of 5+ picks the window starting at it)
                val start = minOf(run.indexOf(last), run.size - 4)
                return run.subList(start, start + 4)
            }
        }
        return null
    }

    /** Parses a 1-based column string ("4435") for the debug `import`; stops at the first illegal move. */
    fun fromCode(code: String, firstSeat: Seat = Seat.RED): Position {
        var p = Position(firstSeat)
        for (ch in code) {
            val c = ch.digitToIntOrNull()?.minus(1) ?: continue
            if (!canPlay(p, c)) break
            p = p.play(c)
        }
        return p
    }

    /**
     * Easy's giveaway check (design gameSpec.hint): dropping [seat]'s disc in [col] lets the other side win on the cell
     * right above it next turn.
     */
    fun givesAway(p: Position, col: Int, seat: Seat): Boolean {
        if (!canPlay(p, col) || p.toMove != seat) return false
        val after = p.play(col)
        if (after.isOver) return false
        val above = after.heights[col]
        if (above >= ROWS) return false
        return hasFour(after.board(seat.other) or bit(col, above))
    }
}
