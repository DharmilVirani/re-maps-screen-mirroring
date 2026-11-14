package com.dharmil.mirrordash.tripper.video

import java.io.ByteArrayOutputStream

internal class H264AccessUnitAssembler(
    private val partialFrameFlag: Int,
    private val maxAccessUnitBytes: Int = DEFAULT_MAX_ACCESS_UNIT_BYTES,
) {
    private val bytes = ByteArrayOutputStream()
    private var presentationTimeUs = 0L
    private var flags = 0
    private var discardingOversizedAccessUnit = false

    init {
        require(maxAccessUnitBytes > 0)
    }

    fun append(chunk: ByteArray, chunkPresentationTimeUs: Long, chunkFlags: Int): AccessUnit? {
        require(chunkPresentationTimeUs >= 0L)
        if (discardingOversizedAccessUnit) {
            if (chunkFlags and partialFrameFlag == 0) discardingOversizedAccessUnit = false
            return null
        }
        if (chunk.size > maxAccessUnitBytes - bytes.size()) {
            bytes.reset()
            flags = 0
            discardingOversizedAccessUnit = chunkFlags and partialFrameFlag != 0
            throw IllegalArgumentException("H.264 access unit exceeds size limit")
        }
        if (bytes.size() == 0) presentationTimeUs = chunkPresentationTimeUs
        bytes.write(chunk)
        flags = flags or chunkFlags
        if (chunkFlags and partialFrameFlag != 0) return null
        return AccessUnit(bytes.toByteArray(), presentationTimeUs, flags).also {
            bytes.reset()
            flags = 0
        }
    }

    fun abort(currentChunkFlags: Int) {
        bytes.reset()
        flags = 0
        discardingOversizedAccessUnit = currentChunkFlags and partialFrameFlag != 0
    }

    internal data class AccessUnit(val bytes: ByteArray, val presentationTimeUs: Long, val flags: Int)

    private companion object {
        const val DEFAULT_MAX_ACCESS_UNIT_BYTES = 1_000_000
    }
}
