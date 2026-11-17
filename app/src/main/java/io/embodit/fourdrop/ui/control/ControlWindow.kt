package io.embodit.fourdrop.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.LocalContentColor
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.fourdrop.BuildConfig
import io.embodit.fourdrop.R
import io.embodit.fourdrop.domain.Confirm
import io.embodit.fourdrop.domain.Language
import io.embodit.fourdrop.domain.Notice
import io.embodit.fourdrop.domain.Opponent
import io.embodit.fourdrop.domain.Phase
import io.embodit.fourdrop.domain.PlayState
import io.embodit.fourdrop.domain.Rules
import io.embodit.fourdrop.domain.Seat
import io.embodit.fourdrop.domain.Settings
import io.embodit.fourdrop.platform.Graph
import io.embodit.fourdrop.platform.TAG
import io.embodit.fourdrop.ui.Dialog
import io.embodit.fourdrop.ui.Page
import io.embodit.fourdrop.ui.Ui
import io.embodit.fourdrop.ui.board.plaqueLine
import io.embodit.fourdrop.ui.theme.FDColors
import kotlin.system.exitProcess

/**
 * The control window (Planar WindowContainer beside the volume, 560 x 440 dp): all 2D UI, three pages switched by the
 * native SpatialUI TabBar (D8): 对局 / 规则 / 设置. No page scrolls (D44); every page lays its content out with
 * SpaceBetween, so the blank outside the content is only the window padding. ≤ 7 tappable controls per screen (B31)
 * counting the three tabs.
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val nav = LocalSpatialNavigator.current
    DisposableEffect(owner, nav) {
        graph.window.attachPanel(nav)
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // S01: the rules page with [知道了] on the very first launch
    LaunchedEffect(state.welcome) { if (state.welcome) Ui.page = Page.RULES }
    LaunchedEffect(state.confirm) { if (state.confirm != null) { Ui.page = Page.PLAY; Ui.dialog = null } }
    LaunchedEffect(Ui.page) {
        Log.i(TAG, "PAGE ${Ui.page} ${Ui.page.stateId}")
        if (Ui.page != Page.SETTINGS && Ui.dialog == Dialog.EXIT) Ui.dialog = null
    }
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(250); now = SystemClock.uptimeMillis() } }

    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
        when {
            Ui.dialog == Dialog.OPPONENT -> OpponentDialog(state, graph)
            Ui.dialog == Dialog.LANGUAGE -> LanguageDialog(settings, graph)
            Ui.dialog == Dialog.EXIT -> ConfirmPage(stringResource(R.string.confirm_exit), stringResource(R.string.confirm_exit_line), stringResource(R.string.confirm_exit_yes), R.drawable.ic_logout, "exit", danger = true, onYes = { exitApp(context, graph) }, onNo = { Ui.dialog = null })
            Ui.page == Page.PLAY -> PlayPage(state, settings, graph, now)
            Ui.page == Page.RULES -> RulesPage(state, graph)
            else -> SettingsPage(settings, graph)
        }
    }

    // The native TabBar is its own system window: resolve the labels here, in the app-language context, and pass
    // plain strings in (ui_gates tabbar-lang; an English cold start shows three English tabs).
    val tabs = listOf(
        Triple(Page.PLAY, R.drawable.ic_grid_view, stringResource(R.string.tab_play)),
        Triple(Page.RULES, R.drawable.ic_menu_book, stringResource(R.string.tab_rules)),
        Triple(Page.SETTINGS, R.drawable.ic_settings, stringResource(R.string.tab_settings)),
    )
    NativeTabs(tabs)
}

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, content = content)
}

@Composable
internal fun opponentName(o: Opponent): String = stringResource(when (o) { Opponent.EASY -> R.string.opp_easy; Opponent.SERIOUS -> R.string.opp_serious; Opponent.HARD -> R.string.opp_hard; Opponent.TWO -> R.string.opp_two })

@Composable
private fun opponentNote(o: Opponent): String = stringResource(when (o) { Opponent.EASY -> R.string.opp_easy_note; Opponent.SERIOUS -> R.string.opp_serious_note; Opponent.HARD -> R.string.opp_hard_note; Opponent.TWO -> R.string.opp_two_note })

@Composable
private fun sideName(seat: Seat): String = stringResource(if (seat == Seat.RED) R.string.side_red else R.string.side_yellow)

/** The turn card's second line: what you can do next, or why the game ended (design gameSpec.end). */
@Composable
internal fun statusLine(st: PlayState, now: Long): String {
    return when {
        st.phase == Phase.LOADING -> stringResource(R.string.s00)
        st.phase == Phase.OVER -> {
            val line = Rules.winLine(st.pos)
            if (line == null) stringResource(R.string.result_draw) else {
                val dir = when { line[0].col == line[1].col -> R.string.line_column; line[0].row == line[1].row -> R.string.line_row; else -> R.string.line_diagonal }
                stringResource(R.string.result_line, sideName(st.pos.winner!!), stringResource(dir), st.pos.moves.size)
            }
        }
        st.notice == Notice.NEXT_GAME && st.noticeFresh(now) && st.pending != null -> stringResource(R.string.status_next, opponentName(st.pending))
        st.phase == Phase.CLEARING -> stringResource(R.string.status_clearing)
        st.phase == Phase.THINKING -> stringResource(R.string.status_thinking)
        st.phase == Phase.DROPPING -> stringResource(R.string.status_dropping)
        st.grab != null -> stringResource(R.string.status_grab)
        st.aim != null -> stringResource(R.string.status_aim)
        st.pos.moves.isEmpty() -> stringResource(R.string.status_play)
        else -> stringResource(R.string.status_moves, st.pos.moves.size + 1, opponentName(st.opponent))
    }
}

