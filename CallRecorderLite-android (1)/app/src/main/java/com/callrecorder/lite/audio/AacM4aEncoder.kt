package com.callrecorder.lite.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.nio.ByteBuffer

class AacM4aEncoder(
    fileDescriptor: FileDescriptor,
    private val sampleRate: Int = 44_100,
    private val channelCount: Int = 1,
    bitRate: Int = 64_000
) {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val bufferInfo = MediaCodec.BufferInfo()

    private var trackIndex = -1
    private var muxerStarted = false
    private var totalFramesEncoded = 0L
    private var pcmByteScratch = ByteArray(0)

    @Volatile
    var encodedOutputBytes = 0L
        private set

    init {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        muxer = MediaMuxer(fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun encodePcmChunk(samples: ShortArray, sampleCount: Int) {
        if (sampleCount <= 0) return

        val byteCount = sampleCount * 2
        if (pcmByteScratch.size < byteCount) {
            pcmByteScratch = ByteArray(byteCount)
        }
        for (i in 0 until sampleCount) {
            val s = samples[i].toInt()
            pcmByteScratch[i * 2] = (s and 0xFF).toByte()
            pcmByteScratch[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
        }

        var offset = 0
        while (offset < byteCount) {
            val inputIdx = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (inputIdx >= 0) {
                val inputBuf: ByteBuffer = codec.getInputBuffer(inputIdx) ?: continue
                inputBuf.clear()
                val chunkLen = minOf(byteCount - offset, inputBuf.remaining())
                inputBuf.put(pcmByteScratch, offset, chunkLen)

                val ptsUs = (totalFramesEncoded * 1_000_000L) / sampleRate
                codec.queueInputBuffer(inputIdx, 0, chunkLen, ptsUs, 0)

                totalFramesEncoded += (chunkLen / (2 * channelCount))
                offset += chunkLen
            }
            drainOutput(endOfStream = false)
        }
    }

    fun finish(): Long {
        try {
            var eosQueued = false
            for (attempt in 0 until 20) {
                val inputIdx = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIdx >= 0) {
                    val ptsUs = (totalFramesEncoded * 1_000_000L) / sampleRate
                    codec.queueInputBuffer(
                        inputIdx,
                        0,
                        0,
                        ptsUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    eosQueued = true
                    break
                }
                drainOutput(endOfStream = false)
            }
            if (eosQueued) {
                drainOutput(endOfStream = true)
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
        }
        return (totalFramesEncoded * 1_000L) / sampleRate
    }

    private fun drainOutput(endOfStream: Boolean) {
        val timeoutUs = if (endOfStream) CODEC_TIMEOUT_US else 0L
        var eosAttempts = 0

        while (true) {
            val outIdx = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++eosAttempts > 25) break
                }

                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }

                outIdx >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                        muxerStarted
                    ) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                        encodedOutputBytes += bufferInfo.size
                    }

                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(outIdx, false)
                    if (isEos) break
                }
            }
        }
    }

    companion object {
        private const val CODEC_TIMEOUT_US = 10_000L
    }
}
