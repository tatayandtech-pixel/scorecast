package com.scorecast.app.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Size
import com.scorecast.app.GameState
import com.scorecast.app.LogoEntry
import com.scorecast.app.LogoHolder
import com.scorecast.app.LogoSlot
import com.scorecast.app.OverlayPosition
import com.scorecast.app.ServerTimeSync
import com.scorecast.app.SportConfig
import com.scorecast.app.clockDisplay
import com.scorecast.app.toClockString

object ScoreboardOverlay {

    fun create(state: GameState, logos: List<LogoEntry>, config: SportConfig?, frame: Size): Bitmap {
        val bmp = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawLogos(canvas, logos, frame)
        drawScoreboard(canvas, state, config, frame)
        return bmp
    }

    // ---- scoreboard bar ----

    private fun drawScoreboard(canvas: Canvas, state: GameState, config: SportConfig?, frame: Size) {
        val perTeamFields = config?.extraFields?.filter { it.perTeam } ?: emptyList()
        val hasExtras = perTeamFields.isNotEmpty()

        val baseBarH = (frame.height * 0.15f).toInt().coerceAtLeast(108)
        val barW = (frame.width * 0.54f).toInt().coerceAtLeast(480)
        val barH = if (hasExtras) (baseBarH * 1.38f).toInt() else baseBarH
        val marginX = (frame.width * 0.02f).toInt()
        val marginY = (frame.height * 0.03f).toInt()

        val barLeft = when (state.overlayPosition) {
            OverlayPosition.TOP_LEFT, OverlayPosition.BOTTOM_LEFT -> marginX.toFloat()
            OverlayPosition.TOP_RIGHT, OverlayPosition.BOTTOM_RIGHT -> (frame.width - barW - marginX).toFloat()
            OverlayPosition.BOTTOM_CENTER -> ((frame.width - barW) / 2f)
        }
        val barTop = when (state.overlayPosition) {
            OverlayPosition.TOP_LEFT, OverlayPosition.TOP_RIGHT -> marginY.toFloat()
            else -> (frame.height - barH - marginY).toFloat()
        }

        canvas.save()
        canvas.translate(barLeft, barTop)
        drawBar(canvas, state, config, perTeamFields, barW, barH)
        canvas.restore()
    }

