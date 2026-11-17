package io.embodit.fourdrop.ui.board

import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import io.embodit.fourdrop.domain.Cell
import io.embodit.fourdrop.domain.Layout
import io.embodit.fourdrop.domain.Motion
import io.embodit.fourdrop.domain.Notice
import io.embodit.fourdrop.domain.Phase
import io.embodit.fourdrop.domain.PlayState
import io.embodit.fourdrop.domain.Rules
import io.embodit.fourdrop.domain.Seat
import io.embodit.fourdrop.domain.Settings
import io.embodit.fourdrop.domain.V3
import io.embodit.fourdrop.platform.TAG
import io.embodit.fourdrop.ui.board.input.GazeTargets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sin

/** How a disc looks: plain lacquer, dimmed (not part of the four), glowing (the four). */
enum class DiscLook { NORMAL, DIM, GLOW }

data class DiscPose(val pos: V3, val rot: Q4, val visible: Boolean = true, val look: DiscLook = DiscLook.NORMAL)

/**
 * Where each of the 42 discs is this frame — pure, so the D35 / geometry unit tests use the same math as the scene.
 * Disc ids: red 0..20, yellow 21..41; disc i of a seat lies in tray slot 20 − i until it is played (Layout.traySlot).
 */
object DiscPoses {
    /** Upright in the rack, facing the user (the mesh axis is Z). */
    val UP: Q4 = Q4.ID
    /** Lying flat in the tray. */
    val FLAT: Q4 = Q4.rotX((-PI / 2).toFloat())

    fun id(seat: Seat, i: Int) = seat.ordinal * Rules.DISCS_PER_SEAT + i

    private fun lerp(a: DiscPose, b: DiscPose, u: Float, arc: Float = 0f) =
        DiscPose(a.pos.lerp(b.pos, u) + V3(0f, arc * sin(PI.toFloat() * u.coerceIn(0f, 1f)), 0f), a.rot.slerp(b.rot, u))

    fun tray(seat: Seat, i: Int) = DiscPose(Layout.traySlot(seat, i), FLAT)
    fun over(col: Int, y: Float = Layout.LIFT_Y) = DiscPose(V3(Layout.colX(col), y, 0f), UP)

    fun all(st: PlayState, now: Long, reduced: Boolean): List<DiscPose> {
        val slow = st.slow.coerceAtLeast(1)
        fun e(start: Long): Long = ((now - start) / slow).coerceAtLeast(0)
        val out = ArrayList<DiscPose>(Rules.CELLS)
        val line = st.winLine?.toSet()
        val dump = st.dump
        for (seat in Seat.entries) for (i in 0 until Rules.DISCS_PER_SEAT) {
            out += if (dump != null) dumpPose(dump, seat, i, e(dump.startedAt), reduced) else livePose(st, seat, i, now, ::e, reduced, line)
        }
        return out
    }

    private fun dumpPose(d: io.embodit.fourdrop.domain.Dump, seat: Seat, i: Int, t: Long, reduced: Boolean): DiscPose {
        val dd = d.plan.firstOrNull { it.seat == seat && it.trayIndex == i } ?: return tray(seat, i)
        if (reduced) return tray(seat, i)
        val cell = Layout.cell(dd.cell.col, dd.cell.row)
        return when {
            t < dd.fallStart -> DiscPose(cell, UP)
            t < dd.fallEnd -> { val s = (t - dd.fallStart) / 1000f; DiscPose(V3(cell.x, cell.y - 0.5f * Motion.G * s * s, 0f), UP) }
            t < dd.returnStart -> DiscPose(V3(cell.x, Motion.DUMP_FLOOR_Y, 0f), UP, visible = false)
            else -> {
                val u = Motion.easeOutCubic((t - dd.returnStart).toFloat() / Motion.DUMP_RETURN_MS)
                val slot = Layout.traySlot(seat, i)
                DiscPose(slot + V3(0f, 0.02f * (1 - u), 0f), FLAT)
            }
        }
    }

