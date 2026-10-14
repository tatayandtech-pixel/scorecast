package com.scorecast.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * The intro clip the operator picked: played full-frame, with its own sound, as soon as a match
 * goes live, then cut to the camera (owner request, 2026-10-14). Beyond spec — the nearest spec
 * item is §15's deferred "swap the camera feed for a full-frame graphic mid-stream".
 */
data class IntroClip(
    val video: File,
    /** The soundtrack pre-decoded to the stream's own PCM format (see [IntroClipStore.SAMPLE_RATE]),
     *  or null when the clip has no audio track — the live mic then stays on air under it. */
    val pcm: File?,
    val name: String,
    val durationMs: Long,
    /** Size as displayed, i.e. with the container's rotation applied — what aspect-fit needs. */
    val displayWidth: Int,
    val displayHeight: Int,
)

/**
 * Owns the one intro slot. The clip is copied into app-private storage when picked — a picker
 * grant only lasts for the process, and decoding from a cloud-backed URI mid-broadcast could
 * stall — and kept across matches until replaced or removed. It deliberately does NOT live under
 * getExternalFilesDir(MOVIES): [RecordingsManager] lists every .mp4 there, so the intro would
 * show up as a recording and be wiped by "Delete all recordings".
 *
 * Everything that can fail is checked here, at pick time, so nothing about the clip can fail
 * for the first time on air: duration, resolution, HDR, whether this phone has a decoder for it,
 * and its soundtrack, which is decoded and resampled once now rather than on the audio thread
 * during the broadcast.
 */
object IntroClipStore {
    /** Matches [StreamConfig.AUDIO_SAMPLE_RATE] and the stereo 16-bit capture StreamerHolder sets. */
    const val SAMPLE_RATE = StreamConfig.AUDIO_SAMPLE_RATE
    const val BYTES_PER_FRAME = 4

    /** "Up to 60 seconds", allowing for a trim that lands a few frames over the minute. */
    private const val MAX_DURATION_MS = 61_000L
    /** 1080p. Bigger clips have to be decoded alongside the live camera and the stream's own
     *  encoder, which a mid-range phone can't be relied on to keep up with. */
    private const val MAX_LONG_SIDE = 1920
    private const val MAX_SHORT_SIDE = 1088
    private const val MAX_FRAME_RATE = 61f
    /** Room for the decoded soundtrack (~10.6 MB for a minute) plus some slack. */
    private const val STORAGE_HEADROOM_BYTES = 64L shl 20
    private const val TIMEOUT_US = 10_000L
    private const val DECODE_DEADLINE_MS = 60_000L

    private const val PREFS_NAME = "intro_clip"
    private const val DIR_NAME = "intro"
    private const val TAG = "IntroClipStore"

    private val _clip = MutableStateFlow<IntroClip?>(null)
    val clip: StateFlow<IntroClip?> = _clip.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** Why the last pick was refused, in words the operator can act on; null once it succeeds. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Its own scope rather than the composable's, so leaving the wizard step mid-copy doesn't
    // cancel the import and leave a half-written file behind.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Rejected(message: String) : Exception(message)

