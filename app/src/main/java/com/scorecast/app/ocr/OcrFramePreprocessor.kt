package com.scorecast.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import java.io.File
import java.io.FileOutputStream

/**
 * Turns a camera frame into the bitmap that is actually handed to Tesseract.
 *
 * ## Why this exists
 *
 * The scanner used to convert the frame with the **Y (luminance) plane only**. That is fatal for
 * physical scoreboards: the digits are *coloured LEDs* (red for the scores, green for the
 * period) sitting on a black board, and BT.601 luminance throws most of that colour away.
 *
 * The luminance of pure red (255, 0, 0) is `0.299 * 255 = 76` — only 30% grey — so a bright red
 * "70" became a dim grey "70" on a near-black board. Tesseract's own binarisation could not
 * separate that, and the board came back as garbage like `2022111`, `22222222` or
 * `113:1 1 321111: 1111111 :101111`. The engine then threw those away as low confidence, and
 * the matcher never saw a score at all.
 *
 * Working from RGB instead fixes the contrast, because the channel that carries the LED colour
 * is the one that is bright:
 *
 * ```
 *   red LED    (230,  40,  30)  -> max = 230   bright, as it should be
 *   green LED  ( 60, 220,  60)  -> max = 220   bright, as it should be
 *   black board( 12,  10,  14)  -> max =  14   dark, as it should be
 * ```
 *
 * ## The other half: the background
 *
 * The board is usually photographed against a brightly lit ceiling, walls or a screen. Those
 * background pixels are *just as bright* as the LEDs, so a plain brightness threshold cannot
 * tell "scoreboard digit" from "ceiling". [findScoreboardRect] therefore locates the board
 * first — it is a large, dark rectangle — and the frame is cropped to it before recognition.
 */
object OcrFramePreprocessor {

    private const val TAG = "OcrFramePreprocessor"

    /**
     * Converts a YUV_420_888 camera frame to an opaque RGB bitmap.
     *
     * Uses the standard BT.601 full-range conversion. Camera frames are usually *limited*
     * range (Y 16..235) but the resulting values are only ever used to pick the brightest
     * channel, so the small offset does not matter.
     */
    @ExperimentalGetImage
    fun ImageProxy.toRgbBitmap(): Bitmap? {
        val image = this.image ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val y = copyPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, width, height)
        val uvWidth = (width + 1) / 2
        val uvHeight = (height + 1) / 2
        val u = copyPlane(uPlane.buffer, uPlane.rowStride, uPlane.pixelStride, uvWidth, uvHeight)
        val v = copyPlane(vPlane.buffer, vPlane.rowStride, vPlane.pixelStride, uvWidth, uvHeight)
        if (y == null || u == null || v == null) return null