// ------------------------------------------------------------------ 对局 Game

@Composable
private fun PlayPage(st: PlayState, settings: Settings, graph: Graph, now: Long) {
    val flow = graph.flow
    if (st.phase == Phase.LOADING) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(20.dp))
            Txt(stringResource(R.string.s00), "play/loading", style = PicoTheme.typography.titleLarge)
        }
        return
    }
    when (st.confirm) {
        Confirm.RESTART -> { ConfirmPage(stringResource(R.string.confirm_restart), stringResource(R.string.confirm_restart_line), stringResource(R.string.confirm_restart_yes), R.drawable.ic_restart_alt, "restart", danger = true, onYes = { flow.confirm() }, onNo = { flow.cancelConfirm() }); return }
        Confirm.UNDO -> {
            val last = st.pos.lastSeat ?: Seat.RED
            ConfirmPage(stringResource(R.string.confirm_undo, sideName(last)), null, stringResource(R.string.confirm_undo_yes), R.drawable.ic_undo, "undo", danger = true, onYes = { flow.confirm() }, onNo = { flow.cancelConfirm() })
            return
        }
        null -> Unit
    }
    val ctx = LocalContext.current
    val panelClosed = false
    val big = plaqueLine(st, panelClosed, now) { id, args -> ctx.getString(id, *args) }.first
    val over = st.phase == Phase.OVER
    val seatColor = when {
        over && st.pos.winner != null -> st.pos.winner
        over -> null
        st.phase == Phase.DROPPING -> st.drop?.seat
        else -> st.pos.toMove
    }
    PageColumn {
        // the turn card: the side's disc, the big line, one status line
        Row(
            Modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(24.dp)).background(FDColors.CardFace).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DiscDot(seatColor, Modifier.size(52.dp))
            Spacer(Modifier.width(18.dp))
            // gate:allow button-gap two lines of text inside the turn card (not buttons); the button below is 16 dp away
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(big, "play/turn", style = PicoTheme.typography.titleLarge.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold), color = FDColors.Ink)
                Txt(statusLine(st, now), "play/status", style = PicoTheme.typography.bodyMedium, color = FDColors.InkSecondary, maxLines = 2)
            }
        }
        val oppLabel = if (st.pending != null) stringResource(R.string.btn_opponent_next, opponentName(st.pending)) else stringResource(R.string.btn_opponent, opponentName(st.opponent))
        SecondaryButton("$oppLabel  ▸", "play/opponent", onClick = { Ui.dialog = Dialog.OPPONENT }, enabled = !st.inputLocked, icon = if ((st.pending ?: st.opponent) == Opponent.TWO) R.drawable.ic_group else R.drawable.ic_smart_toy, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (over) PrimaryButton(stringResource(R.string.btn_again), "play/again", onClick = { flow.requestRestart() }, icon = R.drawable.ic_replay, modifier = Modifier.weight(1.4f))
            else PrimaryButton(stringResource(R.string.btn_undo), "play/undo", onClick = { flow.undo() }, enabled = st.canUndo, icon = R.drawable.ic_undo, modifier = Modifier.weight(1.4f))
            // D15: mute is always one glance away
            ToggleButton(stringResource(if (settings.sound) R.string.btn_mute else R.string.btn_sound), "play/mute", on = !settings.sound,
                onClick = { graph.settings.update { it.copy(sound = !it.sound) } }, icon = if (settings.sound) R.drawable.ic_volume_off else R.drawable.ic_volume_up, modifier = Modifier.weight(1f))
        }
        if (over) SecondaryButton(stringResource(R.string.btn_undo), "play/undo2", onClick = { flow.undo() }, enabled = st.canUndo, icon = R.drawable.ic_undo, modifier = Modifier.fillMaxWidth())
        else SecondaryButton(stringResource(R.string.btn_restart), "play/restart", onClick = { flow.requestRestart() }, enabled = st.canRestart, icon = R.drawable.ic_restart_alt, modifier = Modifier.fillMaxWidth())
    }
}

