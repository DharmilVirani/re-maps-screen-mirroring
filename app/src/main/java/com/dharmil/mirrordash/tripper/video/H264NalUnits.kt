package com.dharmil.mirrordash.tripper.video

import java.io.ByteArrayOutputStream

internal object H264NalUnits {
    private val startCode = byteArrayOf(0, 0, 0, 1)

    fun detectFraming(codecData: ByteArray): H264NalFraming {
        if (parseAvcConfiguration(codecData) != null) return H264NalFraming.LENGTH_PREFIXED
        if (!isAnnexB(codecData)) return H264NalFraming.UNKNOWN
        for (lengthSize in 1..4) {
            val normalized = normalizeLengthPrefixed(codecData, lengthSize) ?: continue
            if (!normalized.contentEquals(codecData)) return H264NalFraming.UNKNOWN
        }
        return H264NalFraming.ANNEX_B
    }

    fun toAnnexB(
        data: ByteArray,
        nalLengthSize: Int = 4,
        codecConfig: Boolean = false,
        framing: H264NalFraming = H264NalFraming.UNKNOWN,
    ): ByteArray {
        require(data.isNotEmpty()) { "Empty H.264 buffer" }
        if (codecConfig) parseAvcConfiguration(data)?.let { return it.units }
        when (framing) {
            H264NalFraming.ANNEX_B -> {
                if (isAnnexB(data)) return data.copyOf()
                require(isNalHeader(data[0])) { "Invalid Annex B NAL unit" }
                return startCode + data
            }
            H264NalFraming.LENGTH_PREFIXED ->
                return checkNotNull(normalizeLengthPrefixed(data, nalLengthSize)) {
                    "Invalid length-prefixed H.264 data"
                }
            H264NalFraming.UNKNOWN -> Unit
        }
        val annexB = data.takeIf(::isAnnexB)
        var lengthPrefixed = normalizeLengthPrefixed(data, nalLengthSize)
        for (lengthSize in 1..4) {
            if (lengthSize == nalLengthSize) continue
            val normalized = normalizeLengthPrefixed(data, lengthSize) ?: continue
            require(lengthPrefixed == null || lengthPrefixed.contentEquals(normalized)) {
                "Ambiguous H.264 NAL framing"
            }
            lengthPrefixed = normalized
        }
        if (annexB != null && lengthPrefixed != null) {
            require(annexB.contentEquals(lengthPrefixed)) { "Ambiguous H.264 NAL framing" }
        }
        val rawNal = data.takeIf { isNalHeader(it[0]) }?.let { startCode + it }
        if (rawNal != null && lengthPrefixed != null) {
            require(rawNal.contentEquals(lengthPrefixed)) { "Ambiguous H.264 NAL framing" }
        }
        annexB?.let { return it.copyOf() }
        lengthPrefixed?.let { return it }
        return checkNotNull(rawNal) { "Invalid H.264 NAL unit" }
    }

    fun avcNalLengthSize(data: ByteArray): Int? = parseAvcConfiguration(data)?.nalLengthSize

    fun hasParameterSets(annexB: ByteArray): Boolean {
        var hasSps = false
        var hasPps = false
        forEachNal(annexB) { type, _, _ ->
            hasSps = hasSps || type == NAL_SPS
            hasPps = hasPps || type == NAL_PPS
        }
        return hasSps && hasPps
    }

    fun forEachNal(annexB: ByteArray, action: (type: Int, start: Int, end: Int) -> Unit) {
        var marker = findStartCode(annexB, 0)
        if (marker < 0) {
            require(annexB.isNotEmpty()) { "Empty H.264 access unit" }
            action(annexB[0].toInt() and 0x1F, 0, annexB.size)
            return
        }
        while (marker >= 0) {
            val nalStart = marker + startCodeLength(annexB, marker)
            val nextMarker = findStartCode(annexB, nalStart)
            val nalEnd = if (nextMarker >= 0) nextMarker else annexB.size
            if (nalStart < nalEnd) action(annexB[nalStart].toInt() and 0x1F, nalStart, nalEnd)
            marker = nextMarker
        }
    }

    private fun normalizeLengthPrefixed(data: ByteArray, lengthSize: Int): ByteArray? {
        if (lengthSize !in 1..4) return null
        val output = ByteArrayOutputStream(data.size + data.size / 16)
        var offset = 0
        var units = 0
        while (offset < data.size) {
            if (offset + lengthSize > data.size) return null
            var nalLength = 0
            repeat(lengthSize) { nalLength = (nalLength shl 8) or (data[offset++].toInt() and 0xFF) }
            if (nalLength <= 0 || nalLength > data.size - offset) return null
            if (!isNalHeader(data[offset])) return null
            output.write(startCode)
            output.write(data, offset, nalLength)
            offset += nalLength
            units++
        }
        return output.toByteArray().takeIf { units > 0 }
    }

    private fun isAnnexB(data: ByteArray): Boolean {
        if (findStartCode(data, 0) < 0) return false
        var valid = false
        var allValid = true
        forEachNal(data) { type, start, _ ->
            valid = true
            allValid = allValid && isNalHeader(data[start]) && type in 1..23
        }
        return valid && allValid
    }

    private fun isNalHeader(header: Byte): Boolean {
        val value = header.toInt() and 0xFF
        val type = value and NAL_TYPE_MASK
        return value and FORBIDDEN_ZERO_BIT == 0 && type in 1..23
    }

    private fun parseAvcConfiguration(data: ByteArray): AvcConfiguration? {
        if (data.size < 7 || data[0].toInt() != 1) return null
        return runCatching {
            val nalLengthSize = (data[4].toInt() and 0x03) + 1
            var offset = 5
            val spsCount = data[offset++].toInt() and 0x1F
            val output = ByteArrayOutputStream(data.size + 16)
            repeat(spsCount) { offset = writeLengthPrefixedNal(data, offset, output) }
            val ppsCount = data[offset++].toInt() and 0xFF
            repeat(ppsCount) { offset = writeLengthPrefixedNal(data, offset, output) }
            require(output.size() > 0)
            AvcConfiguration(output.toByteArray(), nalLengthSize)
        }.getOrNull()
    }

    private fun writeLengthPrefixedNal(data: ByteArray, offset: Int, output: ByteArrayOutputStream): Int {
        require(offset + 2 <= data.size)
        val size = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        val nalStart = offset + 2
        require(size > 0 && size <= data.size - nalStart)
        output.write(startCode)
        output.write(data, nalStart, size)
        return nalStart + size
    }

    private fun findStartCode(data: ByteArray, from: Int): Int {
        for (index in from until data.size - 2) {
            if (data[index] == 0.toByte() && data[index + 1] == 0.toByte()) {
                if (data[index + 2] == 1.toByte()) return index
                if (index + 3 < data.size && data[index + 2] == 0.toByte() && data[index + 3] == 1.toByte()) {
                    return index
                }
            }
        }
        return -1
    }

    private fun startCodeLength(data: ByteArray, offset: Int): Int =
        if (offset + 3 < data.size && data[offset + 2] == 0.toByte()) 4 else 3

    private data class AvcConfiguration(val units: ByteArray, val nalLengthSize: Int)

    private const val NAL_SPS = 7
    private const val NAL_PPS = 8
    private const val NAL_TYPE_MASK = 0x1F
    private const val FORBIDDEN_ZERO_BIT = 0x80
}

internal enum class H264NalFraming { UNKNOWN, ANNEX_B, LENGTH_PREFIXED }
