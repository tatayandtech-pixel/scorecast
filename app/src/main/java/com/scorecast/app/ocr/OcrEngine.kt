package com.scorecast.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

/**
 * Open-source Tesseract OCR wrapper for reading digits from physical basketball scoreboards.
 *
 * Uses the `tess-two` library (`com.rmtheis:tess-two`) with a digit-only character whitelist
 * for maximum accuracy on scoreboard displays. The English traineddata model is downloaded
 * on first use from the upstream tessdata repository, cached to internal storage, and never
 * leaves the device.
 *
 * Lifecycle: call [initialize] on a background thread before [recognize], and [release]
 * when done (e.g. on mirror device session end).
 *
 * Note on the traineddata path: `TessBaseAPI.init()` requires the data path to be the folder
 * that *contains* a `tessdata` subfolder — it throws
 * `IllegalArgumentException("Data path must contain subfolder tessdata!")` otherwise. The model
 * therefore lives in `<filesDir>/tessdata/` and we hand `init()` the parent, `<filesDir>`.
 */
class OcrEngine(private val context: Context) {

    companion object {
        private const val TAG = "OcrEngine"
        private const val TESSDATA_DIR = "tessdata"
        private const val TRAINED_DATA_FILENAME = "eng.traineddata"
        // Uses the legacy tesseract-ocr/tessdata repo, NOT tessdata_fast.
        //
        // tess-two 9.1.0 bundles Tesseract 3.05.00 (its native lib reports
        // "tesseract 3.05.00" and carries the legacy CUBE LSTM engine — it has no LSTM engine and
        // its OEM constants are CUBE-named: OEM_TESSERACT_ONLY/CUBE_ONLY/TESSERACT_CUBE_COMBINED).
        // The tessdata_fast/tessdata_best repos only ship models for Tesseract 4.0.0+, whose
        // traineddata layout 3.05 cannot read — Tesseract then reports that it could not load any
        // data file and every recognition returns empty. The legacy tessdata repo still carries the
        // 3.05-compatible eng.traineddata (legacy + CUBE components), so it is the only source
        // this engine can use. It is ~23 MB rather than ~4 MB, but it is the compatible one.
        private const val TRAINED_DATA_URL =
            "https://raw.githubusercontent.com/tesseract-ocr/tessdata/main/eng.traineddata"

        /**
         * Minimum MEAN confidence (0..1) of a whole frame for the frame to be considered at all.
         *
         * Measured on-device with the legacy eng.traineddata (Tesseract 3.05) on rendered
         * scoreboard text:
         * ```
         *   scores       "88  77"     -> 0.94
         *   clock        "12:00"      -> 0.86
         *   clock        "10:30"      -> 0.84
         *   shot clock   "24"         -> 0.92
         *   noise        "HOME GUEST" -> 0.52
         *   noise        "Q2"         -> 0.52
         *   noise        "KICKOFF"    -> 0.17
         *   blank frame                -> 0.00
         * ```
         * 0.5 drops the noise frames while keeping every clock.
         *
         * This used to be a single 0.85 threshold applied to the frame's MEAN confidence, which
         * rejected every frame whose only content was a game clock (a clock on its own scores at
         * best 0.84) — the game clock could therefore never be read at all, no matter how well
         * Tesseract recognised it.
         */
        private const val MIN_MEAN_CONFIDENCE = 0.5f

        /**
         * Minimum confidence (0..1) of a SINGLE recognised word for that word to be reported.
         *
         * Measured per-word confidences on the same inputs:
         * ```
         *   scores       "88  77"     -> 0.95 / 0.95
         *   clock        "12:00"      -> 0.86
         *   clock        "10:30"      -> 0.85
         *   clock        "2:30"       -> 0.79   (colon dropped — see OcrScoreMatcher)
         *   shot clock   "24"         -> 0.92
         *   noise        "HOME GUEST" -> 0.34 / 0.71
         *   noise        "Q2"         -> 0.53
         *   noise        "KICKOFF"    -> 0.17
         * ```
         * 0.75 keeps every real reading and drops every measured noise word. Filtering per word
         * — rather than only per frame — is what lets a frame that contains a real score next to
         * some unreadable background still yield its score.
         */
        private const val MIN_WORD_CONFIDENCE = 0.75f

        /**
         * Bitmaps wider than this are downscaled before recognition (speed, not accuracy).
         *
         * 1280 rather than the 640 this used to be. Measured on-device with a rendered
         * scoreboard frame (clock "10:30" and scores "88  77"):
         * ```
         *   width  clock reading   scores        recognise time
         *    480   "10:30" (0.85)  0.93 / 0.95   ~29 ms
         *    640   "10230" (0.78)  0.92 / 0.95   ~26 ms
         *    800   "10:30" (0.85)  0.97 / 0.97   ~37 ms
         *    960   "10:30" (0.93)  0.98 / 0.99   ~46 ms
         *   1280   "10:30" (0.92)  0.97 / 0.99   ~64 ms
         * ```
         * Halving the frame to 640 happens to land on a resampling ratio that blurs the colon of
         * a game clock away entirely (it became "10230") and is measurably worse for the scores
         * too. 1280 keeps the clock readable and the scores sharper for ~40 ms more per frame —
         * irrelevant next to the 2 s debounce the scanner applies to score updates, and the
         * camera's KEEP_ONLY_LATEST strategy drops frames while OCR runs anyway.
         */
        private const val MAX_RECOGNIZE_WIDTH = 1280
    }

