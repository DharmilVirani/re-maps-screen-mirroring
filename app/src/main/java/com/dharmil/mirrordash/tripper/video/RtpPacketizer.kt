package com.dharmil.mirrordash.tripper.video

import java.security.SecureRandom

data class EncodedFrame(val annexB: ByteArray, val presentationTimeUs: Long)

class RtpPacketizer(
    initialSequence: Int = random.nextInt(1 shl 16),
    private val ssrc: Int = random.nextInt(),
    private val payloadType: Int = H264_PAYLOAD_TYPE,
    private val maxPayloadBytes: Int = TripperVideoSpec.RTP_MAX_PAYLOAD_BYTES,
) {
    private var sequence = initialSequence

    init {
        require(initialSequence in 0..0xFFFF)
        require(payloadType in 0..0x7F)
        require(maxPayloadBytes in 256..MAX_SAFE_PAYLOAD_BYTES)
    }

    /** Emits RFC 6184 single-NAL or FU-A packets; the final packet carries the marker bit. */
    fun packetize(frame: EncodedFrame, emit: (ByteArray) -> Unit): Int {
        require(frame.presentationTimeUs >= 0L)
        val nals = ArrayList<NalRange>(4)
        H264NalUnits.forEachNal(frame.annexB) { type, start, end ->
            require(type in 1..23) { "Unsupported H.264 NAL unit type: $type" }
            nals += NalRange(start, end)
        }
        require(nals.isNotEmpty()) { "No H.264 NAL units in frame" }

        val rtpTimestamp = (
            frame.presentationTimeUs / 1_000L * RTP_CLOCK_RATE / 1_000L +
                frame.presentationTimeUs % 1_000L * RTP_CLOCK_RATE / MICROS_PER_SECOND
            ) and RTP_TIMESTAMP_MASK
        var packetCount = 0
        for ((nalIndex, nal) in nals.withIndex()) {
            val marker = nalIndex == nals.lastIndex
            val size = nal.end - nal.start
            if (size <= maxPayloadBytes) {
                emit(rtpPacket(size, rtpTimestamp, marker) { packet ->
                    frame.annexB.copyInto(packet, RTP_HEADER_BYTES, nal.start, nal.end)
                })
                packetCount++
            } else {
                val header = frame.annexB[nal.start].toInt() and 0xFF
                val fuIndicator = (header and NRI_MASK) or FU_A_TYPE
                val fuType = header and NAL_TYPE_MASK
                val fragmentBytes = maxPayloadBytes - FU_HEADER_BYTES
                var offset = nal.start + 1
                while (offset < nal.end) {
                    val fragmentSize = minOf(fragmentBytes, nal.end - offset)
                    val first = offset == nal.start + 1
                    val last = offset + fragmentSize == nal.end
                    val packet = rtpPacket(fragmentSize + FU_HEADER_BYTES, rtpTimestamp, marker && last) { target ->
                        target[RTP_HEADER_BYTES] = fuIndicator.toByte()
                        target[RTP_HEADER_BYTES + 1] = (
                            fuType or (if (first) FU_START else 0) or (if (last) FU_END else 0)
                        ).toByte()
                        frame.annexB.copyInto(
                            target,
                            RTP_HEADER_BYTES + FU_HEADER_BYTES,
                            offset,
                            offset + fragmentSize,
                        )
                    }
                    emit(packet)
                    packetCount++
                    offset += fragmentSize
                }
            }
        }
        return packetCount
    }

    private inline fun rtpPacket(
        payloadSize: Int,
        timestamp: Long,
        marker: Boolean,
        writePayload: (ByteArray) -> Unit,
    ): ByteArray {
        val packet = ByteArray(RTP_HEADER_BYTES + payloadSize)
        packet[0] = RTP_VERSION.toByte()
        packet[1] = (payloadType or if (marker) MARKER_MASK else 0).toByte()
        packet[2] = (sequence ushr 8).toByte()
        packet[3] = sequence.toByte()
        packet[4] = (timestamp ushr 24).toByte()
        packet[5] = (timestamp ushr 16).toByte()
        packet[6] = (timestamp ushr 8).toByte()
        packet[7] = timestamp.toByte()
        packet[8] = (ssrc ushr 24).toByte()
        packet[9] = (ssrc ushr 16).toByte()
        packet[10] = (ssrc ushr 8).toByte()
        packet[11] = ssrc.toByte()
        sequence = (sequence + 1) and 0xFFFF
        writePayload(packet)
        return packet
    }

    private data class NalRange(val start: Int, val end: Int)

    private companion object {
        val random = SecureRandom()
        const val RTP_HEADER_BYTES = 12
        const val FU_HEADER_BYTES = 2
        const val RTP_VERSION = 0x80
        const val MARKER_MASK = 0x80
        const val NRI_MASK = 0xE0
        const val NAL_TYPE_MASK = 0x1F
        const val FU_A_TYPE = 28
        const val FU_START = 0x80
        const val FU_END = 0x40
        const val H264_PAYLOAD_TYPE = 96
        const val MAX_SAFE_PAYLOAD_BYTES = 1_450
        const val RTP_CLOCK_RATE = 90_000L
        const val MICROS_PER_SECOND = 1_000_000L
        const val RTP_TIMESTAMP_MASK = 0xFFFF_FFFFL
    }
}
