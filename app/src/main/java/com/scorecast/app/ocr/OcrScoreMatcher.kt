package com.scorecast.app.ocr

import android.graphics.Rect
import android.util.Log
import com.scorecast.app.GameState
import com.scorecast.app.SportConfig

/**
 * Sport-config-driven matcher that interprets raw OCR text lines from a physical scoreboard
 * and extracts structured score/clock/period values.
 *
 * See OcrScoreMatcher.Companion for parsing helpers.
 * See match() for the full matching strategy.
 */
class OcrScoreMatcher {

    companion object {
        private const val TAG = "OcrScoreMatcher"

        /**
         * A token must be at least this fraction as tall as the tallest token on the board to
         * be considered a score.
         *
         * On the reference scoreboard the two scores are the big seven-segment numerals while
         * the quarter and the timeout/foul counters are noticeably smaller. A ratio of 0.6 keeps
         * the scores and drops the small counters.
         */
        const val SCORE_HEIGHT_RATIO = 0.6f

        /**
         * Highest plausible score, used to reject obvious misreads such as "700" for "70".
         */
        const val MAX_PLAUSIBLE_SCORE = 999

        /**
         * True when the token is a clock rather than a score.
         *
         * A clock contains a colon. This is deliberately based on the *text* and not on
         * [parseClockSeconds], because a clock that is too garbled to be parsed as MM:SS (say
         * "0:0" with a missing second digit) is still a clock and must not be read as a score.
         */
        fun isClockToken(text: String): Boolean = text.contains(':')

        /** Minimum millis between OCR-driven updates (debounce). */
        const val MIN_UPDATE_INTERVAL_MS = 2_000L

        /**
         * True when [detected] is a plausible new value for a score that currently reads
         * [current].
         *
         * A score only ever moves **upwards**, so:
         * * a delta of 0 is not a change and is rejected,
         * * a negative delta is a misread (a board never un-scores) and is rejected,
         * * an implausibly large value is rejected outright.
         *
         * The *size* of an upward jump is deliberately NOT restricted to the sport's scoring
         * increments. The scoreboard in the reference photo reads 70:64 while the app's state
         * may still say 0:0, so the difference is 70 — far more than one increment of 1, 2 or
         * 3. An earlier version of this check required the delta to be one of the increments
         * (or at most the largest increment), which meant a score was never detected until the
         * app happened to be within a single basket of it. Rejecting large jumps re-creates
         * exactly that bug, so the increment list is only used to recognise the shape of a
         * normal increment, never to veto a larger one.
         *
         * @param detected  the score read from the board
         * @param current   the score the app currently displays
         * @param maxScore  highest plausible score
         */
        fun isPlausibleScore(detected: Int, current: Int, maxScore: Int = MAX_PLAUSIBLE_SCORE): Boolean {
            if (detected < 0 || detected > maxScore) return false
            if (detected == current) return false
            return detected > current
        }

        /** Parses the first integer found in a text string. */
        fun parseIntDigits(text: String): Int? =
            Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()

        /**
         * Parses a MM:SS clock string into total seconds.
         *
         * The colon is required: it is the only reliable signal that a token is a clock rather
         * than a score. Tesseract reads a clock reliably when the minutes have two digits
         * ("12:00", "10:30"); with a single minute digit the colon is often missed and the token
         * comes back as a bare number ("2:30" is read as "2230"), which is then ignored rather
         * than misreported.
         *
         * A bare number is deliberately NOT reinterpreted as MMSS: "2230" is ambiguous between
         * 2:30 and 22:30, and guessing would put a wrong time on the mirror.
         */
        fun parseClockSeconds(text: String): Int? {
            val m = Regex("(\\d{1,2}):(\\d{2})").find(text.trim()) ?: return null
            return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
        }

        /** Parses a 1–maxPeriods integer for period/quarter/set detection. */
        fun parsePeriod(text: String, maxPeriods: Int): Int? {
            val n = parseIntDigits(text) ?: return null
            return if (n in 1..maxPeriods) n else null
        }
    }

