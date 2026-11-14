package com.dharmil.mirrordash.tripper

import java.net.InetAddress

data class TripperProfile(
    val ssid: String = "RE_LT09_260125",
    val wifiPassphrase: String = "",
    val bikeIp: String = "192.168.1.1",
    val controlPort: String = "2000",
    val responsePort: String = "2002",
    val videoPort: String = "5000",
    val width: String = "1280",
    val height: String = "720",
    val frameRate: String = "30",
    val bitrate: String = "5000000",
) {
    fun validate(): ValidatedTripperProfile {
        val normalizedSsid = ssid
        require(normalizedSsid.isNotEmpty() && normalizedSsid.toByteArray(Charsets.UTF_8).size <= 32) {
            "SSID must be between 1 and 32 UTF-8 bytes."
        }
        require(wifiPassphrase.isEmpty() || wifiPassphrase.length in 8..63) {
            "Wi-Fi password must be empty or 8–63 characters."
        }
        require(wifiPassphrase.isEmpty() || wifiPassphrase.all { it in ' '..'~' }) {
            "Wi-Fi password must use printable ASCII characters."
        }
        val ipBytes = parseIpv4(bikeIp.trim())
        val control = controlPort.toPort("Control port")
        val response = responsePort.toPort("Response port")
        val video = videoPort.toPort("Video port")
        require(control != response && control != video && response != video) {
            "Control, response and video ports must be different."
        }
        val outputWidth = width.toIntOrNull()?.takeIf { it in 144..1280 && it % 2 == 0 }
            ?: throw IllegalArgumentException("Width must be an even number from 144 to 1280.")
        val outputHeight = height.toIntOrNull()?.takeIf { it in 144..720 && it % 2 == 0 }
            ?: throw IllegalArgumentException("Height must be an even number from 144 to 720.")
        val fps = frameRate.toIntOrNull()?.takeIf { it in 1..30 }
            ?: throw IllegalArgumentException("Frame rate must be from 1 to 30 fps.")
        val bits = bitrate.toIntOrNull()?.takeIf { it in 64_000..5_000_000 }
            ?: throw IllegalArgumentException("Bitrate must be from 64,000 to 5,000,000 bps.")

        return ValidatedTripperProfile(
            ssid = normalizedSsid,
            wifiPassphrase = wifiPassphrase,
            bikeAddress = InetAddress.getByAddress(ipBytes),
            controlPort = control,
            responsePort = response,
            videoPort = video,
            width = outputWidth,
            height = outputHeight,
            frameRate = fps,
            bitrate = bits,
        )
    }

    private fun String.toPort(label: String): Int = toIntOrNull()?.takeIf { it in 1..65535 }
        ?: throw IllegalArgumentException("$label must be from 1 to 65535.")

    private fun parseIpv4(input: String): ByteArray {
        val octets = input.split('.')
        require(octets.size == 4) { "Enter the TFT's IPv4 address, for example 192.168.1.1." }
        return ByteArray(4) { index ->
            val octet = octets[index]
            require(octet.isNotEmpty() && octet.all { it in '0'..'9' }) { "Enter a valid IPv4 address." }
            val value = octet.toIntOrNull()?.takeIf { it in 0..255 }
                ?: throw IllegalArgumentException("Each IPv4 number must be from 0 to 255.")
            value.toByte()
        }
    }
}

data class ValidatedTripperProfile(
    val ssid: String,
    val wifiPassphrase: String,
    val bikeAddress: InetAddress,
    val controlPort: Int,
    val responsePort: Int,
    val videoPort: Int,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitrate: Int,
)
