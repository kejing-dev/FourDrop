package io.embodit.fourdrop.domain

import kotlin.random.Random

/** The four opponents of the opponent dialog (design mustHave.aiAndLocal2p). */
enum class Opponent(val thinkMs: Long) {
    EASY(600), SERIOUS(800), HARD(1500), TWO(0);

    val isAi: Boolean get() = this != TWO
}

/**
 * Bitboard negamax α-β for four in a row (design gameSpec.ai): iterative deepening, a transposition table and
 * centre-first move ordering. Three levels decided by how far they look and whether they slip:
 *  - Easy: one move ahead — win if it can, block if it must, otherwise a random column that does not hand you a four;
 *    30 % of its turns it misses diagonals (both its own and yours);
 *  - Serious: α-β depth 6;
 *  - Hard: iterative deepening + TT against a time limit (1.5 s on the device) or a node budget (unit tests).
 * Pure Kotlin, no Android, runs on Dispatchers.Default; [Abort] is how a deadline or a cancelled search stops.
 */
class Engine(private val cancelled: () -> Boolean = { false }) {
    companion object {
        const val WIN = 100_000
        const val EASY_DIAGONAL_MISS = 0.30
        const val SERIOUS_DEPTH = 6
        private val ORDER = intArrayOf(3, 2, 4, 1, 5, 0, 6)
        private const val H1 = Rules.H1
        private val BOTTOM = LongArray(Rules.COLS) { 1L shl (it * H1) }
        private val COLUMN = LongArray(Rules.COLS) { ((1L shl Rules.ROWS) - 1) shl (it * H1) }
        private val TOP = LongArray(Rules.COLS) { 1L shl (Rules.ROWS - 1 + it * H1) }

        /** Every line of four cells on the rack (69): its mask and its four cell bits. */
        private val WINDOWS: List<LongArray> = buildList {
            for (c in 0 until Rules.COLS) for (r in 0 until Rules.ROWS) for ((dc, dr) in listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)) {
                val ec = c + 3 * dc; val er = r + 3 * dr
                if (ec !in 0 until Rules.COLS || er !in 0 until Rules.ROWS) continue
                val cells = LongArray(4) { k -> Rules.bit(c + k * dc, r + k * dr) }
                add(longArrayOf(cells.fold(0L) { a, b -> a or b }, *cells))
            }
        }
        private val CENTRE = COLUMN[3]

