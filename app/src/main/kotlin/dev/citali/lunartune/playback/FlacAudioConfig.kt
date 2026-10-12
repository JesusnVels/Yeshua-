/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 *
 * FlacAudioConfig.kt
 *
 * Drop-in replacement for createRenderersFactory() in MusicService.kt.
 *
 * Key changes from the stock factory:
 *  1. setEnableFloatOutput(true) — passes 32-bit float PCM to AudioTrack instead
 *     of quantising to 16-bit, preserving full dynamic range of 24-bit FLAC sources.
 *
 *  2. EXTENSION_RENDERER_MODE_PREFER — when the ffmpeg Media3 extension is on the
 *     classpath it is preferred over the platform FLAC decoder, giving a more
 *     consistent bit-exact decode across all Android versions.
 *
 *  3. SilenceSkippingAudioProcessor is gated behind a preference flag so lossless
 *     playback is never gap-trimmed unless the user wants it.
 */

package dev.citali.lunartune.playback

import android.content.Context
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor

/**
 * Builds a [DefaultRenderersFactory] optimised for high-quality FLAC decode.
 *
 * @param context Application context.
 * @param enableSilenceSkip When false (recommended for lossless), silence trimming
 *   is removed from the audio processor chain.
 */
fun buildFlacOptimizedRenderersFactory(
    context: Context,
    enableSilenceSkip: Boolean = false,
): DefaultRenderersFactory =
    object : DefaultRenderersFactory(context) {

        init {
            // Prefer ffmpeg/FLAC extension decoder over the platform decoder.
            // Falls back gracefully to MediaCodec FLAC if the extension is absent.
            setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
        }

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink {
            val audioProcessors = buildList {
                // Silence-skip only when enabled; never trim lossless streams by default.
                // NOTE: SilenceSkippingAudioProcessor is a Java class — positional args only.
                if (enableSilenceSkip) {
                    add(
                        SilenceSkippingAudioProcessor(
                            1_500_000L,
                            0.35f,
                            500_000L,
                            10,
                            150.toShort(),
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
