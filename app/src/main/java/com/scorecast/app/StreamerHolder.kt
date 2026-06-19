package com.scorecast.app

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresPermission
import com.scorecast.app.overlay.CameraOverlayVideoSourceFactory
import com.scorecast.app.overlay.ScoreboardOverlay
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.UriMediaDescriptor
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the single StreamPack pipeline for Phase 1 and exposes a small state surface.
 *
 * Lives outside the Activity so the stream is unaffected by configuration changes / backgrounding;
 * [StreamingService] keeps the process alive while this is streaming.
 */
object StreamerHolder {

    sealed interface State {
        data object Idle : State
        data object Starting : State
        data object Live : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Live camera orientation tuning (spec README "Bring-up order"). */
    data class Flip(val horizontal: Boolean, val vertical: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var streamer: SingleStreamer? = null
    private var previewSurface: Surface? = null

    private val _flip = MutableStateFlow(
        Flip(StreamConfig.CAMERA_FLIP_HORIZONTAL, StreamConfig.CAMERA_FLIP_VERTICAL)
    )
    val flip: StateFlow<Flip> = _flip.asStateFlow()

    val isStreaming: Boolean get() = _state.value is State.Live || _state.value is State.Starting

    @RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO])
    suspend fun start(context: Context, ingestUrl: String, streamKey: String) {
        if (streamer != null) return
        _state.value = State.Starting
        try {
            val cameraId = backCameraId(context)
            val videoSourceFactory = CameraOverlayVideoSourceFactory(cameraId) { size ->
                if (StreamConfig.USE_OVERLAY) ScoreboardOverlay.createStatic(size) else null
            }

            val s = SingleStreamer(
                context = context.applicationContext,
                audioSourceFactory = MicrophoneSourceFactory(),
                videoSourceFactory = videoSourceFactory,
            )
            s.setVideoConfig(
                VideoConfig(
                    startBitrate = StreamConfig.VIDEO_BITRATE,
                    resolution = StreamConfig.RESOLUTION,
                    fps = StreamConfig.FPS,
                )
            )
            s.setAudioConfig(
                AudioConfig(
                    startBitrate = StreamConfig.AUDIO_BITRATE,
                    sampleRate = StreamConfig.AUDIO_SAMPLE_RATE,
                    channelConfig = AudioFormat.CHANNEL_IN_STEREO,
                )
            )
            streamer = s

            // Wire operator preview + current flip state into our custom source.
            applyPreview()
            applyFlip()

            // Surface error propagation from the pipeline.
            scope.launch {
                s.throwableFlow.collect { t -> if (t != null) fail(t) }
            }

            val url = buildRtmpUrl(ingestUrl, streamKey)
            s.open(UriMediaDescriptor(url))
            s.startStream()
            // Re-apply in case StreamPack created the video source lazily during startStream();
            // by now the source (and its compositor) certainly exist.
            applyFlip()
            applyPreview()
            _state.value = State.Live
            Log.i(TAG, "Streaming to $url")
        } catch (t: Throwable) {
            fail(t)
        }
    }

    suspend fun stop() {
        val s = streamer ?: run { _state.value = State.Idle; return }
        streamer = null
        try {
            s.stopStream()
            s.close()
        } catch (t: Throwable) {
            Log.w(TAG, "stop() error", t)
        } finally {
            s.release()
            _state.value = State.Idle
        }
    }

    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        applyPreview()
    }

    /** Toggle camera mirroring/rotation live; persists across (re)starts within this process. */
    fun setFlip(horizontal: Boolean, vertical: Boolean) {
        _flip.value = Flip(horizontal, vertical)
        applyFlip()
    }

    private fun source(): com.scorecast.app.overlay.CameraOverlayVideoSource? =
        streamer?.videoInput?.sourceFlow?.value as? com.scorecast.app.overlay.CameraOverlayVideoSource

    private fun applyPreview() {
        source()?.setPreviewSurface(previewSurface)
    }

    private fun applyFlip() {
        _flip.value.let { source()?.setFlip(it.horizontal, it.vertical) }
    }

    private fun fail(t: Throwable) {
        Log.e(TAG, "Stream error", t)
        _state.value = State.Error(t.message ?: t.javaClass.simpleName)
        scope.launch { runCatching { stop() } }
    }

    /** Joins the pasted ingest URL and stream key into a single RTMP target URL. */
    private fun buildRtmpUrl(ingestUrl: String, streamKey: String): String {
        val base = ingestUrl.trim().trimEnd('/')
        val key = streamKey.trim().trimStart('/')
        return if (key.isEmpty()) base else "$base/$key"
    }

    private fun backCameraId(context: Context): String {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = cm.cameraIdList
        return ids.firstOrNull { id ->
            cm.getCameraCharacteristics(id)[CameraCharacteristics.LENS_FACING] ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.first()
    }

    private const val TAG = "StreamerHolder"
}
