package com.scorecast.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import android.view.Surface
import io.github.thibaultbee.streampack.core.elements.processing.video.source.DefaultSourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.ISurfaceSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.VideoSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSourceFactory
import io.github.thibaultbee.streampack.core.pipelines.IVideoDispatcherProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A StreamPack video source that streams the camera with the scoreboard burned in.
 *
 * It wraps StreamPack's own [io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSource]
 * (so we reuse its Camera2 session handling) and routes the camera through [OverlayCompositor], which
 * GL-composites the overlay and renders into the surface StreamPack hands us for the encoder.
 *
 * We report an identity [DefaultSourceInfoProvider] (rotation 0, no mirror) so StreamPack's
 * downstream surface processor does NOT re-transform our already-composited frame — orientation is
 * owned entirely by [OverlayCompositor].
 */
class CameraOverlayVideoSource internal constructor(
    val cameraId: String,
    private val camera: IVideoSourceInternal,
    private val overlayProvider: (Size) -> Bitmap?,
) : ISurfaceSourceInternal, IVideoSourceInternal {

    private val cameraSurface = camera as ISurfaceSourceInternal

    override val timebase get() = cameraSurface.timebase

    override val infoProviderFlow: StateFlow<ISourceInfoProvider> =
        MutableStateFlow(DefaultSourceInfoProvider() as ISourceInfoProvider).asStateFlow()

    override val isStreamingFlow: StateFlow<Boolean> get() = camera.isStreamingFlow

    private var config: VideoSourceConfig? = null
    private var outputSurface: Surface? = null
    private var compositor: OverlayCompositor? = null
    private var previewSurface: Surface? = null

    /** Operator preview SurfaceView; applied immediately if streaming, else when the stream starts. */
    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        compositor?.setPreviewSurface(surface)
    }

    // --- ISurfaceSourceInternal ---

    override suspend fun getOutput(): Surface? = outputSurface

    override suspend fun setOutput(surface: Surface) {
        outputSurface = surface
    }

    override suspend fun resetOutput() {
        camera.stopStream()
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
            overlayPosition = OverlayPosition.BOTTOM_CENTER,
        )
        comp.start()
        previewSurface?.let { comp.setPreviewSurface(it) }
        compositor = comp

        // Camera renders into the compositor's external-OES input instead of the encoder directly.
        cameraSurface.setOutput(comp.cameraInputSurface)
        camera.startStream()
    }

    override suspend fun stopStream() {
        camera.stopStream()
        compositor?.release()
        compositor = null
    }

    override suspend fun release() {
        camera.release()
        compositor?.release()
        compositor = null
    }
}

/**
 * Factory StreamPack uses to build the source. Creates the inner [CameraSourceFactory] source with
 * the dispatcher provider StreamPack supplies, then wraps it.
 */
class CameraOverlayVideoSourceFactory(
    private val cameraId: String,
    private val overlayProvider: (Size) -> Bitmap?,
) : IVideoSourceInternal.Factory {

    @SuppressLint("MissingPermission") // CAMERA is requested at runtime before the streamer is built.
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
