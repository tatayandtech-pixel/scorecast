package com.scorecast.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * Normalises a picked logo (owner request): the plain background around it is made transparent,
 * then the logo is fitted, whole and centred, onto a transparent [SIZE]×[SIZE] canvas. 180px is
 * also about the width the overlay draws a logo at on a 720p frame (14% of 1280).
 */
object LogoProcessor {
    const val SIZE = 180

    // How close (RGB distance) a pixel must be to the background colour to count as background,
    // and how much of the image's border must be that colour before we trust there is a plain
    // background at all — a photo's busy edges fail this and are left untouched.
    private const val TOLERANCE = 40
    private const val MIN_UNIFORM_BORDER = 0.7f

    fun process(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        removeBackground(pixels, w, h)
        val cleaned = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        return fitCentered(cleaned).also { if (it !== cleaned) cleaned.recycle() }
    }

    /** Clears the background connected to the image's edges, in place. Only the region reachable
     *  from the border is removed, so white lettering inside a logo on a white background stays.
     *  Does nothing when the image already has transparency or has no plain background. */
    private fun removeBackground(pixels: IntArray, w: Int, h: Int) {
        val border = borderIndices(w, h)
        if (border.count { Color.alpha(pixels[it]) < 250 } > border.size / 10) return

        val bg = dominantColor(border.map { pixels[it] })
        if (border.count { distance(pixels[it], bg) <= TOLERANCE } < border.size * MIN_UNIFORM_BORDER) return

        val removed = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        var head = 0
        var tail = 0
        for (i in border) {
            if (!removed[i] && distance(pixels[i], bg) <= TOLERANCE) {
                removed[i] = true
                queue[tail++] = i
            }
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            // Compared against the background colour, not the neighbour, so the fill can't creep
            // step by step through a gradient into the logo itself.
            fun visit(n: Int) {
                if (!removed[n] && distance(pixels[n], bg) <= TOLERANCE) {
                    removed[n] = true
                    queue[tail++] = n
                }
            }
            if (x > 0) visit(i - 1)
            if (x < w - 1) visit(i + 1)
            if (y > 0) visit(i - w)
            if (y < h - 1) visit(i + w)
        }

        // Soften the cut: logo pixels touching the removed area fade with how close they are to
        // the background colour, so anti-aliased edges don't keep a hard halo of the old colour.
        for (i in pixels.indices) {
            if (removed[i]) { pixels[i] = Color.TRANSPARENT; continue }
            val x = i % w
            val y = i / w
            val touches = (x > 0 && removed[i - 1]) || (x < w - 1 && removed[i + 1]) ||
                (y > 0 && removed[i - w]) || (y < h - 1 && removed[i + w])
            if (!touches) continue
            val d = distance(pixels[i], bg)
            if (d < 2 * TOLERANCE) {
                val alpha = (255 * (d - TOLERANCE) / TOLERANCE).coerceIn(0, 255)
                pixels[i] = (alpha shl 24) or (pixels[i] and 0x00FFFFFF)
            }
        }
    }

    private fun fitCentered(src: Bitmap): Bitmap {
        val scale = minOf(SIZE.toFloat() / src.width, SIZE.toFloat() / src.height)
        val dw = src.width * scale
        val dh = src.height * scale
        val left = (SIZE - dw) / 2f
        val top = (SIZE - dh) / 2f
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888) // starts fully transparent
        Canvas(out).drawBitmap(
            src, null, RectF(left, top, left + dw, top + dh),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return out
    }

    private fun borderIndices(w: Int, h: Int): List<Int> = buildList {
        for (x in 0 until w) { add(x); if (h > 1) add((h - 1) * w + x) }
        for (y in 1 until h - 1) { add(y * w); if (w > 1) add(y * w + w - 1) }
    }

    /** The most common border colour, bucketed coarsely so JPEG noise doesn't split it. */
    private fun dominantColor(colors: List<Int>): Int {
        val buckets = colors.groupBy { (Color.red(it) shr 4 shl 8) or (Color.green(it) shr 4 shl 4) or (Color.blue(it) shr 4) }
        val top = buckets.values.maxBy { it.size }
        return Color.rgb(
            top.sumOf { Color.red(it) } / top.size,
            top.sumOf { Color.green(it) } / top.size,
            top.sumOf { Color.blue(it) } / top.size,
        )
    }

    private fun distance(a: Int, b: Int): Int {
        val dr = Color.red(a) - Color.red(b)
        val dg = Color.green(a) - Color.green(b)
        val db = Color.blue(a) - Color.blue(b)
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble()).toInt()
    }
}
