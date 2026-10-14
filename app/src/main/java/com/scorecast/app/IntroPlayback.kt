package com.scorecast.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.scorecast.app.overlay.CameraOverlayVideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Puts the intro clip on air: its picture through the compositor's second input (the camera keeps
 * running underneath and stays the frame clock), its sound through [IntroAudioSource] in place of
 * the mic. Nothing in StreamPack's graph, the encoders or the RTMP connection changes, so the cut
 * back to the camera is instant and the hard-won camera-session handling is never touched.
 *
 * Picture and sound stay in step by sharing one start instant, `t0`, on System.nanoTime() — the
 * clock StreamPack stamps audio in and converts camera frames into ("uptime"). Sound is placed by
 * timestamp: the mic frame stamped t carries the clip from t - t0. A camera frame, though, is
 * already [CameraOverlayVideoSource.cameraLatencyNs] old by the time it is drawn and stamped with
 * its exposure time, so each clip frame is released that much later than t0 + pts for it to land
 * on the camera frame stamped t0 + pts.
 */
object IntroPlayback {

    sealed interface State {
        data object Idle : State
        /** [endsAtNanos] is on System.nanoTime(). */
        data class Playing(val endsAtNanos: Long) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Once the intro has gone out in a match, a Retry after a failure must not replay it on top of
     *  the game. Cleared by End Match ([newMatch]). */
    @Volatile var playedThisMatch = false
        private set

    /** A clip read and ready to go, so nothing waits on disk once the stream is live. */
    class Prepared internal constructor(val clip: IntroClip, internal val pcm: ByteArray?) {
        val aspect: Float get() = clip.displayWidth.toFloat() / clip.displayHeight
    }

    /** Black, before the first clip frame, for this long — the decoder's head start. */
    private const val PREROLL_NS = 300_000_000L
    private const val JOIN_TIMEOUT_MS = 500L
    private const val TAG = "IntroPlayback"

    private var decoder: VideoDecoder? = null
    private var source: CameraOverlayVideoSource? = null
    /** Read on the audio encoder's thread for every frame; null whenever no intro is playing. */
    @Volatile private var audio: AudioCursor? = null

    suspend fun prepare(clip: IntroClip): Prepared? = withContext(Dispatchers.IO) {
        runCatching { Prepared(clip, clip.pcm?.readBytes()) }
            .onFailure { Log.w(TAG, "Intro unavailable, going live without it", it) }
            .getOrNull()
    }

    /**
     * Starts [prepared] on [source], which should already be showing black for it
     * ([CameraOverlayVideoSource.showIntro]). Hands the frame back to the camera when the clip ends.
     * Never throws: the intro must not be able to cost the broadcast itself.
     */
    @Synchronized
    fun start(prepared: Prepared, source: CameraOverlayVideoSource) {
        detach()?.requestStop()
        playedThisMatch = true
        try {
            val surface = source.introSurface ?: error("no intro surface")
            val t0 = System.nanoTime() + PREROLL_NS
            this.source = source
            audio = prepared.pcm?.let { AudioCursor(it, t0) }
            decoder = VideoDecoder(prepared.clip.video, surface, t0) { source.cameraLatencyNs }.also { it.start() }
            _state.value = State.Playing(t0 + prepared.clip.durationMs * 1_000_000)
            Log.i(TAG, "Intro started: ${prepared.clip.durationMs} ms, audio=${prepared.pcm != null}")
        } catch (t: Throwable) {
            Log.w(TAG, "Intro couldn't start, staying on the camera", t)
            decoder = null
            audio = null
            this.source = null
            source.hideIntro()
        }
    }

    /** The operator's Skip: straight back to the camera and the mic. */
    fun skip() {
        synchronized(this) { detach() }?.requestStop()
    }

    /** For teardown: also waits for the decoder to let go of the compositor's surface, which is
     *  about to be released. Blocks briefly, so call off the main thread. */
    fun stopAndWait() {
        val d = synchronized(this) { detach() } ?: return
        d.requestStop()
        d.join(JOIN_TIMEOUT_MS)
    }

    fun newMatch() {
        playedThisMatch = false
    }

    /** Called by [IntroAudioSource] on the audio encoder's thread with each mic frame, after the
     *  mic has filled it. Must never throw: the frame is the encoder's own input buffer. */
    fun fillAudio(buffer: ByteBuffer, timestampUs: Long) {
        val cursor = audio ?: return
        try {
            cursor.fill(buffer, timestampUs)
        } catch (t: Throwable) {
            Log.w(TAG, "Intro audio dropped", t)
            audio = null
        }
    }