    private fun drawBar(
        canvas: Canvas,
        state: GameState,
        config: SportConfig?,
        perTeamFields: List<com.scorecast.app.ExtraFieldConfig>,
        barW: Int,
        barH: Int,
    ) {
        val hasExtras = perTeamFields.isNotEmpty()
        val homeColor = parseColor(state.homeColorHex, Color.argb(255, 30, 64, 175))
        val awayColor = parseColor(state.awayColorHex, Color.argb(255, 185, 28, 28))

        val r = barH * 0.14f
        canvas.drawRoundRect(RectF(0f, 0f, barW.toFloat(), barH.toFloat()), r, r,
            paint { color = Color.argb(220, 10, 12, 18) })

        val stripW = barH * 0.09f
        canvas.drawRoundRect(RectF(0f, 0f, stripW, barH.toFloat()), r, r, paint { color = homeColor })
        canvas.drawRoundRect(RectF(barW - stripW, 0f, barW.toFloat(), barH.toFloat()), r, r, paint { color = awayColor })

        val cx = barW / 2f
        canvas.drawRect(cx - 1f, barH * 0.12f, cx + 1f, barH * 0.88f,
            paint { color = Color.argb(80, 255, 255, 255) })

        val scoreSize = barH * 0.44f
        val nameSize  = barH * 0.20f
        val infoSize  = barH * 0.16f
        val pad = stripW + barH * 0.06f

        val whiteBold = paint {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val dimPaint = paint {
            color = Color.argb(200, 170, 180, 195)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        // Y positions chosen so no row overlaps the one below it.
        // scoreSize cap-height ≈ 0.7 × scoreSize; digits have no descenders.
        val nameY   = barH * if (hasExtras) 0.24f else 0.28f
        val scoreY  = barH * if (hasExtras) 0.62f else 0.70f
        val infoY   = barH * if (hasExtras) 0.80f else 0.90f
        val extrasY = barH * 0.95f

        // Names row. setsGames sports (spec §6/§7) append banked sets won, e.g. "LIONS (2)".
        val isSetsGames = config?.scoringModel == "setsGames"
        val homeName = if (isSetsGames) "${state.homeTeam} (${state.setsWonHome})" else state.homeTeam
        val awayName = if (isSetsGames) "${state.awayTeam} (${state.setsWonAway})" else state.awayTeam
        whiteBold.textSize = nameSize; whiteBold.textAlign = Paint.Align.LEFT
        canvas.drawText(homeName, pad, nameY, whiteBold)
        whiteBold.textAlign = Paint.Align.RIGHT
        canvas.drawText(awayName, barW - pad, nameY, whiteBold)

        // Scores row.
        whiteBold.textSize = scoreSize; whiteBold.textAlign = Paint.Align.RIGHT
        canvas.drawText(state.homeScore.toString(), cx - barH * 0.12f, scoreY, whiteBold)
        whiteBold.textAlign = Paint.Align.LEFT
        canvas.drawText(state.awayScore.toString(), cx + barH * 0.12f, scoreY, whiteBold)

        // Period / clock / custom text row.
        val clock = state.clockDisplay(ServerTimeSync.nowMs()).toClockString()
        val center = buildString {
            append("${state.periodLabel}${state.period}  •  $clock")
            if (state.customText.isNotBlank()) append("  •  ${state.customText}")
        }
        dimPaint.textSize = infoSize; dimPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(center, cx, infoY, dimPaint)

        // Extra-fields row (fouls, cards, etc.) when the sport defines perTeam stats.
        if (hasExtras) {
            val extrasSize = infoSize * 0.88f
            val homeExtras = perTeamFields.joinToString("  ") { ef ->
                "${ef.label.take(3)}:${state.extraFields["${ef.key}_home"] ?: 0}"
            }
            val awayExtras = perTeamFields.joinToString("  ") { ef ->
                "${ef.label.take(3)}:${state.extraFields["${ef.key}_away"] ?: 0}"
            }
            dimPaint.textSize = extrasSize; dimPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(homeExtras, pad, extrasY, dimPaint)
            dimPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(awayExtras, barW - pad, extrasY, dimPaint)
        }
    }

    // ---- logos ----

    // Owner request: fixed corners, not free placement — "logo" always top-left, sponsor logo
    // always top-right, both sized to this fixed box width (height follows each image's own
    // aspect ratio, so nothing gets stretched). 2+ logos in the same corner rotate every
    // LOGO_ROTATION_INTERVAL_MS, purely as a function of wall-clock time — no ticking state to
    // keep in sync with the render loop.
    private const val LOGO_BOX_WIDTH_FRACTION = 0.14f
    private const val LOGO_MARGIN_FRACTION = 0.02f
    private const val LOGO_ROTATION_INTERVAL_MS = 10_000L

    private fun drawLogos(canvas: Canvas, logos: List<LogoEntry>, frame: Size) {
        val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val boxW = frame.width * LOGO_BOX_WIDTH_FRACTION
        val marginX = frame.width * LOGO_MARGIN_FRACTION
        val marginY = frame.height * LOGO_MARGIN_FRACTION
        val now = System.currentTimeMillis()

        for (slot in LogoSlot.entries) {
            val group = logos.filter { it.slot == slot }
            if (group.isEmpty()) continue
            // A single logo in its corner is always shown; 2+ sharing it rotate over time.
            val active = if (group.size == 1) group[0]
                else group[((now / LOGO_ROTATION_INTERVAL_MS) % group.size).toInt()]
            val bmp = LogoHolder.getBitmap(active.id) ?: continue
            val logoH = bmp.height * boxW / bmp.width
            val x = when (slot) {
                LogoSlot.TOP_LEFT -> marginX
                LogoSlot.TOP_RIGHT -> frame.width - boxW - marginX
            }
            canvas.drawBitmap(bmp, null, RectF(x, marginY, x + boxW, marginY + logoH), logoPaint)
        }
    }

    // ---- helpers ----

    private fun parseColor(hex: String, fallback: Int): Int =
        try { Color.parseColor(hex) } catch (_: Exception) { fallback }

    private inline fun paint(block: Paint.() -> Unit): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply(block)
}
