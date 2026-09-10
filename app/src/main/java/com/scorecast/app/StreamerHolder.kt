package com.scorecast.app

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresPermission
import com.scorecast.app.overlay.CameraOverlayVideoSource
import com.scorecast.app.overlay.CameraOverlayVideoSourceFactory
import com.scorecast.app.overlay.ScoreboardOverlay
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.UriMediaDescriptor
import io.github.thibaultbee.streampack.core.elements.endpoints.CombineEndpoint
import io.github.thibaultbee.streampack.core.elements.endpoints.CombineEndpointFactory
import io.github.thibaultbee.streampack.core.elements.endpoints.DynamicEndpointFactory
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
import java.io.File

object StreamerHolder {

    sealed interface State {
        data object Idle : State
        data object Starting : State
        data object Live : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    data class Flip(val horizontal: Boolean, val vertical: Boolean, val rotation: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var streamer: SingleStreamer? = null
    private var previewSurface: Surface? = null

    private val _flip = MutableStateFlow(
        Flip(StreamConfig.CAMERA_FLIP_HORIZONTAL, StreamConfig.CAMERA_FLIP_VERTICAL, StreamConfig.CAMERA_ROTATION_DEGREES)
    )
    val flip: StateFlow<Flip> = _flip.asStateFlow()

    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

    /** The file being recorded in the current session; null for stream-only mode. */
    var currentRecordingFile: File? = null
        private set

    /** Wall-clock time [State.Live] was entered, for the elapsed on-air timer in the live UI;
     *  0L while not live. Local device time only — this is a display-only elapsed counter, not a
     *  synced value, so raw device time (not [ServerTimeSync]) is the right source here. */
    private val _liveStartedAtMs = MutableStateFlow(0L)
    val liveStartedAtMs: StateFlow<Long> = _liveStartedAtMs.asStateFlow()

    val isStreaming: Boolean get() = _state.value is State.Live || _state.value is State.Starting

    @RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO])
    suspend fun start(
        context: Context,
        ingestUrl: String,
        streamKey: String,
        mode: RecordingMode = RecordingMode.STREAM_ONLY,
        recordingFile: File? = null,
    ) {
        if (streamer != null) return
        _state.value = State.Starting
        currentRecordingFile = recordingFile
        try {
            val cameraId = backCameraId(context)
            val videoSourceFactory = CameraOverlayVideoSourceFactory(cameraId) { size ->
                val state = GameStateHolder.state.value
                ScoreboardOverlay.create(state, LogoHolder.logos.value, SportConfigLoader.getCached(state.sport), size)
            }

            val endpointFactory = when (mode) {
                RecordingMode.STREAM_ONLY, RecordingMode.RECORD_ONLY -> DynamicEndpointFactory()
                RecordingMode.STREAM_AND_RECORD -> CombineEndpointFactory(
                    DynamicEndpointFactory(), DynamicEndpointFactory()
                )
            }

            val s = SingleStreamer(
                context = context.applicationContext,
                audioSourceFactory = MicrophoneSourceFactory(),
                videoSourceFactory = videoSourceFactory,
                endpointFactory = endpointFactory,
            )
            s.setVideoConfig(VideoConfig(
                startBitrate = StreamConfig.VIDEO_BITRATE,
                resolution = StreamConfig.RESOLUTION,
                fps = StreamConfig.FPS,
            ))
            s.setAudioConfig(AudioConfig(
                startBitrate = StreamConfig.AUDIO_BITRATE,
                sampleRate = StreamConfig.AUDIO_SAMPLE_RATE,
                channelConfig = AudioFormat.CHANNEL_IN_STEREO,
            ))
            streamer = s

            applyPreview()
            applyFlip()

            // Warm up the camera before opening the network connection: on this hardware, camera
            // bring-up can take 150ms-1.5s+, and StreamPack only starts the camera as part of
            // startStream() — which normally runs after open() already connected. Facebook's
            // ingest was observed closing the connection while waiting for video data during that
            // gap. Warming up first means real frames are already flowing by the time open()
            // connects and startStream() attaches the encoder.
            source()?.warmUp()

            scope.launch { s.throwableFlow.collect { t -> if (t != null) fail(t) } }

            val descriptor = when (mode) {
                RecordingMode.STREAM_ONLY -> UriMediaDescriptor(buildRtmpUrl(ingestUrl, streamKey))
                RecordingMode.RECORD_ONLY -> UriMediaDescriptor(requireNotNull(recordingFile).toUri())
                RecordingMode.STREAM_AND_RECORD -> CombineEndpoint.CombineDescriptor(listOf(
                    UriMediaDescriptor(buildRtmpUrl(ingestUrl, streamKey)),
                    UriMediaDescriptor(requireNotNull(recordingFile).toUri()),
                ))
            }

            s.open(descriptor)
            s.startStream()
            applyFlip()
            applyPreview()
            _state.value = State.Live
            _liveStartedAtMs.value = System.currentTimeMillis()
            Log.i(TAG, "Started: mode=$mode")
        } catch (t: Throwable) {
            fail(t)
        }
    }