    private var tessBaseApi: TessBaseAPI? = null
    private var initialized = false

    /** Mean confidence (0..1) of the most recent recognition. */
    var lastConfidence: Float = 0f
        private set

    /**
     * A single OCR-detected word with its position and confidence.
     *
     * [imageWidth]/[imageHeight] are the dimensions of the image the [boundingBox] is expressed
     * in (the engine may downscale before recognition). The matcher needs them to turn a pixel
     * position into a 0..1 fraction, and it cannot infer them from the boxes themselves: a
     * scoreboard in the middle of the frame produces boxes far smaller than the frame.
     */
    data class OcrTextLine(
        val text: String,
        val confidence: Float,
        val boundingBox: Rect,
        val imageWidth: Int = 0,
        val imageHeight: Int = 0,
    )

    /**
     * Initializes Tesseract, downloading the English traineddata model if needed.
     * Must be called on a background thread.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (initialized) return@withContext true
        try {
            // The model goes in <filesDir>/tessdata/, but init() is handed <filesDir> because
            // Tesseract expects the data path to CONTAIN a "tessdata" subfolder.
            val tessDataDir = File(context.filesDir, TESSDATA_DIR).apply { mkdirs() }
            val trainedData = File(tessDataDir, TRAINED_DATA_FILENAME)

            if (!trainedData.exists() || trainedData.length() == 0L) {
                Log.i(TAG, "Downloading Tesseract English model...")
                if (!downloadTrainedData(trainedData)) {
                    Log.e(TAG, "Failed to download traineddata")
                    return@withContext false
                }
            }

            val api = TessBaseAPI()
            // Whitelist: digits, plus ':' and space.
            //
            // Digits are what a scoreboard mostly shows — basketball/volleyball/etc. scores are
            // pure integers — and whitelisting them also makes a separate blacklist
            // unnecessary: Tesseract ignores the blacklist entirely once a whitelist is set.
            // ':' and space are needed for the game clock: a clock reads "10:30", and with a
            // digits-only whitelist the colon could never be recognised, which made the
            // matcher's parseClockSeconds() unreachable in practice.
            //
            // The whitelist and the page segmentation mode are applied AFTER init() further
            // down: Tesseract resets its parameters during init(), so anything set beforehand
            // is silently discarded and the whitelist would have no effect at all.

            // init() throws IllegalArgumentException if the data path is missing or has no
            // "tessdata" subfolder, so it must be the parent of the folder we just created.
            //
            // OEM_DEFAULT (= 3, TESSERACT+CUBE combined) is used rather than OEM_TESSERACT_ONLY
            // (= 0, legacy engine only): the 3.05-compatible eng.traineddata carries both the
            // legacy and the CUBE/LSTM components, and CUBE is markedly more accurate on the large
            // high-contrast digits a scoreboard shows. OEM_TESSERACT_ONLY would throw away the
            // better engine for no benefit.
            if (!api.init(context.filesDir.absolutePath, "eng", TessBaseAPI.OEM_DEFAULT)) {
                Log.e(TAG, "Tesseract init() returned false")
                api.end()
                return@withContext false
            }

            // Applied AFTER init() on purpose. Tesseract resets its parameters (including
            // tessedit_char_whitelist) while initialising, so a whitelist set beforehand is
            // thrown away: Tesseract then recognised letters, so "KICKOFF" was read as
            // "KICKOFF" and the clock colon was read inconsistently.
            api.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, "0123456789: ")
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)

            tessBaseApi = api
            initialized = true
            Log.i(TAG, "Tesseract initialized with digit whitelist")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Tesseract init failed", e)
            false
        }
    }

    fun isReady(): Boolean = initialized && tessBaseApi != null

    /** Recognizes text in the given bitmap and returns detected lines with bounding boxes. */
    suspend fun recognize(bitmap: Bitmap): List<OcrTextLine> = withContext(Dispatchers.IO) {
        val api = tessBaseApi ?: return@withContext emptyList()
        try {
            // Scale down for speed — OCR at half resolution is still accurate for large digits.
            val scaled = if (bitmap.width > MAX_RECOGNIZE_WIDTH) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    MAX_RECOGNIZE_WIDTH,
                    (MAX_RECOGNIZE_WIDTH.toFloat() / bitmap.width * bitmap.height).toInt(),
                    true,
                )
            } else {
                bitmap
            }