    /** Restores the clip kept from an earlier session. */
    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dir = File(context.filesDir, DIR_NAME)
        val video = File(dir, "intro.mp4")
        val pcm = File(dir, "intro.pcm").takeIf { prefs.getBoolean("hasAudio", false) }
        if (!prefs.contains("durationMs") || !video.exists() || (pcm != null && !pcm.exists())) return
        _clip.value = IntroClip(
            video = video,
            pcm = pcm,
            name = prefs.getString("name", null) ?: "Intro video",
            durationMs = prefs.getLong("durationMs", 0L),
            displayWidth = prefs.getInt("displayWidth", 1920),
            displayHeight = prefs.getInt("displayHeight", 1080),
        )
    }

    fun import(context: Context, uri: Uri) {
        if (_importing.value) return
        _importing.value = true
        _error.value = null
        val app = context.applicationContext
        scope.launch {
            try {
                _clip.value = importBlocking(app, uri)
            } catch (e: Rejected) {
                _error.value = e.message
            } catch (t: Throwable) {
                Log.w(TAG, "Intro import failed", t)
                _error.value = "Couldn't use that video. Try a different file."
            } finally {
                _importing.value = false
            }
        }
    }

    fun remove(context: Context) {
        val dir = File(context.filesDir, DIR_NAME)
        File(dir, "intro.mp4").delete()
        File(dir, "intro.pcm").delete()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        _clip.value = null
        _error.value = null
    }

    private fun importBlocking(context: Context, uri: Uri): IntroClip {
        // Cheap checks first, straight from the picked URI, so an over-long or 4K file is refused
        // before hundreds of MB get copied.
        val meta = MediaMetadataRetriever()
        val durationMs: Long
        val width: Int
        val height: Int
        val rotation: Int
        try {
            meta.setDataSource(context, uri)
            if (meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
                throw Rejected("That file has no video in it.")
            }
            durationMs = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            width = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            rotation = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } finally {
            meta.release()
        }
        if (durationMs <= 0L || width <= 0 || height <= 0) throw Rejected("Couldn't read that video.")
        if (durationMs >= MAX_DURATION_MS) {
            throw Rejected("That clip is ${formatDuration(durationMs)} long. Pick one that's 60 seconds or shorter.")
        }
        if (maxOf(width, height) > MAX_LONG_SIDE || minOf(width, height) > MAX_SHORT_SIDE) {
            throw Rejected("That clip is ${width}×$height. Use one that's 1080p or smaller.")
        }

        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val (name, size) = describe(context, uri)
        if (size > 0 && dir.usableSpace < size + STORAGE_HEADROOM_BYTES) {
            throw Rejected("Not enough free storage to keep that clip.")
        }

        val newVideo = File(dir, "incoming.mp4")
        val newPcm = File(dir, "incoming.pcm")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw Rejected("Couldn't open that video.")
            input.use { src -> newVideo.outputStream().use { src.copyTo(it, 1 shl 16) } }
            checkVideoTrack(newVideo)
            val hasAudio = decodeAudio(newVideo, newPcm)

            // Commit: rename() replaces the old files atomically, so until here the old clip, if
            // any, stays in place and in use — and a stream already playing it keeps its open
            // handle to the old data.
            val video = File(dir, "intro.mp4")
            val pcm = File(dir, "intro.pcm")
            check(newVideo.renameTo(video)) { "rename failed" }
            if (hasAudio) check(newPcm.renameTo(pcm)) { "rename failed" } else pcm.delete()
            val turned = rotation == 90 || rotation == 270
            val clip = IntroClip(
                video = video,
                pcm = if (hasAudio) pcm else null,
                name = name,
                durationMs = durationMs,
                displayWidth = if (turned) height else width,
                displayHeight = if (turned) width else height,
            )
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString("name", clip.name)
                .putLong("durationMs", clip.durationMs)
                .putInt("displayWidth", clip.displayWidth)
                .putInt("displayHeight", clip.displayHeight)
                .putBoolean("hasAudio", hasAudio)
                .apply()
            return clip
        } finally {
            newVideo.delete()
            newPcm.delete()
        }
    }

    private fun describe(context: Context, uri: Uri): Pair<String, Long> {
        var name = "Intro video"
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
        }
        return name to size
    }

    /** Refuses what would fail or look wrong on air: HDR and other 10-bit video (washed out or
     *  green through the 8-bit compositor), slow-motion frame rates (a real-time 120/240 fps decode
     *  beside the live encoder would choke), and anything this phone has no decoder for. */
    private fun checkVideoTrack(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: throw Rejected("That file has no video in it.")
            val transfer = format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)
            if (transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG ||
                isTenBit(format)
            ) {
                throw Rejected("That clip was recorded in HDR, which looks washed out on air. Use one recorded or exported with HDR off.")
            }
            val fps = format.numberOrNull(MediaFormat.KEY_FRAME_RATE)?.toFloat()
            if (fps != null && fps > MAX_FRAME_RATE) {
                throw Rejected("That clip is ${fps.roundToInt()} fps (slow motion). Use one that's 60 fps or less.")
            }
            if (findDecoder(format) == null) {
                throw Rejected("This phone can't play that video format. Export it as an MP4 (H.264).")
            }
        } finally {
            extractor.release()
        }
    }

    /** The decoder to play [format] with. Asked without the container's level: MediaExtractor's
     *  own docs warn it often doesn't match the bitstream, and it can falsely rule out every
     *  decoder. A fresh format rather than a copy, which minSdk 26 lacks. */
    fun findDecoder(format: MediaFormat): String? {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        val width = format.intOrNull(MediaFormat.KEY_WIDTH) ?: return null
        val height = format.intOrNull(MediaFormat.KEY_HEIGHT) ?: return null
        val query = MediaFormat.createVideoFormat(mime, width, height)
        format.intOrNull(MediaFormat.KEY_PROFILE)?.let { query.setInteger(MediaFormat.KEY_PROFILE, it) }
        format.numberOrNull(MediaFormat.KEY_FRAME_RATE)?.let { query.setFloat(MediaFormat.KEY_FRAME_RATE, it.toFloat()) }
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(query)
    }

    /** HDR10 and friends often carry no colour box for the transfer check to see, but they all use
     *  a 10-bit profile. Profile numbers are per codec, hence the MIME switch. */
    @Suppress("InlinedApi") // Compile-time constants; reading a newer value off an older track is harmless.
    private fun isTenBit(format: MediaFormat): Boolean {
        val profile = format.intOrNull(MediaFormat.KEY_PROFILE) ?: return false
        return when (format.getString(MediaFormat.KEY_MIME)) {
            MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION -> true
            MediaFormat.MIMETYPE_VIDEO_HEVC -> profile == CodecProfileLevel.HEVCProfileMain10 ||
                profile == CodecProfileLevel.HEVCProfileMain10HDR10 ||
                profile == CodecProfileLevel.HEVCProfileMain10HDR10Plus
            MediaFormat.MIMETYPE_VIDEO_AVC -> profile == CodecProfileLevel.AVCProfileHigh10 ||
                profile == CodecProfileLevel.AVCProfileHigh422 ||
                profile == CodecProfileLevel.AVCProfileHigh444
            MediaFormat.MIMETYPE_VIDEO_VP9 -> profile == CodecProfileLevel.VP9Profile2 ||
                profile == CodecProfileLevel.VP9Profile3 ||
                profile == CodecProfileLevel.VP9Profile2HDR ||
                profile == CodecProfileLevel.VP9Profile3HDR ||
                profile == CodecProfileLevel.VP9Profile2HDR10Plus ||
                profile == CodecProfileLevel.VP9Profile3HDR10Plus
            MediaFormat.MIMETYPE_VIDEO_AV1 -> profile == CodecProfileLevel.AV1ProfileMain10 ||
                profile == CodecProfileLevel.AV1ProfileMain10HDR10 ||
                profile == CodecProfileLevel.AV1ProfileMain10HDR10Plus
            else -> false
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    /** Containers store some keys (frame rate, notably) as an Int on one file and a Float on the
     *  next, and getNumber() is API 29. */
    private fun MediaFormat.numberOrNull(key: String): Number? {
        if (!containsKey(key)) return null
        return runCatching { getInteger(key) }.getOrNull() ?: runCatching { getFloat(key) }.getOrNull()
    }

    /**
     * Decodes the first audio track of [file] to [SAMPLE_RATE] stereo 16-bit little-endian PCM in
     * [out] — the exact bytes the stream's audio encoder takes, so playback is a plain copy.
     * Returns false if the clip has no audio track.
     */
    private fun decodeAudio(file: File, out: File): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track).apply {
                // Let the decoder fold 5.1 down to stereo with the stream's own downmix
                // coefficients: keeping only the first two channels would drop the centre one,
                // which carries the dialogue.
                @Suppress("DEPRECATION")
                setInteger(MediaFormat.KEY_AAC_MAX_OUTPUT_CHANNEL_COUNT, 2)
                @Suppress("InlinedApi")
                setInteger(MediaFormat.KEY_MAX_OUTPUT_CHANNEL_COUNT, 2)
            }
            val codec = try {
                MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                    .apply { configure(format, null, null, 0); start() }
            } catch (t: Throwable) {
                throw Rejected("Couldn't read that clip's sound. Export it again as an MP4 (H.264 with AAC audio).")
            }
            try {
                out.outputStream().buffered(1 shl 16).use { sink -> drainAudio(extractor, codec, format, sink) }
            } finally {
                codec.release()
            }
            return true
        } finally {
            extractor.release()
        }
    }

    private fun drainAudio(extractor: MediaExtractor, codec: MediaCodec, format: MediaFormat, sink: OutputStream) {
        val info = MediaCodec.BufferInfo()
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var isFloat = false
        var resampler: StereoResampler? = null
        var inputDone = false
        val deadline = SystemClock.elapsedRealtime() + DECODE_DEADLINE_MS
        while (SystemClock.elapsedRealtime() < deadline) {
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
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // The real output format: HE-AAC, for one, decodes at twice its declared rate.
                val f = codec.outputFormat
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            } else if (o >= 0) {
                val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val r = resampler ?: StereoResampler(rate, SAMPLE_RATE, sink).also {
                    resampler = it
                    // A soundtrack that starts after the picture keeps its offset, so the two
                    // stay in step on air (the video side times frames by the same clip clock).
                    if (info.presentationTimeUs > 0) it.silence(info.presentationTimeUs)
                }
                // Encoder priming that an edit list places before zero is not part of the clip;
                // keeping it would start the sound 20-50 ms late.
                val skipFrames = if (info.presentationTimeUs < 0) {
                    (-info.presentationTimeUs * rate + 999_999) / 1_000_000
                } else {
                    0L
                }
                val bytesPerSample = if (isFloat) 4 else 2
                buf.position(minOf(buf.limit().toLong(), info.offset + skipFrames * channels * bytesPerSample).toInt())
                if (isFloat) {
                    val samples = buf.asFloatBuffer()
                    while (samples.remaining() >= channels) {
                        val l = samples.get()
                        val rr = if (channels > 1) samples.get() else l
                        repeat(channels - minOf(channels, 2)) { samples.get() }
                        r.push((l * 32767f).roundToInt().coerceIn(-32768, 32767), (rr * 32767f).roundToInt().coerceIn(-32768, 32767))
                    }
                } else {
                    val samples = buf.asShortBuffer()
                    while (samples.remaining() >= channels) {
                        val l = samples.get().toInt()
                        val rr = if (channels > 1) samples.get().toInt() else l
                        repeat(channels - minOf(channels, 2)) { samples.get() }
                        r.push(l, rr)
                    }
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    r.flush()
                    return
                }
            }
        }
        throw Rejected("Couldn't read that clip's sound. Export it again as an MP4 (H.264 with AAC audio).")
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}

