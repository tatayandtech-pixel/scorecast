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

        /** Minimum millis between OCR-driven updates (debounce). */
        const val MIN_UPDATE_INTERVAL_MS = 2_000L

        /** Only accept a score change that matches one of the sport's valid increments. */
        fun isValidScoreDelta(detected: Int, current: Int, increments: List<Int>): Boolean {
            val delta = detected - current
            if (delta == 0) return false
            if (delta < 0) return false
            return increments.contains(delta) || delta <= increments.max()
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

        var homeScore: Int? = null
        var awayScore: Int? = null
        var clockSeconds: Int? = null
        var period: Int? = null
        var shotClockSeconds: Int? = null
        var totalConfidence = 0f
        var matchedLines = 0
        val rawTexts = mutableListOf<String>()

        // Normalise positions against the IMAGE size, not against the widest/tallest box: a
        // scoreboard sitting in the middle of the frame produces boxes far smaller than the
        // frame, and normalising by the boxes would put every token on the wrong side.
        val imgWidth = ocrLines.maxOfOrNull {
            if (it.imageWidth > 0) it.imageWidth else it.boundingBox.width()
        } ?: 1
        val imgHeight = ocrLines.maxOfOrNull {
            if (it.imageHeight > 0) it.imageHeight else it.boundingBox.bottom
        } ?: 1

        for (line in ocrLines) {
            rawTexts.add(line.text)
            totalConfidence += line.confidence
            matchedLines++

            val number = parseIntDigits(line.text) ?: continue
            // A clock token ("10:30") also contains digits but is not a score. Without this
            // guard the clock's leading "10" can satisfy the score-plausibility check and
            // overwrite a real score.
            val isClockToken = parseClockSeconds(line.text) != null
            val centerX = line.boundingBox.centerX().toFloat() / imgWidth
            val centerY = line.boundingBox.centerY().toFloat() / imgHeight

            val isHomeSide = centerX < 0.5f
            val isAwaySide = centerX >= 0.5f

            // Score detection: left = home, right = away, validated by sport increments.
            if (isHomeSide && !isClockToken && number != current.homeScore &&
                isValidScoreDelta(number, current.homeScore, config.scoreIncrements)) {
                homeScore = number
            } else if (isAwaySide && !isClockToken && number != current.awayScore &&
                isValidScoreDelta(number, current.awayScore, config.scoreIncrements)) {
                awayScore = number
            }

            // Clock detection (top center, MM:SS format).
            if (config.clockDirection != "none") {
                parseClockSeconds(line.text)?.let { secs ->
                    if (config.clockDirection == "down" && secs in 0..config.periodLength && clockSeconds == null) {
                        clockSeconds = secs
                    }
                }
            }

            // Period/quarter/set detection (small number 1..periods).
            if (!isClockToken) {
                parsePeriod(line.text, config.periods)?.let { p ->
                    if (p != current.period && period == null) period = p
                }
            }

            // Shot clock detection (basketball only: 0–24, upper-left quadrant).
            config.shotClockSeconds?.let { shotMax ->
                // number is guaranteed non-null after line 92's `val number = parseIntDigits(line.text) ?: continue`
                if (!isClockToken && number in 0..shotMax && centerY < 0.5f &&
                    number != current.shotClockBaseRemainingSeconds.toInt() && shotClockSeconds == null) {
                    shotClockSeconds = number
                }
            }
        }

        if (matchedLines == 0) return null
        val avgConfidence = totalConfidence / matchedLines

        val result = MatchResult(
            homeScore = homeScore, awayScore = awayScore,
            clockSeconds = clockSeconds, period = period,
            shotClockSeconds = shotClockSeconds,
            confidence = avgConfidence, rawText = rawTexts.joinToString(" | "),
        )

        val hasScore = homeScore != null || awayScore != null
        val hasClock = clockSeconds != null
        val hasPeriod = period != null
        val hasShotClock = shotClockSeconds != null

        if (!hasScore && !hasClock && !hasPeriod && !hasShotClock) {
            Log.d(TAG, "No plausible fields matched. OCR: ${rawTexts.joinToString()}")
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

