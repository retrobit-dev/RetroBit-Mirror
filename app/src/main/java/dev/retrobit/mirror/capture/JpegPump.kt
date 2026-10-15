package dev.retrobit.mirror.capture

import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.PixelFormat
import dev.retrobit.mirror.util.DisplayInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class JpegPump(
    private val projection: MediaProjection,
    private val displayW: Int,
    private val displayH: Int,
    private val dpi: Int
) {
    private val clients = AtomicInteger(0)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val busy = AtomicInteger(0)

    val latest = AtomicReference<ByteArray?>(null)

    @Volatile
    var generation = 0

    fun retain() {
        if (clients.incrementAndGet() == 1) start()
    }

    fun release() {
        if (clients.decrementAndGet() <= 0) {
            clients.set(0)
            stop()
        }
    }

    @Synchronized
    private fun start() {
        if (handler != null) return
        val fitted = DisplayInfo.evenFit(displayW, displayH, 960)
        val worker = HandlerThread("retrobit-jpeg")
        worker.start()
        thread = worker
        val h = Handler(worker.looper)
        handler = h
        val imageReader = ImageReader.newInstance(fitted.first, fitted.second, PixelFormat.RGBA_8888, 2)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ r ->
            if (!busy.compareAndSet(0, 1)) {
                r.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            var image = r.acquireLatestImage()
            try {
                if (image != null) {
                    val jpeg = toJpeg(image, 48)
                    image.close()
                    image = null
                    if (jpeg != null) {
                        latest.set(jpeg)
                        generation++
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "jpeg ${t.message}")
            } finally {
                image?.close()
                busy.set(0)
            }
        }, h)
        display = projection.createVirtualDisplay(
            "RetroBitJpeg",
            fitted.first,
            fitted.second,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            h
        )
    }

    @Synchronized
    private fun stop() {
        try {
            display?.release()
        } catch (_: Throwable) {
        }
        display = null
        try {
            reader?.close()
        } catch (_: Throwable) {
        }
        reader = null
        handler = null
        thread?.quitSafely()
        thread = null
    }

    private fun toJpeg(image: android.media.Image, quality: Int): ByteArray? {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        if (pixelStride <= 0) return null
        val rowStride = plane.rowStride
        val padded = rowStride / pixelStride
        val bitmap = Bitmap.createBitmap(padded, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        val cropped = if (padded == width) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, height)
        val out = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (cropped !== bitmap) cropped.recycle()
        bitmap.recycle()
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "RetroBitJpeg"
    }
}
