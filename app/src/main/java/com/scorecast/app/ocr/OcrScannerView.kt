package com.scorecast.app.ocr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.scorecast.app.GameState
import com.scorecast.app.SportConfig
import com.scorecast.app.ocr.OcrScoreMatcher.ScoreDelta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val TAG = "OcrScannerView"

/**
 * Holds the shared OCR state for the scanner. The OcrEngine is lazily initialized
 * and shared across configuration changes via a static holder.
 */
object OcrCameraController {
    var engine: OcrEngine? = null
    val matcher = OcrScoreMatcher()
    val statusText = MutableStateFlow("Point camera at physical scoreboard")

    /** Current game state — set by MirrorScoringScreen before entering OCR mode. */
    var currentGameState: GameState? = null

    /** Current sport config — set by MirrorScoringScreen to drive matching logic. */
    var currentSportConfig: SportConfig? = null
}

/**
 * CameraX-based scanner view for the mirror device. Points the phone's camera at a physical
 * basketball scoreboard, runs Tesseract OCR on periodic frames, and pushes detected score
 * changes as atomic increments to Firebase.
 *
 * This is the mirror side of the OCR scoring feature. The main phone continues streaming
 * normally; this device only captures video and writes score diffs to Firebase.
 */
@Composable
fun OcrScannerView(
    onScoreDelta: (ScoreDelta) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            Toast.makeText(context, "Camera permission needed for OCR scoring.", Toast.LENGTH_LONG).show()
        }
    }

    // Re-check the permission on resume. The launcher callback above only covers the in-app
    // request flow; if the user grants the camera from the system Settings app while this
    // screen is backgrounded, nothing would ever update `hasPermission` and the gate would
    // stay up over a working camera.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!hasPermission) {
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            Text("Camera permission needed for OCR scoring")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Grant camera permission")
            }
        }
        return
    }

    val ocrEngine = remember {
        OcrCameraController.engine ?: OcrEngine(context.applicationContext).also {
            OcrCameraController.engine = it
        }
    }
    var ocrStatusText by remember { mutableStateOf("Initializing OCR…") }
    var lastUpdateMs by remember { mutableStateOf(0L) }
    var scanEnabled by remember { mutableStateOf(true) }

    // Initialize Tesseract on startup
    LaunchedEffect(ocrEngine) {
        if (ocrEngine.initialize()) {
            ocrStatusText = "Point camera at physical scoreboard"
        } else {
            ocrStatusText = "OCR engine failed to initialize"
            scanEnabled = false
        }
    }

    // Release Tesseract when the scanner is destroyed
    DisposableEffect(Unit) {
        onDispose {
            ocrEngine.release()
            OcrCameraController.engine = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                .build()
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy ->
                                analyzeFrame(
                                    proxy = proxy,
                                    ocrEngine = ocrEngine,
                                    coroutineScope = coroutineScope,
                                    onScoreDelta = { delta ->
                                        val now = System.currentTimeMillis()
                                        if (now - lastUpdateMs >= OcrScoreMatcher.MIN_UPDATE_INTERVAL_MS) {
                                            lastUpdateMs = now
                                            onScoreDelta(delta)
                                            ocrStatusText = "Updated: +${delta.homeDelta}/${delta.awayDelta}"
                                        }
                                    },
                                    onStatus = { ocrStatusText = it },
                                    scanEnabled = { scanEnabled },
                                )
                            }
                        }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                            preview, analysis,
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera bind failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
        )

        // Camera aiming aid: center crosshair box
        Canvas(
            modifier = Modifier
                .align(Alignment.Center)
                .size(200.dp)
                .border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
        ) {
            drawLine(
                color = Color.White.copy(alpha = 0.3f),
                strokeWidth = 2f,
                start = Offset(size.width / 2, 0f),
                end = Offset(size.width / 2, size.height),
            )
            drawLine(
                color = Color.White.copy(alpha = 0.3f),
                strokeWidth = 2f,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
            )
        }

        // Status bar at top
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(12.dp)
                .align(Alignment.TopCenter),
        ) {
            Text(
                text = ocrStatusText, color = Color.White,
                fontSize = 14.sp, fontWeight = FontWeight.Bold,
            )
        }

        // Controls at bottom
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.8f))
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .align(Alignment.BottomCenter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = { scanEnabled = !scanEnabled }) {
                Text(if (scanEnabled) "Pause" else "Scan", color = Color.White)
            }
            Text(
                text = if (scanEnabled) "Scanning" else "Paused",
                color = if (scanEnabled) Color(0xFF4CAF50) else Color(0xFFFF9800),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Analyzes a camera frame with OCR and notifies the callback of score deltas.
 * Runs OCR on a background coroutine — the ImageAnalysis analyzer must close the proxy
 * promptly to avoid blocking the camera pipeline.
 */
@androidx.camera.core.ExperimentalGetImage
private fun analyzeFrame(
    proxy: ImageProxy,
    ocrEngine: OcrEngine,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onScoreDelta: (ScoreDelta) -> Unit,
    onStatus: (String) -> Unit,
    scanEnabled: () -> Boolean,
) {
    if (!scanEnabled()) {
        proxy.close()
        return
    }

    val bitmap = proxy.toGrayscaleBitmap()
    if (bitmap == null) {
        proxy.close()
        return
    }
    proxy.close()

    coroutineScope.launch {
        val lines = ocrEngine.recognize(bitmap)
        val current = OcrCameraController.currentGameState
        val config = OcrCameraController.currentSportConfig

        if (lines.isEmpty() || current == null || config == null) {
            onStatus("No readable digits — aim at scoreboard")
            return@launch
        }

        val result = OcrCameraController.matcher.match(lines, current, config)
        if (result == null) {
            onStatus("Reading…")
            return@launch
        }

        val delta = OcrCameraController.matcher.computeDelta(result, current)
        if (delta.homeDelta != 0 || delta.awayDelta != 0) {
            onScoreDelta(delta)
            onStatus("Detected: HOME ${current.homeScore + delta.homeDelta} / AWAY ${current.awayScore + delta.awayDelta}")
        } else {
            onStatus("Score unchanged: ${current.homeScore}–${current.awayScore}")
        }
    }
}

/**
 * Converts a YUV_420_888 ImageProxy to a grayscale ARGB Bitmap for Tesseract OCR.
 * Uses only the Y (luminance) plane — for digit recognition on LED scoreboards,
 * grayscale is sufficient and avoids the YUV-to-NV21 interleaving complexity.
 *
 * Renamed away from `toBitmap()` because androidx.camera.core.ImageProxy now exposes a
 * member function `toBitmap()` that shadows this extension (compile warning at call site).
 */
@androidx.camera.core.ExperimentalGetImage
private fun ImageProxy.toGrayscaleBitmap(): Bitmap? {
    val image = this.image ?: return null
    val yBuffer = image.planes[0].buffer // Y (luminance)
    if (yBuffer.remaining() < image.width * image.height) return null

    val y = ByteArray(image.width * image.height)
    yBuffer.get(y)

    return Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also { bmp ->
        val pixels = IntArray(image.width * image.height)
        for (i in pixels.indices) {
            val v = y[i].toInt() and 0xFF
            // Grayscale: R=G=B=v, A=255
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bmp.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
    }
}
