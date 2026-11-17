package io.embodit.fourdrop.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** The phases of design states[] that live in the game (S02 / S10–S13 are window states). */
enum class Phase(val id: String) { LOADING("S00"), PLAY("S03"), DROPPING("S05"), THINKING("S06"), CLEARING("S07"), OVER("S09") }

enum class Sound { PICK, TICK, LAND, BACK, FULL, SLIDE, WIN, LOSE }

/** One-off lines on the seat plaque and the panel's status row (design content.plaqueLines); they show [GameFlow.NOTICE_MS]. */
enum class Notice { NONE, FULL, GIVEAWAY, PULL_MORE, RESUMED, NEXT_GAME, NOT_NOW }

/** The window's confirmations that belong to the game (design panels.dialogs.confirm; exit lives in the window). */
enum class Confirm { RESTART, UNDO }

/** The lifted disc hovering over a column (S04, tap path). [from] = the column it came from, null = from the tray. */
data class Aim(val col: Int, val from: Int?, val startedAt: Long)

/** The disc carried by hand on the rack plane (S04, drag path); [snap] = the column it is held over. */
data class Grab(val x: Float, val y: Float, val snap: Int?, val startedAt: Long, val startX: Float, val startY: Float)

/** A committed drop being animated (S05). */
data class Drop(val col: Int, val row: Int, val seat: Seat, val trayIndex: Int, val x0: Float, val y0: Float, val startedAt: Long, val durationMs: Long)

/** An undone disc rising back to its tray. */
data class Rise(val cell: Cell, val seat: Seat, val trayIndex: Int, val startedAt: Long, val durationMs: Long)

/** A disc going back to its tray without a drop (tap on the tray, a release off the columns, a full column). */
data class Back(val seat: Seat, val trayIndex: Int, val from: V3, val startedAt: Long)

/** The computer's telegraph: its disc rises from the yellow tray to the column (220 ms) and hovers 300 ms (S06). */
data class AiLift(val col: Int, val startedAt: Long)

/** The dump (S07): the old position's discs, how each falls and comes back. */
data class Dump(val plan: List<Motion.DumpDisc>, val startedAt: Long, val durationMs: Long)

/** The slide springing back after a short pull. */
data class SlideBack(val from: Float, val startedAt: Long)

/** The one state every surface reads (scene, control window, plaques, debug dump). */
data class PlayState(
    val phase: Phase = Phase.LOADING,
    val pos: Position = Position(),
    val opponent: Opponent = Opponent.EASY,
    /** Chosen mid-game: applies to the next game (design panels.dialogs.opponent 「下一盘生效」). */
    val pending: Opponent? = null,
    val aim: Aim? = null,
    val grab: Grab? = null,
    val drop: Drop? = null,
    val rises: List<Rise> = emptyList(),
    val back: Back? = null,
    val aiLift: AiLift? = null,
    val dump: Dump? = null,
    val slide: Float = 0f,
    val slideHeld: Boolean = false,
    val slideBack: SlideBack? = null,
    val notice: Notice = Notice.NONE,
    val noticeAt: Long = 0,
    val confirm: Confirm? = null,
    /** S01: the rules page opens with [知道了] on the very first launch. */
    val welcome: Boolean = false,
    /** Drops left in which the column mouths glow (first game only). */
    val litLeft: Int = 0,
    val winsRed: Int = 0,
    val winsYellow: Int = 0,
    val thinkingSince: Long = 0,
    val overAt: Long = 0,
    /** Debug `slow 2|4`: every animation and the timers run this many times slower (QA mid-motion captures). */
    val slow: Int = 1,
) {
    /** The seat whose hand is on the rack now (Red against the computer; the side to move with two players). */
    val handSeat: Seat get() = if (opponent == Opponent.TWO) pos.toMove else Seat.RED
    val humanTurn: Boolean get() = phase == Phase.PLAY && confirm == null && !pos.isOver && (opponent == Opponent.TWO || pos.toMove == Seat.RED)
    val inputLocked: Boolean get() = phase in setOf(Phase.LOADING, Phase.DROPPING, Phase.CLEARING) || confirm != null
    val isOver: Boolean get() = phase == Phase.OVER
    val canUndo: Boolean get() = !inputLocked && pos.moves.isNotEmpty() && (opponent == Opponent.TWO || pos.used(Seat.RED) > 0)
    val canRestart: Boolean get() = !inputLocked && pos.moves.isNotEmpty()
    val midGame: Boolean get() = pos.moves.isNotEmpty() && !pos.isOver
    val winLine: List<Cell>? get() = if (phase == Phase.OVER) Rules.winLine(pos) else null
    /** The disc a tap / grab would take: the next one of the hand seat (0..20). */
    val nextIndex: Int get() = pos.used(handSeat)
    fun noticeFresh(now: Long, ms: Long = GameFlow.NOTICE_MS): Boolean = notice != Notice.NONE && (now - noticeAt) < ms * slow

    /** Design state id for the debug dump and the screenshot list. */
    fun stateId(now: Long): String = when {
        confirm != null -> "S13"
        phase == Phase.LOADING -> "S00"
        welcome -> "S01"
        phase == Phase.CLEARING -> "S07"
        phase == Phase.DROPPING -> "S05"
        phase == Phase.THINKING -> "S06"
        phase == Phase.OVER -> "S09"
        notice == Notice.RESUMED && noticeFresh(now, GameFlow.RESUMED_MS) -> "S16"
        (notice == Notice.FULL || notice == Notice.GIVEAWAY) && (aim != null || grab != null) -> "S08"
        aim != null || grab != null -> "S04"
        else -> "S03"
    }
}

