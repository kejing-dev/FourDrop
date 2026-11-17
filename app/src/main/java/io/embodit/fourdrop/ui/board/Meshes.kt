package io.embodit.fourdrop.ui.board

import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Programmatic meshes (design assets: 「程序化…运行时网格」, no Tripo — the 42 holes, the plate gap and the column width
 * must match the rules grid to the millimetre). Blocks are chamfered rounded rectangles, the plates are a 7 × 6 grid of
 * square frames around round holes, the disc is a smooth chamfered cylinder. Everything is built once off the main
 * thread (SceneResources) with planar UVs for the oak grain texture.
 */
internal object Meshes {
    data class P2(val x: Float, val y: Float) {
        operator fun plus(o: P2) = P2(x + o.x, y + o.y)
        operator fun minus(o: P2) = P2(x - o.x, y - o.y)
        operator fun times(k: Float) = P2(x * k, y * k)
        val length: Float get() = sqrt(x * x + y * y)
        fun dot(o: P2) = x * o.x + y * o.y
    }

    class Builder {
        val pos = ArrayList<Vector3>()
        val nor = ArrayList<Vector3>()
        val uv = ArrayList<Vector2>()
        val idx = ArrayList<Int>()

        /** A flat-shaded triangle with planar UVs on its dominant plane. */
        fun tri(a: Vector3, b: Vector3, c: Vector3, uvScale: Float) {
            val n = normal(a, b, c)
            val base = pos.size
            for (p in listOf(a, b, c)) {
                pos += p; nor += n
                uv += when {
                    abs(n.y) > 0.6f -> Vector2(p.x / uvScale + 0.5f, p.z / uvScale + 0.5f)
                    abs(n.z) > 0.6f -> Vector2(p.x / uvScale + 0.5f, p.y / uvScale + 0.5f)
                    else -> Vector2(p.z / uvScale + 0.5f, p.y / uvScale + 0.5f)
                }
            }
            idx += base; idx += base + 1; idx += base + 2
        }

        fun quad(a: Vector3, b: Vector3, c: Vector3, d: Vector3, uvScale: Float) { tri(a, b, c, uvScale); tri(a, c, d, uvScale) }

        /** A vertex with its own normal (smooth surfaces); returns its index. */
        fun vertex(p: Vector3, n: Vector3, t: Vector2): Int { pos += p; nor += n; uv += t; return pos.size - 1 }

