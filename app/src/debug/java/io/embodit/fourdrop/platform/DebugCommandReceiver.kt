package io.embodit.fourdrop.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.embodit.fourdrop.domain.Language
import io.embodit.fourdrop.domain.Layout
import io.embodit.fourdrop.domain.Opponent
import io.embodit.fourdrop.domain.Settings
import io.embodit.fourdrop.ui.Containers
import io.embodit.fourdrop.ui.Dialog
import io.embodit.fourdrop.ui.Page
import io.embodit.fourdrop.ui.Ui
import io.embodit.fourdrop.ui.board.BoardListener
import io.embodit.fourdrop.ui.board.BoardNames
import io.embodit.fourdrop.ui.board.BoardTargets
import io.embodit.fourdrop.ui.board.SceneHolder
import io.embodit.fourdrop.ui.board.input.BoardInputLog
import io.embodit.fourdrop.ui.board.input.InputSource
import io.embodit.fourdrop.ui.board.input.Offset3M
import io.embodit.fourdrop.ui.board.input.resolveUp
import io.embodit.fourdrop.ui.board.routeDragEnd
import io.embodit.fourdrop.ui.board.routeDragMove
import io.embodit.fourdrop.ui.board.routeDragStart
import io.embodit.fourdrop.ui.board.routeTap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug builds only. Every command goes through the same calls the volume and the window use (D29): taps through
 * [routeTap] with the holder name a ray would hit, drags through routeDragStart / routeDragMove / routeDragEnd with
 * physical metres like the board-input template sends.
 *
 *   adb shell am broadcast -a io.embodit.fourdrop.DEBUG -p io.embodit.fourdrop --es cmd <cmd> [--es a "<arg>"]
 *
 * Columns in aim / drop / grab are 0-based (0..6, design verification.debugHooks); import takes the 1-based column
 * string ("4435"). Commands: reset · goto S00..S16 [restart|undo|exit for S13] · aim <c> · drop [c] · grab <c> · release
 * · pull <m> · pullrelease · dump · undo · confirm · no · opp easy|serious|hard|two · import <columns> · lang zh|en|system
 * · sound on|off · vol 45|80|100 · reduced on|off · slow 1|2|4 · closepanel · reopen · seed <n> · page play|rules|settings
 * · welcome · tap <holder> · replay "<holder> [hand-ray|eye-hand|controller]" · hover <holder>|off · s00hold on|off
 * · ftest on|off · restart · clearsave · state | dump-state · evidence
 */
