package com.scorecast.app.ocr

import com.scorecast.app.GameState
import com.scorecast.app.SportConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the score-matching logic.
 *
 * These run without a device (the Tesseract engine needs one and stays in androidTest), so
 * they cover the part of the pipeline that decides *which* recognised number is the score.
 */
class OcrScoreMatcherTest {

    private val config = SportConfig(
        sport = "basketball",
        displayName = "Basketball",
        periods = 4,
        periodLabel = "Q",
        periodLength = 600,
        clockDirection = "down",
        scoreIncrements = listOf(1, 2, 3),
    )

    /**
     * Builds a token as Tesseract would report it.
     *
     * @param height Height of the bounding box in pixels. The score digits on a real board are
     *   much taller than the quarter and the timeout counters, and that size difference is
     *   what identifies the scores.
     */
    private fun token(
        text: String,
        number: Int,
        centerX: Float,
        centerY: Float,
        height: Int,
        isClock: Boolean = false,
    ) = OcrScoreMatcher.NumericToken(
        text = text,
        number = number,
        isClock = isClock,
        centerX = centerX,
        centerY = centerY,
        height = height,
    )

    /**
     * The exact layout of the reference scoreboard:
     *
     * ```
     *                    0:0            <- clock, top centre
     *   70                              <- HOME score, left
     *                    4              <- quarter, centre
     *                       64          <- VISITOR score, right
     *   0     0        0     0         <- small timeout / foul counters along the bottom
     * ```
     *
     * The scores are the tall numerals; the quarter and the counters are small.
     */
    private val referenceBoard = listOf(
        token("0:0", 0, centerX = 0.46f, centerY = 0.15f, height = 60, isClock = true),
        token("70", 70, centerX = 0.13f, centerY = 0.42f, height = 90),
        token("4", 4, centerX = 0.46f, centerY = 0.45f, height = 40),
        token("64", 64, centerX = 0.86f, centerY = 0.42f, height = 90),
        token("0", 0, centerX = 0.13f, centerY = 0.80f, height = 45),
        token("0", 0, centerX = 0.30f, centerY = 0.80f, height = 45),
        token("0", 0, centerX = 0.62f, centerY = 0.80f, height = 45),
        token("0", 0, centerX = 0.86f, centerY = 0.80f, height = 45),
    )

    @Test
    fun selectsTheTwoLargeDigitsAsHomeAndVisitorScores() {
        val (home, away) = OcrScoreMatcher().selectScores(referenceBoard, currentHome = 0, currentAway = 0)

        assertEquals("HOME score must come from the left of the board", 70, home)
        assertEquals("VISITOR score must come from the right of the board", 64, away)
    }

    /**
     * Regression guard for the bug that made the scores undetectable.
     *
     * The scoreboard reads 70:64 while the app still believes the game is at 0:0. The old
     * rule required the difference to be one of the sport's scoring increments (1, 2 or 3),
     * so a difference of 70 was rejected and no score was ever reported.
     */
    @Test
    fun acceptsAScoreFarAboveTheCurrentOne() {
        val (home, away) = OcrScoreMatcher().selectScores(referenceBoard, currentHome = 0, currentAway = 0)

        assertEquals(70, home)
        assertEquals(64, away)
    }

    /**
     * A score never goes down, so a smaller reading is a misread and must be ignored.
     */
    @Test
    fun ignoresAScoreBelowTheCurrentOne() {
        val board = listOf(
            token("7", 7, centerX = 0.13f, centerY = 0.42f, height = 90),
            token("6", 6, centerX = 0.86f, centerY = 0.42f, height = 90),
        )

        val (home, away) = OcrScoreMatcher().selectScores(board, currentHome = 70, currentAway = 64)

        assertNull("A score below the current one is a misread", home)
        assertNull("A score below the current one is a misread", away)
    }