/**
 * The middle layer (rules → state → render). Main-thread confined. The volume input, the panel buttons and the debug
 * broadcast all call these same functions (profile D29: debug commands take the user's path). A drop is committed and
 * saved before its animation plays (design lifecycle 「先提交后动画」), so a kill mid-fall keeps the move.
 */
class GameFlow(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val store: GameStore,
    private val settings: () -> Settings,
    private val onSettings: (Settings) -> Unit = {},
    private val onSound: (Sound, Float) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = {},
    private val aiDispatcher: CoroutineDispatcher = Dispatchers.Default,
    seed: Long = System.nanoTime(),
) {
    companion object {
        const val NOTICE_MS = 2500L
        const val RESUMED_MS = 1500L
        const val MIN_THINK_MS = 600L
        const val LIT_DROPS = 3
        /** Easy only: the "giveaway" check (design gameSpec.hint). */
        fun warnsGiveaway(o: Opponent) = o == Opponent.EASY
    }

    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()
    private val s: PlayState get() = _state.value
    private var job: Job? = null
    private var soundJob: Job? = null
    var rng: Random = Random(seed)
        private set
    private var engine = Engine { !(job?.isActive ?: false) }

    private fun set(next: PlayState) { _state.value = next }
    private fun now() = clock()
    private val reduced: Boolean get() = settings().reducedMotion
    private fun scaled(ms: Long) = ms * s.slow

    // ------------------------------------------------------------------ lifecycle

    /** Cold start: S01 on the first launch, S16 when a save exists (the position comes straight back, no replay), else S03. */
    fun start() {
        if (s.phase != Phase.LOADING) return
        val st = settings()
        val saved = store.load()
        val pos = saved?.position ?: Position()
        val opponent = saved?.opponent ?: st.opponent
        val resumed = saved != null && pos.moves.isNotEmpty()
        set(
            PlayState(
                phase = if (pos.isOver) Phase.OVER else Phase.PLAY,
                pos = pos, opponent = opponent, pending = saved?.pending,
                welcome = !st.firstRunDone,
                litLeft = if (st.firstGameLit) 0 else (LIT_DROPS - pos.used(Seat.RED)).coerceAtLeast(0),
                winsRed = st.winsRed, winsYellow = st.winsYellow,
                notice = if (resumed) Notice.RESUMED else Notice.NONE, noticeAt = now(),
                overAt = now(),
            ),
        )
        log("FLOW start moves=${pos.code()} opp=$opponent resumed=$resumed state=${s.stateId(now())}")
        if (aiToMove()) think()
    }

    /** S01 [知道了]. */
    fun welcomeDone() {
        if (!s.welcome) return
        onSettings(settings().copy(firstRunDone = true))
        set(s.copy(welcome = false))
        log("FLOW welcome done")
    }

    private fun aiToMove() = s.phase == Phase.PLAY && s.opponent.isAi && s.pos.toMove == Seat.YELLOW && !s.pos.isOver

    private fun save() = store.save(SavedGame(s.pos, s.opponent, s.pending))

    private fun notice(n: Notice) = set(s.copy(notice = n, noticeAt = now()))

    // ------------------------------------------------------------------ tap path (I01–I03, I05, I06, I07)

    /** A tap on column [c] (its 56 cm target or the hovering disc above it). */
    fun tapColumn(c: Int) {
        if (c !in 0 until Rules.COLS) return
        if (!s.humanTurn) {
            if (s.phase == Phase.THINKING) { notice(Notice.NOT_NOW); log("FLOW tap col$c refused: thinking") }
            else log("FLOW tap col$c ignored in ${s.phase}")
            return
        }
        if (s.grab != null) return
        val aim = s.aim
        when {
            aim == null -> {
                set(s.copy(aim = Aim(c, null, now()), back = null))
                onSound(Sound.PICK, 1f)
                aimNotice(c)
            }
            aim.col == c -> {
                if (s.pos.isFull(c)) { refuseFull(); return }
                drop(c, Layout.colX(c), Layout.LIFT_Y)
            }
            else -> {
                set(s.copy(aim = Aim(c, aim.col, now())))
                aimNotice(c)
            }
        }
    }

    private fun aimNotice(c: Int) {
        val seat = s.handSeat
        when {
            s.pos.isFull(c) -> refuseFull()
            warnsGiveaway(s.opponent) && Rules.givesAway(s.pos, c, seat) -> { notice(Notice.GIVEAWAY); log("FLOW giveaway col$c") }
            s.notice == Notice.FULL || s.notice == Notice.GIVEAWAY -> notice(Notice.NONE)
        }
    }

    /** Full column: the disc shakes twice, the mouth turns red, the plaque says so, a soft 「啵」 (no drop). */
    private fun refuseFull() {
        notice(Notice.FULL)
        onSound(Sound.FULL, 1f)
        log("FLOW full column refused")
    }

    /** A tap on the hand seat's own tray: the hovering disc goes home. */
    fun tapTray(seat: Seat) {
        val aim = s.aim ?: return
        if (seat != s.handSeat || !s.humanTurn) return
        set(s.copy(aim = null, back = Back(seat, s.nextIndex, V3(Layout.colX(aim.col), Layout.LIFT_Y, 0f), now()), notice = Notice.NONE))
        onSound(Sound.BACK, 1f)
        log("FLOW back to tray")
    }

    // ------------------------------------------------------------------ drag path (I04)

    /** Pinch on the own tray: its top disc comes up into the hand. Returns false when it is not this seat's turn. */
    fun grabStart(seat: Seat): Boolean {
        if (!s.humanTurn || seat != s.handSeat || s.pos.used(seat) >= Rules.DISCS_PER_SEAT) return false
        val from = Layout.traySlot(seat, s.nextIndex)
        val (x, y) = Layout.clampGrab(from.x, from.y)
        set(s.copy(grab = Grab(x, y, null, now(), x, y), aim = null, back = null, notice = Notice.NONE))
        onSound(Sound.PICK, 1f)
        log("FLOW grab $seat")
        return true
    }

    /** The hand moved by ([dx], [dy]) metres on the rack plane since the pinch. */
    fun grabMove(dx: Float, dy: Float) {
        val g = s.grab ?: return
        val (x, y) = Layout.clampGrab(g.startX + dx, g.startY + dy)
        val snap = Layout.snapColumn(x, y, g.snap)
        if (snap != g.snap && snap != null) {
            onSound(Sound.PICK, 1.05f)
            if (s.pos.isFull(snap)) refuseFull()
            else if (warnsGiveaway(s.opponent) && Rules.givesAway(s.pos, snap, s.handSeat)) notice(Notice.GIVEAWAY)
            else if (s.notice == Notice.GIVEAWAY || s.notice == Notice.FULL) notice(Notice.NONE)
        }
        set(s.copy(grab = g.copy(x = x, y = y, snap = snap)))
    }

    /** Release: held over a column = drop; anywhere else (or a full column) = back to the tray. */
    fun grabEnd() {
        val g = s.grab ?: return
        val c = g.snap
        if (c != null && !s.pos.isFull(c)) { drop(c, Layout.colX(c), maxOf(g.y, Layout.SNAP_Y)); return }
        if (c != null) refuseFull()
        set(s.copy(grab = null, back = Back(s.handSeat, s.nextIndex, V3(g.x, g.y, 0f), now())))
        onSound(Sound.BACK, 1f)
        log("FLOW grab released off the columns -> tray")
    }

    fun grabCancel() {
        val g = s.grab ?: return
        set(s.copy(grab = null, back = Back(s.handSeat, s.nextIndex, V3(g.x, g.y, 0f), now())))
    }

    // ------------------------------------------------------------------ drop (I02 / I04 / I09 → I08)

    private fun drop(c: Int, x0: Float, y0: Float) {
        val seat = s.pos.toMove
        val before = s.pos
        val trayIndex = before.used(seat)
        val row = before.heights[c]
        val pos = before.play(c)
        val dur = Motion.dropMs(y0, row, reduced)
        var st = settings()
        // commit first: the move, the counters and the save are written before the animation (design lifecycle)
        val winner = pos.winner
        if (winner != null) st = if (winner == Seat.RED) st.copy(winsRed = st.winsRed + 1) else st.copy(winsYellow = st.winsYellow + 1)
        val humanDrop = s.opponent == Opponent.TWO || seat == Seat.RED
        val lit = if (humanDrop && s.litLeft > 0) s.litLeft - 1 else s.litLeft
        if (lit == 0 && !st.firstGameLit && humanDrop) st = st.copy(firstGameLit = true)
        if (st != settings()) onSettings(st)
        set(
            s.copy(
                phase = Phase.DROPPING, pos = pos, aim = null, grab = null, aiLift = null, back = null,
                drop = Drop(c, row, seat, trayIndex, x0, y0, now(), dur),
                notice = if (s.notice == Notice.RESUMED) Notice.RESUMED else Notice.NONE,
                litLeft = lit, winsRed = st.winsRed, winsYellow = st.winsYellow,
            ),
        )
        save()
        log("FLOW drop $seat col$c row$row moves=${pos.code()} winner=$winner draw=${pos.isDraw}")
        scheduleDropSounds(y0, row)
        job?.cancel()
        job = scope.launch {
            delay(scaled(dur))
            finishDrop()
        }
    }

    /** One 「哒」 per row passed, each 3 % lower, then the landing (design sound.tick / land). */
    private fun scheduleDropSounds(y0: Float, row: Int) {
        soundJob?.cancel()
        if (reduced) { onSound(Sound.LAND, 1f); return }
        val ticks = Motion.tickTimes(y0, row)
        val land = Motion.fallMs(y0 - Layout.rowY(row))
        soundJob = scope.launch {
            var t = 0L
            ticks.forEachIndexed { k, (at, _) ->
                delay(scaled(at - t)); t = at
                onSound(Sound.TICK, 1f - 0.03f * (k + 1))
            }
            delay(scaled((land - t).coerceAtLeast(0)))
            onSound(Sound.LAND, 1f)
        }
    }

    private fun finishDrop() {
        val d = s.drop ?: return
        val pos = s.pos
        if (pos.isOver) {
            set(s.copy(phase = Phase.OVER, drop = null, overAt = now()))
            val humanWon = pos.winner != null && (s.opponent == Opponent.TWO || pos.winner == Seat.RED)
            onSound(if (humanWon) Sound.WIN else Sound.LOSE, 1f)
            log("FLOW over winner=${pos.winner} line=${Rules.winLine(pos)} by=${d.seat}")
            return
        }
        set(s.copy(phase = Phase.PLAY, drop = null))
        if (aiToMove()) think()
    }

    // ------------------------------------------------------------------ the computer (S06, I09)

    private fun think() {
        val pos = s.pos
        val level = s.opponent
        set(s.copy(phase = Phase.THINKING, thinkingSince = now(), aiLift = null))
        log("FLOW thinking $level")
        job?.cancel()
        val myRng = Random(rng.nextLong())
        job = scope.launch {
            val t0 = now()
            val col = runCatching { withContext(aiDispatcher) { engine.choose(pos, level, myRng) } }
                .getOrElse { if (!isActive) return@launch; Rules.playable(pos).first() }
            if (!isActive || s.pos != pos) return@launch
            log("FLOW ai $level -> col$col depth=${engine.lastDepth} nodes=${engine.lastNodes} in ${now() - t0} ms")
            delay(scaled((MIN_THINK_MS - (now() - t0)).coerceAtLeast(0)))
            set(s.copy(aiLift = AiLift(col, now())))
            onSound(Sound.PICK, 1f)
            delay(scaled(if (reduced) 0 else Motion.LIFT_MS + Motion.AI_HOVER_MS))
            if (s.phase != Phase.THINKING || s.pos != pos) return@launch
            drop(col, Layout.colX(col), Layout.LIFT_Y)
        }
    }

    // ------------------------------------------------------------------ undo (I10)

    /** [悔一步]: against the computer back to your last turn (cancels its search); two players: one move, after a confirm. */
    fun undo() {
        if (!s.canUndo) return
        if (s.opponent == Opponent.TWO) { set(s.copy(confirm = Confirm.UNDO)); log("FLOW confirm undo"); return }
        doUndo()
    }

    private fun doUndo() {
        job?.cancel()
        var pos = s.pos
        var st = settings()
        val rises = ArrayList<Rise>()
        var removed = 0
        val t = now()
        while (pos.moves.isNotEmpty() && (removed == 0 || (s.opponent.isAi && pos.toMove != Seat.RED))) {
            val cell = pos.lastCell!!
            val seat = pos.lastSeat!!
            pos.winner?.let { w -> st = if (w == Seat.RED) st.copy(winsRed = (st.winsRed - 1).coerceAtLeast(0)) else st.copy(winsYellow = (st.winsYellow - 1).coerceAtLeast(0)) }
            rises += Rise(cell, seat, pos.used(seat) - 1, t, Motion.undoMs(cell.row, reduced))
            pos = pos.copy(moves = pos.moves.dropLast(1))
            removed++
            if (s.opponent == Opponent.TWO) break
        }
        if (st != settings()) onSettings(st)
        set(s.copy(phase = Phase.PLAY, pos = pos, rises = rises, aim = null, grab = null, aiLift = null, drop = null, confirm = null, notice = Notice.NONE, winsRed = st.winsRed, winsYellow = st.winsYellow))
        save()
        onSound(Sound.BACK, 1f)
        log("FLOW undo $removed -> ${pos.code()}")
    }

    // ------------------------------------------------------------------ dump / new game (I11–I13, S07)

    /** [倒掉重来] / [再来一局]: a game in progress asks first (design panels.dialogs.confirm). */
    fun requestRestart() {
        if (s.inputLocked) return
        if (s.midGame) { set(s.copy(confirm = Confirm.RESTART)); log("FLOW confirm restart"); return }
        dump()
    }

    fun confirm() {
        when (s.confirm) {
            Confirm.RESTART -> { set(s.copy(confirm = null)); dump() }
            Confirm.UNDO -> doUndo()
            null -> Unit
        }
    }

    fun cancelConfirm() { if (s.confirm != null) set(s.copy(confirm = null)) }

    /** The slide opens, the discs pour out and come back to the trays; the next game is committed first. */
    private fun dump() {
        job?.cancel()
        val old = s.pos
        val plan = Motion.dumpPlan(old)
        val opponent = s.pending ?: s.opponent
        // two players: the first seat alternates every game (design gameSpec.turn); against the computer red always starts
        val first = if (opponent == Opponent.TWO && old.moves.isNotEmpty()) old.firstSeat.other else if (opponent == Opponent.TWO) old.firstSeat else Seat.RED
        val dur = Motion.dumpMs(plan, reduced)
        set(
            s.copy(
                phase = Phase.CLEARING, pos = Position(first), opponent = opponent, pending = null,
                aim = null, grab = null, drop = null, aiLift = null, back = null, rises = emptyList(), confirm = null,
                dump = Dump(plan, now(), dur), slideHeld = false, slideBack = null, notice = Notice.NONE,
            ),
        )
        if (opponent != settings().opponent) onSettings(settings().copy(opponent = opponent))
        save()
        log("FLOW dump ${old.moves.size} discs -> new game first=$first opp=$opponent")
        onSound(Sound.SLIDE, 1f)
        soundJob?.cancel()
        if (!reduced && plan.isNotEmpty()) {
            // the pour is the 42 landings on their own curves, never more than one 「哒」 per 25 ms
            val times = plan.map { it.fallEnd }.sorted().fold(ArrayList<Long>()) { acc, t -> if (acc.isEmpty() || t - acc.last() >= 25) acc += t; acc }
            soundJob = scope.launch {
                var t = 0L
                for (at in times) { delay(scaled(at - t)); t = at; onSound(Sound.TICK, 0.9f + rng.nextFloat() * 0.15f) }
            }
        }
        job = scope.launch {
            delay(scaled(dur))
            set(s.copy(phase = Phase.PLAY, dump = null, slide = 0f))
            log("FLOW new game ready")
            if (aiToMove()) think()
        }
    }

    // ------------------------------------------------------------------ the slide (I11)

    fun slideStart(): Boolean {
        if (s.inputLocked || s.phase !in setOf(Phase.PLAY, Phase.OVER, Phase.THINKING)) return false
        if (s.aim != null) set(s.copy(aim = null, back = Back(s.handSeat, s.nextIndex, V3(Layout.colX(s.aim!!.col), Layout.LIFT_Y, 0f), now())))
        set(s.copy(slideHeld = true, slideBack = null))
        onSound(Sound.SLIDE, 1f)
        log("FLOW slide grab")
        return true
    }

    /** The hand pulled [dz] metres toward the user since the pinch. In a game in progress the slide stops at 5 cm first. */
    fun slideMove(dz: Float) {
        if (!s.slideHeld) return
        val raw = dz.coerceIn(0f, Layout.SLIDE_MAX)
        val v = if (!s.midGame) raw else when {
            raw < Layout.SLIDE_DETENT -> raw
            raw < Layout.SLIDE_DETENT + Layout.SLIDE_DETENT_HOLD -> Layout.SLIDE_DETENT
            else -> (Layout.SLIDE_DETENT + (raw - Layout.SLIDE_DETENT - Layout.SLIDE_DETENT_HOLD) * (Layout.SLIDE_MAX - Layout.SLIDE_DETENT) / (Layout.SLIDE_MAX - Layout.SLIDE_DETENT - Layout.SLIDE_DETENT_HOLD)).coerceAtMost(Layout.SLIDE_MAX)
        }
        if (s.midGame && v >= Layout.SLIDE_DETENT && s.slide < Layout.SLIDE_DETENT) { notice(Notice.PULL_MORE); onSound(Sound.PICK, 0.9f) }
        set(s.copy(slide = v))
    }

    /** Release: far enough = dump (5 cm on an empty rack or after the game, 10 cm mid-game), else spring back. */
    fun slideEnd() {
        if (!s.slideHeld) return
        val need = if (s.midGame) Layout.SLIDE_DUMP_GAME else Layout.SLIDE_DUMP_EMPTY
        if (s.slide >= need) { set(s.copy(slideHeld = false)); log("FLOW slide pulled ${"%.3f".format(s.slide)} -> dump"); dump(); return }
        set(s.copy(slideHeld = false, slideBack = SlideBack(s.slide, now()), slide = 0f))
        log("FLOW slide released short -> back")
    }

    fun slideCancel() { if (s.slideHeld) set(s.copy(slideHeld = false, slideBack = SlideBack(s.slide, now()), slide = 0f)) }

    // ------------------------------------------------------------------ opponent (I14)

    /** Empty rack: takes effect now; a game in progress: next game (design S02). */
    fun chooseOpponent(o: Opponent) {
        if (s.inputLocked && s.phase != Phase.LOADING) return
        if (o == s.opponent) { set(s.copy(pending = null)); save(); return }
        if (s.pos.moves.isEmpty() && s.phase == Phase.PLAY) {
            set(s.copy(opponent = o, pending = null, aim = null))
            onSettings(settings().copy(opponent = o))
            log("FLOW opponent $o now")
        } else {
            set(s.copy(pending = o))
            notice(Notice.NEXT_GAME)
            log("FLOW opponent $o next game")
        }
        save()
    }

    // ------------------------------------------------------------------ background

    /** Leaving the foreground: the search stops, an animation in flight is settled (its move is already saved). */
    fun onBackground() {
        when {
            s.drop != null -> { job?.cancel(); finishDrop(); job?.cancel() }
            s.dump != null -> { job?.cancel(); set(s.copy(phase = Phase.PLAY, dump = null, slide = 0f)) }
            s.phase == Phase.THINKING -> { job?.cancel(); set(s.copy(aiLift = null)) }
        }
        if (s.phase == Phase.THINKING) job?.cancel()
        soundJob?.cancel()
        if (s.phase != Phase.LOADING) save()
        log("FLOW background ${s.phase}")
    }

    fun onForeground() {
        if (s.phase == Phase.THINKING && job?.isActive != true) think()
        else if (aiToMove()) think()
    }

    fun stopJobs() { job?.cancel(); soundJob?.cancel() }

    // ------------------------------------------------------------------ debug (same paths as the user, D29)

    /** `import <columns>`: sets up a position for QA (the drops are not animated). */
    fun debugImport(code: String, opponent: Opponent? = null) {
        job?.cancel()
        val o = opponent ?: s.opponent
        val p = Rules.fromCode(code, Seat.RED)
        set(s.copy(phase = if (p.isOver) Phase.OVER else Phase.PLAY, pos = p, opponent = o, pending = null, aim = null, grab = null, drop = null, rises = emptyList(), back = null, aiLift = null, dump = null, confirm = null, notice = Notice.NONE, welcome = false, overAt = now()))
        save()
        log("FLOW import ${p.code()} opp=$o")
        if (aiToMove()) think()
    }

    fun debugSlow(k: Int) { set(s.copy(slow = k.coerceIn(1, 8))); log("FLOW slow ${s.slow}") }

    fun debugSeed(seed: Long) { rng = Random(seed); log("FLOW seed $seed") }

    /** `goto S00`: back to the loading state (the scene is re-entered by the next start()). */
    fun debugLoading() { job?.cancel(); set(PlayState()) }

    /** A process restart as the cold start sees it (S16). */
    fun debugRestart() { job?.cancel(); set(PlayState()); start() }

    fun dump(now: Long = now()): String {
        val st = s
        return "state=${st.stateId(now)} phase=${st.phase} moves=${st.pos.code()} first=${st.pos.firstSeat} toMove=${st.pos.toMove} opp=${st.opponent} pending=${st.pending} " +
            "aim=${st.aim?.col} grab=${st.grab?.let { "%.3f,%.3f snap=%s".format(it.x, it.y, it.snap) }} slide=${"%.3f".format(st.slide)} notice=${st.notice} confirm=${st.confirm} " +
            "winner=${st.pos.winner} line=${st.winLine} wins=${st.winsRed}:${st.winsYellow} lit=${st.litLeft} welcome=${st.welcome} red=${Rules.DISCS_PER_SEAT - st.pos.used(Seat.RED)} yellow=${Rules.DISCS_PER_SEAT - st.pos.used(Seat.YELLOW)}"
    }
}
