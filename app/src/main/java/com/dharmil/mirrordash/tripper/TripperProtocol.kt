package com.dharmil.mirrordash.tripper

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

// Reverse-engineered packet templates reference OpenMotoDash/better-dash; see third_party/OpenMotoDash/LICENSE.
data class TripperSegment(val type: Int, val subtype: Int, val payload: ByteArray)

object TripperProtocol {
    private val authRequest = hexBytes("0016000200000000020100054B314720000804000101")
    private val sessionKeyHeader = hexBytes("0095000200000000020100054B3147200008000080")
    private val navigationTemplate = hexBytes(
        "007e001100000000020100054B31472025050100145461696c6c65206465204d617320647520477200" +
            "050200013c050300013405050002000a05060001300507000130050800043033303305540001300509" +
            "0002004f0546000110050a000155050c000104050b0006303031303030055500012006050001aa060d0001aa",
    )
    private val marker = byteArrayOf(0x4B, 0x31, 0x47, 0x20)
    private val routeTitleMarker = byteArrayOf(0x05, 0x01, 0x00, 0x14)

    private val navigationContext = hexBytes("0016000200000000020100054B31472000052E00011E")
    private val emptyRouteLists = hexBytes(
        "002A000600000000020100054B31472000052F0001000530000100053100010005320001000533000100",
    )
    private val startNavigation = hexBytes("0016000200000000020100054B31472000068000010B")
    private val projectionFrame = hexBytes("0016000200000000020100054B314720000556000155")
    private val projectionOn = hexBytes("0016000200000000020100054B314720000605000155")
    private val projectionStop = hexBytes("0016000200000000020100054B3147200005560001AA")
    private val projectionOff = hexBytes("0016000200000000020100054B3147200006050001AA")
    private val frameDecodedAck = hexBytes("0016000200000000020100054B314720000611000155")

    fun navigationContext(sequence: Int): ByteArray = withSequence(navigationContext, sequence)

    fun emptyRouteLists(sequence: Int): ByteArray = withSequence(emptyRouteLists, sequence)

    fun startNavigation(sequence: Int): ByteArray = withSequence(startNavigation, sequence)

    fun projectionFrame(sequence: Int): ByteArray = withSequence(projectionFrame, sequence)

    fun projectionOn(sequence: Int): ByteArray = withSequence(projectionOn, sequence)

    fun projectionStop(sequence: Int): ByteArray = withSequence(projectionStop, sequence)

    fun projectionOff(sequence: Int): ByteArray = withSequence(projectionOff, sequence)

    fun frameDecodedAck(sequence: Int): ByteArray = withSequence(frameDecodedAck, sequence)

    fun routeCard(title: String, sequence: Int, projectionEnabled: Boolean = false): ByteArray {
        require(title.isNotBlank())
        val markerOffset = findMarker(navigationTemplate)
        val titleFieldOffset = indexOf(navigationTemplate, routeTitleMarker, markerOffset + marker.size + 1)
        require(titleFieldOffset >= 0) { "Invalid route-card template" }
        val titleLength = readUnsignedShort(navigationTemplate, titleFieldOffset + 2)
        val titleOffset = titleFieldOffset + 4
        val suffixOffset = titleOffset + titleLength
        val titleBytes = title.takeUtf8Bytes(MAX_ROUTE_TITLE_BYTES)
        val routeTitle = titleBytes + byteArrayOf(0)
        val packet = ByteArray(
            navigationTemplate.size - titleLength + routeTitle.size,
        )
        navigationTemplate.copyInto(packet, 0, 0, titleFieldOffset)
        packet[titleFieldOffset] = 0x05
        packet[titleFieldOffset + 1] = 0x01
        packet[titleFieldOffset + 2] = (routeTitle.size ushr 8).toByte()
        packet[titleFieldOffset + 3] = routeTitle.size.toByte()
        routeTitle.copyInto(packet, titleFieldOffset + 4)
        navigationTemplate.copyInto(packet, titleFieldOffset + 4 + routeTitle.size, suffixOffset)
        if (projectionEnabled) {
            val projectionFlag = byteArrayOf(0x06, 0x05, 0x00, 0x01, 0xAA.toByte())
            val flagOffset = indexOf(packet, projectionFlag, titleFieldOffset)
            if (flagOffset >= 0) packet[flagOffset + projectionFlag.lastIndex] = PROJECTION_ENABLED.toByte()
        }
        return withSequence(packet, sequence)
    }

    fun activeNavigation(
        sequence: Int,
        maneuverCode: Int,
        distanceToManeuverMeters: Int,
        remainingDistanceMeters: Int,
    ): ByteArray {
        require(maneuverCode in 0..0x0F)
        val primaryDistance = distanceToManeuverMeters.coerceIn(0, MAX_NAV_DISTANCE_METERS)
        val totalDistance = remainingDistanceMeters.coerceIn(0, MAX_NAV_DISTANCE_METERS)
        val tlvs = arrayOf(
            byteArrayOf(0x05, 0x02, 0x00, 0x01, maneuverCode.toByte()),
            byteArrayOf(0x05, 0x04, 0x00, 0x02, (primaryDistance ushr 8).toByte(), primaryDistance.toByte()),
            byteArrayOf(0x05, 0x06, 0x00, 0x01, 0x30),
            byteArrayOf(0x05, 0x09, 0x00, 0x02, (totalDistance ushr 8).toByte(), totalDistance.toByte()),
            byteArrayOf(0x05, 0x46, 0x00, 0x01, 0x30),
            byteArrayOf(0x05, 0x0A, 0x00, 0x01, PROJECTION_ENABLED.toByte()),
            byteArrayOf(0x06, 0x05, 0x00, 0x01, PROJECTION_ENABLED.toByte()),
            byteArrayOf(0x06, 0x0D, 0x00, 0x01, PROJECTION_DISABLED.toByte()),
        )
        val packet = ByteArray(OUTER_HEADER_BYTES + ACTIVE_NAV_HEADER.size + tlvs.sumOf(ByteArray::size))
        packet[2] = 0
        packet[3] = (tlvs.size + 1).toByte()
        ACTIVE_NAV_HEADER.copyInto(packet, OUTER_HEADER_BYTES)
        var offset = OUTER_HEADER_BYTES + ACTIVE_NAV_HEADER.size
        for (tlv in tlvs) {
            tlv.copyInto(packet, offset)
            offset += tlv.size
        }
        return withSequence(packet, sequence)
    }

