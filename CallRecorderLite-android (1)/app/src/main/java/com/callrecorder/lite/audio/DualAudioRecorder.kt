package com.callrecorder.lite.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Process
import java.io.FileDescriptor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class DualAudioRecorder(
    private val fileDescriptor: FileDescriptor,
    private val mediaProjection: MediaProjection?,
    private val onFinished: (durationMs: Long, hasAudioData: Boolean) -> Unit
) {
    private val isRunning = AtomicBoolean(false)
    private var playbackThread: Thread? = null
    private var mixerThread: Thread? = null

    private val playbackQueue = ArrayBlockingQueue<ShortArray>(PLAYBACK_QUEUE_CAPACITY)

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return

        mixerThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            runRecordingPipeline()
        }, "DualAudioMixerThread").apply { start() }
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        runCatching { playbackThread?.join(800) }
        runCatching { mixerThread?.join(2_000) }
        playbackThread = null
        mixerThread = null
    }

    private fun runRecordingPipeline() {
        var micRecord: AudioRecord? = null
        var playbackRecord: AudioRecord? = null
        var encoder: AacM4aEncoder? = null
        var durationMs = 0L
        var hasAudio = false

        try {
            encoder = AacM4aEncoder(
                fileDescriptor = fileDescriptor,
                sampleRate = SAMPLE_RATE,
                channelCount = 1,
                bitRate = 64_000
            )

            micRecord = createBestMicrophoneRecord()
            playbackRecord = createPlaybackCaptureRecord()

            if (micRecord == null && playbackRecord == null) {
                return
            }

            if (playbackRecord != null) {
                val activePlayback = playbackRecord
                runCatching { activePlayback.startRecording() }
                if (activePlayback.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    playbackThread = Thread({
                        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                        readPlaybackLoop(activePlayback)
                    }, "PlaybackCaptureThread").apply { start() }
                } else {
                    runCatching { activePlayback.release() }
                    playbackRecord = null
                }
            }

            if (micRecord != null) {
                runCatching { micRecord.startRecording() }
                if (micRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    runCatching { micRecord.release() }
                    micRecord = null
                }
            }

            if (micRecord == null && playbackRecord == null) {
                return
            }

            val micBuffer = ShortArray(FRAME_SAMPLES)
            val mixedBuffer = ShortArray(FRAME_SAMPLES)

            while (isRunning.get()) {
                val micRead = if (micRecord != null) {
                    micRecord.read(micBuffer, 0, FRAME_SAMPLES)
                } else {
                    0
                }

                if (micRecord != null && micRead <= 0) {
                    if (micRead == AudioRecord.ERROR_INVALID_OPERATION || micRead == AudioRecord.ERROR_DEAD_OBJECT) {
                        // Mic lost focus or errored; pace lightly so playback capture can continue if active
                        Thread.sleep(10)
                    }
                    continue
                }

                val playbackFrame = playbackQueue.poll()

                val sampleCount = when {
                    micRead > 0 -> micRead
                    playbackFrame != null -> playbackFrame.size
                    else -> {
                        Thread.sleep(15)
                        continue
                    }
                }

                val playbackHasSignal = playbackFrame != null && hasNonZeroAudio(playbackFrame)

                for (i in 0 until sampleCount) {
                    val micSample = if (micRead > 0 && i < micRead) micBuffer[i].toInt() else 0
                    val pbSample = if (playbackHasSignal && i < playbackFrame!!.size) {
                        playbackFrame[i].toInt()
                    } else {
                        0
                    }

                    // When VoIP apps set allowAudioPlaybackCapture=false, playbackFrame contains zeros.
                    // Passing micSample unmodified preserves full mic + earpiece bleed clarity.
                    val summed = micSample + pbSample
                    mixedBuffer[i] = summed
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                }

                encoder.encodePcmChunk(mixedBuffer, sampleCount)
            }
        } catch (_: Throwable) {
            // Ensure encoder/records are always released cleanly on unexpected audio HAL errors
        } finally {
            isRunning.set(false)
            runCatching { playbackThread?.join(500) }
            runCatching { micRecord?.stop() }
            runCatching { micRecord?.release() }
            runCatching { playbackRecord?.stop() }
            runCatching { playbackRecord?.release() }

            if (encoder != null) {
                durationMs = runCatching { encoder.finish() }.getOrDefault(0L)
                hasAudio = encoder.encodedOutputBytes > 0L
            }
            onFinished(durationMs, hasAudio)
        }
    }

    private fun readPlaybackLoop(playbackRecord: AudioRecord) {
        val temp = ShortArray(FRAME_SAMPLES)
        while (isRunning.get()) {
            val read = playbackRecord.read(temp, 0, FRAME_SAMPLES)
            if (read > 0) {
                val frame = temp.copyOf(read)
                if (!playbackQueue.offer(frame)) {
                    playbackQueue.poll()
                    playbackQueue.offer(frame)
                }
            } else if (read < 0) {
                break
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createBestMicrophoneRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(FRAME_SAMPLES * 2)
        val bufferBytes = maxOf(minBuf, FRAME_SAMPLES * 4)

        val preferredSources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )

        for (source in preferredSources) {
            val candidate = runCatching {
                AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
                )
            }.getOrNull()

            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                return candidate
            }
            runCatching { candidate?.release() }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun createPlaybackCaptureRecord(): AudioRecord? {
        val projection = mediaProjection ?: return null
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(FRAME_SAMPLES * 2)
        val bufferBytes = maxOf(minBuf, FRAME_SAMPLES * 4)

        return runCatching {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            if (record.state == AudioRecord.STATE_INITIALIZED) {
                record
            } else {
                record.release()
                null
            }
        }.getOrNull()
    }

    private fun hasNonZeroAudio(samples: ShortArray): Boolean {
        for (i in samples.indices step 8) {
            if (abs(samples[i].toInt()) > 4) return true
        }
        return false
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        private const val FRAME_SAMPLES = 1024
        private const val PLAYBACK_QUEUE_CAPACITY = 16
    }
}