/** A disc seen face-on: vermilion or cream, with the groove ring; grey ring when nobody (a draw). */
@Composable
private fun DiscDot(seat: Seat?, modifier: Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2
        val c = when (seat) { Seat.RED -> FDColors.Red; Seat.YELLOW -> FDColors.Yellow; null -> FDColors.OakDark }
        drawCircle(c, r)
        drawCircle(if (seat == Seat.YELLOW) FDColors.YellowEdge else Color(0x55000000), r, style = Stroke(width = r * 0.08f))
        drawCircle(Color(0x33000000), r * 0.62f, style = Stroke(width = r * 0.06f))
    }
}

/** S13: a whole-page confirmation card; the volume ignores drops while it is open (PlayState.inputLocked). */
@Composable
private fun ConfirmPage(q: String, line: String?, yes: String, icon: Int, tag: String, danger: Boolean, onYes: () -> Unit, onNo: () -> Unit) {
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(FDColors.CardFace).padding(28.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(72.dp), tint = FDColors.Ink)
        Txt(q, "confirm/$tag", style = PicoTheme.typography.titleLarge.copy(fontSize = 30.sp), color = FDColors.Ink, maxLines = 2)
        if (line != null) Txt(line, "confirm/$tag-line", style = PicoTheme.typography.titleMedium, color = FDColors.InkSecondary, maxLines = 2)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SecondaryButton(stringResource(R.string.btn_cancel), "confirm/cancel", onClick = onNo, modifier = Modifier.weight(1f))
            // design: the dangerous choice on the right
            SecondaryButton(yes, "confirm/yes", danger = danger, onClick = onYes, modifier = Modifier.weight(1f))
        }
    }
}

/** S02: four opponents with one line each, [取消]; mid-game it applies to the next game. */
@Composable
private fun OpponentDialog(st: PlayState, graph: Graph) {
    val current = st.pending ?: st.opponent
    PageColumn {
        Txt(if (st.pos.moves.isNotEmpty() && !st.pos.isOver) stringResource(R.string.opp_next_game) else stringResource(R.string.opp_title), "opp/title", style = PicoTheme.typography.titleMedium, color = FDColors.Ink)
        Opponent.entries.forEach { o ->
            ChoiceRow(opponentName(o), opponentNote(o), selected = o == current, tag = "opp/${o.name.lowercase()}") { graph.flow.chooseOpponent(o); Ui.dialog = null }
        }
        SecondaryButton(stringResource(R.string.btn_cancel), "opp/cancel", onClick = { Ui.dialog = null }, modifier = Modifier.fillMaxWidth())
    }
}

