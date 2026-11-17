package io.embodit.fourdrop.ui.board

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import io.embodit.fourdrop.domain.Layout
import io.embodit.fourdrop.domain.Rules
import io.embodit.fourdrop.domain.Seat
import io.embodit.fourdrop.domain.V3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!

internal fun V3.sdk() = Vector3(x, y, z)

/** RGB int → linear-ish Color4 for materials. */
internal fun rgb(c: Int, a: Float = 1f) = Color4(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f, a)

/**
 * Everything the volume draws, created once off the main thread (resource creation waits on the renderer, so it runs
 * concurrently on Dispatchers.IO) and kept global; [close] releases it (design budget: ≤ 9 shared meshes, the disc is
 * one mesh cloned 42 times; 5 material families: oak, red lacquer, yellow lacquer, brass, unlit marks).
 */
internal class SceneResources private constructor() {
    private val all = ArrayList<Resource>()
    fun <T : Resource> keep(r: T): T { r.toGlobal(); synchronized(all) { all += r }; return r }
    fun close() { synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() } }

    lateinit var base: MeshResource; private set
    lateinit var slot: MeshResource; private set
    lateinit var post: MeshResource; private set
    lateinit var plate: MeshResource; private set
    lateinit var rib: MeshResource; private set
    lateinit var slide: MeshResource; private set
    lateinit var handle: MeshResource; private set
    lateinit var tray: MeshResource; private set
    lateinit var disc: MeshResource; private set
    lateinit var ring: MeshResource; private set
    lateinit var mouth: MeshResource; private set
    lateinit var bar: MeshResource; private set
    lateinit var seatFace: MeshResource; private set
    lateinit var countFace: MeshResource; private set

    lateinit var oak: PhysicallyBasedMaterial; private set
    lateinit var oakDark: PhysicallyBasedMaterial; private set
    lateinit var brass: PhysicallyBasedMaterial; private set
    lateinit var slotDark: UnlitMaterial; private set
    /** Disc lacquer per seat: normal, dimmed (the losers after a four), glowing (the four). */
    lateinit var lacquer: Map<Seat, PhysicallyBasedMaterial>; private set
    lateinit var lacquerDim: Map<Seat, PhysicallyBasedMaterial>; private set
    lateinit var lacquerGlow: Map<Seat, PhysicallyBasedMaterial>; private set
    lateinit var ringCalm: UnlitMaterial; private set
    lateinit var ringWarn: UnlitMaterial; private set
    lateinit var barMat: UnlitMaterial; private set
    /** One per column so each mouth can be tinted by its own state (lit / full); textured with its number. */
    lateinit var mouthMats: List<UnlitMaterial>; private set
    lateinit var mouthTex: List<TextureResource>; private set
    lateinit var physics: PhysicsMaterialResource; private set
    lateinit var columnShape: ShapeResource; private set
    lateinit var trayShape: ShapeResource; private set
    lateinit var handleShape: ShapeResource; private set

    companion object {
        const val RED = 0xC8412E
        const val YELLOW = 0xEBD9A8

        fun pbr(color: Int, rough: Float, metal: Float = 0f, tex: TextureResource? = null, glow: Float = 0.05f): PhysicallyBasedMaterial =
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                if (tex != null) { setBaseColor(Color4(1f, 1f, 1f, 1f)); setBaseColorTexture(tex) } else setBaseColor(rgb(color))
                setRoughness(rough); setMetallic(metal)
                setEmissiveColor(rgb(scale(color, glow)))
                setCullingMode(MaterialCullingMode.NONE)
            }

        fun unlit(color: Color4, tex: TextureResource? = null): UnlitMaterial =
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color)
                tex?.let { setBaseColorTexture(it) }
                setCullingMode(MaterialCullingMode.BACK)
                runCatching { setApplyToneMapping(false) }
            }

        suspend fun create(): SceneResources = coroutineScope {
            val r = SceneResources()
            fun <T : Resource> T.k(): T = r.keep(this)
            val io = Dispatchers.IO
            val L = Layout
            val oakTex = async(io) { TextureResource.create(BoardTextures.wood(BoardTextures.OAK, 5)).k() }
            val oakDarkTex = async(io) { TextureResource.create(BoardTextures.wood(BoardTextures.OAK_DARK, 9)).k() }
            val mouthTex = (1..Rules.COLS).map { n -> async(io) { TextureResource.create(BoardTextures.mouthNumber(n)).k() } }
            listOf(
                async(io) { r.base = Meshes.block(L.BASE_W, L.BASE_H, L.BASE_D, 0.012f, 0.003f, "base").k() },
                async(io) { r.slot = Meshes.face(L.BASE_SLOT_W, L.BASE_SLOT_D, "slot").k() },
                async(io) { r.post = Meshes.block(L.POST_W, L.RACK_TOP - L.BASE_TOP, L.POST_D, 0.004f, 0.0015f, "post").k() },
                async(io) { r.plate = Meshes.holePlate(L.PLATE_W, L.PLATE_Y0, L.RACK_TOP, L.PLATE_T, Rules.COLS, Rules.ROWS, L.COL_PITCH, L.ROW_PITCH, L.ROW0_Y, L.HOLE_D, "plate").k() },
                async(io) { r.rib = Meshes.block(L.RIB_T, L.RACK_TOP - L.SLIDE_Y1, L.PLATE_GAP, 0.0005f, 0.0003f, "rib").k() },
                async(io) { r.slide = Meshes.block(L.SLIDE_W, L.SLIDE_Y1 - L.SLIDE_Y0, L.SLIDE_D, 0.003f, 0.001f, "slide").k() },
                async(io) { r.handle = Meshes.block(L.HANDLE_W, L.HANDLE_Y1 - L.HANDLE_Y0, L.HANDLE_Z1 - L.HANDLE_Z0, 0.005f, 0.0015f, "handle").k() },
                async(io) { r.tray = Meshes.block(L.TRAY_W, L.TRAY_H, L.TRAY_D, 0.01f, 0.002f, "tray").k() },
                async(io) { r.disc = Meshes.disc(L.DISC_R, L.DISC_T, L.DISC_CHAMFER, "disc").k() },
                async(io) { r.ring = Meshes.annulus(0.0365f, 0.0330f, "ring").k() },
                async(io) { r.mouth = Meshes.face(L.MOUTH_W, L.MOUTH_H, "mouth").k() },
                async(io) { r.bar = Meshes.face(1f, L.WINBAR_W, "winBar").k() },
                async(io) { r.seatFace = Meshes.face(L.SEAT_PLAQUE.size.x, L.SEAT_PLAQUE.size.y, "seatPlaque").k() },
                async(io) { r.countFace = Meshes.face(0.10f, 0.04f, "countPlaque").k() },
                async(io) { r.columnShape = ShapeResource.createBox(L.COLUMN_TARGET.sdk()).k() },
                async(io) { r.trayShape = ShapeResource.createBox(L.trayTarget(Seat.RED).size.sdk()).k() },
                async(io) { r.handleShape = ShapeResource.createBox(L.HANDLE_TARGET.sdk()).k() },
                async(io) { r.physics = PhysicsMaterialResource().k() },
            ).awaitAll()
            val ot = oakTex.await()
            r.oak = pbr(BoardTextures.OAK and 0xFFFFFF, 0.6f, tex = ot).k()
            r.oakDark = pbr(BoardTextures.OAK_DARK and 0xFFFFFF, 0.65f, tex = oakDarkTex.await()).k()
            r.brass = pbr(0xB8913F, 0.35f, metal = 0.85f).k()
            r.slotDark = unlit(rgb(0x1A140F)).k()
            r.lacquer = mapOf(Seat.RED to pbr(RED, 0.35f).k(), Seat.YELLOW to pbr(YELLOW, 0.35f).k())
            r.lacquerDim = mapOf(Seat.RED to pbr(scale(RED, 0.4f), 0.45f, glow = 0f).k(), Seat.YELLOW to pbr(scale(YELLOW, 0.4f), 0.45f, glow = 0f).k())
            r.lacquerGlow = mapOf(Seat.RED to pbr(RED, 0.3f, glow = 0.55f).k(), Seat.YELLOW to pbr(YELLOW, 0.3f, glow = 0.45f).k())
            r.ringCalm = unlit(rgb(0xDFF6FF)).k()
            r.ringWarn = unlit(rgb(0xFF9A2E)).k()
            r.barMat = unlit(rgb(0xFFF1C2)).k()
            r.mouthTex = mouthTex.awaitAll()
            r.mouthMats = r.mouthTex.map { unlit(Color4(0.93f, 0.85f, 0.72f, 1f), it).k() }
            r
        }

        /** Each RGB channel times [k]. */
        fun scale(c: Int, k: Float): Int = listOf(16, 8, 0).fold(0) { acc, sh -> acc or (((((c shr sh) and 255) * k).toInt().coerceIn(0, 255)) shl sh) }
    }
}