    private fun livePose(st: PlayState, seat: Seat, i: Int, now: Long, e: (Long) -> Long, reduced: Boolean, line: Set<Cell>?): DiscPose {
        val pos = st.pos
        val used = pos.used(seat)
        if (i < used) {
            val cell = cellOf(st, seat, i)
            val look = when { line == null -> DiscLook.NORMAL; cell in line -> DiscLook.GLOW; else -> DiscLook.DIM }
            val d = st.drop
            if (d != null && d.seat == seat && d.trayIndex == i) {
                val t = e(d.startedAt)
                if (t < d.durationMs) return DiscPose(V3(d.x0, Motion.dropY(d.y0, d.row, t, reduced), 0f), UP, look = look)
            }
            return DiscPose(Layout.cell(cell.col, cell.row), UP, look = look)
        }
        st.rises.firstOrNull { it.seat == seat && it.trayIndex == i }?.let { r ->
            val t = e(r.startedAt)
            if (t < r.durationMs) {
                if (reduced) return tray(seat, i)
                val fall = r.durationMs - Motion.TO_TRAY_MS
                return if (t < fall) DiscPose(V3(Layout.colX(r.cell.col), Motion.dropY(Layout.LIFT_Y, r.cell.row, fall - t, false), 0f), UP)
                else lerp(over(r.cell.col), tray(seat, i), Motion.easeInOutCubic((t - fall).toFloat() / Motion.TO_TRAY_MS), arc = 0.04f)
            }
        }
        if (i == used) {
            val hand = st.handSeat == seat
            val aim = st.aim
            if (hand && aim != null) {
                val t = e(aim.startedAt)
                val from = aim.from?.let { over(it) } ?: tray(seat, i)
                val dur = if (aim.from == null) Motion.LIFT_MS else Motion.MOVE_MS
                val u = if (reduced) 1f else if (aim.from == null) Motion.easeOutCubic(t.toFloat() / dur) else Motion.easeInOutCubic(t.toFloat() / dur)
                val shake = if (st.notice == Notice.FULL) Motion.shake(e(st.noticeAt), reduced) else 0f
                val p = lerp(from, over(aim.col), u, arc = if (aim.from == null) 0f else 0.01f)
                return p.copy(pos = p.pos + V3(0f, shake, 0f))
            }
            val g = st.grab
            if (hand && g != null) {
                val t = e(g.startedAt)
                val x = g.snap?.let { Layout.colX(it) } ?: g.x
                val target = DiscPose(V3(x, g.y, 0f), UP)
                val shake = if (st.notice == Notice.FULL) Motion.shake(e(st.noticeAt), reduced) else 0f
                val p = if (reduced || t >= Motion.LIFT_MS) target else lerp(tray(seat, i), target, Motion.easeOutCubic(t.toFloat() / Motion.LIFT_MS))
                return p.copy(pos = p.pos + V3(0f, shake, 0f))
            }
            val ai = st.aiLift
            if (seat == Seat.YELLOW && ai != null && st.phase == Phase.THINKING) {
                val t = e(ai.startedAt)
                return if (reduced) over(ai.col) else lerp(tray(seat, i), over(ai.col), Motion.easeOutCubic(t.toFloat() / Motion.LIFT_MS))
            }
            val b = st.back
            if (b != null && b.seat == seat && b.trayIndex == i) {
                val t = e(b.startedAt)
                if (!reduced && t < Motion.TO_TRAY_MS) return lerp(DiscPose(b.from, UP), tray(seat, i), Motion.easeInOutCubic(t.toFloat() / Motion.TO_TRAY_MS), arc = 0.03f)
            }
        }
        return tray(seat, i)
    }

