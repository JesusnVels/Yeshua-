/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import dev.yeshua.player.utils.Blowfish
import dev.yeshua.player.utils.DeezerStreamResolver
import java.security.MessageDigest

/**
 * Crypto helpers for Deezer's protected streams, following deemix's
 * well-known scheme exactly.
 *
 * Song key derivation ([genSongKey], deemix's `genSongKey`): take the MD5
 * hex digest of the song id as an ASCII string; the 16 key bytes are
 * `md5hex[i] XOR md5hex[i + 16] XOR SECRET[i]` for i in 0..15, with
 * SECRET = "jo6aey6haid2Teih".
 *
 * Stream layout ("BF_CBC_STRIPE"): the file is a sequence of 2048-byte
 * chunks; only every 3rd chunk (indices 0, 3, 6, ...) is encrypted, with
 * Blowfish-CBC, a fresh IV per chunk (the ASCII bytes of "00000000"), and
 * no padding. The last chunk may be short; inside an encrypted chunk only
 * the complete 8-byte blocks are ciphered — a trailing partial block is
 * stored in the clear, exactly as Deezer writes it.
 */
internal object DeezerCrypto {
    const val CHUNK_SIZE = 2048

    private const val SECRET = "jo6aey6haid2Teih"

    /** CBC IV for every encrypted chunk: ASCII "00000000". */
    val CHUNK_IV = byteArrayOf(0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30)

    fun genSongKey(songId: String): ByteArray {
        val md5Hex =
            MessageDigest
                .getInstance("MD5")
                .digest(songId.toByteArray(Charsets.US_ASCII))
                .joinToString("") { "%02x".format(it) }
        return ByteArray(16) { i ->
            (md5Hex[i].code xor md5Hex[i + 16].code xor SECRET[i].code).toByte()
        }
    }

    /**
     * Decrypts one chunk in place. [length] is the number of valid bytes
     * (the final chunk of a file may be shorter than [CHUNK_SIZE]); only
     * complete 8-byte blocks are run through the cipher.
     */
    fun decryptChunkInPlace(
        cipher: Blowfish,
        buffer: ByteArray,
        length: Int,
    ) {
        var previous = Blowfish.readBlock(CHUNK_IV, 0)
        var offset = 0
        while (offset + 8 <= length) {
            val cipherBlock = Blowfish.readBlock(buffer, offset)
            val plainBlock = cipher.decryptBlock(cipherBlock) xor previous
            Blowfish.writeBlock(buffer, offset, plainBlock)
            previous = cipherBlock
            offset += 8
        }
    }

    /** Encrypt counterpart of [decryptChunkInPlace]; used by tests/tools. */
    fun encryptChunkInPlace(
        cipher: Blowfish,
        buffer: ByteArray,
        length: Int,
    ) {
        var previous = Blowfish.readBlock(CHUNK_IV, 0)
        var offset = 0
        while (offset + 8 <= length) {
            val plainBlock = Blowfish.readBlock(buffer, offset) xor previous
            val cipherBlock = cipher.encryptBlock(plainBlock)
            Blowfish.writeBlock(buffer, offset, cipherBlock)
            previous = cipherBlock
            offset += 8
        }
    }
}

/**
 * [DataSource] wrapper that transparently decrypts Deezer streams.
 *
 * It activates per-open, based on the [DataSpec]:
 * - key starts with [DeezerStreamResolver.CACHE_KEY_PREFIX] ("deezer:") —
 *   the remainder is the Deezer song id (the playback path, where
 *   MusicService's Deezer resolver returns specs keyed this way), or
 * - the spec URI is one [DeezerStreamResolver] registered during
 *   resolution (the download path, where specs keep the song's own
 *   media id as key — see DEEZER_WIRING.md).
 *
 * Any other spec is passed through completely untouched, so this wrapper
 * is safe to install around the player's shared upstream factory.
 *
 * Random access: opens are aligned down to the containing 2048-byte
 * chunk and the prefix bytes are decrypted then skipped, so a read
 * starting mid-chunk (including inside an encrypted chunk, whose CBC
 * chain restarts at the chunk boundary) yields correct plaintext.
 * Decryption is length-preserving, so lengths reported to callers are
 * the plaintext lengths.
 */