    fun echoAck(buttonCode: Int, sequence: Int): ByteArray {
        require(buttonCode in 0..0xFF)
        val packet = hexBytes("0016000200000000020100054B314720000680000100")
        packet[packet.lastIndex] = buttonCode.toByte()
        return withSequence(packet, sequence)
    }

    fun authenticationRequest(sequence: Int): ByteArray = withSequence(authRequest, sequence)

    fun authenticationPacket(
        ssid: String,
        modulus: ByteArray,
        exponent: ByteArray,
        sessionKey: ByteArray,
        sequence: Int,
    ): ByteArray {
        require(ssid.isNotEmpty())
        require(sessionKey.size == 32)
        require(exponent.size in 1..4)
        val modulusBytes = if (modulus.size == 129 && modulus[0] == 0.toByte()) {
            modulus.copyOfRange(1, modulus.size)
        } else {
            modulus
        }
        require(modulusBytes.size == 128)
        val modulusNumber = BigInteger(1, modulusBytes)
        require(modulusNumber.bitLength() == 1024 && modulusNumber.testBit(0))
        val exponentNumber = BigInteger(1, exponent)
        require(exponentNumber >= BigInteger.valueOf(3L))
        require(exponentNumber.bitLength() <= 32 && exponentNumber.testBit(0))
        require(exponentNumber < modulusNumber)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(modulusNumber, exponentNumber),
        )
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(ssid.toByteArray(StandardCharsets.UTF_8) + sessionKey)
        require(encrypted.size == 128) { "Unsupported Tripper RSA key size" }
        return withSequence(sessionKeyHeader + encrypted, sequence)
    }

    fun parse(datagram: ByteArray): List<TripperSegment> {
        require(datagram.size >= 8) { "Truncated Tripper packet header" }
        val packetLength = readUnsignedShort(datagram, 0)
        val segmentCount = readUnsignedShort(datagram, 2)
        require(packetLength == datagram.size) { "Invalid Tripper packet length" }
        require(segmentCount <= (packetLength - 8) / 4) { "Invalid Tripper segment count" }

        val segments = ArrayList<TripperSegment>(segmentCount)
        var offset = 8
        repeat(segmentCount) {
            require(offset + 4 <= packetLength) { "Truncated Tripper segment header" }
            val type = datagram[offset].toInt() and 0xFF
            val subtype = datagram[offset + 1].toInt() and 0xFF
            val payloadLength = readUnsignedShort(datagram, offset + 2)
            offset += 4
            require(payloadLength <= packetLength - offset) { "Truncated Tripper segment payload" }
            segments += TripperSegment(type, subtype, datagram.copyOfRange(offset, offset + payloadLength))
            offset += payloadLength
        }
        require(offset == packetLength) { "Unexpected bytes after Tripper segments" }
        return segments
    }

    fun withSequence(packet: ByteArray, sequence: Int): ByteArray {
        require(sequence in 0..0xFF)
        require(packet.size <= 0xFFFF)
        val markerOffset = findMarker(packet)
        require(markerOffset >= 0 && markerOffset + marker.size < packet.size) {
            "Missing Tripper K1G marker"
        }
        return packet.copyOf().also {
            it[markerOffset + marker.size] = sequence.toByte()
            it[0] = (packet.size ushr 8).toByte()
            it[1] = packet.size.toByte()
        }
    }

    private fun readUnsignedShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun indexOf(source: ByteArray, value: ByteArray, start: Int): Int {
        if (value.isEmpty()) return start.coerceAtMost(source.size)
        for (offset in start..source.size - value.size) {
            if (value.indices.all { source[offset + it] == value[it] }) return offset
        }
        return -1
    }

    private fun String.takeUtf8Bytes(maxBytes: Int): ByteArray {
        val bytes = toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= maxBytes) return bytes
        var end = maxBytes
        while (end > 0 && bytes[end].toInt() and UTF8_CONTINUATION_MASK == UTF8_CONTINUATION_PREFIX) end--
        return bytes.copyOf(end)
    }

    private fun findMarker(packet: ByteArray): Int {
        for (offset in 0..packet.size - marker.size) {
            var matches = true
            for (index in marker.indices) {
                if (packet[offset + index] != marker[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return offset
        }
        return -1
    }

    private fun hexBytes(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private val ACTIVE_NAV_HEADER = hexBytes("00000000020100054B31472000")

    private const val MAX_ROUTE_TITLE_BYTES = 60
    private const val MAX_NAV_DISTANCE_METERS = 0xFFFF
    private const val OUTER_HEADER_BYTES = 4
    private const val PROJECTION_ENABLED = 0x55
    private const val PROJECTION_DISABLED = 0xAA
    private const val UTF8_CONTINUATION_MASK = 0xC0
    private const val UTF8_CONTINUATION_PREFIX = 0x80
}
