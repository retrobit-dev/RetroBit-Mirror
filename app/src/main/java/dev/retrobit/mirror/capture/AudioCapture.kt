package dev.retrobit.mirror.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log

class AudioCapture(
    private val projection: MediaProjection,
    private val onConfig: (csd: ByteArray, sampleRate: Int, channels: Int) -> Unit,
    private val onFrame: (data: ByteArray, ptsUs: Long) -> Unit
) {
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    @Volatile
    var running = false
        private set

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            try {
                loop()
            } catch (t: Throwable) {
                Log.w(TAG, "audio stopped: ${t.message}")
            } finally {
                release()
            }
        }, "retrobit-audio").also { it.start() }
    }

    fun stop() {
        running = false
        try {
            record?.stop()
        } catch (_: Throwable) {
        }
        thread?.join(600)
        release()
    }

    private fun loop() {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(min.coerceAtLeast(4096) * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            Log.w(TAG, "AudioRecord not initialized")
            return
        }
        record = recorder
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec = encoder
        val outFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        recorder.startRecording()
        val pcm = ByteArray(4096)
        val info = MediaCodec.BufferInfo()
        var sentConfig = false
        while (running) {
            val read = recorder.read(pcm, 0, pcm.size)
            if (read > 0) {
                val inIndex = encoder.dequeueInputBuffer(8_000)
                if (inIndex >= 0) {
                    val buf = encoder.getInputBuffer(inIndex)
                    buf?.clear()
                    buf?.put(pcm, 0, read)
                    val pts = System.nanoTime() / 1000
                    encoder.queueInputBuffer(inIndex, 0, read, pts, 0)
                }
            }
            while (true) {
                val outIndex = encoder.dequeueOutputBuffer(info, 0)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val csd = encoder.outputFormat.getByteBuffer("csd-0")
                    if (csd != null) {
                        val bytes = ByteArray(csd.remaining())
                        csd.get(bytes)
                        onConfig(bytes, SAMPLE_RATE, 2)
                        sentConfig = true
                    }
                } else if (outIndex >= 0) {
                    val buf = encoder.getOutputBuffer(outIndex)
                    if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        if (!sentConfig) {
                            val csd = encoder.outputFormat.getByteBuffer("csd-0")
                            if (csd != null) {
                                val bytes = ByteArray(csd.remaining())
                                csd.duplicate().get(bytes)
                                onConfig(bytes, SAMPLE_RATE, 2)
                                sentConfig = true
                            }
                        }
                        val data = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.get(data)
                        onFrame(data, info.presentationTimeUs)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                } else {
                    break
                }
            }
        }
    }

    private fun release() {
        try {
            record?.stop()
        } catch (_: Throwable) {
        }
        try {
            record?.release()
        } catch (_: Throwable) {
        }
        record = null
        try {
            codec?.stop()
        } catch (_: Throwable) {
        }
        try {
            codec?.release()
        } catch (_: Throwable) {
        }
        codec = null
    }

    companion object {
        private const val TAG = "RetroBitAudio"
        const val SAMPLE_RATE = 48_000
    }
}
