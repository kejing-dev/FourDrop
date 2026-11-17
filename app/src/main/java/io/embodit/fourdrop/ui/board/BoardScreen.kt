package io.embodit.fourdrop.ui.board

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.fourdrop.R
import io.embodit.fourdrop.domain.GameFlow
import io.embodit.fourdrop.domain.Notice
import io.embodit.fourdrop.domain.Opponent
import io.embodit.fourdrop.domain.Phase
import io.embodit.fourdrop.domain.PlayState
import io.embodit.fourdrop.domain.Seat
import io.embodit.fourdrop.domain.VolumeScale
import io.embodit.fourdrop.platform.Graph
import io.embodit.fourdrop.platform.TAG
import io.embodit.fourdrop.ui.board.input.BoardInputConfig
import io.embodit.fourdrop.ui.board.input.BoardInputListener
import io.embodit.fourdrop.ui.board.input.BoardInputLog
import io.embodit.fourdrop.ui.board.input.InputSource
import io.embodit.fourdrop.ui.board.input.Offset3M
import io.embodit.fourdrop.ui.board.input.TargetResolver
import io.embodit.fourdrop.ui.board.input.boardInput
import io.embodit.fourdrop.ui.theme.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Runs the volume's per-frame motion inside the ECS (never in Compose) and feeds the window's level gate. */
class BoardFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        SceneHolder.scene?.onFrame(context.deltaTime)
        SceneHolder.graph?.window?.stepFrame()
    }
}

internal object SceneHolder {
    @Volatile var scene: BoardScene? = null
    @Volatile var graph: Graph? = null
    /** The volume's measured uniform scale (1 = the manifest's 0.90 × 0.76 × 0.40 m). */
    @Volatile var scale: Float = 1f
    @Volatile var scaleLogged: Boolean = false
    /** Debug: draw the 「F↑」 test image on the plaques and column numbers (D10). */
    @Volatile var orientationTest: Boolean = false
}

/**
 * The rack volume (default container). Opens the control window beside it through the level gate, builds the scene
 * and routes every pinch / trigger / drag on a holder (column, own tray, slide handle) to [GameFlow] through the factory
 * board-input template (D50: hand ray, controller ray and eye-hand alike).
 */
@Composable
fun BoardScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    val scene = remember { BoardScene(scope) }
    val converter = LocalPhysicalLengthConverter.current

    DisposableEffect(navigator) {
        graph.window.attach(navigator)
        graph.window.request("start")
        onDispose { graph.window.detach() }
    }

    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.flow.onBackground() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> { Log.i(TAG, "LIFECYCLE foreground"); graph.flow.onForeground() }
                // the app icon tapped again brings the volume to the front; a closed panel comes back
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    Log.i(TAG, "LIFECYCLE resume panel=${graph.window.panelState}")
                    if (graph.window.panelState == "ON_DESTROY") graph.window.request("resume")
                }
                androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> { Log.i(TAG, "VOLUME destroyed"); graph.window.onVolumeLifecycle("ON_DESTROY") }
                androidx.lifecycle.Lifecycle.Event.ON_CREATE -> graph.window.onVolumeLifecycle("ON_CREATE")
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(scene) {
        SceneHolder.scene = scene
        SceneHolder.graph = graph
        registerSystem<BoardFrameSystem>()
        onDispose {
            unregisterSystem<BoardFrameSystem>()
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            scene.dispose()
        }
    }

    val loc = remember(context, settings.language) { context.localized(settings.language) }
    val zh = loc.getString(R.string.lang_code) == "zh"
    // tick so timed lines (notices, 「接着上次」) expire on the plaque
    var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(250); tick++ } }
    @Suppress("UNUSED_VARIABLE") val tickNow = tick
    val panelClosed = graph.window.panelState == "ON_DESTROY" && state.phase != Phase.LOADING
    val plaque = plaqueLine(state, panelClosed, android.os.SystemClock.uptimeMillis()) { id, args -> loc.getString(id, *args) }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { px ->
                // VolumeScale: the measured volume width (px → m) over the manifest's 0.90 m (build.md 「体积可缩放就要跟着缩放」)
                val wM = with(converter) { converter.dpToLength(px.width.toFloat().toDp(), LengthUnit.Meters) }
                val k = VolumeScale.fromWidth(wM)
                if (k != SceneHolder.scale || !SceneHolder.scaleLogged) { SceneHolder.scaleLogged = true; SceneHolder.scale = k; scene.setScale(k); Log.i(TAG, "SCALE view=${px.width}x${px.height} width=${"%.3f".format(wM)} m k=${"%.3f".format(k)}") }
            }
            .boardInput(remember { BoardTargets() }, remember(graph, scene) { BoardListener(graph, scene) }, BoardInputConfig(dragStartM = 0.02f, logTag = TAG)),
        initial = { content, _ ->
            content.addEntity(scene.root)
            val t0 = android.os.SystemClock.elapsedRealtime()
            val prefs = context.getSharedPreferences("debug", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("s00hold", false)) { Log.i(TAG, "S00 hold 10 s"); kotlinx.coroutines.delay(10_000) }
            val resources = withContext(NonCancellable + Dispatchers.Default) { runCatching { SceneResources.create() }.onFailure { Log.e(TAG, "SCENE resources failed", it) }.getOrNull() }
            Log.i(TAG, "SCENE resources ${resources != null} in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
            if (resources != null) scene.install(resources)
            if (graph.flow.state.value.phase == Phase.LOADING) graph.flow.start()
        },
        update = { _, _ ->
            scene.apply(state, settings, PlaqueText(plaque.first, zh, plaque.second), SceneHolder.orientationTest)
        },
    )
}

