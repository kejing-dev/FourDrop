package io.embodit.fourdrop.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * The computer (design gameSpec.ai): tactical basics for every level, and the strength ladder (acceptance M2: fixed
 * seeds, draws count half; Hard vs Serious ≥ 85 %, Serious vs Easy ≥ 80 %, Easy vs the win / block / random script
 * 40–60 %). The ladder plays `-PstrengthGames=200` games per pair for the evidence run (default 40 to keep `test` quick);
 * Hard is bounded by a node budget here so the run is reproducible (on the device it is the 1.5 s clock).
 */
class EngineTest {
    /** ~1 M nodes ≈ what a headset searches in 1.5 s (the JVM does ~12 M; engine-depth.txt). */
    private val hardBudget = 1_000_000L

    @Test fun everyLevelTakesAnImmediateFour() {
        val pos = Rules.fromCode("445566") // red: columns 3 or 7 win
        for (level in listOf(Opponent.SERIOUS, Opponent.HARD)) {
            val c = Engine().choose(pos, level, Random(1), nodeBudget = hardBudget)
            assertTrue("$level plays a winning column, got $c", c == 2 || c == 6)
        }
        // Easy sees rows every time (it only misses diagonals)
        repeat(20) { s -> val c = Engine().choose(pos, Opponent.EASY, Random(s)); assertTrue(c == 2 || c == 6) }
    }

    @Test fun everyLevelBlocksAnImmediateFour() {
        val threat = Rules.fromCode("47516") // red 4 5 6 on the bottom row, 7 already yellow: yellow must block 3
        assertEquals(Seat.YELLOW, threat.toMove)
        for (level in listOf(Opponent.SERIOUS, Opponent.HARD)) {
            val c = Engine().choose(threat, level, Random(2), nodeBudget = hardBudget)
            assertEquals("$level blocks", 2, c)
        }
        repeat(20) { s -> assertEquals(2, Engine().choose(threat, Opponent.EASY, Random(s))) }
    }

    @Test fun easyAvoidsHandingOverAFour() {
        // red to move; column 2 would let yellow four right above it
        val pos = Rules.fromCode("1111273473")
        // it only walks into it on the turns it is blind to diagonals (30 %), and then only by chance among the columns
        val gave = (0 until 200).count { s -> Engine().choose(pos, Opponent.EASY, Random(s)) == 1 }
        assertTrue("gave it away $gave / 200", gave <= 200 * 0.30)
        val safe = (0 until 200).count { s -> Engine().choose(pos, Opponent.SERIOUS, Random(s)) == 1 }
        assertEquals("Serious never does", 0, safe)
    }

    @Test fun hardFindsTheForcedWinOnAnOpenRow() {
        // red to move with two open ends on the bottom row (_ R R _ _): Hard plays the double threat
        val pos = Rules.fromCode("2737")
        val c = Engine().choose(pos, Opponent.HARD, Random(3), nodeBudget = hardBudget)
        assertEquals("the open-ended three", 3, c)
    }

    @Test fun hardReachesDepth() {
        val e = Engine()
        e.choose(Rules.fromCode("4435"), Opponent.HARD, Random(4), nodeBudget = 400_000)
        assertTrue("depth ${e.lastDepth}", e.lastDepth >= 8)
        val t0 = System.nanoTime()
        e.choose(Rules.fromCode("4435"), Opponent.HARD, Random(4), timeMs = 1500)
        val ms = (System.nanoTime() - t0) / 1_000_000
        File("build/reports").mkdirs()
        File("build/reports/engine-depth.txt").writeText("JVM 1.5 s mid-game (4435): depth ${e.lastDepth}, ${e.lastNodes} nodes, $ms ms\n")
        assertTrue("time limit kept: $ms ms", ms < 2500)
    }

    /** Plain minimax with the same leaf evaluation (no pruning, no table): the α-β + TT values must match exactly. */
    private fun reference(e: Engine, p: Position, depth: Int, ply: Int): Int {
        if (p.moves.size == Rules.CELLS) return 0
        for (c in Rules.playable(p)) if (p.play(c).winner != null) return Engine.WIN - ply
        if (depth == 0) return e.evalOf(p)
        return Rules.playable(p).maxOf { -reference(e, p.play(it), depth - 1, ply + 1) }
    }

    @Test fun searchMatchesBruteForce() {
        val rng = Random(9)
        var checked = 0
        repeat(60) {
            var p = Position()
            repeat(rng.nextInt(4, 16)) { val l = Rules.playable(p); if (l.isNotEmpty() && !p.isOver) p = p.play(l[rng.nextInt(l.size)]) }
            if (p.isOver) return@repeat
            val e = Engine()
            for (d in 1..4) {
                val want = reference(e, p, d, 0)
                assertEquals("${p.code()} depth $d", want, e.valueOf(p, d, freshTt = true))
                assertEquals("${p.code()} depth $d (table reused)", want, e.valueOf(p, d, freshTt = false))
                checked++
            }
        }
        assertTrue(checked > 100)
    }

    // ------------------------------------------------------------------ strength ladder

    private fun games(): Int = System.getProperty("strength.games")?.toIntOrNull() ?: 40

    private fun move(e: Engine, pos: Position, level: Opponent?, rng: Random): Int =
        if (level == null) e.script(pos, rng) else e.choose(pos, level, rng, nodeBudget = hardBudget)

    /** Score of [a] against [b] over [n] games (colours alternate, a draw counts half), seeds 1..n. */
    private fun match(a: Opponent?, b: Opponent?, n: Int): Double {
        var score = 0.0
        val ea = Engine(); val eb = Engine()
        for (g in 1..n) {
            val rng = Random(g * 7919L)
            val aFirst = g % 2 == 1
            var pos = Position()
            while (!pos.isOver) {
                val aToMove = (pos.moves.size % 2 == 0) == aFirst
                val c = if (aToMove) move(ea, pos, a, rng) else move(eb, pos, b, rng)
                pos = pos.play(c)
            }
            val w = pos.winner
            score += when {
                w == null -> 0.5
                (w == Seat.RED) == aFirst -> 1.0
                else -> 0.0
            }
        }
        return score / n
    }

    @Test fun strengthLadder() {
        val n = games()
        val t0 = System.currentTimeMillis()
        val hardVsSerious = match(Opponent.HARD, Opponent.SERIOUS, n)
        val seriousVsEasy = match(Opponent.SERIOUS, Opponent.EASY, n)
        val easyVsScript = match(Opponent.EASY, null, n)
        val report = "games per pair $n (fixed seeds, colours alternate, draw = ½; Hard node budget $hardBudget)\n" +
            "Hard vs Serious ${"%.1f".format(hardVsSerious * 100)} %  (need ≥ 85 %)\n" +
            "Serious vs Easy ${"%.1f".format(seriousVsEasy * 100)} %  (need ≥ 80 %)\n" +
            "Easy vs script  ${"%.1f".format(easyVsScript * 100)} %  (need 40–60 %)\n" +
            "time ${(System.currentTimeMillis() - t0) / 1000} s\n"
        File("build/reports").mkdirs()
        File("build/reports/strength-$n.txt").writeText(report)
        println(report)
        assertTrue(report, hardVsSerious >= 0.85)
        assertTrue(report, seriousVsEasy >= 0.80)
        assertTrue(report, easyVsScript in 0.40..0.60)
    }
}
