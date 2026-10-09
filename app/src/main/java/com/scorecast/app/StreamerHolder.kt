package com.scorecast.app

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresPermission
import com.scorecast.app.overlay.CameraOverlayVideoSource
import com.scorecast.app.overlay.CameraOverlayVideoSourceFactory
import com.scorecast.app.overlay.ScoreboardOverlay
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.UriMediaDescriptor
import io.github.thibaultbee.streampack.core.elements.endpoints.CombineEndpoint
import io.github.thibaultbee.streampack.core.elements.endpoints.CombineEndpointFactory
import io.github.thibaultbee.streampack.core.elements.endpoints.DynamicEndpointFactory
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.EOFException
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object StreamerHolder {

    sealed interface State {
        data object Idle : State
        data object Starting : State
        data object Live : State
        /** The connection dropped after going live and [attempt] is the retry in progress. The
         *  match is still on, so the UI treats this like Live (spec §10 stream resilience). */
        data class Reconnecting(val attempt: Int) : State
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

    /** True when the current session only records locally — nothing is on air, so the UI must
     *  not say "LIVE" even though the streamer itself is in [State.Live]. */
    var recordOnly: Boolean = false
        private set

    /** Wall-clock time [State.Live] was entered, for the elapsed on-air timer in the live UI;
     *  0L while not live. Local device time only — this is a display-only elapsed counter, not a
     *  synced value, so raw device time (not [ServerTimeSync]) is the right source here. */
    private val _liveStartedAtMs = MutableStateFlow(0L)
    val liveStartedAtMs: StateFlow<Long> = _liveStartedAtMs.asStateFlow()

    val isStreaming: Boolean
        get() = _state.value.let { it is State.Live || it is State.Starting || it is State.Reconnecting }

    /** What [reconnect] needs to reopen the same broadcast. In memory only, like the key itself. */
    private class Session(
        val ingestUrl: String,
        val streamKey: String,
        val mode: RecordingMode,
        val firstRecordingFile: File?,
    ) {
        var part = 1
    }
    private var session: Session? = null
    private var reconnectJob: Job? = null

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
        recordOnly = mode == RecordingMode.RECORD_ONLY
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

            session = Session(ingestUrl, streamKey, mode, recordingFile)
            scope.launch { s.throwableFlow.collect { t -> if (t != null) onStreamError(t) } }

            s.open(descriptor(ingestUrl, streamKey, mode, recordingFile))
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
        reconnectJob?.cancelAndJoin()
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
        session = null
        currentRecordingFile = null
        recordOnly = false
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

    /**
     * Routes an error raised by the streamer while it runs. Only a broadcast that was already on
     * air is retried: a failure while Starting is almost always a wrong URL or key, which no
     * amount of retrying fixes, so it still fails straight to [State.Error].
     */
    @Synchronized
    private fun onStreamError(t: Throwable) {
        // A null streamer means teardown is closing it, and errors from that are expected.
        if (streamer == null) return
        when (_state.value) {
            // The retry loop owns errors raised by its own attempts.
            is State.Reconnecting -> return
            State.Live -> if (!recordOnly) {
                Log.w(TAG, "Stream dropped, reconnecting", t)
                _state.value = State.Reconnecting(attempt = 1)
                reconnectJob = scope.launch { reconnect(t) }
                return
            }
            else -> Unit
        }
        fail(t)
    }

    /** Retries the dropped broadcast with exponential backoff until it is back on air, or gives
     *  up after [RECONNECT_GIVE_UP_MS] and reports the last failure. */
    private suspend fun reconnect(cause: Throwable) {
        val giveUpAt = SystemClock.elapsedRealtime() + RECONNECT_GIVE_UP_MS
        var lastError = cause
        var attempt = 1
        while (SystemClock.elapsedRealtime() < giveUpAt) {
            _state.value = State.Reconnecting(attempt)
            delay(RECONNECT_DELAYS_MS.getOrElse(attempt - 1) { RECONNECT_DELAYS_MS.last() })
            try {
                reopen()
                _state.value = State.Live
                Log.i(TAG, "Reconnected on attempt $attempt")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Reconnect attempt $attempt failed", e)
                lastError = e
            }
            attempt++
        }
        fail(lastError)
    }

    /**
     * Closes the dead connection and opens it again on the same streamer, mirroring [start]'s
     * warm-up → open → start order. stopStream() also stops the camera, so it is warmed up again.
     *
     * Stream + record goes through one [CombineEndpoint], which only opens with every descriptor
     * at once. The MP4 therefore closes along with the RTMP connection, and reopening the same
     * path would overwrite it, so each reconnect records into a new part file.
     */
    private suspend fun reopen() {
        val s = streamer ?: throw CancellationException("Stream was stopped")
        val sess = session ?: throw CancellationException("Stream was stopped")
        runCatching { s.stopStream() }
        runCatching { s.close() }

        val partFile = sess.firstRecordingFile?.let { partFile(it, sess.part + 1) }
        try {
            source()?.warmUp()
            s.open(descriptor(sess.ingestUrl, sess.streamKey, sess.mode, partFile))
            s.startStream()
        } catch (t: Throwable) {
            // Don't leave an empty part behind for every failed attempt.
            partFile?.delete()
            throw t
        }
        if (partFile != null) {
            sess.part++
            currentRecordingFile = partFile
        }
        applyFlip()
        applyPreview()
    }

    private fun partFile(first: File, part: Int): File =
        File(first.parentFile, "${first.nameWithoutExtension}_part$part.${first.extension}")

    private fun descriptor(
        ingestUrl: String,
        streamKey: String,
        mode: RecordingMode,
        recordingFile: File?,
    ): MediaDescriptor = when (mode) {
        RecordingMode.STREAM_ONLY -> UriMediaDescriptor(buildRtmpUrl(ingestUrl, streamKey))
        RecordingMode.RECORD_ONLY -> UriMediaDescriptor(requireNotNull(recordingFile).toUri())
        RecordingMode.STREAM_AND_RECORD -> CombineEndpoint.CombineDescriptor(listOf(
            UriMediaDescriptor(buildRtmpUrl(ingestUrl, streamKey)),
            UriMediaDescriptor(requireNotNull(recordingFile).toUri()),
        ))
    }

    private fun fail(t: Throwable) {
        Log.e(TAG, "Stream error", t)
        val message = describe(t)
        scope.launch {
            // Tear down first, then publish Error last, so nothing in the teardown path can
            // clobber it. State.Error is terminal until the operator acknowledges it via
            // acknowledgeError() or starts/ends a new stream.
            runCatching { teardown() }
            _state.value = State.Error(message)
        }
    }

    /**
     * Turns a streaming failure into something the operator can act on.
     *
     * The library's own messages describe the socket rather than the cause — a rejected stream
     * key surfaces as the bare word "Connection lost", which reads as a network problem and sends
     * the operator off to check their wifi. It is worth distinguishing: an ingest server that
     * completes the RTMP handshake and *then* hangs up within a second is refusing the
     * credentials, not running short of bandwidth. A genuinely bad network fails differently
     * (DNS, connect, or timeout), so those get their own text.
     */
    private fun describe(t: Throwable): String {
        // Bounded in case a throwable chain ever loops back on itself.
        val chain = generateSequence(t) { it.cause }.take(10).toList()
        return when {
            chain.any { it is EOFException } ->
                "The server accepted the connection, then closed it. Check the stream key is " +
                    "current and that the broadcast is live on the platform."
            chain.any { it is UnknownHostException } ->
                "Couldn't find the ingest server. Check the ingest URL."
            chain.any { it is SocketTimeoutException } ->
                "Timed out reaching the ingest server. Check the network connection."
            chain.any { it is ConnectException } ->
                "Couldn't reach the ingest server. Check the ingest URL and the network."
            else -> t.message ?: t.javaClass.simpleName
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

    /** Wait before each reconnect attempt; the last value repeats until [RECONNECT_GIVE_UP_MS]. */
    private val RECONNECT_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 10_000)
    private const val RECONNECT_GIVE_UP_MS = 5 * 60 * 1000L

    private const val TAG = "StreamerHolder"
}
