package com.scorecast.app.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Size

/**
 * Overlay anchor inside the 16:9 frame. Phase 1 ships only the static graphic; the five
 * selectable positions from spec §7 are wired here so Phase 2 can drive them from state.
 */
enum class OverlayPosition { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT }

/**
 * Builds the Phase 1 STATIC test scoreboard. No live state, no clock — just a recognizable
 * graphic so we can confirm it is burned into the encoded frames reaching Facebook Live.
 */
object ScoreboardOverlay {

    /** Renders the scoreboard bar once into an ARGB_8888 bitmap sized for [frame]. */
    fun createStatic(frame: Size): Bitmap {
        // Size the bar relative to the frame so it looks right at any resolution.
        val barWidth = (frame.width * 0.42f).toInt().coerceAtLeast(360)
        val barHeight = (frame.height * 0.14f).toInt().coerceAtLeast(96)

        val bmp = Bitmap.createBitmap(barWidth, barHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 12, 14, 20) }
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 0, 200, 120) }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 170, 178, 190)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val r = barHeight * 0.18f
        canvas.drawRoundRect(RectF(0f, 0f, barWidth.toFloat(), barHeight.toFloat()), r, r, bg)
        // Accent strip down the left edge.
        canvas.drawRoundRect(RectF(0f, 0f, barHeight * 0.10f, barHeight.toFloat()), r, r, accent)

        val cx = barWidth / 2f
        val score = barHeight * 0.46f
        val label = barHeight * 0.18f

        white.textSize = score
        canvas.drawText("LIONS  00  :  00  TIGERS", cx, barHeight * 0.46f, white)

        dim.textSize = label
        canvas.drawText("Q1   •   10:00   •   SCORECAST TEST", cx, barHeight * 0.80f, dim)

        return bmp
    }
}
