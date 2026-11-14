package com.dharmil.mirrordash.tripper

import android.net.Network
import androidx.annotation.WorkerThread
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

data class ReceivedControlPacket(val bytes: ByteArray, val source: InetAddress)

class TripperTransport private constructor(
    private val bikeAddress: InetAddress,
    private val controlPort: Int,
    private val videoPort: Int,
    private val controlSocket: DatagramSocket,
    private val videoSocket: DatagramSocket,
    private val receiveSocket: DatagramSocket,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val receiveBuffer = ByteArray(MAX_RECEIVE_PACKET_BYTES)
    private val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)

    @WorkerThread
    fun sendControl(packet: ByteArray) {
        check(!closed.get()) { "Tripper transport is closed" }
        require(packet.isNotEmpty() && packet.size <= MAX_CONTROL_PACKET_BYTES)
        controlSocket.send(DatagramPacket(packet, packet.size, bikeAddress, controlPort))
    }

    @WorkerThread
    fun sendVideo(packet: ByteArray) {
        check(!closed.get()) { "Tripper transport is closed" }
        require(packet.size in RTP_HEADER_MIN_BYTES..MAX_VIDEO_PACKET_BYTES)
        videoSocket.send(DatagramPacket(packet, packet.size, bikeAddress, videoPort))
    }

    @WorkerThread
    @Synchronized
    @Throws(IOException::class)
    fun receiveControl(timeoutMillis: Int = RECEIVE_TIMEOUT_MILLIS): ReceivedControlPacket {
        check(!closed.get()) { "Tripper transport is closed" }
        receiveSocket.soTimeout = timeoutMillis
        receivePacket.length = receiveBuffer.size
        receiveSocket.receive(receivePacket)
        return ReceivedControlPacket(receiveBuffer.copyOf(receivePacket.length), receivePacket.address)
    }

    fun isFromBike(packet: ReceivedControlPacket): Boolean = packet.source == bikeAddress

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        controlSocket.close()
        videoSocket.close()
        receiveSocket.close()
    }

    companion object {
        private const val RECEIVE_TIMEOUT_MILLIS = 500
        private const val RECEIVE_BUFFER_BYTES = 262_144
        private const val MAX_RECEIVE_PACKET_BYTES = 16_384
        private const val MAX_CONTROL_PACKET_BYTES = 65_535
        private const val MAX_VIDEO_PACKET_BYTES = 1_212
        private const val RTP_HEADER_MIN_BYTES = 13
        private const val IPV4_BYTES = 4

        fun open(
            network: Network,
            bikeAddress: InetAddress,
            controlPort: Int,
            responsePort: Int,
            videoPort: Int,
        ): TripperTransport {
            require(bikeAddress.address.size == IPV4_BYTES) { "Tripper address must be IPv4" }
            require(controlPort in 1..65535 && responsePort in 1..65535 && videoPort in 1..65535)
            var control: DatagramSocket? = null
            var video: DatagramSocket? = null
            var receive: DatagramSocket? = null
            try {
                control = DatagramSocket(null).apply {
                    network.bindSocket(this)
                    bind(InetSocketAddress(controlPort))
                }
                video = DatagramSocket(null).apply {
                    network.bindSocket(this)
                    bind(null)
                }
                receive = DatagramSocket(null).apply {
                    network.bindSocket(this)
                    bind(InetSocketAddress(responsePort))
                    soTimeout = RECEIVE_TIMEOUT_MILLIS
                    receiveBufferSize = RECEIVE_BUFFER_BYTES
                }
                return TripperTransport(bikeAddress, controlPort, videoPort, control, video, receive)
            } catch (error: Throwable) {
                control?.close()
                video?.close()
                receive?.close()
                throw error
            }
        }
    }
}