        /** Row (0-based) of a single cell bit. */
        private fun rowOf(bit: Long): Int = java.lang.Long.numberOfTrailingZeros(bit) % H1
    }

    class Abort : RuntimeException(null, null, false, false)

    /** Search statistics of the last [choose] (depth reached, nodes), for the ui-audit and the log. */
    var lastDepth = 0; private set
    var lastNodes = 0L; private set

    private var nodes = 0L
    private var nodeBudget = Long.MAX_VALUE
    private var deadlineNs = Long.MAX_VALUE
    private val ttKey = LongArray(1 shl 18)
    private val ttVal = IntArray(1 shl 18)
    private val ttDepth = ByteArray(1 shl 18)
    private val ttFlag = ByteArray(1 shl 18)
    private val ttMove = ByteArray(1 shl 18)

    /**
     * The column [level] plays in [p]. [timeMs] bounds Hard on the device; [nodeBudget] bounds it deterministically in
     * tests. [rng] breaks ties between equally good columns (fixed seeds make games reproducible).
     */
    fun choose(p: Position, level: Opponent, rng: Random, timeMs: Long = level.thinkMs, nodeBudget: Long = Long.MAX_VALUE): Int {
        val legal = Rules.playable(p)
        require(legal.isNotEmpty()) { "no legal move" }
        nodes = 0L
        lastDepth = 0
        return when (level) {
            Opponent.EASY -> easy(p, rng)
            Opponent.SERIOUS -> {
                this.nodeBudget = Long.MAX_VALUE; deadlineNs = Long.MAX_VALUE
                root(p, SERIOUS_DEPTH, rng).first.also { lastDepth = SERIOUS_DEPTH }
            }
            Opponent.HARD, Opponent.TWO -> hard(p, rng, timeMs, nodeBudget)
        }.also { lastNodes = nodes }
    }

    // ------------------------------------------------------------------ Easy and the test script

    private fun easy(p: Position, rng: Random): Int {
        val me = p.toMove
        val blind = rng.nextDouble() < EASY_DIAGONAL_MISS
        fun four(b: Long) = if (blind) Rules.hasFourStraight(b) else Rules.hasFour(b)
        val legal = Rules.playable(p)
        legal.firstOrNull { four(p.board(me) or Rules.bit(it, p.heights[it])) }?.let { return it }
        legal.firstOrNull { four(p.board(me.other) or Rules.bit(it, p.heights[it])) }?.let { return it }
        val safe = legal.filter { c ->
            val above = p.heights[c] + 1
            above >= Rules.ROWS || !four(p.board(me.other) or Rules.bit(c, above))
        }
        val pool = safe.ifEmpty { legal }
        return pool[rng.nextInt(pool.size)]
    }

    /** The strength test's baseline (design gameSpec.ai.strengthAcceptance): win if it can, block if it must, else random. */
    fun script(p: Position, rng: Random): Int {
        val me = p.toMove
        val legal = Rules.playable(p)
        legal.firstOrNull { Rules.hasFour(p.board(me) or Rules.bit(it, p.heights[it])) }?.let { return it }
        legal.firstOrNull { Rules.hasFour(p.board(me.other) or Rules.bit(it, p.heights[it])) }?.let { return it }
        return legal[rng.nextInt(legal.size)]
    }

    // ------------------------------------------------------------------ Hard

    private fun hard(p: Position, rng: Random, timeMs: Long, budget: Long): Int {
        nodeBudget = budget
        deadlineNs = if (budget != Long.MAX_VALUE || timeMs <= 0) Long.MAX_VALUE else System.nanoTime() + timeMs * 1_000_000
        var best = root(p, 1, rng).also { lastDepth = 1 }
        val maxDepth = Rules.CELLS - p.moves.size
        var depth = 2
        while (depth <= maxDepth) {
            val next = try { root(p, depth, rng, preferred = best.first) } catch (_: Abort) { break }
            // a proven loss says nothing about which column a fallible opponent is most likely to miss: keep the
            // last column that still had a fight in it (the second player is lost against perfect play)
            if (next.second <= -WIN + 100 && best.second > -WIN + 100) break
            best = next
            lastDepth = depth
            if (next.second >= WIN - 100) break // a forced win is proven; deeper search changes nothing
            depth++
        }
        return best.first
    }

    // ------------------------------------------------------------------ search

    /** Test hook: the negamax value of [p] at [depth] for the side to move (full window, a fresh TT when [freshTt]). */
    internal fun valueOf(p: Position, depth: Int, freshTt: Boolean = true): Int {
        if (freshTt) ttKey.fill(0L)
        nodeBudget = Long.MAX_VALUE; deadlineNs = Long.MAX_VALUE
        return negamax(p.board(p.toMove), p.boards[0] or p.boards[1], p.moves.size, depth, -WIN - 1, WIN + 1, 0)
    }

    /** Test hook: the static evaluation for the side to move. */
    internal fun evalOf(p: Position): Int = evaluate(p.board(p.toMove), p.boards[0] or p.boards[1], p.moves.size)

    /** Best column at [depth] and its score (ties broken by [rng]). */
    private fun root(p: Position, depth: Int, rng: Random, preferred: Int? = null): Pair<Int, Int> {
        val cur = p.board(p.toMove)
        val mask = p.boards[0] or p.boards[1]
        val n = p.moves.size
        val order = (if (preferred != null) listOf(preferred) else emptyList()) + ORDER.filter { it != preferred }
        // an immediate four always wins
        order.firstOrNull { canPlay(mask, it) && winning(cur, mask, it) }?.let { return it to WIN }
        var bestScore = Int.MIN_VALUE
        val bests = ArrayList<Int>()
        var alpha = -WIN - 1
        for (col in order) {
            if (!canPlay(mask, col)) continue
            val score = -negamax(cur xor mask, mask or (mask + BOTTOM[col]), n + 1, depth - 1, -WIN - 1, -alpha + 1, 1)
            if (score > bestScore) { bestScore = score; bests.clear(); bests += col } else if (score == bestScore) bests += col
            if (score > alpha) alpha = score
        }
        return bests[rng.nextInt(bests.size)] to bestScore
    }

    private fun canPlay(mask: Long, col: Int) = mask and TOP[col] == 0L

    private fun winning(cur: Long, mask: Long, col: Int): Boolean = Rules.hasFour(cur or ((mask + BOTTOM[col]) and COLUMN[col]))

    private fun tick() {
        nodes++
        if (nodes and 1023L == 0L && (nodes > nodeBudget || System.nanoTime() > deadlineNs || cancelled())) throw Abort()
        if (nodes > nodeBudget) throw Abort()
    }

    /** Score for the side to move ([cur] = its discs). */
    private fun negamax(cur: Long, mask: Long, n: Int, depth: Int, a0: Int, beta: Int, ply: Int): Int {
        tick()
        if (n >= Rules.CELLS) return 0
        for (col in 0 until Rules.COLS) if (canPlay(mask, col) && winning(cur, mask, col)) return WIN - ply
        if (depth <= 0) return evaluate(cur, mask, n)
        var alpha = a0
        val key = cur + mask
        val slot = ((key xor (key ushr 29)) and ((1L shl 18) - 1)).toInt()
        var ttBest = -1
        if (ttKey[slot] == key) {
            ttBest = ttMove[slot].toInt()
            if (ttDepth[slot] >= depth) {
                val v = ttVal[slot]
                when (ttFlag[slot].toInt()) {
                    0 -> return v
                    1 -> if (v >= beta) return v
                    2 -> if (v <= alpha) return v
                }
            }
        }
        var best = Int.MIN_VALUE
        var bestCol = -1
        var tried = 0
        for (k in -1 until Rules.COLS) {
            val col = if (k < 0) ttBest else ORDER[k]
            if (col < 0 || (k >= 0 && col == ttBest) || !canPlay(mask, col)) continue
            tried++
            val score = -negamax(cur xor mask, mask or (mask + BOTTOM[col]), n + 1, depth - 1, -beta, -alpha, ply + 1)
            if (score > best) { best = score; bestCol = col }
            if (score > alpha) alpha = score
            if (alpha >= beta) break
        }
        if (tried == 0) return 0
        ttKey[slot] = key
        ttVal[slot] = best
        ttDepth[slot] = depth.coerceAtMost(127).toByte()
        ttFlag[slot] = (if (best <= a0) 2 else if (best >= beta) 1 else 0).toByte()
        ttMove[slot] = bestCol.toByte()
        return best
    }

    /**
     * Static score for the side to move (design gameSpec.ai.eval): disc counts mean nothing, so only centre column,
     * open threes (worth more on the row parity that favours their owner: the first player's odd rows, counted
     * from 1, the second player's even rows), open twos.
     */
    private fun evaluate(cur: Long, mask: Long, n: Int): Int {
        val opp = cur xor mask
        val meFirst = n % 2 == 0
        var s = 6 * (java.lang.Long.bitCount(cur and CENTRE) - java.lang.Long.bitCount(opp and CENTRE))
        for (w in WINDOWS) {
            val m = w[0]
            val mine = java.lang.Long.bitCount(cur and m)
            val theirs = java.lang.Long.bitCount(opp and m)
            if (mine > 0 && theirs > 0) continue
            if (mine == 3 || theirs == 3) {
                val empty = m and mask.inv()
                // a threat on the owner's good row parity is what decides the endgame (zugzwang)
                val oddRow = rowOf(empty) % 2 == 0 // 0-based even = 1-based odd
                val mineGood = oddRow == meFirst
                if (mine == 3) s += if (mineGood) 60 else 30
                else s -= if (!mineGood) 70 else 35
            } else if (mine == 2) s += 6 else if (theirs == 2) s -= 6
        }
        return s
    }
}
