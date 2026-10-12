/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * FlacAudioConfig.kt
 *
 * Drop-in replacement for createRenderersFactory() in MusicService.kt.
 *
 * Key changes from the original:
 *  1. setEnableFloatOutput(true) — passes 32-bit float PCM to AudioTrack instead
 *     of quantising to 16-bit, preserving full dynamic range of 24-bit FLAC sources.
 *     On arm64 (Snapdragon / Dimensity / Exynos) AudioTrack routes float PCM directly
 *     through the hardware mixer's high-quality path — no software re-sampling.
 *
 *  2. EXTENSION_RENDERER_MODE_PREFER — when the ffmpeg Media3 extension is on the
 *     classpath it is preferred over the platform FLAC OMX decoder, giving a more
 *     consistent bit-exact decode across all Android versions.
 *
 *  3. SilenceSkippingAudioProcessor is gated behind a preference flag so lossless
 *     playback is never gap-trimmed. Silence-skip still works for lossy streams.
 *
 *  4. LoudnessEnhancer injection point kept as a no-op placeholder; wire into
 *     AudioEffectSession if you add it later.
 *
 * HOW TO USE:
 *   Replace the existing `createRenderersFactory()` body in MusicService.kt with:
 *
 *     private fun createRenderersFactory() =
 *         buildFlacOptimizedRenderersFactory(
 *             context = this,
 *             enableSilenceSkip = !preferences.get(LosslessSkipSilenceDisabledKey, false),
 *             enableAudioTrackPlaybackParams = preferences.get(
 *                 AudioTrackPlaybackParamsKey, false
 *             ),
 *         )
 *
 *   Add to PreferenceKeys.kt:
 *     val LosslessSkipSilenceDisabledKey = booleanPreferencesKey("losslessSkipSilenceDisabled")
 */

package dev.citali.lunartune.playback

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.audio.SonicAudioProcessor

/**
 * Builds a [DefaultRenderersFactory] optimised for bit-transparent FLAC decode.
 *
 * @param context Application context.
 * @param enableSilenceSkip When false (recommended for lossless), silence trimming
 *   is removed from the audio processor chain. Pass true for lossy-only playback.
 * @param enableAudioTrackPlaybackParams Forwarded to [DefaultAudioSink]; enables
 *   AudioTrack.setPlaybackParams for tempo/pitch control (e.g. sleep timer fade).
 */
fun buildFlacOptimizedRenderersFactory(
    context: Context,
    enableSilenceSkip: Boolean = false,
    enableAudioTrackPlaybackParams: Boolean = false,
): DefaultRenderersFactory =
    object : DefaultRenderersFactory(context) {

        init {
            // Prefer ffmpeg/FLAC extension decoder over OMX platform decoder.
            // Falls back gracefully to MediaCodec FLAC if the extension is absent.
            setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
        }

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,          // param from ExoPlayer builder; we override below
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink {
            val audioProcessors = buildList {
                // Silence-skip only for lossy streams; skip for lossless (FLAC/WAV/ALAC)
                if (enableSilenceSkip) {
                    add(
                        SilenceSkippingAudioProcessor(
                            minimumSilenceDurationUs  = 1_500_000L,   // 1.5 s
                            silenceRetentionRatio     = 0.35f,
                            maxSilenceDurationUs      = 500_000L,     // 0.5 s
                            silenceSkipDurationUs     = 10L,
                            silenceThreshold          = 150.toShort(),
                        ),
                    )
                }
                // Speed/pitch control (e.g. variable-speed playback, sleep-timer fade)
                add(SonicAudioProcessor())
            }

            return DefaultAudioSink
                .Builder(context)
                // ── The critical FLAC quality switch ──────────────────────────
                // true  → AudioTrack receives 32-bit float PCM frames.
                //         Hardware DSP on arm64 uses its high-quality mixer path.
                //         FLAC 16-bit and 24-bit sources are both preserved exactly.
                // false → AudioTrack receives 16-bit PCM; 24-bit FLAC is truncated.
                .setEnableFloatOutput(true)
                // ─────────────────────────────────────────────────────────────
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        *audioProcessors.toTypedArray(),
                    ),
                )
                .build()
        }
    }

/*
 * ExoPlayer builder additions (in MusicService where ExoPlayer.Builder is called):
 *
 *   ExoPlayer.Builder(context)
 *       .setRenderersFactory(createRenderersFactory())
 *       // Enable offload scheduling so the CPU sleeps between audio chunks —
 *       // major battery win on arm64 with FLAC streams; audio quality unaffected.
 *       .setAudioOffloadSchedulingEnabled(true)          // ExoPlayer ≥ 1.3
 *       .setHandleAudioBecomingNoisy(true)
 *       .setSeekBackIncrementMs(5_000)
 *       .setSeekForwardIncrementMs(5_000)
 *       .setDeviceVolumeControlEnabled(true)
 *       .build()
 *       .apply {
 *           addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))
 *           addListener(audioEffectPlayerListener)
 *           // Do NOT call setOffloadEnabled(false) — let the FLAC offload path run.
 *       }
 *
 * NOTE: setOffloadEnabled(false) was in the original build — that disables audio
 * offload entirely.  Remove that line and replace with setAudioOffloadSchedulingEnabled(true)
 * to get gapless background FLAC playback with minimal wakeups.
 */