    /**
     * The small quarter and timeout counters must not be mistaken for the score when the tall
     * scores are present. The height cut-off is *relative* to the tallest token, so the tall
     * numerals set the bar and the short ones fall below it.
     */
    @Test
    fun tallScoresWinOverSmallCounters() {
        val board = listOf(
            token("70", 70, centerX = 0.13f, centerY = 0.42f, height = 90),
            token("64", 64, centerX = 0.86f, centerY = 0.42f, height = 90),
            token("0", 0, centerX = 0.13f, centerY = 0.80f, height = 30),
            token("0", 0, centerX = 0.86f, centerY = 0.80f, height = 30),
        )

        val (home, away) = OcrScoreMatcher().selectScores(board, currentHome = 0, currentAway = 0)

        assertEquals(70, home)
        assertEquals(64, away)
    }

    /**
     * The clock is in the top centre, which puts it right on the 0.5 boundary. It must never
     * be read as a score, however large it is.
     */
    @Test
    fun theClockIsNeverReadAsAScore() {
        val board = listOf(
            token("12:30", 12, centerX = 0.49f, centerY = 0.10f, height = 120, isClock = true),
        )

        val (home, away) = OcrScoreMatcher().selectScores(board, currentHome = 0, currentAway = 0)

        assertNull("The clock must not be read as the home score", home)
        assertNull("The clock must not be read as the away score", away)
    }

    /**
     * End-to-end check of the matcher over the reference board: the score is detected, the
     * clock is read, the quarter is read, and the small counters are ignored.
     */
    @Test
    fun matchReadsTheReferenceScoreboard() {
        val result = OcrScoreMatcher().matchTokens(
            referenceBoard,
            GameState(homeScore = 0, awayScore = 0, sport = "basketball"),
            config,
        )

        assertNotNull("The matcher found nothing in $referenceBoard", result)
        assertEquals("HOME score", 70, result!!.homeScore)
        assertEquals("VISITOR score", 64, result.awayScore)
        assertEquals("Quarter", 4, result.period)
    }

    @Test
    fun clockIsParsedButNeverBecomesAScore() {
        // 08:30 = 510 seconds, which is inside the 600-second quarter window so the clock
        // is accepted by the matcher's range check.
        val board = listOf(
            token("08:30", 0, centerX = 0.49f, centerY = 0.10f, height = 120, isClock = true),
        )

        val result = OcrScoreMatcher().matchTokens(
            board,
            GameState(homeScore = 0, awayScore = 0, sport = "basketball"),
            config,
        )

        assertNotNull(result)
        assertEquals("Clock should be read as 510 seconds", 510, result!!.clockSeconds)
        assertNull("A clock must not be mistaken for the home score", result.homeScore)
        assertNull("A clock must not be mistaken for the away score", result.awayScore)
    }

    /**
     * The plausibility helper must not veto a large upward jump.
     *
     * This is the bug that made the score invisible: the board read 70 while the app still
     * said 0, and the old check required the difference to be a single scoring increment.
     */
    @Test
    fun plausibilityCheckAcceptsLargeUpwardJumps() {
        assertTrue(
            "A jump from 0 to 70 must be accepted",
            OcrScoreMatcher.isPlausibleScore(detected = 70, current = 0),
        )
        assertTrue(
            "A jump from 0 to 64 must be accepted",
            OcrScoreMatcher.isPlausibleScore(detected = 64, current = 0),
        )
        assertTrue(
            "A normal +3 must be accepted",
            OcrScoreMatcher.isPlausibleScore(detected = 73, current = 70),
        )
    }

    @Test
    fun plausibilityCheckRejectsDropsAndAbsurdValues() {
        assertFalse(
            "A score cannot go down",
            OcrScoreMatcher.isPlausibleScore(detected = 7, current = 70),
        )
        assertFalse(
            "An unchanged score is not an update",
            OcrScoreMatcher.isPlausibleScore(detected = 70, current = 70),
        )
        assertFalse(
            "An implausibly large score is a misread",
            OcrScoreMatcher.isPlausibleScore(detected = 70000, current = 70),
        )
        assertFalse(
            "A negative score is impossible",
            OcrScoreMatcher.isPlausibleScore(detected = -1, current = 0),
        )
    }
}
