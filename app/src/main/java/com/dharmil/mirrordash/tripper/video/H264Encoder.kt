package com.dharmil.mirrordash.tripper.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class H264Encoder(
    private val spec: TripperVideoSpec,
    private val listener: Listener,
) : AutoCloseable {
    private val lifecycleLock = Any()
    private var codec: MediaCodec? = null
    private var callbackThread: HandlerThread? = null
    private var inputSurface: Surface? = null
    @Volatile private var closed = false

    /** Callbacks run on the encoder thread; listeners must be quick and must not wait for UI work. */
    interface Listener {
        fun onEncodedFrame(frame: EncodedFrame)
        fun onEncoderError(error: Throwable)
    }

    fun start(): Surface = synchronized(lifecycleLock) {
        check(codec == null) { "Encoder is already started" }
        check(!closed) { "Encoder cannot be restarted after close" }
        var encoder: MediaCodec? = null
        var thread: HandlerThread? = null
        var surface: Surface? = null
        try {
            val codecInfo = findHardwareEncoder()
            encoder = MediaCodec.createByCodecName(codecInfo.name)
            thread = HandlerThread(CALLBACK_THREAD_NAME).also { it.start() }
            val format = createFormat(codecInfo)
            val activeEncoder = checkNotNull(encoder)
            val activeThread = checkNotNull(thread)
            activeEncoder.setCallback(createCallback(), Handler(activeThread.looper))
            activeEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val createdSurface = activeEncoder.createInputSurface()
            surface = createdSurface
            activeEncoder.start()
            codec = activeEncoder
            callbackThread = activeThread
            inputSurface = createdSurface
            createdSurface
        } catch (error: Throwable) {
            try {
                surface?.release()
            } finally {
                try {
                    encoder?.release()
                } finally {
                    thread?.quitSafely()
                }
            }
            throw error
        }
    }

    override fun close() {
        val (activeCodec, activeSurface, activeThread) = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            Triple(codec, inputSurface, callbackThread).also {
                codec = null
                inputSurface = null
                callbackThread = null
            }
        }
        try {
            try {
                activeCodec?.stop()
            } catch (_: IllegalStateException) {
                // A codec that failed asynchronously may already be stopped.
            } finally {
                activeCodec?.release()
            }
        } finally {
            try {
                activeSurface?.release()
            } finally {
                activeThread?.quitSafely()
                if (activeThread != null && Thread.currentThread() !== activeThread) activeThread.join(1_000)
            }
        }
    }

    private fun createCallback() = object : MediaCodec.Callback() {
        private var codecConfig = ByteArray(0)
        private var nalLengthSize = 4
        private var nalFraming = H264NalFraming.UNKNOWN
        private val accessUnitAssembler = H264AccessUnitAssembler(MediaCodec.BUFFER_FLAG_PARTIAL_FRAME)

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            synchronized(lifecycleLock) {
                if (closed) return
                try {
                    val config = ByteArrayOutputStream()
                    for (key in CODEC_SPECIFIC_DATA_KEYS) {
                        format.getByteBuffer(key)?.let { source ->
                            val bytes = source.remainingBytes()
                            if (bytes.isNotEmpty()) {
                                H264NalUnits.detectFraming(bytes)
                                    .takeIf { it != H264NalFraming.UNKNOWN }
                                    ?.let { nalFraming = it }
                                H264NalUnits.avcNalLengthSize(bytes)?.let { nalLengthSize = it }
                                config.write(
                                    H264NalUnits.toAnnexB(
                                        bytes,
                                        nalLengthSize,
                                        codecConfig = true,
                                        framing = nalFraming,
                                    ),
                                )
                            }
                        }
                    }
                    codecConfig = config.toByteArray()
                } catch (error: Throwable) {
                    notifyError(error)
                }
            }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            synchronized(lifecycleLock) {
                var encodedFrame: EncodedFrame? = null
                var callbackError: Throwable? = null
                try {
                    if (closed) return
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        if (info.size > 0) {
                            codec.getOutputBuffer(index)?.copyRange(info)?.let { bytes ->
                                H264NalUnits.detectFraming(bytes)
                                    .takeIf { it != H264NalFraming.UNKNOWN }
                                    ?.let { nalFraming = it }
                                H264NalUnits.avcNalLengthSize(bytes)?.let { nalLengthSize = it }
                                val normalized = H264NalUnits.toAnnexB(
                                    bytes,
                                    nalLengthSize,
                                    codecConfig = true,
                                    framing = nalFraming,
                                )
                                codecConfig = mergeCodecConfig(codecConfig, normalized)
                            }
                        }
                        return
                    }
                    if (info.size <= 0) return

                    val bytes = codec.getOutputBuffer(index)?.copyRange(info) ?: return
                    val accessUnit = accessUnitAssembler.append(
                        H264NalUnits.toAnnexB(bytes, nalLengthSize, framing = nalFraming),
                        info.presentationTimeUs,
                        info.flags,
                    ) ?: return
                    var frameBytes = accessUnit.bytes
                    if (accessUnit.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 &&
                        codecConfig.isNotEmpty() && !H264NalUnits.hasParameterSets(frameBytes)
                    ) {
                        frameBytes = codecConfig + frameBytes
                    }
                    encodedFrame = EncodedFrame(frameBytes, accessUnit.presentationTimeUs)
                } catch (error: Throwable) {
                    accessUnitAssembler.abort(info.flags)
                    callbackError = error
                } finally {
                    if (!closed) runCatching { codec.releaseOutputBuffer(index, false) }
                }
                if (closed) return
                callbackError?.let {
                    notifyError(it)
                    return
                }
                encodedFrame?.let { frame ->
                    try {
                        listener.onEncodedFrame(frame)
                    } catch (error: Exception) {
                        notifyError(error)
                    }
                }
            }
        }

        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) = notifyError(error)

        private fun notifyError(error: Throwable) {
            synchronized(lifecycleLock) {
                if (!closed) runCatching { listener.onEncoderError(error) }
            }
        }
    }

    private fun findHardwareEncoder(): MediaCodecInfo =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            if (!info.isEncoder || !info.isHardwareAccelerated || !info.supportedTypes.any { it.equals(MIME, true) }) {
                return@firstOrNull false
            }
            val capabilities = runCatching { info.getCapabilitiesForType(MIME) }.getOrNull() ?: return@firstOrNull false
            val supportsSurface = capabilities.colorFormats.any { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface }
            val supportsBaseline = capabilities.profileLevels.any {
                it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            }
            val supportsSize = capabilities.videoCapabilities?.areSizeAndRateSupported(
                spec.widthPx,
                spec.heightPx,
                spec.frameRate.toDouble(),
            ) == true
            supportsSurface && supportsBaseline && supportsSize
        } ?: error("No hardware H.264 encoder supports the Tripper output format")

    private fun createFormat(codecInfo: MediaCodecInfo): MediaFormat {
        val format = MediaFormat.createVideoFormat(MIME, spec.widthPx, spec.heightPx).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, spec.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, spec.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, TripperVideoSpec.GOP_SECONDS)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_PRIORITY, REALTIME_PRIORITY)
        }
        val capabilities = codecInfo.getCapabilitiesForType(MIME).encoderCapabilities
        if (capabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        return format
    }

    private fun mergeCodecConfig(previous: ByteArray, next: ByteArray): ByteArray =
        if (H264NalUnits.hasParameterSets(next)) next else previous + next

    private fun ByteBuffer.remainingBytes(): ByteArray = duplicate().let { copy ->
        ByteArray(copy.remaining()).also(copy::get)
    }

    private fun ByteBuffer.copyRange(info: MediaCodec.BufferInfo): ByteArray = duplicate().let { copy ->
        copy.position(info.offset)
        copy.limit(info.offset + info.size)
        ByteArray(info.size).also(copy::get)
    }

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val CALLBACK_THREAD_NAME = "re-connector-h264"
        const val REALTIME_PRIORITY = 0
        val CODEC_SPECIFIC_DATA_KEYS = arrayOf("csd-0", "csd-1")
    }
}