    /** The cell of [seat]'s disc [i] (its i-th move). */
    fun cellOf(st: PlayState, seat: Seat, i: Int): Cell {
        val h = IntArray(Rules.COLS)
        var k = 0
        st.pos.moves.forEachIndexed { m, c ->
            val row = h[c]++
            if (st.pos.seatAt(m) == seat) { if (k == i) return Cell(c, row); k++ }
        }
        return Cell(0, 0)
    }

    /** The slide's pull this frame (held by hand, springing back, or opening for the dump). */
    fun slidePull(st: PlayState, now: Long, reduced: Boolean): Float {
        val slow = st.slow.coerceAtLeast(1)
        val d = st.dump
        if (d != null) {
            if (reduced) return 0f
            val t = (now - d.startedAt) / slow
            val back = d.plan.maxOfOrNull { it.returnStart } ?: 0L
            return if (t < back) Layout.SLIDE_MAX * Motion.easeOutCubic(t / 150f)
            else Layout.SLIDE_MAX * (1f - Motion.easeOutBack((t - back).toFloat() / Motion.SLIDE_BACK_MS)).coerceIn(-0.1f, 1f)
        }
        if (st.slideHeld) return st.slide
        val b = st.slideBack ?: return 0f
        val t = (now - b.startedAt) / slow
        if (reduced || t >= Motion.SLIDE_BACK_MS) return 0f
        return b.from * (1f - Motion.easeOutBack(t.toFloat() / Motion.SLIDE_BACK_MS))
    }

    /** The column the drop-point ring marks (aimed or carried over), or null. */
    fun ringColumn(st: PlayState): Int? {
        if (!st.humanTurn) return null
        val c = st.aim?.col ?: st.grab?.snap ?: return null
        return if (st.pos.isFull(c)) null else c
    }
}

/**
 * Every entity of the volume. Compose hands over [PlayState] + [Settings] through [apply]; everything that moves is
 * placed in [onFrame] (driven by BoardFrameSystem), never through Compose (spatial-sdk-guideline).
 *
 * ```
 * root (volume centre; +X right, +Y up, +Z toward the user; scaled with the volume)
 * ├ base (+ the dark slot under the columns), two posts, front / back hole plates, 8 ribs
 * ├ slide (moves along +Z), "handle" (holder: collider + hover) → brass handle
 * ├ "tray:RED" / "tray:YELLOW" (holders on the hand seat's tray only) → tray block
 * ├ 7 × "col:c" (holder: 6.15 × 56 × 4 cm collider) → column-mouth number plate (the hover is drawn on it)
 * ├ 42 discs "disc:<seat>:<i>" (no collider), the drop-point ring, the light bar
 * ├ seat plaque, two count plaques on the base front, key light
 * ```
 */
internal class BoardScene(private val scope: CoroutineScope) {
    val root: Entity = Entity().apply { setName("root") }

    private var res: SceneResources? = null
    private var built = false
    private var state = PlayState()
    private var settings = Settings()
    private var plaque = PlaqueText("", false, false)
    private var orientationTest = false
    private var scale = 1f

    private val discs = ArrayList<ModelEntity>()
    private val discLook = Array(Rules.CELLS) { DiscLook.NORMAL }
    private val columns = ArrayList<Entity>()
    private val trays = HashMap<Seat, Entity>()
    private val trayPickable = HashMap<Seat, Boolean>()
    private var slide: Entity? = null
    private var handle: Entity? = null
    private var ring: ModelEntity? = null
    private var bar: ModelEntity? = null
    private var seatMat: UnlitMaterial? = null
    private var seatTex: TextureResource? = null
    private var seatShown: String? = null
    private var seatBusy = false
    private val countMats = HashMap<Seat, UnlitMaterial>()
    private val countTex = HashMap<Seat, TextureResource>()
    private val countShown = HashMap<Seat, String>()
    private var countBusy = false
    private var mouthTestShown = false
    private val drawn = Array(Rules.CELLS) { V3.ZERO }

    @Volatile private var hover: String? = null