/**
 * Streams stereo frames from [inRate] to [outRate] by linear interpolation, writing 16-bit
 * little-endian frames to [sink]. Linear is plenty here: the result is about to be AAC-encoded at
 * 128 kbps, and phone AAC already low-passes well below where its aliasing would land. Mono is
 * duplicated to both channels by the caller; extra channels are dropped.
 */
private class StereoResampler(inRate: Int, private val outRate: Int, private val sink: OutputStream) {
    private val step = inRate.toDouble() / outRate
    /** Position of the next output frame, in input frames after [prevL]/[prevR]. */
    private var phase = 0.0
    private var prevL = 0
    private var prevR = 0
    private var primed = false
    private val out = ByteArray(1 shl 14)
    private var used = 0

    fun silence(durationUs: Long) {
        repeat((durationUs * outRate / 1_000_000).toInt()) { write(0, 0) }
    }

    fun push(l: Int, r: Int) {
        if (!primed) {
            prevL = l
            prevR = r
            primed = true
            return
        }
        while (phase < 1.0) {
            write(prevL + ((l - prevL) * phase).roundToInt(), prevR + ((r - prevR) * phase).roundToInt())
            phase += step
        }
        phase -= 1.0
        prevL = l
        prevR = r
    }

    fun flush() {
        if (primed) write(prevL, prevR)
        sink.write(out, 0, used)
        used = 0
    }

    private fun write(l: Int, r: Int) {
        if (used + IntroClipStore.BYTES_PER_FRAME > out.size) {
            sink.write(out, 0, used)
            used = 0
        }
        out[used] = l.toByte()
        out[used + 1] = (l shr 8).toByte()
        out[used + 2] = r.toByte()
        out[used + 3] = (r shr 8).toByte()
        used += IntroClipStore.BYTES_PER_FRAME
    }
}