    private fun onDecoderFinished(d: VideoDecoder) {
        synchronized(this) { if (decoder === d) detach() }
    }

    /** Swaps the camera and mic back in. Returns the decoder for the caller to stop. */
    private fun detach(): VideoDecoder? {
        val d = decoder ?: return null
        decoder = null
        audio = null
        source?.hideIntro()
        source = null
        _state.value = State.Idle
        Log.i(TAG, "Intro ended")
        return d
    }

    /**
     * Copies the clip's PCM into mic frames by timestamp. Anchored once, on the first frame, then
     * strictly contiguous, so jitter in the mic's timestamps can't open gaps or repeats (clicks)
     * in the sound. Frames before t0 get silence rather than the mic, so the venue doesn't cut in
     * for the moment before the clip starts.
     */
    private class AudioCursor(private val pcm: ByteArray, private val t0Ns: Long) {
        private var anchored = false
        /** Byte offset into [pcm] of the next byte to play; negative while still before t0. */
        private var position = 0L

        fun fill(buffer: ByteBuffer, timestampUs: Long) {
            val start = buffer.position()
            val length = buffer.limit() - start
            if (!anchored) {
                val frames = (timestampUs * 1000 - t0Ns) * IntroClipStore.SAMPLE_RATE / 1_000_000_000L
                position = frames * IntroClipStore.BYTES_PER_FRAME
                anchored = true
            }
            // Absolute puts only: the encoder reads position..limit as the region to encode, so
            // moving either would corrupt the frame, and a throw here would surface as a stream
            // error and set off a reconnect.
            for (i in 0 until length) {
                val src = position + i
                buffer.put(start + i, if (src >= 0 && src < pcm.size) pcm[src.toInt()] else 0)
            }
            position += length
        }
    }

    /**
     * Decodes the clip's video track straight into the compositor's intro surface, releasing each
     * frame at its moment. The extractor's format carries the container's rotation, which a
     * decoder rendering to a Surface applies itself, so portrait phone clips arrive upright.
     */
    private class VideoDecoder(
        private val file: File,
        private val surface: Surface,
        private val t0Ns: Long,
        private val cameraLatencyNs: () -> Long,
    ) : Thread("scorecast-intro") {
        @Volatile private var stopRequested = false

        fun requestStop() {
            stopRequested = true
        }

        override fun run() {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(file.path)
                val track = (0 until extractor.trackCount).first {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                // The decoder pick-time validation vouched for, not just the first for the MIME
                // type, which may not handle this clip's size or profile.
                codec = IntroClipStore.findDecoder(format)?.let { MediaCodec.createByCodecName(it) }
                    ?: MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                codec.configure(format, surface, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var shownAny = false
                var lastOutputNs = System.nanoTime()
                while (!stopRequested) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (i >= 0) {
                            val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (o < 0) {
                        // A decoder that wedges — before its first frame or after the last input —
                        // must not hold viewers on black or a frozen frame; hand back to the camera.
                        if (System.nanoTime() - lastOutputNs > STALL_NS) break
                        continue
                    }
                    lastOutputNs = System.nanoTime()
                    val dueNs = t0Ns + info.presentationTimeUs * 1000 + cameraLatencyNs()
                    var waitNs = dueNs - System.nanoTime()
                    while (waitNs > 0 && !stopRequested) {
                        Thread.sleep(minOf(SLEEP_SLICE_MS, waitNs / 1_000_000 + 1))
                        waitNs = dueNs - System.nanoTime()
                    }
                    lastOutputNs = System.nanoTime()
                    // A frame that is already well late is dropped, so a slow patch catches up
                    // rather than pushing the rest of the clip late — but never the first one, so
                    // the clip always appears.
                    val render = info.size > 0 && (!shownAny || -waitNs < LATE_DROP_NS)
                    codec.releaseOutputBuffer(o, render)
                    if (render) shownAny = true
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            } catch (t: Throwable) {
                // Expected when teardown released the surface under a decoder that was told to
                // stop; anything else ends the intro early and hands back to the camera.
                if (!stopRequested) Log.w(TAG, "Intro video stopped early", t)
            } finally {
                runCatching { codec?.release() }
                extractor.release()
                onDecoderFinished(this)
            }
        }

        private companion object {
            const val TIMEOUT_US = 10_000L
            const val SLEEP_SLICE_MS = 10L
            const val LATE_DROP_NS = 66_000_000L
            const val STALL_NS = 2_000_000_000L
        }
    }
}