/** The language picker: follow system / 中文 / English, [取消]. */
@Composable
private fun LanguageDialog(settings: Settings, graph: Graph) {
    val options = listOf(Language.SYSTEM to stringResource(R.string.lang_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en))
    PageColumn {
        Txt(stringResource(R.string.lang_title), "lang/title", style = PicoTheme.typography.titleLarge, color = FDColors.Ink)
        options.forEach { (l, name) ->
            ChoiceRow(name, null, selected = settings.language == l, tag = "lang/${l.name.lowercase()}") { graph.settings.update { it.copy(language = l) }; Ui.dialog = null }
        }
        SecondaryButton(stringResource(R.string.btn_cancel), "lang/cancel", onClick = { Ui.dialog = null }, modifier = Modifier.fillMaxWidth())
    }
}

// ------------------------------------------------------------------ 规则 Rules

@Composable
private fun RulesPage(st: PlayState, graph: Graph) {
    PageColumn {
        RulesDiagram(Modifier.fillMaxWidth().height(118.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(R.string.how_to_drop, R.string.how_to_carry, R.string.how_to_dump).forEach { id ->
                Txt(stringResource(id), "rules/howto", style = PicoTheme.typography.bodySmall, color = FDColors.Ink, maxLines = 2)
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(FDColors.CardFace).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(R.string.mistake_1, R.string.mistake_2, R.string.mistake_3).forEachIndexed { i, id ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(FDColors.Primary), contentAlignment = Alignment.Center) {
                        Txt("${i + 1}", "rules/n", style = PicoTheme.typography.labelSmall, color = FDColors.OnPrimary)
                    }
                    Spacer(Modifier.width(8.dp))
                    Txt(stringResource(id), "rules/mistake", style = PicoTheme.typography.bodySmall, color = FDColors.Ink, maxLines = 2, modifier = Modifier.weight(1f))
                }
            }
        }
        if (st.welcome) PrimaryButton(stringResource(R.string.btn_got_it), "rules/gotit", onClick = { graph.flow.welcomeDone(); Ui.page = Page.PLAY }, modifier = Modifier.fillMaxWidth())
    }
}