class DeezerDecryptingDataSource(
    private val upstream: DataSource,
) : BaseDataSource(/* isNetwork = */ true) {
    class Factory(
        private val upstreamFactory: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DeezerDecryptingDataSource =
            DeezerDecryptingDataSource(upstreamFactory.createDataSource())
    }

    private var cipher: Blowfish? = null
    private var openedSpec: DataSpec? = null

    // Decryption-mode stream state (unused when passing through).
    private var firstFill = true
    private var skipInFirstChunk = 0L
    private var bytesRemainingInWindow = Long.MAX_VALUE
    private var chunkIndex = 0L
    private val chunkBuffer = ByteArray(DeezerCrypto.CHUNK_SIZE)
    private var chunkLength = 0
    private var chunkCursor = 0
    private var upstreamExhausted = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val songId =
            dataSpec.key
                ?.takeIf { it.startsWith(DeezerStreamResolver.CACHE_KEY_PREFIX) }
                ?.removePrefix(DeezerStreamResolver.CACHE_KEY_PREFIX)
                ?.takeIf { it.isNotBlank() }
                ?: dataSpec.uri?.toString()?.let { DeezerStreamResolver.songIdForStream(it) }

        if (songId == null) {
            // Not a Deezer stream: pure pass-through.
            cipher = null
            openedSpec = dataSpec
            val length = upstream.open(dataSpec)
            transferStarted(dataSpec)
            return length
        }

        cipher = Blowfish(DeezerCrypto.genSongKey(songId))
        openedSpec = dataSpec

        val chunkSize = DeezerCrypto.CHUNK_SIZE.toLong()
        val alignedPosition = dataSpec.position - (dataSpec.position % chunkSize)
        firstFill = true
        skipInFirstChunk = dataSpec.position - alignedPosition
        chunkIndex = alignedPosition / chunkSize
        chunkLength = 0
        chunkCursor = 0
        upstreamExhausted = false

        val upstreamSpec =
            dataSpec
                .buildUpon()
                .setPosition(alignedPosition)
                .setLength(C.LENGTH_UNSET.toLong())
                .build()
        val upstreamLength = upstream.open(upstreamSpec)
        transferStarted(dataSpec)

        val availableFromPosition =
            if (upstreamLength == C.LENGTH_UNSET.toLong()) {
                C.LENGTH_UNSET.toLong()
            } else {
                (upstreamLength - skipInFirstChunk).coerceAtLeast(0L)
            }
        bytesRemainingInWindow =
            when {
                dataSpec.length == C.LENGTH_UNSET.toLong() -> availableFromPosition
                availableFromPosition == C.LENGTH_UNSET.toLong() -> dataSpec.length
                else -> minOf(dataSpec.length, availableFromPosition)
            }
        return if (bytesRemainingInWindow == C.LENGTH_UNSET.toLong()) {
            C.LENGTH_UNSET.toLong()
        } else {
            bytesRemainingInWindow
        }
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (cipher == null) {
            val read = upstream.read(buffer, offset, length)
            if (read > 0) bytesTransferred(read)
            return read
        }
        if (length == 0) return 0
        if (bytesRemainingInWindow == 0L) return C.RESULT_END_OF_INPUT

        if (chunkCursor >= chunkLength) {
            if (!fillChunk()) return C.RESULT_END_OF_INPUT
        }
        val toCopy =
            minOf(
                length.toLong(),
                (chunkLength - chunkCursor).toLong(),
                if (bytesRemainingInWindow == C.LENGTH_UNSET.toLong()) {
                    Long.MAX_VALUE
                } else {
                    bytesRemainingInWindow
                },
            ).toInt()
        System.arraycopy(chunkBuffer, chunkCursor, buffer, offset, toCopy)
        chunkCursor += toCopy
        if (bytesRemainingInWindow != C.LENGTH_UNSET.toLong()) {
            bytesRemainingInWindow -= toCopy
        }
        bytesTransferred(toCopy)
        return toCopy
    }

    /**
     * Reads the next chunk from upstream and decrypts it when its index
     * falls on an encrypted stripe. Returns false at end of stream.
     */
    private fun fillChunk(): Boolean {
        if (upstreamExhausted) return false
        var filled = 0
        while (filled < DeezerCrypto.CHUNK_SIZE) {
            val read =
                upstream.read(chunkBuffer, filled, DeezerCrypto.CHUNK_SIZE - filled)
            if (read == C.RESULT_END_OF_INPUT) {
                upstreamExhausted = true
                break
            }
            if (read == 0) break
            filled += read
        }
        if (filled == 0) return false
        if (chunkIndex % 3L == 0L) {
            DeezerCrypto.decryptChunkInPlace(cipher!!, chunkBuffer, filled)
        }
        chunkLength = filled
        // Only the first chunk served by this open can carry a skip (the
        // bytes before the requested position inside the aligned chunk).
        chunkCursor =
            if (firstFill) {
                firstFill = false
                val skip = skipInFirstChunk.toInt().coerceAtMost(filled)
                skipInFirstChunk = 0L
                skip
            } else {
                0
            }
        chunkIndex++
        return true
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        val wasOpen = openedSpec != null
        try {
            upstream.close()
        } finally {
            cipher = null
            openedSpec = null
            chunkLength = 0
            chunkCursor = 0
            bytesRemainingInWindow = Long.MAX_VALUE
            if (wasOpen) {
                transferEnded()
            }
        }
    }
}
