package com.scorecast.app

import android.util.Size

/**
 * Phase 1 tunables in one place.
 *
 * Phase 1 goal (spec §12.1): camera -> STATIC burned-in scoreboard -> H.264 -> RTMP(S) -> Facebook
 * Live (primary; YouTube is a secondary target), landscape, manual ingest-URL/stream-key paste.
 * Nothing from later phases lives here.
 */
object StreamConfig {

    /** Every preset is 16:9; the UI sizes the preview from this, the encoder from [VideoQuality]. */
    const val ASPECT_RATIO = 16f / 9f
    /** The camera's ceiling on the phones in use (their AE ranges stop at 30), so no 60 fps preset. */
    const val FPS = 30
    const val AUDIO_BITRATE = 128_000
    const val AUDIO_SAMPLE_RATE = 44_100

    /**
     * Pre-filled Facebook Live (primary target) RTMPS ingest server; the operator pastes only the
     * stream key from Facebook Live Producer ("Use stream key"). StreamPack's DynamicEndpoint routes
     * rtmps:// to its RTMP endpoint over TLS.
     *
     * Secondary target (YouTube): replace the field in-app with rtmp://a.rtmp.youtube.com/live2
     */
    const val DEFAULT_INGEST_URL = "rtmps://live-api-s.facebook.com:443/rtmp/"

    /**
     * Burn-in toggle for incremental bring-up (see README "Bring-up order").
     *  - false: stream raw camera (prove camera -> encode -> RTMP(S) -> Facebook first).
     *  - true : composite the static scoreboard into every encoded frame.
     * Same pipeline either way — this only controls whether the overlay quad is drawn.
     */
    const val USE_OVERLAY = true

    /**
     * Boot defaults for the on-device GL camera-orientation toggles (see CameraOverlayVideoSource).
     * The camera SurfaceTexture transform handles most of it, but sensor orientation vs.
     * locked-landscape can still need a nudge. These set the initial state; the operator can flip
     * mirroring/rotation live from the UI (next frame, even mid-stream) — no recompile needed.
     */
    const val CAMERA_FLIP_VERTICAL = false
    const val CAMERA_FLIP_HORIZONTAL = false

    /**
     * Boot default for camera rotation in degrees (0/90/180/270). Back-camera sensors are usually
     * mounted at 90°, so a locked-landscape app often needs a rotation that the SurfaceTexture
     * transform alone doesn't supply. The operator can cycle this live from the UI ("Rotate 90°").
     */
    const val CAMERA_ROTATION_DEGREES = 90
}

/**
 * Picked per match on wizard step 4 (owner request, 2026-10-15: best quality by default). There is
 * no adaptive bitrate over RTMP on StreamPack 3.1.2, so a stream either fits the venue's upload or
 * stalls — hence a choice rather than a silent upgrade. 6 Mbps sits inside every supported
 * platform's 1080p30 ceiling, Twitch's 6 Mbps cap being the lowest.
 */
enum class VideoQuality(val label: String, val resolution: Size, val bitrate: Int) {
    HIGH("High · 1080p", Size(1920, 1080), 6_000_000),
    STANDARD("Standard · 720p", Size(1280, 720), 4_000_000),
}