class DebugCommandReceiver : BroadcastReceiver() {
    companion object {
        /** A mid game, red to move (design states S03 「import 4435」). */
        const val MID = "4435"
        /** Red to move; dropping in column 2 (index 1) lets yellow four right above (Easy's giveaway warning, S08b). */
        const val GIVEAWAY = "1111273473"
        /** Red to move; columns 3 or 7 (index 2 / 6) win along the bottom row (S09). */
        const val ONE_SHORT = "445566"
        /** 41 moves with no four; the 42nd fills the rack (S09 draw). */
        const val DRAW_41 = "75734113277357564726241632313465225465461"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val flow = graph.flow
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val a = intent.getStringExtra("a").orEmpty().trim().trim('"')
        val on = a == "on" || a == "true" || a == "1"
        val f = a.split(" ").filter { it.isNotBlank() }
        Log.i(TAG, "CMD $cmd $a")
        fun settings(change: (Settings) -> Settings) = graph.settings.update(change)
        fun later(ms: Long, block: () -> Unit) { graph.scope.launch { delay(ms); block() } }
        fun tap(holder: String) = routeTap(graph, holder)
        fun col(c: Int) = tap(BoardNames.col(c.coerceIn(0, 6)))
        fun reset(code: String = "", opp: Opponent? = null) {
            flow.debugSlow(1); Ui.dialog = null; Ui.page = Page.PLAY
            flow.debugImport(code, opp)
        }
        /** The drag path: pinch the hand seat's tray, carry the disc over column [c] (physical metres, as the template sends). */
        fun grab(c: Int) {
            val seat = flow.state.value.handSeat
            val hit = routeDragStart(flow, BoardNames.tray(seat))
            val g = flow.state.value.grab ?: run { Log.w(TAG, "GRAB refused"); return }
            val k = SceneHolder.scale
            routeDragMove(flow, hit, Offset3M((Layout.colX(c) - g.startX) * k, (Layout.LIFT_Y + 0.01f - g.startY) * k, 0f), k)
            heldDrag = hit
        }
        fun pull(m: Float) {
            if (heldDrag !is BoardNames.HandleHit) heldDrag = routeDragStart(flow, BoardNames.HANDLE)
            routeDragMove(flow, heldDrag, Offset3M(0f, 0f, m * SceneHolder.scale), SceneHolder.scale)
        }
        when (cmd) {
            "reset" -> reset()
            "goto", "hold" -> when (f.getOrNull(0)?.uppercase()) {
                "S00" -> { flow.debugLoading(); Log.i(TAG, "S00 held (or: s00hold on, force-stop and start, capture within 10 s)") }
                "S01" -> { settings { it.copy(firstRunDone = false) }; Ui.dialog = null; flow.debugRestart() }
                "S02" -> { Ui.page = Page.PLAY; Ui.dialog = Dialog.OPPONENT }
                "S03" -> reset()
                "S04" -> { reset(MID); later(300) { col(3) } }
                "S05" -> { reset(MID); flow.debugSlow(4); later(300) { col(3); later(1600) { col(3) } } }
                "S06" -> { reset(); flow.debugSlow(4); later(200) { flow.debugImport("$MID${3}") } }
                "S07" -> { reset(MID); flow.debugSlow(4); later(300) { flow.requestRestart(); flow.confirm() } }
                "S08", "S08A" -> { reset("444444"); later(300) { col(3) } }
                "S08B" -> { reset(GIVEAWAY, Opponent.EASY); later(300) { col(1) } }
                "S09" -> { reset(ONE_SHORT); later(300) { col(6); later(300) { col(6) } } }
                "S09D" -> reset(DRAW_41, Opponent.EASY)
                "S10" -> graph.window.closeControlOnly()
                "S11" -> { Ui.dialog = null; Ui.page = Page.SETTINGS }
                "S12" -> { Ui.dialog = null; Ui.page = Page.RULES }
                "S13" -> when (f.getOrNull(1)) {
                    "undo" -> { reset(MID, Opponent.TWO); later(300) { flow.undo() } }
                    "exit" -> { Ui.page = Page.SETTINGS; Ui.dialog = Dialog.EXIT }
                    else -> { reset(MID); later(300) { flow.requestRestart() } }
                }
                "S16" -> { reset(MID); later(300) { flow.debugRestart() } }
                else -> Log.w(TAG, "unknown state $a")
            }
            "aim" -> a.toIntOrNull()?.let { col(it) }
            "drop" -> {
                val c = a.toIntOrNull() ?: flow.state.value.aim?.col ?: 3
                if (flow.state.value.aim?.col != c) col(c)
                col(c)
            }
            "grab" -> grab(a.toIntOrNull() ?: 3)
            "release" -> { routeDragEnd(flow, heldDrag); heldDrag = BoardNames.NoHit }
            "pull" -> pull(a.toFloatOrNull() ?: 0.05f)
            "pullrelease" -> { routeDragEnd(flow, heldDrag); heldDrag = BoardNames.NoHit }
            "dump" -> { flow.requestRestart(); flow.confirm() }
            "undo" -> flow.undo()
            "confirm" -> flow.confirm()
            "no" -> flow.cancelConfirm()
            "opp" -> Opponent.entries.firstOrNull { it.name.equals(a, true) }?.let { flow.chooseOpponent(it) }
            "import" -> flow.debugImport(a)
            "lang" -> settings { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "sound" -> settings { it.copy(sound = on) }
            "vol" -> settings { it.copy(volume = when (a) { "45" -> 0; "100" -> 2; else -> 1 }) }
            "reduced" -> settings { it.copy(reducedMotion = on) }
            "slow" -> flow.debugSlow(a.toIntOrNull() ?: 1)
            "seed" -> a.toLongOrNull()?.let { flow.debugSeed(it) }
            "page" -> { Ui.dialog = null; Ui.page = when (a) { "rules" -> Page.RULES; "settings" -> Page.SETTINGS; else -> Page.PLAY } }
            "welcome" -> flow.welcomeDone()
            "tap" -> tap(a)
            "closepanel" -> graph.window.closeControlOnly()
            "reopen" -> graph.window.reopen()
            "restart" -> flow.debugRestart()
            "clearsave" -> { context.deleteFile("game.txt"); context.deleteFile("game.bak") }
            "s00hold" -> context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean("s00hold", on).apply()
            "ftest" -> SceneHolder.orientationTest = on
            // replay <holder> [src]: the real scene entities a ray lands on for that holder (holder, its deepest mesh
            // child) through the template's resolveUp, then the deepest one into the same listener.onTap
            "replay" -> {
                val scene = SceneHolder.scene ?: run { Log.w(TAG, "REPLAY not ready"); return }
                val src = InputSource.entries.firstOrNull { it.label == f.getOrNull(1) } ?: InputSource.HAND_RAY
                var last: String? = null
                val ents = scene.replayEntities(f.getOrNull(0).orEmpty())
                if (ents.isEmpty()) BoardInputLog.write(TAG, "REPLAY ${f.getOrNull(0)} src=${src.label} no entity")
                for (e in ents) {
                    val (t, path) = resolveUp(e, BoardTargets())
                    BoardInputLog.write(TAG, "REPLAY ${f.getOrNull(0)} src=${src.label} entity=${e.getName()} path=$path -> square=${BoardTargets.squareOf(t)}")
                    last = t
                }
                if (ents.isNotEmpty()) BoardListener(graph, scene).onTap(last, src, null)
            }
            "hover" -> SceneHolder.scene?.setHover(a.ifBlank { null }?.takeIf { it != "off" })
            "state", "dump-state" -> Log.i(TAG, "DUMP ${flow.dump()} page=${Ui.page} dialog=${Ui.dialog} window=${graph.window.panelState} volume=${graph.window.volumeState} scale=${SceneHolder.scale}")
            "evidence" -> Log.i(TAG, "EVIDENCE ${SceneHolder.scene?.evidence() ?: "no scene"} containers=${Containers.BOARD}")
            else -> Log.w(TAG, "unknown debug command: $cmd")
        }
    }
}

/** The drag a `grab` / `pull` command is holding until `release` / `pullrelease` (process-wide, like a held pinch). */
private var heldDrag: BoardNames.Hit = BoardNames.NoHit