    /** D50 hover from the board-input template: a hint only, never a state change. */
    fun setHover(name: String?) {
        if (name == hover) return
        hover = name
        if (name != null) Log.i(TAG, "HOVER $name")
    }

    val ready: Boolean get() = built

    private fun place(e: Entity, v: V3) = e.transform().setPosition(v.sdk())

    /** The volume's uniform scale (VolumeScale): the whole content follows the system resize. */
    fun setScale(k: Float) {
        if (k == scale) return
        scale = k
        root.transform().setScaleVector(Vector3(k, k, k))
        Log.i(TAG, "SCALE root=${"%.3f".format(k)}")
    }

    private fun unlitTextured(r: SceneResources): UnlitMaterial =
        UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(Color4(1f, 1f, 1f, 1f)); setCullingMode(MaterialCullingMode.BACK); runCatching { setApplyToneMapping(false) } }.also { r.keep(it) }

    fun install(r: SceneResources) {
        if (built) { r.close(); return }
        res = r
        val t0 = SystemClock.elapsedRealtime()
        val L = Layout
        // ---- base, posts, plates, ribs
        root.addChild(ModelEntity(r.base, r.oakDark).apply { setName("base"); place(this, L.BASE.centre) })
        root.addChild(ModelEntity(r.slot, r.slotDark).apply {
            setName("baseSlot"); place(this, V3(0f, L.BASE_TOP + 0.0006f, 0f))
            transform().setQuaternion(Q4.rotX((-PI / 2).toFloat()).sdk())
        })
        for (side in listOf(-1, 1)) root.addChild(ModelEntity(r.post, r.oak).apply { setName("post$side"); place(this, L.post(side).centre) })
        root.addChild(ModelEntity(r.plate, r.oak).apply { setName("plateFront"); place(this, V3(0f, 0f, L.PLATE_Z0 + L.PLATE_T / 2)) })
        root.addChild(ModelEntity(r.plate, r.oak).apply { setName("plateBack"); place(this, V3(0f, 0f, -(L.PLATE_Z0 + L.PLATE_T / 2))) })
        for (k in 0..Rules.COLS) root.addChild(ModelEntity(r.rib, r.oak).apply { setName("rib$k"); place(this, V3(L.ribX(k), (L.RACK_TOP + L.SLIDE_Y1) / 2, 0f)) })
        // ---- slide + handle (the handle holder carries the collider; the brass block is the hover's visible mesh)
        slide = ModelEntity(r.slide, r.oakDark).apply { setName("slide"); place(this, L.slide(0f).centre); root.addChild(this) }
        handle = Entity().apply {
            setName(BoardNames.HANDLE)
            place(this, L.handleTarget(0f).centre)
            GazeTargets.install(this, r.handleShape, r.physics)
            addChild(ModelEntity(r.handle, r.brass).apply { setName("handleBrass"); place(this, L.handle(0f).centre - L.handleTarget(0f).centre) })
            root.addChild(this)
        }
        // ---- trays (holders = the whole own tray; the tray block is their visible child)
        for (seat in Seat.entries) {
            val target = L.trayTarget(seat)
            val holder = Entity().apply {
                setName(BoardNames.tray(seat))
                place(this, target.centre)
                addChild(ModelEntity(r.tray, r.oak).apply { setName("trayBlock:$seat"); place(this, L.tray(seat).centre - target.centre) })
            }
            root.addChild(holder)
            trays[seat] = holder
            trayPickable[seat] = false
        }
        // ---- column targets with their numbered mouth plates
        for (c in 0 until Rules.COLS) {
            val target = L.columnTarget(c)
            val holder = Entity().apply { setName(BoardNames.col(c)); place(this, target.centre) }
            GazeTargets.install(holder, r.columnShape, r.physics)
            holder.addChild(ModelEntity(r.mouth, r.mouthMats[c]).apply { setName("mouth:$c"); place(this, V3(0f, L.MOUTH_Y - target.centre.y, L.MOUTH_Z)) })
            root.addChild(holder)
            columns += holder
        }
        // ---- discs (one mesh, 42 clones of the entity, per-seat lacquer)
        for (seat in Seat.entries) for (i in 0 until Rules.DISCS_PER_SEAT) {
            val d = ModelEntity(r.disc, r.lacquer.getValue(seat)).apply { setName("disc:$seat:$i") }
            root.addChild(d)
            discs += d
        }
        ring = ModelEntity(r.ring, r.ringCalm).apply { setName("ring"); enabled = false; root.addChild(this) }
        bar = ModelEntity(r.bar, r.barMat).apply { setName("winBar"); enabled = false; root.addChild(this) }
        // ---- plaques on the base front
        seatMat = unlitTextured(r)
        root.addChild(ModelEntity(r.seatFace, seatMat!!).apply { setName("seatPlaque"); place(this, L.SEAT_PLAQUE.centre) })
        for (seat in Seat.entries) {
            val m = unlitTextured(r)
            countMats[seat] = m
            root.addChild(ModelEntity(r.countFace, m).apply { setName("countPlaque:$seat"); place(this, L.winsPlaque(seat).centre) })
        }
        buildLight()
        built = true
        Log.i(TAG, "SCENE built in ${SystemClock.elapsedRealtime() - t0} ms volume=${L.VOLUME}")
        onFrame(0f)
    }

    private fun buildLight() {
        runCatching {
            val light = Entity().apply { setName("keyLight") }
            // B24: a moderate warm key light from the front right, above (not over-exposed)
            light.components.set(DirectionalLightComponent(Color4(1f, 0.95f, 0.88f, 1f), 0.9f, true))
            light.transform().setQuaternion((Q4.rotY((25 * PI / 180).toFloat()) * Q4.rotX((-40 * PI / 180).toFloat())).sdk())
            root.addChild(light)
        }.onFailure { Log.w(TAG, "LIGHT unavailable: $it") }
    }

    fun apply(next: PlayState, s: Settings, plaqueText: PlaqueText, fTest: Boolean) {
        state = next
        settings = s
        plaque = plaqueText
        orientationTest = fTest
        if (!built) return
        onFrame(0f)
    }

    // ---------------------------------------------------------------- async textures

    private fun refreshSeat() {
        val p = plaque
        val key = "${p.text}|${p.zh}|${p.alert}|$orientationTest"
        if (key == seatShown || seatBusy || p.text.isEmpty()) return
        seatBusy = true
        val test = orientationTest
        scope.launch {
            val tex = withContext(Dispatchers.IO) { runCatching { TextureResource.create(BoardTextures.seatPlaque(p.text, p.zh, p.alert, test)) }.getOrNull() }
            if (tex != null) {
                seatMat?.setBaseColorTexture(tex)
                seatTex?.let { old -> runCatching { old.close() } }
                seatTex = tex
                seatShown = key
                Log.i(TAG, "PLAQUE \"${p.text}\"${if (p.alert) " alert" else ""}")
            }
            seatBusy = false
        }
    }

    private fun refreshCounts() {
        if (countBusy) return
        val st = state
        val keys = Seat.entries.associateWith { seat ->
            val left = if (st.dump != null) 0 else Rules.DISCS_PER_SEAT - st.pos.used(seat)
            val wins = if (seat == Seat.RED) st.winsRed else st.winsYellow
            "$left|$wins|$orientationTest"
        }
        val todo = keys.filter { (seat, k) -> countShown[seat] != k }
        if (todo.isEmpty()) return
        countBusy = true
        val test = orientationTest
        scope.launch {
            for ((seat, k) in todo) {
                val f = k.split("|")
                val tex = withContext(Dispatchers.IO) { runCatching { TextureResource.create(BoardTextures.countPlaque(f[0].toInt(), f[1].toInt(), seat == Seat.RED, test)) }.getOrNull() }
                if (tex != null) {
                    countMats[seat]?.setBaseColorTexture(tex)
                    countTex[seat]?.let { old -> runCatching { old.close() } }
                    countTex[seat] = tex
                    countShown[seat] = k
                }
            }
            countBusy = false
        }
    }

    private fun refreshMouthTest(r: SceneResources) {
        if (orientationTest == mouthTestShown) return
        mouthTestShown = orientationTest
        scope.launch {
            val tex = withContext(Dispatchers.IO) { (1..Rules.COLS).map { n -> runCatching { r.keep(TextureResource.create(BoardTextures.mouthNumber(n, orientationTest))) }.getOrNull() } }
            tex.forEachIndexed { c, t -> t?.let { r.mouthMats[c].setBaseColorTexture(it) } }
        }
    }

    // ---------------------------------------------------------------- per frame

    fun onFrame(@Suppress("UNUSED_PARAMETER") dt: Float) {
        if (!built) return
        val r = res ?: return
        val st = state
        val now = SystemClock.uptimeMillis()
        val reduced = settings.reducedMotion
        refreshSeat(); refreshCounts(); refreshMouthTest(r)

        // ---- discs
        val poses = DiscPoses.all(st, now, reduced)
        for ((k, p) in poses.withIndex()) {
            val d = discs[k]
            drawn[k] = p.pos
            d.enabled = p.visible
            place(d, p.pos)
            d.transform().setQuaternion(p.rot.sdk())
            if (discLook[k] != p.look) {
                discLook[k] = p.look
                val seat = if (k < Rules.DISCS_PER_SEAT) Seat.RED else Seat.YELLOW
                val mat: Material = when (p.look) { DiscLook.NORMAL -> r.lacquer.getValue(seat); DiscLook.DIM -> r.lacquerDim.getValue(seat); DiscLook.GLOW -> r.lacquerGlow.getValue(seat) }
                d.components[ModelComponent::class.java]?.materials?.set(0, mat)
            }
        }

        // ---- slide + handle
        val pull = DiscPoses.slidePull(st, now, reduced)
        slide?.let { place(it, Layout.slide(pull).centre) }
        handle?.let { place(it, Layout.handleTarget(pull).centre) }

        // ---- only the hand seat's tray takes taps / pinches (design targets.trayTop: the other tray is not clickable)
        for (seat in Seat.entries) {
            val want = st.phase != Phase.LOADING && st.handSeat == seat
            if (trayPickable[seat] != want) {
                trayPickable[seat] = want
                val e = trays.getValue(seat)
                if (want) GazeTargets.install(e, r.trayShape, r.physics)
                else { e.components.remove(CollisionComponent::class.java); e.components.remove(InteractableComponent::class.java); e.components.remove(HoverEffectComponent::class.java) }
            }
        }

        // ---- drop-point ring (calm, or orange when Easy warns of a giveaway)
        val rc = DiscPoses.ringColumn(st)
        ring?.let { e ->
            if (rc == null) e.enabled = false else {
                val warn = st.notice == Notice.GIVEAWAY
                e.components[ModelComponent::class.java]?.materials?.set(0, if (warn) r.ringWarn else r.ringCalm)
                val cell = Layout.cell(rc, st.pos.heights[rc])
                place(e, V3(cell.x, cell.y, Layout.RING_Z))
                e.enabled = true
            }
        }

        // ---- column mouths: first-game glow (breathing), red on a full-column refusal
        val fullCol = if (st.notice == Notice.FULL && st.noticeFresh(now)) (st.aim?.col ?: st.grab?.snap) else null
        val lit = st.litLeft > 0 && st.humanTurn
        val breath = (sin(now / 450.0) * 0.5 + 0.5).toFloat()
        for (c in 0 until Rules.COLS) {
            val col = when {
                c == fullCol -> Color4(0.96f, 0.48f, 0.42f, 1f)
                lit -> Color4(0.93f + 0.07f * breath, 0.85f + 0.12f * breath, 0.72f - 0.10f * breath, 1f)
                else -> Color4(0.93f, 0.85f, 0.72f, 1f)
            }
            r.mouthMats[c].setBaseColor(col)
        }

        // ---- the light bar over the four (wipes in 350 ms)
        val line = st.winLine
        bar?.let { b ->
            if (line == null) b.enabled = false else {
                val a = Layout.cell(line.first().col, line.first().row)
                val z = Layout.cell(line.last().col, line.last().row)
                val u = if (reduced) 1f else Motion.easeOutCubic(((now - st.overAt) / st.slow.coerceAtLeast(1)).toFloat() / Motion.WINBAR_MS)
                val dv = z - a
                val len = dv.length * u + Layout.WINBAR_W
                place(b, V3(a.x + dv.x * u / 2, a.y + dv.y * u / 2, Layout.WINBAR_Z))
                b.transform().setQuaternion(Q4.rotZ(atan2(dv.y, dv.x)).sdk())
                b.transform().setScaleVector(Vector3(len, 1f, 1f))
                b.enabled = true
            }
        }
    }

    /** Debug hit replay (template README 必带单测 4): the holder named [target] and its deepest child mesh. */
    fun replayEntities(target: String): List<Entity> {
        val out = ArrayList<Entity>()
        fun deepest(e: Entity): Entity { var x = e; while (x.getChildren().isNotEmpty()) x = x.getChildren().first(); return x }
        fun visit(e: Entity) {
            if (runCatching { e.getName() }.getOrNull() == target && e.enabled) { out += e; deepest(e).takeIf { it !== e }?.let { out += it } }
            e.getChildren().forEach { visit(it) }
        }
        visit(root)
        return out
    }

    fun evidence(): String {
        val st = state
        val placed = (0 until Rules.CELLS).filter { discs.getOrNull(it)?.enabled == true }.size
        return "volume=${Layout.VOLUME} scale=$scale plaque=\"$seatShown\" counts=$countShown discsVisible=$placed " +
            "trayPick=${trayPickable} ring=${ring?.enabled} bar=${bar?.enabled} slide=${"%.3f".format(DiscPoses.slidePull(st, SystemClock.uptimeMillis(), settings.reducedMotion))} " +
            "lifted=" + (0 until Rules.CELLS).filter { drawn[it].y > Layout.SNAP_Y }.joinToString(",") { "$it@${drawn[it]}" }
    }

    fun dispose() {
        seatTex?.let { runCatching { it.close() } }
        countTex.values.forEach { runCatching { it.close() } }
        res?.close()
        res = null
    }
}

/** The seat plaque's line (the same source as the panel's status row, D38). */
data class PlaqueText(val text: String, val zh: Boolean, val alert: Boolean)

/** Every holder name (one place; never built inside Entity().apply {} where `id` means the entity's id, build.md). */
object BoardNames {
    const val HANDLE = "handle"
    fun col(c: Int) = "col:$c"
    fun tray(seat: Seat) = "tray:$seat"

    sealed interface Hit
    data class ColumnHit(val col: Int) : Hit
    data class TrayHit(val seat: Seat) : Hit
    data object HandleHit : Hit
    data object NoHit : Hit

    /** A holder name → what was hit; out-of-range names resolve to nothing (a naming bug shows in the log). */
    fun hitOf(name: String?): Hit {
        if (name == null) return NoHit
        val f = name.split(":")
        return when (f[0]) {
            "col" -> f.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0 until Rules.COLS && f.size == 2 }?.let { ColumnHit(it) } ?: NoHit
            "tray" -> if (f.size == 2) Seat.entries.firstOrNull { it.name == f[1] }?.let { TrayHit(it) } ?: NoHit else NoHit
            HANDLE -> if (f.size == 1) HandleHit else NoHit
            else -> NoHit
        }
    }
}