/** The three panels (拿到列口 / 掉到底 / 连四赢), drawn in the rack's own colours. */
@Composable
private fun RulesDiagram(modifier: Modifier) {
    val labels = listOf(stringResource(R.string.rules_step1), stringResource(R.string.rules_step2), stringResource(R.string.rules_step3))
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (k in 0..2) {
            Column(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(16.dp)).background(FDColors.CardFace).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.weight(1f).fillMaxWidth()) { drawPanel(k) }
                Txt(labels[k], "rules/step$k", style = PicoTheme.typography.labelMedium, color = FDColors.Ink, align = TextAlign.Center)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPanel(k: Int) {
    // a 4 × 3 corner of the rack: oak face with holes; red / yellow discs in some
    val cols = 4; val rows = 3
    val cell = minOf(size.width / (cols + 0.4f), size.height / (rows + 1.3f))
    val w = cell * cols; val h = cell * rows
    val ox = (size.width - w) / 2; val oy = size.height - h - cell * 0.05f
    drawRoundRect(FDColors.Oak, Offset(ox - cell * 0.12f, oy - cell * 0.12f), Size(w + cell * 0.24f, h + cell * 0.24f), androidx.compose.ui.geometry.CornerRadius(cell * 0.15f))
    val filled = when (k) {
        0 -> mapOf((0 to 0) to FDColors.Yellow, (1 to 0) to FDColors.Red)
        1 -> mapOf((0 to 0) to FDColors.Yellow, (1 to 0) to FDColors.Red, (2 to 0) to FDColors.Red)
        else -> mapOf((0 to 0) to FDColors.Red, (1 to 0) to FDColors.Red, (2 to 0) to FDColors.Red, (3 to 0) to FDColors.Red, (0 to 1) to FDColors.Yellow, (1 to 1) to FDColors.Yellow)
    }
    for (c in 0 until cols) for (r in 0 until rows) {
        val centre = Offset(ox + (c + 0.5f) * cell, oy + h - (r + 0.5f) * cell)
        drawCircle(filled[c to r] ?: FDColors.Hole, cell * 0.38f, centre)
    }
    when (k) {
        0 -> drawCircle(FDColors.Red, cell * 0.42f, Offset(ox + 2.5f * cell, oy - cell * 0.62f)) // held over the column mouth
        1 -> for (j in 1..3) drawCircle(Color(0x66C8412E), cell * 0.38f, Offset(ox + 2.5f * cell, oy + (j - 1) * cell * 0.45f - cell * 0.4f), style = Stroke(cell * 0.05f)) // the fall
        else -> drawLine(Color(0xFFFFF1C2), Offset(ox + 0.5f * cell, oy + h - 0.5f * cell), Offset(ox + 3.5f * cell, oy + h - 0.5f * cell), strokeWidth = cell * 0.14f)
    }
}

// ------------------------------------------------------------------ 设置 Settings

@Composable
private fun SettingsPage(settings: Settings, graph: Graph) {
    fun upd(change: (Settings) -> Settings) { graph.settings.update(change) }
    val langName = when (settings.language) { Language.SYSTEM -> stringResource(R.string.lang_system); Language.ZH -> stringResource(R.string.lang_zh); Language.EN -> stringResource(R.string.lang_en) }
    PageColumn {
        PickerRow(stringResource(R.string.settings_language), langName, "settings/lang", R.drawable.ic_translate) { Ui.dialog = Dialog.LANGUAGE }
        VolumeRow(stringResource(R.string.settings_volume), settings.volume, listOf(stringResource(R.string.vol_low), stringResource(R.string.vol_mid), stringResource(R.string.vol_high)), R.drawable.ic_volume_up) { v -> upd { it.copy(volume = v) } }
        SwitchRow(stringResource(R.string.settings_reduced), "settings/reduced", settings.reducedMotion, R.drawable.ic_animation) { v -> upd { it.copy(reducedMotion = v) } }
        // D44: the panel can always be put back beside the rack, and quitting is on the first screen (confirmed)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SecondaryButton(stringResource(R.string.btn_reopen), "settings/reopen", onClick = { graph.window.reopen() }, icon = R.drawable.ic_grid_view, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_quit), "settings/exit", onClick = { Ui.dialog = Dialog.EXIT; Log.i(TAG, "CONFIRM EXIT S13") }, icon = R.drawable.ic_logout, modifier = Modifier.weight(1f), danger = true)
        }
        Txt(stringResource(R.string.about_text, BuildConfig.VERSION_NAME), "settings/about", style = PicoTheme.typography.bodySmall, color = FDColors.InkSecondary, maxLines = 2)
    }
}

/** D26 / D44 exit: save, release, close both window containers, remove every task, then end the process. */
internal fun exitApp(context: Context, graph: Graph) {
    Log.i(TAG, "EXIT release + close windows + finishAndRemoveTask + exitProcess")
    runCatching { graph.flow.onBackground() }
    runCatching { graph.flow.stopJobs() }
    runCatching { graph.sounds.release() }
    runCatching { graph.window.closeAll() }
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
    }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 600)
}

/** The native SpatialUI TabBar (D8); labels arrive already resolved in the app language and follow the content colour. */
@Composable
private fun NativeTabs(tabs: List<Triple<Page, Int, String>>) {
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { (page, icon, label) ->
            item(
                selected = Ui.page == page && Ui.dialog == null,
                onClick = { Ui.page = page; if (Ui.dialog != Dialog.EXIT || page != Page.SETTINGS) Ui.dialog = null },
                mainContent = { Icon(painter = painterResource(icon), contentDescription = label) },
                supportContent = { Txt(label, "tabbar/${page.name}", style = PicoTheme.typography.labelMedium, color = LocalContentColor.current) },
            )
        }
    }
}