            api.setImage(scaled)
            val rawText = api.getUTF8Text()
            // meanConfidence() is 0..100 in this library; normalize to 0..1 for comparison.
            val conf = api.meanConfidence() / 100f
            // The result iterator must be consumed BEFORE api.clear() — clear() wipes the
            // recognition result the iterator walks over.
            val lines = parseOcrLines(api, scaled.width, scaled.height, conf)
            api.clear()
            lastConfidence = conf

            if (rawText.isBlank() || conf < MIN_MEAN_CONFIDENCE) return@withContext emptyList()

            lines
        } catch (e: Exception) {
            Log.w(TAG, "OCR recognition failed", e)
            emptyList()
        }
    }

    /**
     * Extracts recognised words with their REAL bounding boxes via Tesseract's result iterator.
     *
     * The previous implementation estimated positions by distributing the recognised lines
     * vertically and gave every line a box spanning the FULL image width. The matcher decides
     * home vs. away from a token's horizontal centre, so a full-width box (centre == exactly
     * 0.5) made `isHomeSide` false for every token: the HOME score could never be detected by
     * OCR at all, and every score was mis-attributed to the away side.
     *
     * Words (not text lines) are used because a scoreboard row such as "88  77" is a single
     * text line; only word-level boxes separate the two scores.
     */
    private fun parseOcrLines(
        api: TessBaseAPI,
        imgWidth: Int,
        imgHeight: Int,
        fallbackConfidence: Float,
    ): List<OcrTextLine> {
        val iterator = try {
            api.getResultIterator()
        } catch (e: Exception) {
            Log.w(TAG, "Could not obtain the Tesseract result iterator", e)
            return emptyList()
        }

        val lines = mutableListOf<OcrTextLine>()
        try {
            iterator.begin()
            do {
                val text = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                if (text.isNullOrBlank()) continue
                val rect = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                val wordConfidence =
                    iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD) / 100f
                // A word with no own confidence falls back to the frame's mean confidence.
                val effectiveConfidence =
                    if (wordConfidence > 0f) wordConfidence else fallbackConfidence
                if (effectiveConfidence < MIN_WORD_CONFIDENCE) {
                    Log.d(TAG, "Dropping low-confidence word '$text' (conf=$effectiveConfidence)")
                    continue
                }
                lines.add(
                    OcrTextLine(
                        text = text.trim(),
                        confidence = effectiveConfidence,
                        boundingBox = rect ?: Rect(0, 0, imgWidth, imgHeight),
                        imageWidth = imgWidth,
                        imageHeight = imgHeight,
                    )
                )
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
        } catch (e: Exception) {
            Log.w(TAG, "Tesseract result iteration failed", e)
        } finally {
            // The iterator owns a native handle. Leaking it leaks native memory every frame,
            // and the scanner runs on every analysed camera frame.
            try {
                iterator.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release the Tesseract result iterator", e)
            }
        }
        return lines
    }

    /**
     * Downloads the traineddata file to the given File. Returns true on success.
     *
     * Downloads to a temporary file first and renames it into place, so an interrupted
     * download can never leave a truncated model behind (Tesseract would then fail to load it
     * on every later run).
     */
    private fun downloadTrainedData(dest: File): Boolean {
        val tmp = File(dest.parentFile, "${dest.name}.part")
        return try {
            val connection = URL(TRAINED_DATA_URL).openConnection().apply {
                connectTimeout = 15_000
                readTimeout = 60_000
            }
            connection.getInputStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    input.copyTo(output)
                }
            }
            if (tmp.length() == 0L) {
                Log.e(TAG, "Downloaded traineddata is empty")
                tmp.delete()
                return false
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                // renameTo can fail across filesystems; fall back to a copy.
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            Log.i(TAG, "Downloaded ${dest.length()} bytes of traineddata")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            tmp.delete()
            dest.delete()
            false
        }
    }

    /** Frees the native Tesseract resources. Safe to call more than once. */
    fun release() {
        try {
            tessBaseApi?.end()
        } catch (e: Exception) {
            Log.w(TAG, "Tesseract release failed", e)
        }
        tessBaseApi = null
        initialized = false
    }
}
