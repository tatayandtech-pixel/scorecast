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
        val barH = (frame.height * 0.15f).toInt().coerceAtLeast(108)
        val barW = (frame.width * 0.54f).toInt().coerceAtLeast(480)
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
        drawBar(canvas, state, config, barW, barH)
        canvas.restore()
    }

    private fun drawBar(
        canvas: Canvas,
        state: GameState,
        config: SportConfig?,
        barW: Int,
        barH: Int,
    ) {
        val homeColor = parseColor(state.homeColorHex, Color.argb(255, 30, 64, 175))
        val awayColor = parseColor(state.awayColorHex, Color.argb(255, 185, 28, 28))

        // Backdrop and text tones match the app's own Void/Muted brand colors — same values as
        // ScoreCastTheme and web-mirror's DESIGN.md (Card Surface / Primary Text / Muted Text,
        // dark and light variants), not a coincidence. Toggled via the fullscreen "Edit style"
        // button (state.scoreboardLight), a local display preference only.
        val r = barH * 0.14f
        val barBg = if (state.scoreboardLight) Color.argb(230, 255, 255, 255) else Color.argb(220, 17, 19, 24)
        val primaryText = if (state.scoreboardLight) Color.argb(255, 26, 26, 26) else Color.WHITE
        val mutedText = if (state.scoreboardLight) Color.argb(220, 84, 91, 104) else Color.argb(200, 154, 160, 171)
        canvas.drawRoundRect(RectF(0f, 0f, barW.toFloat(), barH.toFloat()), r, r,
            paint { color = barBg })

        val stripW = barH * 0.09f
        canvas.drawRoundRect(RectF(0f, 0f, stripW, barH.toFloat()), r, r, paint { color = homeColor })
        canvas.drawRoundRect(RectF(barW - stripW, 0f, barW.toFloat(), barH.toFloat()), r, r, paint { color = awayColor })

        val cx = barW / 2f
        val dividerColor = if (state.scoreboardLight) Color.argb(60, 26, 26, 26) else Color.argb(80, 255, 255, 255)
        canvas.drawRect(cx - 1f, barH * 0.12f, cx + 1f, barH * 0.88f, paint { color = dividerColor })

        val scoreSize = barH * 0.44f
        val nameSize  = barH * 0.20f
        // Owner request: make the period ("Q1") and running clock more prominent — bumped up from
        // 0.16 (was sized to also leave room for the now-removed fouls/timeouts row below it).
        val infoSize  = barH * 0.22f
        val pad = stripW + barH * 0.06f

        val whiteBold = paint {
            color = primaryText
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val dimPaint = paint {
            color = mutedText
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        // Y positions chosen so no row overlaps the one below it.
        // scoreSize cap-height ≈ 0.7 × scoreSize; digits have no descenders.
        val nameY  = barH * 0.26f
        val scoreY = barH * 0.66f
        val infoY  = barH * 0.90f

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
