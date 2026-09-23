package com.scorecast.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import android.view.Surface
import com.scorecast.app.GameStateHolder
import com.scorecast.app.LogoHolder
import com.scorecast.app.SportConfigLoader
import com.scorecast.app.StreamConfig
import io.github.thibaultbee.streampack.core.elements.processing.video.source.DefaultSourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.ISurfaceSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.VideoSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import io.github.thibaultbee.streampack.core.pipelines.IVideoDispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** How often the overlay loop checks whether the scoreboard needs redrawing. Kept well under a
 *  second so a clock tick lands on the broadcast promptly — the check itself is cheap, and a
 *  redraw only follows when something actually changed. */
private const val OVERLAY_POLL_MS = 250L

class CameraOverlayVideoSource internal constructor(
    val cameraId: String,
    private val camera: IVideoSourceInternal,
    private val overlayProvider: (Size) -> Bitmap?,
) : ISurfaceSourceInternal, IVideoSourceInternal {

    private val cameraSurface = camera as? ISurfaceSourceInternal
        ?: error("CameraSource must implement ISurfaceSourceInternal — StreamPack contract violated")

    private val cameraSource: ICameraSource? get() = camera as? ICameraSource

    override val timebase get() = cameraSurface.timebase
    override val infoProviderFlow: StateFlow<ISourceInfoProvider> =
        MutableStateFlow(DefaultSourceInfoProvider() as ISourceInfoProvider).asStateFlow()
    override val isStreamingFlow: StateFlow<Boolean> get() = camera.isStreamingFlow

    private var config: VideoSourceConfig? = null
    private var outputSurface: Surface? = null
    private var compositor: OverlayCompositor? = null
    private var previewSurface: Surface? = null
    private var flipHorizontal = StreamConfig.CAMERA_FLIP_HORIZONTAL
    private var flipVertical = StreamConfig.CAMERA_FLIP_VERTICAL
    private var rotationDegrees = StreamConfig.CAMERA_ROTATION_DEGREES

    private val overlayScope = CoroutineScope(Dispatchers.Default)
    private var overlayJob: Job? = null

    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        compositor?.setPreviewSurface(surface)
    }

    fun setTransform(horizontal: Boolean, vertical: Boolean, rotation: Int) {
        flipHorizontal = horizontal
        flipVertical = vertical
        rotationDegrees = rotation
        compositor?.setTransform(horizontal, vertical, rotation)
    }

    /** Sets camera zoom ratio. Clamped to the camera's supported range. No-op if zoom is unsupported. */
    fun setZoomRatio(ratio: Float) {
        overlayScope.launch {
            try {
                cameraSource?.settings?.zoom?.setZoomRatio(ratio)
            } catch (_: Exception) {}
        }
    }

    suspend fun getZoomRange(): android.util.Range<Float>? =
        cameraSource?.settings?.zoom?.availableRatioRange

    // --- ISurfaceSourceInternal ---

    override suspend fun getOutput(): Surface? = outputSurface
    override suspend fun setOutput(surface: Surface) {
        outputSurface = surface
        // If we're already streaming, this is StreamPack replacing the encoder's input surface
        // in place (e.g. an encoder color-format fallback) — redirect the compositor's render
        // target only. Do NOT touch the camera capture session (see startStream() below).
        compositor?.setOutputSurface(surface)
    }

    override suspend fun resetOutput() {
        cameraSurface.resetOutput()
        compositor?.release()
        compositor = null
        outputSurface = null
    }

    // --- IVideoSourceInternal ---

    override suspend fun configure(config: VideoSourceConfig) {
        this.config = config
        camera.configure(config)
    }

    private suspend fun beginCapture(cfg: VideoSourceConfig) {
        val comp = OverlayCompositor(
            outputSurface = outputSurface,
            size = cfg.resolution,
            overlayBitmap = overlayProvider(cfg.resolution),
            flipHorizontal = flipHorizontal,
            flipVertical = flipVertical,
            rotationDegrees = rotationDegrees,
        )
        comp.start()
        previewSurface?.let { comp.setPreviewSurface(it) }
        compositor = comp

        cameraSurface.setOutput(comp.cameraInputSurface)
        camera.startStream()

        overlayJob = overlayScope.launch {
            // Poll often enough that a clock tick reaches the broadcast promptly, but only redraw
            // when the rendered content actually changed. This loop used to allocate a fresh
            // frame-sized ARGB_8888 bitmap (3.5 MiB at 720p) and re-upload it to the GPU on every
            // single tick regardless of whether anything moved — ~14 MiB/s of garbage for a bar
            // that usually changes once a second at most. Measured on-device: 21.9% janky frames,
            // 150ms at the 90th percentile, "Skipped 72 frames" on the main thread. Being on
            // Dispatchers.Default does not help; the GC pauses it caused stalled every thread.
            val ticker = flow { while (true) { emit(Unit); delay(OVERLAY_POLL_MS) } }
            // Two buffers, alternated. updateOverlay() hands the bitmap to the GL thread and
            // uploads it there, so the buffer we just published must not be the one we draw into
            // next. They are deliberately never recycled: the GL upload is asynchronous, and
            // recycling underneath it would risk a use-after-recycle crash for the sake of
            // collecting 7 MiB once per session.
            val buffers = Array(2) {
                Bitmap.createBitmap(cfg.resolution.width, cfg.resolution.height, Bitmap.Config.ARGB_8888)
            }
            var next = 0
            var lastSignature: ScoreboardOverlay.Signature? = null
            combine(GameStateHolder.state, LogoHolder.logos, ticker) { state, logos, _ ->
                Pair(state, logos)
            }.collect { (state, logos) ->
                val config = SportConfigLoader.getCached(state.sport)
                val signature = ScoreboardOverlay.signature(state, logos, config, cfg.resolution)
                if (signature == lastSignature) return@collect
                lastSignature = signature
                val bitmap = buffers[next]
                next = (next + 1) % buffers.size
                ScoreboardOverlay.renderInto(bitmap, state, logos, config, cfg.resolution)
                compositor?.updateOverlay(bitmap)
            }
        }
    }

    /**
     * Pre-opens the camera and starts its Camera2 capture session before the real encoder output
     * surface exists (rendering to a throwaway placeholder in the meantime — see
     * [OverlayCompositor.setOutputSurface]). Physical camera bring-up was observed taking
     * 150ms-1.5s+ on a Legacy-tier Camera2 HAL; calling this ahead of the streamer's open()/
     * startStream() sequence means that bring-up happens *before* the RTMP connection opens
     * instead of after — StreamPack bundles camera-start into startStream(), which normally runs
     * only once open() has already connected, and Facebook's ingest was observed closing the
     * connection while waiting for video data during that camera warm-up window. No-op if the
     * camera is already running.
     */
    suspend fun warmUp() {
        if (compositor != null) return
        val cfg = requireNotNull(config) { "configure() must be called before warmUp()" }
        beginCapture(cfg)
    }

    override suspend fun startStream() {
        if (compositor != null) {
            // Already running — either warmUp() already brought the camera up, or StreamPack
            // re-invoked startStream() as part of an internal renegotiation (observed on-device
            // as an encoder color-format fallback that swaps the encoder's input surface). Either
            // way the camera capture session is already live — Legacy-tier Camera2 HALs reject
            // reconfiguring an active session's target surface, so treat this as a no-op.
            // setOutput() already redirected the compositor's render target if a new surface
            // arrived.
            return
        }
        val cfg = requireNotNull(config) { "configure() must be called before startStream()" }
        beginCapture(cfg)
    }

    override suspend fun stopStream() {
        overlayJob?.cancel()
        overlayJob = null
        camera.stopStream()
        compositor?.release()
        compositor = null
    }

    override suspend fun release() {
        overlayJob?.cancel()
        overlayJob = null
        overlayScope.cancel()
        camera.release()
        compositor?.release()
        compositor = null
    }
}

class CameraOverlayVideoSourceFactory(
    private val cameraId: String,
    private val overlayProvider: (Size) -> Bitmap?,
) : IVideoSourceInternal.Factory {

    @SuppressLint("MissingPermission")
    override suspend fun create(
        context: Context,
        dispatcherProvider: IVideoDispatcherProvider,
    ): IVideoSourceInternal {
        val camera = CameraSourceFactory(cameraId).create(context, dispatcherProvider)
        return CameraOverlayVideoSource(cameraId, camera, overlayProvider)
    }

    override fun isSourceEquals(source: IVideoSourceInternal?): Boolean =
        source is CameraOverlayVideoSource && source.cameraId == cameraId
}