/**
 * The seat plaque's line and whether it is an alert (design content.plaqueLines; D38: the panel's status row reads the
 * same function, so the two never disagree). One sentence, ≤ 14 汉字 / 30 English characters.
 */
internal fun plaqueLine(st: PlayState, panelClosed: Boolean, now: Long, s: (Int, Array<Any>) -> String): Pair<String, Boolean> {
    fun t(id: Int) = s(id, emptyArray())
    val two = st.opponent == Opponent.TWO
    return when {
        st.phase == Phase.LOADING -> t(R.string.s00) to false
        panelClosed -> t(R.string.plaque_open_panel) to false
        st.phase == Phase.OVER -> {
            val w = st.pos.winner
            t(when { w == null -> R.string.plaque_draw; two -> if (w == Seat.RED) R.string.plaque_red_wins else R.string.plaque_yellow_wins; w == Seat.RED -> R.string.plaque_you_win; else -> R.string.plaque_it_wins }) to false
        }
        st.notice == Notice.FULL && st.noticeFresh(now) -> t(R.string.plaque_full) to true
        st.notice == Notice.GIVEAWAY && (st.aim != null || st.grab?.snap != null) -> t(R.string.plaque_giveaway) to true
        st.notice == Notice.PULL_MORE && st.slideHeld -> t(R.string.plaque_pull_more) to true
        st.phase == Phase.CLEARING -> t(R.string.plaque_clearing) to false
        st.notice == Notice.RESUMED && st.noticeFresh(now, GameFlow.RESUMED_MS) -> t(R.string.plaque_resumed) to false
        st.phase == Phase.THINKING -> t(R.string.plaque_thinking) to false
        st.phase == Phase.DROPPING && !two && st.drop?.seat == Seat.YELLOW -> t(R.string.plaque_thinking) to false // its disc is still falling
        two -> t(if (st.pos.toMove == Seat.RED) R.string.plaque_pass_red else R.string.plaque_pass_yellow) to false
        else -> t(R.string.plaque_your_turn) to false
    }
}

/** Hit entity (or an ancestor) → the holder name the game knows (columns, the own tray, the slide handle). */
internal class BoardTargets : TargetResolver<String> by TargetResolver.byName(::targetOf) {
    companion object {
        fun targetOf(name: String): String? = name.takeIf { BoardNames.hitOf(it) != BoardNames.NoHit }

        /** Hit replay (build.md 命中回放): [path] = the hit node's name first, then its parents'. */
        fun resolvePath(path: List<String>): String? = io.embodit.fourdrop.ui.board.input.resolvePath(path, ::targetOf)

        /** The 「square」 a target stands for in input-log lines: a column, a tray, the handle, or "-". */
        fun squareOf(target: String?): String = when (val h = BoardNames.hitOf(target)) {
            is BoardNames.ColumnHit -> "col${h.col + 1}"
            is BoardNames.TrayHit -> "tray-${h.seat.name.lowercase()}"
            BoardNames.HandleHit -> "handle"
            BoardNames.NoHit -> "-"
        }
    }
}

