package dev.retrobit.mirror.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import dev.retrobit.mirror.model.QualityPreset
import dev.retrobit.mirror.util.DisplayInfo
import java.nio.ByteBuffer

/**
 * Encodes the single MediaProjection surface. Android 14+ allows only one
 * VirtualDisplay per consent, so this class never creates one itself.
 */
class ScreenEncoder(
    private var displayW: Int,
    private var displayH: Int,
    private val preset: QualityPreset,
    private val onAttach: (Surface, Int, Int) -> Unit,
    private val onDetach: () -> Unit,
    private val onConfig: (width: Int, height: Int, csd: ByteArray) -> Unit,
    private val onFrame: (data: ByteArray, keyframe: Boolean, ptsUs: Long) -> Unit,
    private val onError: (String) -> Unit
) {
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var thread: Thread? = null

    @Volatile
    var running = false
        private set

    var width = 0
        private set
    var height = 0
        private set

    private var csd = ByteArray(0)
    private var basePts = 0L

    fun start() {
        if (running) return
        running = true
        Handler(Looper.getMainLooper()).post { boot() }
    }

    fun stop() {
        running = false
        try {
            codec?.signalEndOfInputStream()
        } catch (_: Throwable) {
        }
        val worker = thread
        if (worker != null && worker !== Thread.currentThread()) worker.join(800)
        if (Looper.myLooper() == Looper.getMainLooper()) onDetach()
        else Handler(Looper.getMainLooper()).post { onDetach() }
        releaseCodec()
    }

    fun requestSync() {
        try {
            val bundle = Bundle()
            bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec?.setParameters(bundle)
        } catch (_: Throwable) {
        }
    }

    fun restart(newW: Int, newH: Int) {
        displayW = newW
        displayH = newH
        stop()
        start()
    }

    private fun boot() {
        if (!running) return
        try {
            openEncoder()
        } catch (t: Throwable) {
            Log.e(TAG, "encoder", t)
            running = false
            onError(t.message ?: "Encoder gagal")
            onDetach()
            releaseCodec()
            return
        }
        if (!running) return
        thread = Thread({
            try {
                drain()
            } catch (t: Throwable) {
                Log.e(TAG, "encoder", t)
                if (running) onError(t.message ?: "Encoder gagal")
            } finally {
                releaseCodec()
            }
        }, "retrobit-h264").also { it.start() }
    }

    private fun openEncoder() {
        val fitted = DisplayInfo.evenFit(displayW, displayH, preset.maxSide)
        val aligned = (fitted.first / 16 * 16).coerceAtLeast(16) to (fitted.second / 16 * 16).coerceAtLeast(16)
        var last: Throwable? = null
        for (size in listOf(fitted, aligned).distinct()) {
            try {
                startCodec(size.first, size.second)
                return
            } catch (t: Throwable) {
                last = t
                Log.w(TAG, "size ${size.first}x${size.second} rejected: ${t.message}")
                releaseCodec()
            }
        }
        throw last ?: IllegalStateException("Encoder gagal")
    }

    private fun startCodec(w: Int, h: Int) {
        width = w
        height = h
        val bitrate = (preset.bpp * width * height * preset.fps).toInt().coerceIn(900_000, 16_000_000)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        this.codec = codec
        try {
            codec.configure(format(width, height, preset.fps, bitrate, vendor = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (t: Throwable) {
            Log.w(TAG, "vendor format rejected: ${t.message}")
            codec.configure(format(width, height, preset.fps, bitrate, vendor = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val input = codec.createInputSurface()
        surface = input
        codec.start()
        onAttach(input, width, height)
        Log.i(TAG, "encoder ${width}x$height @${preset.fps} ${bitrate / 1000}kbps")
    }

    private fun drain() {
        val codec = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 12_000)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = codec.outputFormat
                    val sps = fmt.getByteBuffer("csd-0")
                    val pps = fmt.getByteBuffer("csd-1")
                    if (sps != null) {
                        csd = if (pps != null) concat(bytes(sps), bytes(pps)) else bytes(sps)
                        onConfig(width, height, csd)
                    }
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        val data = bytes(buffer, info.offset, info.size)
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (config) {
                            csd = data
                            onConfig(width, height, csd)
                        } else {
                            if (basePts == 0L) basePts = info.presentationTimeUs
                            val pts = (info.presentationTimeUs - basePts).coerceAtLeast(0)
                            val payload = if (key && csd.isNotEmpty() && nalType(data) != 7) concat(csd, data) else data
                            onFrame(payload, key, pts)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    private fun releaseCodec() {
        try {
            codec?.stop()
        } catch (_: Throwable) {
        }
        try {
            codec?.release()
        } catch (_: Throwable) {
        }
        codec = null
        try {
            surface?.release()
        } catch (_: Throwable) {
        }
        surface = null
    }

    private fun format(w: Int, h: Int, fps: Int, bitrate: Int, vendor: Boolean): MediaFormat {
        return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            if (Build.VERSION.SDK_INT >= 23) setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= 30) {
                setInteger(MediaFormat.KEY_LATENCY, 0)
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            if (vendor) {
                runCatching { setInteger("prepend-sps-pps-to-idr-frames", 1) }
                runCatching { setInteger("vendor.qti-ext-enc-low-latency.enable", 1) }
            }
        }
    }

    companion object {
        private const val TAG = "RetroBitEnc"

        private fun bytes(buffer: ByteBuffer, offset: Int = buffer.position(), size: Int = buffer.remaining()): ByteArray {
            val dup = buffer.duplicate()
            dup.position(offset)
            dup.limit(offset + size)
            val out = ByteArray(size)
            dup.get(out)
            return out
        }

        private fun concat(a: ByteArray, b: ByteArray): ByteArray {
            val out = ByteArray(a.size + b.size)
            System.arraycopy(a, 0, out, 0, a.size)
            System.arraycopy(b, 0, out, a.size, b.size)
            return out
        }

        private fun nalType(data: ByteArray): Int {
            var i = 0
            if (data.size > 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) {
                i = 4
            } else if (data.size > 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
                i = 3
            }
            if (i >= data.size) return -1
            return data[i].toInt() and 0x1F
        }
    }
}
