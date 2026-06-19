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

    /** 720p30 @ 4 Mbps — within Facebook Live's recommended ceiling and fine for YouTube too. */
    val RESOLUTION = Size(1280, 720)
    const val FPS = 30
    const val VIDEO_BITRATE = 4_000_000
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
}