        fun build(name: String): MeshResource {
            val minX = pos.minOf { it.x }; val maxX = pos.maxOf { it.x }
            val minY = pos.minOf { it.y }; val maxY = pos.maxOf { it.y }
            val minZ = pos.minOf { it.z }; val maxZ = pos.maxOf { it.z }
            val bounds = BoundingBox(
                center = Vector3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2),
                halfExtent = Vector3(max(1e-4f, (maxX - minX) / 2), max(1e-4f, (maxY - minY) / 2), max(1e-4f, (maxZ - minZ) / 2)),
            )
            return MeshResource.createWithMeshModel(MeshModel(positions = pos, triangleIndices = idx, normals = nor, uv0 = uv), bounds, name)
        }
    }

    private fun normal(a: Vector3, b: Vector3, c: Vector3): Vector3 {
        val ux = b.x - a.x; val uy = b.y - a.y; val uz = b.z - a.z
        val vx = c.x - a.x; val vy = c.y - a.y; val vz = c.z - a.z
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-9f)
        return Vector3(nx / l, ny / l, nz / l)
    }

    /** A convex counter-clockwise outline shrunk by [d] (miter offset). */
    fun inset(p: List<P2>, d: Float): List<P2> {
        val n = p.size
        return p.indices.map { i ->
            val prev = p[(i + n - 1) % n]; val cur = p[i]; val next = p[(i + 1) % n]
            val e1 = (cur - prev).let { it * (1f / it.length) }
            val e2 = (next - cur).let { it * (1f / it.length) }
            val n1 = P2(-e1.y, e1.x); val n2 = P2(-e2.y, e2.x)
            val bis = (n1 + n2).let { it * (1f / it.length) }
            cur + bis * (d / bis.dot(n1))
        }
    }

    /** A rounded rectangle (w along x, d along the second axis), counter-clockwise, [seg] segments per corner. */
    fun roundRect(w: Float, d: Float, r: Float, seg: Int = 3): List<P2> {
        val out = ArrayList<P2>()
        val cs = listOf(P2(w / 2 - r, d / 2 - r) to 0.0, P2(-w / 2 + r, d / 2 - r) to 90.0, P2(-w / 2 + r, -d / 2 + r) to 180.0, P2(w / 2 - r, -d / 2 + r) to 270.0)
        for ((c, a0) in cs) for (k in 0..seg) {
            val a = Math.toRadians(a0 + 90.0 * k / seg)
            out += P2(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
        }
        return out
    }

    /**
     * A chamfered block [w] × [h] × [d] (x, y, z) centred on the origin, rounded vertical edges of radius [r]
     * (posts, base, trays, slide, handle, ribs).
     */
    fun block(w: Float, h: Float, d: Float, r: Float, chamfer: Float, name: String, uvScale: Float = 0.3f): MeshResource {
        val b = Builder()
        // outline in (x, -z) so the CCW winding faces +Y
        val outer = roundRect(w, d, r.coerceAtMost(minOf(w, d) / 2 - 1e-4f))
        val c = chamfer.coerceAtMost(minOf(w, d, h) / 3)
        val inner = inset(outer, c)
        fun v(p: P2, y: Float) = Vector3(p.x, y, -p.y)
        val y0 = -h / 2; val y1 = h / 2
        val n = outer.size
        for (i in 0 until n) {
            val a = outer[i]; val e = outer[(i + 1) % n]; val ai = inner[i]; val ei = inner[(i + 1) % n]
            b.quad(v(a, y0 + c), v(e, y0 + c), v(e, y1 - c), v(a, y1 - c), uvScale)
            b.quad(v(a, y1 - c), v(e, y1 - c), v(ei, y1), v(ai, y1), uvScale)
            b.quad(v(ai, y0), v(ei, y0), v(e, y0 + c), v(a, y0 + c), uvScale)
        }
        for (i in 1 until n - 1) {
            b.tri(v(inner[0], y1), v(inner[i], y1), v(inner[i + 1], y1), uvScale)
            b.tri(v(inner[0], y0), v(inner[i + 1], y0), v(inner[i], y0), uvScale)
        }
        return b.build(name)
    }

    /**
     * One rack plate (design spatialLayout.plates): [w] wide, from [y0] to [y1], [t] thick (z −t/2…+t/2), a grid of
     * [cols] × [rows] round holes of diameter [holeD] on [pitchX] × [pitchY] starting at row-centre [rowY0]; above the
     * last row the plate is solid (the column-mouth band). [seg] segments per hole.
     */
    fun holePlate(w: Float, y0: Float, y1: Float, t: Float, cols: Int, rows: Int, pitchX: Float, pitchY: Float, rowY0: Float, holeD: Float, name: String, seg: Int = 24): MeshResource {
        val b = Builder()
        val zf = t / 2; val zb = -t / 2
        val r = holeD / 2
        val uvs = 0.4f
        val gridTop = rowY0 + (rows - 0.5f) * pitchY
        val gridBottom = rowY0 - 0.5f * pitchY
        for (c in 0 until cols) for (rr in 0 until rows) {
            val cx = (c - (cols - 1) / 2f) * pitchX
            val cy = rowY0 + rr * pitchY
            val hx = pitchX / 2; val hy = pitchY / 2
            for (k in 0 until seg) {
                val a0 = 2 * PI * k / seg; val a1 = 2 * PI * (k + 1) / seg
                val c0 = P2(cos(a0).toFloat(), sin(a0).toFloat()); val c1 = P2(cos(a1).toFloat(), sin(a1).toFloat())
                // the square frame point along the same ray (cell boundary)
                fun sq(u: P2): P2 { val s = minOf(hx / abs(u.x).coerceAtLeast(1e-6f), hy / abs(u.y).coerceAtLeast(1e-6f)); return u * s }
                val i0 = c0 * r; val i1 = c1 * r; val o0 = sq(c0); val o1 = sq(c1)
                fun f(p: P2, z: Float) = Vector3(cx + p.x, cy + p.y, z)
                b.quad(f(i0, zf), f(o0, zf), f(o1, zf), f(i1, zf), uvs) // front
                b.quad(f(i1, zb), f(o1, zb), f(o0, zb), f(i0, zb), uvs) // back
                b.quad(f(i0, zb), f(i0, zf), f(i1, zf), f(i1, zb), uvs) // hole wall
                // the square corners are not on any ray: fill the corner triangle when the ray passes a corner
                val corner = listOf(P2(hx, hy), P2(-hx, hy), P2(-hx, -hy), P2(hx, -hy)).firstOrNull { q ->
                    val a = kotlin.math.atan2(q.y, q.x).let { if (it < 0) it + 2 * PI.toFloat() else it }
                    a > a0 && a < a1
                }
                if (corner != null) {
                    b.tri(f(o0, zf), f(corner, zf), f(o1, zf), uvs)
                    b.tri(f(o1, zb), f(corner, zb), f(o0, zb), uvs)
                }
            }
        }
        // solid band above the grid, and a strip under it when the plate reaches lower
        fun slab(ya: Float, yb: Float) {
            if (yb - ya < 1e-4f) return
            val x0 = -w / 2; val x1 = w / 2
            b.quad(Vector3(x0, ya, zf), Vector3(x1, ya, zf), Vector3(x1, yb, zf), Vector3(x0, yb, zf), uvs)
            b.quad(Vector3(x1, ya, zb), Vector3(x0, ya, zb), Vector3(x0, yb, zb), Vector3(x1, yb, zb), uvs)
            b.quad(Vector3(x0, yb, zb), Vector3(x0, yb, zf), Vector3(x1, yb, zf), Vector3(x1, yb, zb), uvs)
        }
        slab(gridTop, y1)
        slab(y0, gridBottom)
        // the two side edges
        for (sgn in listOf(-1f, 1f)) {
            val x = sgn * w / 2
            if (sgn < 0) b.quad(Vector3(x, y0, zb), Vector3(x, y0, zf), Vector3(x, y1, zf), Vector3(x, y1, zb), uvs)
            else b.quad(Vector3(x, y0, zf), Vector3(x, y0, zb), Vector3(x, y1, zb), Vector3(x, y1, zf), uvs)
        }
        return b.build(name)
    }

    /**
     * The disc (design gameSpec.pieces: Ø 7.6 × 1.3 cm, 2 mm chamfer, a shallow concentric groove on each face): smooth
     * normals around the rim, axis along Z (it stands in the rack facing the user; the tray lays it flat).
     */
    fun disc(radius: Float, thick: Float, chamfer: Float, name: String, seg: Int = 32): MeshResource {
        val b = Builder()
        val h = thick / 2
        val rc = radius - chamfer
        val groove = radius * 0.62f
        // profile rings from the front centre outwards and round to the back: (r, z, normal r, normal z)
        val profile = listOf(
            floatArrayOf(0f, h, 0f, 1f),
            floatArrayOf(groove - 0.003f, h, 0f, 1f),
            floatArrayOf(groove, h - 0.0008f, 0f, 1f),
            floatArrayOf(groove + 0.003f, h, 0f, 1f),
            floatArrayOf(rc, h, 0f, 1f),
            floatArrayOf(rc, h, 0.7071f, 0.7071f),
            floatArrayOf(radius, h - chamfer, 0.7071f, 0.7071f),
            floatArrayOf(radius, h - chamfer, 1f, 0f),
            floatArrayOf(radius, -h + chamfer, 1f, 0f),
            floatArrayOf(radius, -h + chamfer, 0.7071f, -0.7071f),
            floatArrayOf(rc, -h, 0.7071f, -0.7071f),
            floatArrayOf(rc, -h, 0f, -1f),
            floatArrayOf(groove + 0.003f, -h, 0f, -1f),
            floatArrayOf(groove, -h + 0.0008f, 0f, -1f),
            floatArrayOf(groove - 0.003f, -h, 0f, -1f),
            floatArrayOf(0f, -h, 0f, -1f),
        )
        val rings = profile.map { pr ->
            IntArray(seg + 1) { k ->
                val a = 2 * PI * k / seg
                val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
                b.vertex(Vector3(pr[0] * ca, pr[0] * sa, pr[1]), Vector3(pr[2] * ca, pr[2] * sa, pr[3]), Vector2(0.5f + pr[0] / (2 * radius) * ca, 0.5f + pr[0] / (2 * radius) * sa))
            }
        }
        for (i in 0 until rings.size - 1) {
            val a = rings[i]; val c = rings[i + 1]
            if (profile[i][0] == profile[i + 1][0] && profile[i][1] == profile[i + 1][1]) continue // normal seam, no area
            for (k in 0 until seg) {
                // outward-facing winding: front (+z) rings go centre → rim
                b.idx += a[k]; b.idx += c[k]; b.idx += c[k + 1]
                b.idx += a[k]; b.idx += c[k + 1]; b.idx += a[k + 1]
            }
        }
        return b.build(name)
    }

    /** A flat ring (annulus) in the XY plane facing +Z: the drop-point marker. */
    fun annulus(rOuter: Float, rInner: Float, name: String, seg: Int = 40): MeshResource {
        val b = Builder()
        for (k in 0 until seg) {
            val a0 = 2 * PI * k / seg; val a1 = 2 * PI * (k + 1) / seg
            fun p(r: Float, a: Double) = Vector3(r * cos(a).toFloat(), r * sin(a).toFloat(), 0f)
            b.quad(p(rInner, a0), p(rOuter, a0), p(rOuter, a1), p(rInner, a1), 1f)
        }
        return b.build(name)
    }

    /** A quad in the XY plane facing +Z, UV (0,0) top-left → (1,1) bottom-right (textured plaques, column numbers, the light bar). */
    fun face(w: Float, h: Float, name: String): MeshResource {
        val pos = listOf(Vector3(-w / 2, h / 2, 0f), Vector3(w / 2, h / 2, 0f), Vector3(w / 2, -h / 2, 0f), Vector3(-w / 2, -h / 2, 0f))
        val nz = Vector3(0f, 0f, 1f)
        val uv = listOf(Vector2(0f, 0f), Vector2(1f, 0f), Vector2(1f, 1f), Vector2(0f, 1f))
        return MeshResource.createWithMeshModel(
            MeshModel(positions = pos, triangleIndices = listOf(0, 3, 2, 0, 2, 1), normals = List(4) { nz }, uv0 = uv),
            BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(w / 2, h / 2, 0.0005f)), name,
        )
    }
}