/** Every resolved tap / drag goes through [routeTap] / [GameFlow] (the same calls the panel and the debug broadcast make). */
internal class BoardListener(private val graph: Graph, private val scene: BoardScene) : BoardInputListener<String> {
    private var dragging: BoardNames.Hit = BoardNames.NoHit

    override fun onTap(target: String?, source: InputSource, position: Offset3D?) {
        BoardInputLog.write(TAG, "INPUT tap src=${source.label} target=$target -> square=${BoardTargets.squareOf(target)}")
        routeTap(graph, target)
    }

    override fun onDragStart(target: String, source: InputSource): Boolean {
        BoardInputLog.write(TAG, "INPUT drag src=${source.label} target=$target -> square=${BoardTargets.squareOf(target)}")
        if (!graph.window.panelOpen) { graph.window.request("pinch"); return true } // S10: the first pinch only brings the panel back
        dragging = routeDragStart(graph.flow, target)
        return dragging != BoardNames.NoHit
    }

    override fun onDragMove(totalM: Offset3M) = routeDragMove(graph.flow, dragging, totalM, SceneHolder.scale)

    override fun onDragEnd() { routeDragEnd(graph.flow, dragging); dragging = BoardNames.NoHit }

    override fun onDragCancel() {
        when (dragging) { is BoardNames.TrayHit -> graph.flow.grabCancel(); BoardNames.HandleHit -> graph.flow.slideCancel(); else -> Unit }
        dragging = BoardNames.NoHit
    }

    /** D50 hover: a hint only, never a state change. */
    override fun onHover(target: String?, source: InputSource) { scene.setHover(target) }
}

/** Holder name → the one flow call (S10: the first tap reopens the panel and is consumed). */
internal fun routeTap(graph: Graph, name: String?) {
    if (!graph.window.panelOpen) { Log.i(TAG, "S10 tap $name -> reopen panel"); graph.window.request("pinch"); return }
    routeFlow(graph.flow, name)
}

/** [routeTap] without the Android graph (hit-replay unit tests drive a bare GameFlow through it). */
internal fun routeFlow(flow: GameFlow, name: String?) {
    when (val h = BoardNames.hitOf(name)) {
        is BoardNames.ColumnHit -> flow.tapColumn(h.col)
        is BoardNames.TrayHit -> flow.tapTray(h.seat)
        BoardNames.HandleHit -> Unit // the handle only pulls (a tap does nothing)
        BoardNames.NoHit -> Unit
    }
}

/** A drag that starts on the own tray carries its top disc; on the handle it pulls the slide. */
internal fun routeDragStart(flow: GameFlow, name: String?): BoardNames.Hit = when (val h = BoardNames.hitOf(name)) {
    is BoardNames.TrayHit -> if (flow.grabStart(h.seat)) h else BoardNames.NoHit
    BoardNames.HandleHit -> if (flow.slideStart()) h else BoardNames.NoHit
    is BoardNames.ColumnHit -> BoardNames.NoHit
    BoardNames.NoHit -> BoardNames.NoHit
}

/** Physical metres → content metres (÷ the volume scale), then the disc follows x / y, the slide follows +z. */
internal fun routeDragMove(flow: GameFlow, hit: BoardNames.Hit, totalM: Offset3M, scale: Float) {
    val k = VolumeScale.toContent(1f, scale)
    when (hit) {
        is BoardNames.TrayHit -> flow.grabMove(totalM.x * k, totalM.y * k)
        BoardNames.HandleHit -> flow.slideMove(totalM.z * k)
        else -> Unit
    }
}

internal fun routeDragEnd(flow: GameFlow, hit: BoardNames.Hit) {
    when (hit) { is BoardNames.TrayHit -> flow.grabEnd(); BoardNames.HandleHit -> flow.slideEnd(); else -> Unit }
}

