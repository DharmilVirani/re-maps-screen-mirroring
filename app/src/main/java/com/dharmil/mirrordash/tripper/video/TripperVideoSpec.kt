package com.dharmil.mirrordash.tripper.video

data class TripperVideoSpec(
    val widthPx: Int = DEFAULT_WIDTH_PX,
    val heightPx: Int = DEFAULT_HEIGHT_PX,
    val frameRate: Int = DEFAULT_FRAME_RATE,
    val bitrateBps: Int = DEFAULT_BITRATE_BPS,
) {
    init {
        require(widthPx in 144..1280 && widthPx % 2 == 0)
        require(heightPx in 144..720 && heightPx % 2 == 0)
        require(frameRate in 1..30)
        require(bitrateBps in 64_000..5_000_000)
    }

    companion object {
        const val DEFAULT_WIDTH_PX = 526
        const val DEFAULT_HEIGHT_PX = 300
        const val DEFAULT_FRAME_RATE = 4
        const val DEFAULT_BITRATE_BPS = 204_800
        const val RTP_PORT = 5000
        const val RTP_MAX_PAYLOAD_BYTES = 1_200
        const val GOP_SECONDS = 1
    }
}
