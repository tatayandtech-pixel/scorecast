package com.scorecast.app.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scorecast.app.GameState
import com.scorecast.app.SportConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end test for the OCR scoring pipeline: bitmap -> Tesseract -> matcher -> score delta.
 *
 * This is the whole path a camera frame travels before the delta is pushed to Firebase, so it
 * is the only test that proves the pieces actually agree with each other.
 *
 * It is instrumented rather than a JVM unit test because the engine loads native Tesseract
 * libraries and needs the on-device traineddata model.
 *
 * Run with: ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class OcrPipelineInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Basketball-ish config: 1/2/3 point increments, 4 periods, a shot clock. */
    private val config = SportConfig(
        sport = "basketball",
        displayName = "Basketball",
        scoringModel = "flat",
        usesPlayers = false,
        periods = 4,
        periodLabel = "Q",
        periodLength = 720,
        clockDirection = "down",
        scoreIncrements = listOf(1, 2, 3),
        extraFields = emptyList(),
        bestOf = null,
        pointsToWinGame = null,
        finalSetPoints = null,
        winByTwo = false,
        pointCap = null,
        shotClockSeconds = 24,
    )

    @Test
    fun recognisesHomeAndAwayScoresSeparately() = runBlocking {
        val engine = OcrEngine(context)
        try {
            assertTrue("Tesseract failed to initialize", engine.initialize())

            val lines = engine.recognize(twoColumnScoreboardBitmap("88", "77"))

            assertTrue("Expected two OCR lines, got $lines", lines.size >= 2)
            // The home score must be recognisable as being on the LEFT half of the frame.
            // This is the regression guard for the bug where every line got a full-width
            // bounding box, which made the home side undetectable.
            val leftmost = lines.minByOrNull { it.boundingBox.left }!!
            val rightmost = lines.maxByOrNull { it.boundingBox.right }!!
            assertEquals("88", leftmost.text)
            assertEquals("77", rightmost.text)
            assertTrue(
                "Expected the home score's box to start in the left half, was ${leftmost.boundingBox}",
                leftmost.boundingBox.centerX() < leftmost.imageWidth / 2,
            )
            assertTrue(
                "Expected the away score's box to sit in the right half, was ${rightmost.boundingBox}",
                rightmost.boundingBox.centerX() > rightmost.imageWidth / 2,
            )
        } finally {
            engine.release()
        }
    }

    @Test
    fun pipelineProducesHomeAndAwayDeltas() = runBlocking {
        val engine = OcrEngine(context)
        try {
            assertTrue("Tesseract failed to initialize", engine.initialize())

            val current = GameState(homeScore = 85, awayScore = 74, sport = "basketball")
            val lines = engine.recognize(twoColumnScoreboardBitmap("88", "77"))
            val matcher = OcrScoreMatcher()

            val result = matcher.match(lines, current, config)
            assertNotNull("The matcher found nothing in $lines", result)
            assertEquals("Home score must come from the LEFT of the scoreboard", 88, result!!.homeScore)
            assertEquals("Away score must come from the RIGHT of the scoreboard", 77, result.awayScore)

            val delta = matcher.computeDelta(result, current)
            assertEquals("Home delta should be 88 - 85", 3, delta.homeDelta)
            assertEquals("Away delta should be 77 - 74", 3, delta.awayDelta)
        } finally {
            engine.release()
        }
    }

    @Test
    fun clockIsReadAndNotMistakenForAScore() = runBlocking {
        val engine = OcrEngine(context)
        try {
            assertTrue("Tesseract failed to initialize", engine.initialize())

            // A clock reading 12:00 must be read as a clock, not as a score of 12 and not as
            // a period of 12.
            //
            // The minutes have two digits on purpose: with a single minute digit Tesseract
            // tends to miss the colon ("2:30" comes back as "2230"), and the clock then cannot
            // be told apart from a number. Real scoreboards show a leading zero for the same
            // reason, so two-digit minutes are the normal case.
            val lines = engine.recognize(textBitmap("12:00"))
            val matcher = OcrScoreMatcher()
            val result = matcher.match(lines, GameState(homeScore = 0, awayScore = 0), config)

            assertNotNull("The matcher found nothing in $lines", result)
            assertEquals("Clock should be read as 720 seconds", 720, result!!.clockSeconds)
            assertEquals("A clock must not be mistaken for the home score", null, result.homeScore)
            assertEquals("A clock must not be mistaken for the away score", null, result.awayScore)
            assertEquals("A clock must not be mistaken for the period", null, result.period)
        } finally {
            engine.release()
        }
    }

    /**
     * Regression guard: the engine's character whitelist must actually be in force.
     *
     * Tesseract resets its parameters during init(), so a whitelist that was set BEFORE init()
     * was silently discarded and Tesseract happily read letters: "KICKOFF" came back as
     * "KICKOFF" and "Q2" as "Q2", which the matcher would then have had to guess the meaning of.
     * With the whitelist in force only digits, the colon and the space can come back at all.
     */
    @Test
    fun onlyDigitsAndTheClockColonAreRecognised() = runBlocking {
        val engine = OcrEngine(context)
        try {
            assertTrue("Tesseract failed to initialize", engine.initialize())

            val letters = engine.recognize(textBitmap("KICKOFF"))
            assertTrue(
                "Letters must not be recognised, but got $letters",
                letters.none { line -> line.text.any { it.isLetter() } },
            )

            val clock = engine.recognize(textBitmap("12:00"))
            assertTrue(
                "The clock's colon must be recognised, but got $clock",
                clock.any { line -> line.text.contains(':') },
            )
        } finally {
            engine.release()
        }
    }

    /**
     * Regression guard: unreadable text must never become a score.
     *
     * Tesseract happily turns unreadable text into plausible-looking digits, but at a much
     * lower confidence than a real scoreboard reading (measured on-device: 0.34-0.71 for noise
     * against 0.92-0.99 for real scores). The engine drops those words, so the matcher never
     * sees them and cannot push a bogus score to Firebase.
     */
    @Test
    fun unreadableTextIsNotMistakenForAScore() = runBlocking {
        val engine = OcrEngine(context)
        try {
            assertTrue("Tesseract failed to initialize", engine.initialize())

            for (noise in listOf("HOME GUEST", "KICKOFF", "Q2")) {
                val lines = engine.recognize(textBitmap(noise))
                val matcher = OcrScoreMatcher()
                val result = matcher.match(
                    lines, GameState(homeScore = 0, awayScore = 0), config
                )

                assertTrue(
                    "Noise '$noise' must not produce a score, but got $result from $lines",
                    result == null || (result.homeScore == null && result.awayScore == null),
                )
            }
        } finally {
            engine.release()
        }
    }

    /**
     * Renders a scoreboard-like bitmap: large white digits on a black background, which is how
     * physical arena scoreboards look when the camera is pointed at them.
     */
    private fun textBitmap(text: String): Bitmap {
        val bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 180f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(text, 400f, 240f, paint)
        return bitmap
    }

    /**
     * Renders a scoreboard with the HOME score on the left and the AWAY score on the right,
     * which is the layout the matcher relies on to tell the two teams apart.
     */
    private fun twoColumnScoreboardBitmap(home: String, away: String): Bitmap {
        val bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 180f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(home, 200f, 240f, paint)
        canvas.drawText(away, 600f, 240f, paint)
        return bitmap
    }
}