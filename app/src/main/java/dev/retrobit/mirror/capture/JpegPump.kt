package dev.retrobit.mirror.capture

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * JPEG for browsers that cannot decode H.264. Built by decoding the one
 * capture stream, because Android 14+ rejects a second VirtualDisplay.
 */
class JpegPump {
    private val clients = AtomicInteger(0)
    private val lock = Any()
    private var decoder: MediaCodec? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var configuredW = 0
    private var configuredH = 0
    private var seenKey = false
    private var lastOffer = 0L
    private var failedAt = 0L
    private val draining = AtomicBoolean(false)
    private var stopLater: Runnable? = null

    var onNeedKey: (() -> Unit)? = null

    val latest = AtomicReference<ByteArray?>(null)

    @Volatile
    var generation = 0

    fun retain() {
        stopLater?.let { handler?.removeCallbacks(it) }
        if (clients.incrementAndGet() == 1) {
            seenKey = false
            onNeedKey?.invoke()
        }
    }

    fun release() {
        if (clients.decrementAndGet() > 0) return
        clients.set(0)
        val task = Runnable {
            if (clients.get() <= 0) stop()
        }
        stopLater = task
        val h = handler
        if (h != null) h.postDelayed(task, 2_000) else stop()
    }

    fun offer(csd: ByteArray, data: ByteArray, keyframe: Boolean, width: Int, height: Int) {
        if (clients.get() <= 0 || csd.isEmpty() || data.isEmpty() || width < 2 || height < 2) return
        if (android.os.SystemClock.elapsedRealtime() - failedAt < 3_000) return
        if (keyframe) seenKey = true
        if (!seenKey) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!keyframe && now - lastOffer < 110) return
        lastOffer = now
        synchronized(lock) {
            try {
                ensure(csd, width, height)
                val codec = decoder ?: return
                val index = codec.dequeueInputBuffer(0)
                if (index < 0) return
                val buf = codec.getInputBuffer(index) ?: return
                buf.clear()
                if (data.size > buf.capacity()) return
                buf.put(data)
                val flags = if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                codec.queueInputBuffer(index, 0, data.size, now * 1000, flags)
            } catch (t: Throwable) {
                Log.w(TAG, "offer ${t.message}")
                failedAt = android.os.SystemClock.elapsedRealtime()
                stopLocked()
                seenKey = false
            }
        }
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun ensure(csd: ByteArray, width: Int, height: Int) {
        if (decoder != null && configuredW == width && configuredH == height) return
        stopLocked()
        var last: Throwable? = null
        for (fmt in intArrayOf(ImageFormat.YUV_420_888, PixelFormat.RGBA_8888)) {
            try {
                startDecoder(csd, width, height, fmt)
                return
            } catch (t: Throwable) {
                last = t
                Log.w(TAG, "jpeg format $fmt ${t.message}")
                stopLocked()
            }
        }
        throw last ?: IllegalStateException("Decoder JPEG gagal")
    }