        val pixels = IntArray(width * height)
        for (row in 0 until height) {
            val uvRow = row / 2
            for (col in 0 until width) {
                val yy = y[row * width + col].toInt()
                val uvIndex = uvRow * uvWidth + (col / 2)
                val uu = (u[uvIndex].toInt() and 0xFF) - 128
                val vv = (v[uvIndex].toInt() and 0xFF) - 128

                val r = yy + 1.402f * vv
                val g = yy - 0.344136f * uu - 0.714136f * vv
                val b = yy + 1.772f * uu

                pixels[row * width + col] = 0xFF shl 24 or
                    (r.coerceIn(0f, 255f).toInt() shl 16) or
                    (g.coerceIn(0f, 255f).toInt() shl 8) or
                    b.coerceIn(0f, 255f).toInt()
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Reduces an RGB bitmap to a single channel holding the **brightest** colour channel.
     *
     * This is what makes an LED digit bright: whichever channel carries the LED's colour is
     * the bright one, while the black board stays black in every channel.
     */
    fun Bitmap.toBrightestChannelBitmap(): Bitmap {
        val width = this.width
        val height = this.height
        val source = IntArray(width * height)
        this.getPixels(source, 0, width, 0, 0, width, height)

        val out = IntArray(width * height)
        for (i in source.indices) {
            val pixel = source[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val brightest = maxOf(r, g, b)
            out[i] = 0xFF shl 24 or (brightest shl 16) or (brightest shl 8) or brightest
        }
        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Converts an RGB frame into a crisp black-and-white image that contains **only the
     * illuminated LEDs** of the scoreboard.
     *
     * Two facts about physical scoreboards drive this:
     *
     * * The digits are *coloured* LEDs (red for the scores, green for the period, amber for the
     *   clock) on a black board.
     * * The captions around them ("HOME", "VISITOR", "TIME", "T.OUT", ...) are **white printed
     *   text**, not LEDs.
     *
     * Colour separates the two perfectly: an illuminated red LED has a large gap between its
     *   brightest and darkest channel (`max(R,G,B) - min(R,G,B)` is large), while white print
     *   has a gap of zero. So the image is binarised on *saturation* rather than brightness,
     *   which drops every caption and keeps only the digits that are actually lit.
     *
     * Scoreboards with plain white digits have no saturation to exploit, so the brightness
     * channel is used as a fallback — the captions come back then, but the digits are still
     * readable, and the matcher picks the score by size rather than by reading order.
     */
    fun Bitmap.toOcrBinaryBitmap(): Bitmap {
        val width = this.width
        val height = this.height
        val pixelCount = width * height
        if (pixelCount == 0) return this

        val source = IntArray(pixelCount)
        this.getPixels(source, 0, width, 0, 0, width, height)

        // Brightest channel of every pixel. This is what makes an LED digit bright: whichever
        // channel carries the LED's colour is the bright one, while the black board stays black
        // in every channel.
        //
        // This used to be Rec.601 luminance, and that is what made the red scores invisible.
        // The luminance of a red LED is only 30% grey:
        //
        //   red LED    (230,  40,  30)  -> luma = (230*77 + 40*150 + 30*29)/255 =  96
        //   green LED  ( 60, 220,  60)  -> luma = ( 60*77 + 220*150 + 60*29)/255 = 158
        //   black board( 12,  10,  14)  -> luma = ( 12*77 + 10*150 + 14*29)/255 =  12
        //   white print(255, 255, 255)  -> luma = 255
        //
        // The red score digits sit at 96, barely above the black board at 12. The white printed
        // captions and the bright ceiling behind the board pull the Otsu threshold up, and a
        // threshold anywhere above 96 puts the red scores on the dark side of the split. They
        // then get inverted to white and vanish from the binary image - the engine returns no
        // score at all, which is exactly the symptom reported.
        //
        // max(R,G,B) fixes it because the channel that carries the LED's colour is the bright
        // one: red LED scores 230, green LED 220, black board 14. The scores now sit far above
        // the board and survive any reasonable threshold.
        val value = IntArray(pixelCount)
        val histogram = IntArray(256)
        for (i in source.indices) {
            val pixel = source[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val v = maxOf(r, g, b)
            value[i] = v
            histogram[v]++
        }

        val threshold = otsuThreshold(histogram, pixelCount) ?: 128
        Log.d(TAG, "Binarise: threshold=$threshold (${width}x$height)")

        // Inverted: lit content becomes BLACK on a WHITE background, which is what Tesseract
        // expects.
        val out = IntArray(pixelCount)
        for (i in out.indices) {
            val v = if (value[i] > threshold) 0 else 255
            out[i] = 0xFF shl 24 or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Scales a bitmap up so its width is at least [targetWidth] pixels.
     *
     * Tesseract is trained on text roughly 30 pixels tall. A scoreboard in a wide shot is
     * small in the frame, and small digits are recognised far worse than large ones.
     */
    fun Bitmap.upscaleForOcr(targetWidth: Int = 1600): Bitmap {
        if (width >= targetWidth) return this
        val scaledHeight = (height.toFloat() / width * targetWidth).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, targetWidth, scaledHeight, true)
    }

    /**
     * Finds the scoreboard inside the frame: a large, dark, roughly rectangular region.
     *
     * The board is black and the LEDs on it are bright, so "dark" is the reliable signal for
     * "this is the scoreboard", while "bright" is not (the ceiling behind the board is bright
     * too). The mask is computed on a downscaled copy for speed, and the largest connected
     * dark blob gives the board's bounding box.
     *
     * Returns null when no convincing board is found, in which case the caller should fall
     * back to using the whole frame.
     */
    fun Bitmap.findScoreboardRect(): Rect? {
        val width = this.width
        val height = this.height
        if (width < 32 || height < 32) return null

        val scale = 160f / width
        val smallWidth = (width * scale).toInt().coerceAtLeast(16)
        val smallHeight = (height * scale).toInt().coerceAtLeast(16)

        val source = IntArray(width * height)
        this.getPixels(source, 0, width, 0, 0, width, height)

        // Histogram of the luminance, used to pick the "dark" cut-off.
        val histogram = IntArray(256)
        val small = IntArray(smallWidth * smallHeight)
        for (sy in 0 until smallHeight) {
            val y0 = sy * height / smallHeight
            for (sx in 0 until smallWidth) {
                val x0 = sx * width / smallWidth
                val pixel = source[y0 * width + x0]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luma = (r * 77 + g * 150 + b * 29) shr 8
                small[sy * smallWidth + sx] = luma
                histogram[luma]++
            }
        }

        // Otsu split of the luminance histogram: the board is the dark class.
        val darkThreshold = otsuThreshold(histogram, histogram.sum()) ?: return null
        Log.d(TAG, "Board threshold=$darkThreshold (small ${smallWidth}x$smallHeight)")

        // Largest connected dark component (4-way flood fill, iterative).
        val labels = IntArray(smallWidth * smallHeight)
        var bestLabel = 0
        var bestSize = 0
        val stack = ArrayDeque<Int>()
        var label = 0
        for (start in small.indices) {
            if (labels[start] != 0 || small[start] > darkThreshold) continue
            label++
            var size = 0
            stack.clear()
            stack.addLast(start)
            labels[start] = label
            while (stack.isNotEmpty()) {
                val index = stack.removeLast()
                size++
                val x = index % smallWidth
                val y = index / smallWidth
                if (x > 0) pushDark(labels, small, index - 1, darkThreshold, label, stack)
                if (x < smallWidth - 1) pushDark(labels, small, index + 1, darkThreshold, label, stack)
                if (y > 0) pushDark(labels, small, index - smallWidth, darkThreshold, label, stack)
                if (y < smallHeight - 1) pushDark(labels, small, index + smallWidth, darkThreshold, label, stack)
            }
            if (size > bestSize) {
                bestSize = size
                bestLabel = label
            }
        }

        val totalPixels = smallWidth * smallHeight
        // A real board covers a decent chunk of the frame. A tiny dark blob is a shadow or a
        // dark object in the room, not the scoreboard.
        if (bestLabel == 0 || bestSize < totalPixels / 12) {
            Log.d(TAG, "No scoreboard found (largest dark blob ${bestSize}/${totalPixels})")
            return null
        }

        var minX = smallWidth
        var minY = smallHeight
        var maxX = -1
        var maxY = -1
        for (index in labels.indices) {
            if (labels[index] != bestLabel) continue
            val x = index % smallWidth
            val y = index / smallWidth
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (maxX < minX || maxY < minY) return null

        // Scale back to full resolution and pad slightly so the digits at the very edge of the
        // board are not clipped.
        val left = (minX * width / smallWidth * 0.94f).toInt().coerceIn(0, width - 1)
        val top = (minY * height / smallHeight * 0.94f).toInt().coerceIn(0, height - 1)
        val right = (maxX * width / smallWidth * 1.06f).toInt().coerceIn(left + 1, width)
        val bottom = (maxY * height / smallHeight * 1.06f).toInt().coerceIn(top + 1, height)

        Log.d(TAG, "Scoreboard rect=$left,$top,$right,$bottom of ${width}x$height")
        return Rect(left, top, right, bottom)
    }

    /** Crops [bitmap] to [rect], clamping the rect to the bitmap bounds. */
    fun Bitmap.cropTo(rect: Rect): Bitmap {
        val left = rect.left.coerceIn(0, this.width - 1)
        val top = rect.top.coerceIn(0, this.height - 1)
        val right = rect.right.coerceIn(left + 1, this.width)
        val bottom = rect.bottom.coerceIn(top + 1, this.height)
        return Bitmap.createBitmap(this, left, top, right - left, bottom - top)
    }

    /**
     * Writes [bitmap] as a PNG into the app's cache directory.
     *
     * Debug-only helper used to capture the exact frame the engine sees, so the preprocessing
     * can be tuned against a real photo instead of guesswork. Pull it with
     * `adb shell run-as com.scorecast.app cat <path> > frame.png`.
     */
    fun dumpBitmap(context: Context, bitmap: Bitmap, name: String): File? {
        return try {
            val dir = File(context.cacheDir, "ocr_dump").apply { mkdirs() }
            val file = File(dir, "$name.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.i(TAG, "Dumped $name to ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dump $name", e)
            null
        }
    }

    private fun pushDark(
        labels: IntArray,
        small: IntArray,
        index: Int,
        threshold: Int,
        label: Int,
        stack: ArrayDeque<Int>,
    ) {
        if (labels[index] != 0 || small[index] > threshold) return
        labels[index] = label
        stack.addLast(index)
    }

    /** Classic Otsu threshold; returns the class boundary that maximises between-class variance. */
    private fun otsuThreshold(histogram: IntArray, total: Int): Int? {
        if (total <= 0) return null
        var sum = 0L
        for (i in histogram.indices) sum += i.toLong() * histogram[i]
        var sumBackground = 0L
        var weightBackground = 0
        var bestVariance = -1.0
        var bestThreshold = -1
        for (t in 0..255) {
            weightBackground += histogram[t]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break
            sumBackground += t.toLong() * histogram[t]
            val meanBackground = sumBackground.toDouble() / weightBackground
            val meanForeground = (sum - sumBackground).toDouble() / weightForeground
            val variance =
                weightBackground.toDouble() * weightForeground.toDouble() *
                    (meanBackground - meanForeground) * (meanBackground - meanForeground)
            if (variance > bestVariance) {
                bestVariance = variance
                bestThreshold = t
            }
        }
        return bestThreshold.takeIf { it >= 0 }
    }

    /**
     * Copies a camera image plane into a compact sample array, honouring the plane's row and
     * pixel strides (U and V planes are usually half-resolution and interleaved).
     *
     * The strides are passed in explicitly because `ImageProxy.image` is an
     * `android.media.Image`, whose planes are `android.media.Image.Plane` — a different type
     * from `ImageProxy.PlaneProxy`.
     */
    private fun copyPlane(
        buffer: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
    ): ByteArray? {
        val rowBytes = width * pixelStride
        if (rowBytes <= 0 || pixelStride <= 0) return null
        val out = ByteArray(width * height)
        for (row in 0 until height) {
            val rowStart = row * rowStride
            if (rowStart + rowBytes > buffer.limit()) return null
            val base = row * width
            if (pixelStride == 1) {
                buffer.position(rowStart)
                buffer.get(out, base, width)
            } else {
                for (col in 0 until width) {
                    buffer.position(rowStart + col * pixelStride)
                    out[base + col] = buffer.get()
                }
            }
        }
        return out
    }
}


