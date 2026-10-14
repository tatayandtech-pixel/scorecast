package com.scorecast.app

import android.content.Context
import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
import io.github.thibaultbee.streampack.core.elements.utils.pool.IReadOnlyRawFrameFactory

/**
 * StreamPack's own mic source, with the intro's sound swapped in while it plays
 * ([IntroPlayback.fillAudio]). The mic still does every read, so its pacing and timestamps — the
 * audio clock of the whole broadcast, recording included — are exactly what they were.
 *
 * StreamPack has no hook for injecting audio (its only processing step is mute, which runs after
 * the source), so wrapping the source is the one seam, the same way [com.scorecast.app.overlay.
 * CameraOverlayVideoSource] wraps the camera. Note the swap happens after the read, so the intro is
 * heard even while the operator has muted the mic — that mute is the system-wide AudioManager one,
 * which silences the mic itself, not the stream.
 */
class IntroAudioSource(private val mic: IAudioSourceInternal) : IAudioSourceInternal by mic {
    // SingleStreamer drives audio in callback mode, so this is the path actually used: the frame
    // is the AAC encoder's own input buffer.
    override fun fillAudioFrame(frame: RawFrame): RawFrame {
        val filled = mic.fillAudioFrame(frame)
        IntroPlayback.fillAudio(filled.rawBuffer, filled.timestampInUs)
        return filled
    }

    override fun getAudioFrame(frameFactory: IReadOnlyRawFrameFactory): RawFrame {
        val frame = mic.getAudioFrame(frameFactory)
        IntroPlayback.fillAudio(frame.rawBuffer, frame.timestampInUs)
        return frame
    }
}

class IntroAudioSourceFactory(
    private val mic: IAudioSourceInternal.Factory = MicrophoneSourceFactory(),
) : IAudioSourceInternal.Factory {
    override suspend fun create(context: Context): IAudioSourceInternal = IntroAudioSource(mic.create(context))

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean = source is IntroAudioSource
}