    private fun startDecoder(csd: ByteArray, width: Int, height: Int, imageFormat: Int) {
        val worker = HandlerThread("retrobit-jpeg")
        worker.start()
        val h = Handler(worker.looper)
        thread = worker
        handler = h
        val imageReader = ImageReader.newInstance(width, height, imageFormat, 3)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ r ->
            val image = try {
                r.acquireLatestImage()
            } catch (_: Throwable) {
                null
            } ?: return@setOnImageAvailableListener
            try {
                val jpeg = imageToJpeg(image, 46)
                if (jpeg != null && jpeg.size > 32) {
                    latest.set(jpeg)
                    generation++
                }
            } catch (t: Throwable) {
                Log.w(TAG, "jpeg ${t.message}")
            } finally {
                image.close()
            }
        }, h)
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        applyCsd(format, csd)
        codec.configure(format, imageReader.surface, null, 0)
        codec.start()
        decoder = codec
        configuredW = width
        configuredH = height
        draining.set(true)
        Thread({
            val info = MediaCodec.BufferInfo()
            while (draining.get()) {
                try {
                    val out = codec.dequeueOutputBuffer(info, 40_000)
                    if (out >= 0) codec.releaseOutputBuffer(out, true)
                } catch (_: Throwable) {
                    break
                }
            }
        }, "retrobit-jpeg-drain").start()
        Log.i(TAG, "jpeg decoder ${width}x$height format=$imageFormat")
    }

    private fun imageToJpeg(image: Image, quality: Int): ByteArray? {
        return if (image.planes.size >= 3) yuvToJpeg(image, quality) else rgbaToJpeg(image, quality)
    }

    private fun rgbaToJpeg(image: Image, quality: Int): ByteArray? {
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        val pixelStride = plane.pixelStride
        if (pixelStride <= 0) return null
        val rowStride = plane.rowStride
        val padded = rowStride / pixelStride
        val bitmap = Bitmap.createBitmap(padded, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        val cropped = if (padded == image.width) bitmap else Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        val out = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (cropped !== bitmap) cropped.recycle()
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun stopLocked() {
        draining.set(false)
        val codec = decoder
        decoder = null
        configuredW = 0
        configuredH = 0
        try {
            codec?.stop()
        } catch (_: Throwable) {
        }
        try {
            codec?.release()
        } catch (_: Throwable) {
        }
        try {
            reader?.close()
        } catch (_: Throwable) {
        }
        reader = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun applyCsd(format: MediaFormat, csd: ByteArray) {
        val nals = splitNals(csd)
        val sps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
        val pps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
        format.setByteBuffer("csd-0", ByteBuffer.wrap(sps ?: csd))
        if (pps != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
    }

    private fun yuvToJpeg(image: Image, quality: Int): ByteArray? {
        if (image.planes.size < 3) return null
        val width = image.width
        val height = image.height
        val ySize = width * height
        val nv21 = ByteArray(ySize + width * height / 2)
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val yBuf = y.buffer.duplicate()
        val uBuf = u.buffer.duplicate()
        val vBuf = v.buffer.duplicate()
        var dst = 0
        val yPix = y.pixelStride.coerceAtLeast(1)
        if (yPix == 1) {
            for (row in 0 until height) {
                val start = row * y.rowStride
                if (start + width > yBuf.limit()) return null
                yBuf.position(start)
                yBuf.get(nv21, dst, width)
                dst += width
            }
        } else {
            for (row in 0 until height) {
                var src = row * y.rowStride
                for (col in 0 until width) {
                    if (src >= yBuf.limit()) return null
                    nv21[dst++] = yBuf.get(src)
                    src += yPix
                }
            }
        }
        val chromaH = height / 2
        val chromaW = width / 2
        val uPix = u.pixelStride.coerceAtLeast(1)
        val vPix = v.pixelStride.coerceAtLeast(1)
        for (row in 0 until chromaH) {
            for (col in 0 until chromaW) {
                val vi = row * v.rowStride + col * vPix
                val ui = row * u.rowStride + col * uPix
                if (vi >= vBuf.limit() || ui >= uBuf.limit() || dst + 1 >= nv21.size) return null
                nv21[dst++] = vBuf.get(vi)
                nv21[dst++] = uBuf.get(ui)
            }
        }
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        if (!yuv.compressToJpeg(Rect(0, 0, width, height), quality, out)) return null
        return out.toByteArray()
    }

    private fun splitNals(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i < data.size - 3) {
            val long = i + 3 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            val short = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            if (long || short) {
                starts += i + if (long) 4 else 3
                i += if (long) 4 else 3
            } else {
                i++
            }
        }
        if (starts.isEmpty()) return emptyList()
        return starts.mapIndexed { index, begin ->
            var end = if (index + 1 < starts.size) starts[index + 1] else data.size
            while (end > begin && data[end - 1] == 0.toByte()) end--
            data.copyOfRange(begin, end.coerceAtLeast(begin))
        }.filter { it.isNotEmpty() }
    }

    companion object {
        private const val TAG = "RetroBitJpeg"
    }
}
