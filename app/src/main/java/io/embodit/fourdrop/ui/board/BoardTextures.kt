package io.embodit.fourdrop.ui.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import io.embodit.fourdrop.platform.TAG
import kotlin.math.sin
import kotlin.random.Random

/**
 * Canvas textures (design budget: 橡木 1024² + 小牌 / 列号 Canvas ≤ 512²): procedural light-oak grain, the seat plaque,
 * the two count plaques and the seven column numbers. Text images are checked with the 「F↑」 image first (D10).
 * Plaques are 32 px per cm, so text heights in centimetres map straight to pixels.
 */
object BoardTextures {
    val OAK = Color.rgb(0xC4, 0x9C, 0x68)
    val OAK_DARK = Color.rgb(0xA8, 0x82, 0x55)
    const val PX_PER_M = 3200f

    /** Straight-grain wood around [base]: soft growth rings plus fine streaks (no image assets). */
    fun wood(base: Int, seed: Int, size: Int = 512): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val rng = Random(seed)
        val r0 = Color.red(base); val g0 = Color.green(base); val b0 = Color.blue(base)
        val ph = FloatArray(6) { rng.nextFloat() * 6.28f }
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val fx = x.toFloat() / size; val fy = y.toFloat() / size
            val ring = sin(fx * 26f + sin(fy * 3f + ph[0]) * 1.2f + sin(fy * 13f + ph[1]) * 0.2f + ph[2]) * 0.5f + 0.5f
            val streak = sin(fx * 300f + sin(fy * 6f + ph[3]) * 3f + ph[4]) * 0.5f + 0.5f
            val k = 0.90f + 0.07f * ring * ring + 0.04f * streak - 0.04f * (if (streak > 0.96f) 1f else 0f)
            px[y * size + x] = Color.rgb((r0 * k).toInt().coerceIn(0, 255), (g0 * k).toInt().coerceIn(0, 255), (b0 * k).toInt().coerceIn(0, 255))
        }
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        return bmp
    }

    /** "F↑" orientation test (profile D10): the F must read upright from the user with the arrow pointing up. */
    fun orientationTest(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = h * 0.7f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        c.drawText("F↑", w / 2f, h * 0.78f, p)
        return bmp
    }

    val PLAQUE_FACE = Color.rgb(0xF4, 0xE9, 0xD2)
    val PLAQUE_INK = Color.rgb(0x2A, 0x1E, 0x14)
    val PLAQUE_EDGE = Color.rgb(0x6B, 0x4A, 0x2E)
    val PLAQUE_ALERT = Color.rgb(0xB3, 0x26, 0x1E)
    val RED_INK = Color.rgb(0xA8, 0x32, 0x22)
    val YELLOW_INK = Color.rgb(0x7A, 0x5A, 0x10)

    private fun ink(sizePx: Float, bold: Boolean = true) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PLAQUE_INK; textSize = sizePx; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Text size (px) whose glyphs stand [heightM] tall: CJK glyphs fill ~0.88 of the em, Latin capitals ~0.72. */
    fun textPx(heightM: Float, zh: Boolean): Float = heightM * PX_PER_M / if (zh) 0.88f else 0.72f

    private fun frame(w: Int, h: Int, face: Int = PLAQUE_FACE): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLAQUE_EDGE }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        bg.color = face
        c.drawRoundRect(RectF(6f, 6f, w - 6f, h - 6f), 14f, 14f, bg)
        return bmp to c
    }

    /**
     * The seat plaque (design spatialLayout.plaques: 0.44 × 0.04 m → 1408 × 128 px), one line at 2.2 cm (zh) / 2.0 cm
     * (en). A line wider than the face logs OVERFLOW and is drawn smaller (never cut, D13).
     */
    fun seatPlaque(text: String, zh: Boolean, alert: Boolean, test: Boolean = false): Bitmap {
        val w = 1408; val h = 128
        if (test) return orientationTest(w, h)
        val (bmp, c) = frame(w, h)
        val p = ink(textPx(if (zh) 0.022f else 0.020f, zh)).apply { if (alert) color = PLAQUE_ALERT }
        val room = w - 48f
        val tw = p.measureText(text)
        if (tw > room) { Log.w(TAG, "OVERFLOW plaque/seat \"$text\" ${tw.toInt()} px > ${room.toInt()}"); p.textSize *= room / tw }
        val fm = p.fontMetrics
        c.drawText(text, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2, p)
        return bmp
    }

    /**
     * A count plaque under a tray (0.10 × 0.04 m → 320 × 128 px): the discs left (2.2 cm digits) and the wins, digits
     * only (design i18n.pieceText: they do not change with the language), coloured like the side.
     */
    fun countPlaque(left: Int, wins: Int, red: Boolean, test: Boolean = false): Bitmap {
        val w = 320; val h = 128
        if (test) return orientationTest(w, h)
        val (bmp, c) = frame(w, h)
        val big = ink(textPx(0.022f, false)).apply { color = if (red) RED_INK else YELLOW_INK; textAlign = Paint.Align.LEFT }
        val small = ink(textPx(0.014f, false)).apply { textAlign = Paint.Align.RIGHT }
        val fm = big.fontMetrics
        val base = h / 2f - (fm.ascent + fm.descent) / 2
        c.drawText("$left", 22f, base, big)
        // the win count after a small five-point star (a mark, not a word)
        val cx = w - 112f; val cy = 64f
        val star = android.graphics.Path()
        for (k in 0 until 10) {
            val r = if (k % 2 == 0) 26f else 11f
            val a = Math.toRadians(-90.0 + k * 36.0)
            val x = cx + r * Math.cos(a).toFloat(); val y = cy + r * Math.sin(a).toFloat()
            if (k == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
        c.drawPath(star, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xB0, 0x7A, 0x1E) })
        c.drawText("$wins", w - 22f, base, small.apply { textSize = textPx(0.016f, false) })
        return bmp
    }

    /** One column-mouth number (6.15 × 2 cm → 196 × 64 px, digit 1.6 cm), white face so the material tint shows the state. */
    fun mouthNumber(n: Int, test: Boolean = false): Bitmap {
        val w = 196; val h = 64
        if (test) return orientationTest(w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = ink(textPx(0.016f, false)).apply { color = Color.rgb(0x33, 0x26, 0x1A) }
        val fm = p.fontMetrics
        c.drawText("$n", w / 2f, h / 2f - (fm.ascent + fm.descent) / 2, p)
        return bmp
    }
}