    suspend fun stop() {
        teardown()
        _state.value = State.Idle
    }

    /** Clears the operator's acknowledged [State.Error] once they've seen it. */
    fun acknowledgeError() {
        if (_state.value is State.Error) _state.value = State.Idle
    }

    /** Releases the streamer and clears live/recording bookkeeping WITHOUT touching [state]. Split
     *  out of [stop] so [fail] can tear down and still leave State.Error standing: fail() used to
     *  set Error and then call stop(), whose `finally` immediately overwrote it with Idle, so the
     *  cause of a mid-broadcast failure was always discarded before any UI could read it. */
    private suspend fun teardown() {
        val s = streamer ?: run { _liveStartedAtMs.value = 0L; return }
        streamer = null
        currentRecordingFile = null
        _liveStartedAtMs.value = 0L
        try {
            s.stopStream()
            s.close()
        } catch (t: Throwable) {
            Log.w(TAG, "stop() error", t)
        } finally {
            s.release()
        }
    }

    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        applyPreview()
    }

    fun setTransform(horizontal: Boolean, vertical: Boolean, rotation: Int) {
        _flip.value = Flip(horizontal, vertical, ((rotation % 360) + 360) % 360)
        applyFlip()
    }

    fun setZoomRatio(ratio: Float) {
        _zoomRatio.value = ratio.coerceAtLeast(1f)
        source()?.setZoomRatio(ratio)
    }

    /** The camera's actual supported zoom bounds, for sizing a zoom slider. Falls back to 1x-5x
     *  if the camera hasn't reported a range yet (e.g. queried before the stream starts). */
    suspend fun getZoomRange(): ClosedFloatingPointRange<Float> {
        val range = source()?.getZoomRange()
        return if (range != null) range.lower..range.upper else 1f..5f
    }

    private fun source(): CameraOverlayVideoSource? =
        streamer?.videoInput?.sourceFlow?.value as? CameraOverlayVideoSource

    private fun applyPreview() { source()?.setPreviewSurface(previewSurface) }
    private fun applyFlip() { _flip.value.let { source()?.setTransform(it.horizontal, it.vertical, it.rotation) } }

    private fun fail(t: Throwable) {
        Log.e(TAG, "Stream error", t)
        val message = t.message ?: t.javaClass.simpleName
        scope.launch {
            // Tear down first, then publish Error last, so nothing in the teardown path can
            // clobber it. State.Error is terminal until the operator acknowledges it via
            // acknowledgeError() or starts/ends a new stream.
            runCatching { teardown() }
            _state.value = State.Error(message)
        }
    }

    private fun buildRtmpUrl(ingestUrl: String, streamKey: String): String {
        val base = ingestUrl.trim().trimEnd('/')
        val key = streamKey.trim().trimStart('/')
        return if (key.isEmpty()) base else "$base/$key"
    }

    private fun File.toUri(): String = "file://${absolutePath}"

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
