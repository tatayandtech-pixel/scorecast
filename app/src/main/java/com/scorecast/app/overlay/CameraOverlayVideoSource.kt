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
    override suspend fun setOutput(surface: Surface) { outputSurface = surface }

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

    override suspend fun startStream() {
        val cfg = requireNotNull(config) { "configure() must be called before startStream()" }
        val out = requireNotNull(outputSurface) { "setOutput() must be called before startStream()" }

        val comp = OverlayCompositor(
            outputSurface = out,
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
            val ticker = flow { while (true) { emit(Unit); delay(250) } }
            combine(GameStateHolder.state, LogoHolder.logos, ticker) { state, logos, _ ->
                Pair(state, logos)
            }.collect { (state, logos) ->
                val config = SportConfigLoader.getCached(state.sport)
                val bitmap = ScoreboardOverlay.create(state, logos, config, cfg.resolution)
                compositor?.updateOverlay(bitmap)
            }
        }
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
