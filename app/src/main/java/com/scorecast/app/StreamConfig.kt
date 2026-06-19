package com.scorecast.app

import android.util.Size

/**
 * Phase 1 tunables in one place.
 *
 * Phase 1 goal (spec §12.1): camera -> STATIC burned-in scoreboard -> H.264 -> RTMP -> YouTube,
 * landscape, manual ingest-URL/stream-key paste. Nothing from later phases lives here.
 */
object StreamConfig {

    /** 720p30 @ 4 Mbps — a safe, widely-accepted YouTube Live ingest profile. */
    val RESOLUTION = Size(1280, 720)
    const val FPS = 30
    const val VIDEO_BITRATE = 4_000_000
    const val AUDIO_BITRATE = 128_000
    const val AUDIO_SAMPLE_RATE = 44_100

    /** Pre-filled YouTube primary ingest URL; the operator pastes only the stream key. */
    const val DEFAULT_INGEST_URL = "rtmp://a.rtmp.youtube.com/live2"

    /**
     * Burn-in toggle for incremental bring-up (see README "Bring-up order").
     *  - false: stream raw camera (prove camera -> encode -> RTMP -> YouTube first).
     *  - true : composite the static scoreboard into every encoded frame.
     * Same pipeline either way — this only controls whether the overlay quad is drawn.
     */
    const val USE_OVERLAY = true

    /**
     * On-device GL tuning knobs for the camera texture (see CameraOverlayVideoSource).
     * The camera SurfaceTexture transform handles most of it, but sensor orientation vs.
     * locked-landscape can still need a nudge. If the live image is rotated/mirrored,
     * adjust these and re-run — this is the documented Phase 1 tuning point.
     */
    const val CAMERA_FLIP_VERTICAL = false
    const val CAMERA_FLIP_HORIZONTAL = false
}