    data class MatchResult(
        val homeScore: Int? = null,
        val awayScore: Int? = null,
        val clockSeconds: Int? = null,
        val period: Int? = null,
        val shotClockSeconds: Int? = null,
        val confidence: Float,
        val rawText: String,
    )

    data class ScoreDelta(val homeDelta: Int, val awayDelta: Int)

    /**
     * A recognised numeric token, reduced to the plain numbers the selection logic needs.
     *
     * Keeping this free of [android.graphics.Rect] is what makes the selection logic testable
     * on a plain JVM: `Rect` is only available on a device.
     */
    data class NumericToken(
        val text: String,
        val number: Int,
        val isClock: Boolean,
        /** Horizontal centre of the token, as a fraction of the image width (0..1). */
        val centerX: Float,
        /** Vertical centre of the token, as a fraction of the image height (0..1). */
        val centerY: Float,
        /** Height of the token's bounding box in pixels. */
        val height: Int,
        /** Tesseract's confidence for this token, 0..1. */
        val confidence: Float = 1f,
    )

    /**
     * Picks the home and away score out of the recognised tokens.
     *
     * The scores are the **largest** digits on the board. On the reference scoreboard the
     * board shows the clock ("0:0"), the HOME score ("70"), the VISITOR score ("64"), the
     * quarter ("4") and a row of small timeout/foul counters ("0", "0", "0", "0"). Every one
     * of those is a number, so position alone cannot answer "which number is the score".
     * Size can: the two scores are rendered in the big seven-segment numerals, while the
     * quarter and the counters are small.
     *
     * Selection rules:
     * * the token must be at least [SCORE_HEIGHT_RATIO] as tall as the tallest token,
     * * it must be on the correct side of the board (left = home, right = away),
     * * it must not be the clock,
     * * it must be a plausible score: greater than the current score (a score never goes
     *   down, so a smaller reading is a misread) and not absurdly large.
     *
     * Among the tokens that qualify, the tallest wins. A score may jump by more than one
     * increment — the app can start mid-game, or miss a basket — so the jump size is not
     * restricted to the sport's scoring increments.
     */
    fun selectScores(
        tokens: List<NumericToken>,
        currentHome: Int,
        currentAway: Int,
    ): Pair<Int?, Int?> {
        if (tokens.isEmpty()) return null to null

        val tallest = tokens.maxOf { it.height }
        val cutoff = tallest * SCORE_HEIGHT_RATIO

        fun pick(candidates: List<NumericToken>, current: Int): Int? = candidates
            .filter { !it.isClock }
            .filter { it.height >= cutoff }
            .filter { isPlausibleScore(it.number, current, MAX_PLAUSIBLE_SCORE) }
            .maxByOrNull { it.height }
            ?.number

        val home = pick(tokens.filter { it.centerX < 0.5f }, currentHome)
        val away = pick(tokens.filter { it.centerX >= 0.5f }, currentAway)
        return home to away
    }

    /**
     * Matches OCR-detected text lines against the expected scoreboard layout for [config].
     *
     * @param ocrLines  Text lines from [OcrEngine.recognize], with bounding boxes
     * @param current   Current GameState, used for plausibility checks
     * @param config    Sport config, defining which fields to look for
     * @return Detected values and confidence, or null if nothing plausible was found
     */
    fun match(
        ocrLines: List<OcrEngine.OcrTextLine>,
        current: GameState,
        config: SportConfig?,
    ): MatchResult? {
        if (ocrLines.isEmpty() || config == null) return null

        // Normalise positions against the IMAGE size, not against the widest/tallest box: a
        // scoreboard sitting in the middle of the frame produces boxes far smaller than the
        // frame, and normalising by the boxes would put every token on the wrong side.
        val imgWidth = ocrLines.maxOfOrNull {
            if (it.imageWidth > 0) it.imageWidth else it.boundingBox.width()
        } ?: 1
        val imgHeight = ocrLines.maxOfOrNull {
            if (it.imageHeight > 0) it.imageHeight else it.boundingBox.bottom
        } ?: 1

        // Reduce every recognised line to a plain numeric token. This is the only part that
        // needs android.graphics.Rect; the matching itself is pure and JVM-testable.
        val tokens = ocrLines.mapNotNull { line ->
            val number = parseIntDigits(line.text) ?: return@mapNotNull null
            NumericToken(
                text = line.text,
                number = number,
                isClock = isClockToken(line.text),
                centerX = line.boundingBox.centerX().toFloat() / imgWidth,
                centerY = line.boundingBox.centerY().toFloat() / imgHeight,
                height = line.boundingBox.height(),
                confidence = line.confidence,
            )
        }

        return matchTokens(tokens, current, config)
    }

