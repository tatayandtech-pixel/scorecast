package com.scorecast.app.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device tests for the OCR engine.
 *
 * These must be instrumented rather than JVM unit tests: the engine loads the native Tesseract
 * shared libraries and downloads an English traineddata model, neither of which works off-device.
 *
 * Run with: ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class OcrEngineInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun trainedDataIsStoredInsideTessDataSubfolder() {
        // OcrEngine.init() requires the data path to CONTAIN a "tessdata" subfolder, so the model
        // must live in <filesDir>/tessdata/ — not directly in <filesDir>/.
        val tessData = File(context.filesDir, "tessdata")
        assertTrue("Expected a tessdata directory in ${context.filesDir}", tessData.isDirectory)
    }

    @Test
    fun initializesAndRecognizesScoreboardDigits() = runBlocking {
        val engine = OcrEngine(context)
        try {
            assertTrue("Tesseract failed to initialize", engine.initialize())
            assertTrue(engine.isReady())

            val lines = engine.recognize(scoreboardBitmap("88  77"))
            val digits = lines.joinToString("") { it.text }.filter { it.isDigit() }

            assertTrue(
                "Expected to read 88 and 77, but got '$digits' from lines=$lines",
                digits.contains("88") && digits.contains("77"),
            )
        } finally {
            engine.release()
        }
    }

    @Test
    fun blankImageProducesNoDigits() = runBlocking {
        val engine = OcrEngine(context)
        try {
            assertTrue("Tesseract failed to initialize", engine.initialize())

            val blank = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            blank.eraseColor(Color.BLACK)

            assertEquals("A blank frame must not yield any OCR lines", 0, engine.recognize(blank).size)
        } finally {
            engine.release()
        }
    }

    /**
     * Renders a scoreboard-like bitmap: large white digits on a black background, which is how
     * physical arena scoreboards look when the camera is pointed at them.
     */
    private fun scoreboardBitmap(text: String): Bitmap {
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