    /**
     * The pure core of the matcher: interprets [tokens] as a scoreboard.
     *
     * Kept free of Android types so it can be exercised by plain JVM unit tests.
     */
    fun matchTokens(
        tokens: List<NumericToken>,
        current: GameState,
        config: SportConfig,
    ): MatchResult? {
        if (tokens.isEmpty()) return null

        var homeScore: Int? = null
        var awayScore: Int? = null
        var clockSeconds: Int? = null
        var period: Int? = null
        var shotClockSeconds: Int? = null

        // The scores are the LARGEST digits on the board; see selectScores() for the rules.
        val (detectedHome, detectedAway) =
            selectScores(tokens, current.homeScore, current.awayScore)
        homeScore = detectedHome
        awayScore = detectedAway

        // Clock detection (MM:SS format).
        if (config.clockDirection != "none") {
            tokens.firstOrNull { it.isClock }?.let { token ->
                parseClockSeconds(token.text)?.let { secs ->
                    if (config.clockDirection == "down" && secs in 0..config.periodLength &&
                        clockSeconds == null
                    ) {
                        clockSeconds = secs
                    }
                }
            }
        }

        // Period/quarter/set detection (small number 1..periods).
        tokens.filter { !it.isClock }.forEach { token ->
            parsePeriod(token.text, config.periods)?.let { p ->
                if (p != current.period && period == null) period = p
            }
        }

        // Shot clock detection (basketball only: 0..24, upper-left quadrant).
        config.shotClockSeconds?.let { shotMax ->
            tokens.filter { !it.isClock }.forEach { token ->
                if (token.number in 0..shotMax && token.centerY < 0.5f &&
                    token.number != current.shotClockBaseRemainingSeconds.toInt() &&
                    shotClockSeconds == null
                ) {
                    shotClockSeconds = token.number
                }
            }
        }

        val avgConfidence = tokens.sumOf { it.confidence.toDouble() }.toFloat() / tokens.size
        val rawText = tokens.joinToString(" | ") { it.text }

        val result = MatchResult(
            homeScore = homeScore, awayScore = awayScore,
            clockSeconds = clockSeconds, period = period,
            shotClockSeconds = shotClockSeconds,
            confidence = avgConfidence, rawText = rawText,
        )

        val hasScore = homeScore != null || awayScore != null
        val hasClock = clockSeconds != null
        val hasPeriod = period != null
        val hasShotClock = shotClockSeconds != null

        if (!hasScore && !hasClock && !hasPeriod && !hasShotClock) {
            Log.d(TAG, "No plausible fields matched. OCR: $rawText")
            return null
        }

        Log.d(TAG, "Matched: home=$homeScore away=$awayScore clock=$clockSeconds " +
            "period=$period shotClock=$shotClockSeconds conf=$avgConfidence")
        return result
    }

    /** Computes score deltas for Firebase atomic increments (collision-safe). */
    fun computeDelta(result: MatchResult, current: GameState): ScoreDelta {
        val homeDelta = (result.homeScore?.minus(current.homeScore)) ?: 0
        val awayDelta = (result.awayScore?.minus(current.awayScore)) ?: 0
        return ScoreDelta(
            homeDelta = if (homeDelta > 0) homeDelta else 0,
            awayDelta = if (awayDelta > 0) awayDelta else 0,
        )
    }
}

