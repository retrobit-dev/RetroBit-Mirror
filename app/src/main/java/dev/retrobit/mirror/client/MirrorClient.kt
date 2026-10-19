package dev.retrobit.mirror.client

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import dev.retrobit.mirror.model.FoundHost
import dev.retrobit.mirror.net.DiscoveryListener
import dev.retrobit.mirror.net.MuxWriter
import dev.retrobit.mirror.net.tune
import dev.retrobit.mirror.protocol.FrameParser
import dev.retrobit.mirror.protocol.Proto
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

data class ClientStats(
    val phase: String = "idle",
    val detail: String = "",
    val pingMs: Int = -1,
    val fps: Int = 0,
    val videoW: Int = 1224,
    val videoH: Int = 2720,
    val injection: String = "unknown",
    val hostName: String = ""
)

object HardwareInput {
    @Volatile
    var captureKeyboard: Boolean = true

    @Volatile
    var onKey: ((action: Int, code: Int, meta: Int, gamepad: Boolean) -> Unit)? = null

    @Volatile
    var onAxes: ((lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float, hatX: Float, hatY: Float) -> Unit)? = null
}

class MirrorClient {
    val stats = MutableStateFlow(ClientStats())
    val hosts = MutableStateFlow<List<FoundHost>>(emptyList())
    val jpeg = MutableStateFlow<ByteArray?>(null)

    private var listener: DiscoveryListener? = null
    private var socket: Socket? = null
    private var writer: MuxWriter? = null
    private var readThread: Thread? = null
    private val running = AtomicBoolean(false)
    private var decoder: VideoDecoder? = null
    private var audio: PcmAudio? = null
    private var surface: Surface? = null
    private val pending = ArrayList<JSONObject>()
    private val flushHandler = Handler(android.os.Looper.getMainLooper())
    private val flush = Runnable { flushNow() }
    private var frames = 0
    private var lastFpsTick = 0L
    private var jpegPull: JpegPull? = null

    fun startScan(context: android.content.Context) {
        stopScan()
        listener = DiscoveryListener(context) { name, host, tcp, http ->
            val now = SystemClock.elapsedRealtime()
            val current = hosts.value.filter { now - it.seenAt < 4500 }.toMutableList()
            val idx = current.indexOfFirst { it.host == host && it.tcpPort == tcp }
            val found = FoundHost(name, host, tcp, http, now)
            if (idx >= 0) current[idx] = found else current += found
            hosts.value = current.sortedBy { it.name }
        }.also { it.start() }
    }

    fun stopScan() {
        listener?.stop()
        listener = null
    }

    fun connect(host: String, port: Int, pin: String, name: String) {
        disconnect()
        running.set(true)
        stats.value = stats.value.copy(phase = "connecting", detail = "Menyambung ke $host…", pingMs = -1)
        readThread = Thread({
            try {
                val socket = Socket()
                socket.tune()
                socket.connect(InetSocketAddress(host, port), 5000)
                socket.soTimeout = 12_000
                this.socket = socket
                val writer = MuxWriter(socket.getOutputStream()) { running.set(false) }
                this.writer = writer
                val hello = JSONObject().put("name", name).put("pin", pin).put("client", "native").toString()
                writer.sendControl(Proto.encodeUtf(Proto.HELLO, 0, hello))
                val parser = FrameParser()
                val scratch = ByteArray(32 * 1024)
                val input = socket.getInputStream()
                var welcomed = false
                while (running.get() && !socket.isClosed) {
                    val n = try {
                        input.read(scratch)
                    } catch (_: java.net.SocketTimeoutException) {
                        if (!welcomed) break
                        continue
                    }
                    if (n < 0) break
                    parser.append(scratch, 0, n)
                    for (frame in parser.drain()) {
                        when (frame.type) {
                            Proto.WELCOME -> {
                                val json = JSONObject(String(frame.payload, Charsets.UTF_8))
                                if (!json.optBoolean("ok")) {
                                    stats.value = stats.value.copy(phase = "error", detail = "PIN salah atau host menolak")
                                    running.set(false)
                                } else {
                                    welcomed = true
                                    stats.value = stats.value.copy(
                                        phase = "play",
                                        detail = "",
                                        hostName = json.optString("name", host),
                                        videoW = json.optInt("videoW", stats.value.videoW).coerceAtLeast(2),
                                        videoH = json.optInt("videoH", stats.value.videoH).coerceAtLeast(2),
                                        injection = json.optString("injection", "unknown")
                                    )
                                    ensureDecoder()
                                    startJpeg(host, json.optInt("httpPort", 28731), pin)
                                }
                            }
                            Proto.STATE -> {
                                val json = JSONObject(String(frame.payload, Charsets.UTF_8))
                                val w = json.optInt("videoW", 0)
                                val h = json.optInt("videoH", 0)
                                stats.value = stats.value.copy(
                                    injection = json.optString("injection", stats.value.injection),
                                    videoW = if (w > 0) w else stats.value.videoW,
                                    videoH = if (h > 0) h else stats.value.videoH
                                )
                            }
                            Proto.VIDEO_CONFIG -> {
                                ensureDecoder()
                                decoder?.configure(frame.payload, stats.value.videoW, stats.value.videoH)
                            }
                            Proto.VIDEO -> {
                                val key = frame.flags and Proto.FLAG_KEY != 0
                                if (frame.flags and Proto.FLAG_JPEG != 0) {
                                    stats.value = stats.value.copy(detail = "Mode JPEG")
                                } else {
                                    decoder?.queue(frame.payload, key)
                                }
                                frames++
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastFpsTick >= 1000) {
                                    stats.value = stats.value.copy(fps = frames)
                                    frames = 0
                                    lastFpsTick = now
                                }
                            }
                            Proto.AUDIO_CONFIG -> audio?.configure(frame.payload)
                            Proto.AUDIO -> audio?.queue(frame.payload)
                            Proto.PONG -> {
                                if (frame.payload.size >= 8) {
                                    val sent = ByteBuffer.wrap(frame.payload).long
                                    val rtt = (System.nanoTime() - sent) / 1_000_000
                                    stats.value = stats.value.copy(pingMs = rtt.toInt().coerceIn(0, 9999))
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "connect", t)
                if (stats.value.phase != "error") {
                    stats.value = stats.value.copy(phase = "error", detail = t.message ?: "Sambungan putus")
                }
            } finally {
                running.set(false)
                if (stats.value.phase == "play" || stats.value.phase == "connecting") {
                    stats.value = stats.value.copy(phase = "idle", detail = "Terputus")
                }
                closeSocket()
            }
        }, "retrobit-client").also { it.start() }
        Thread({
            while (running.get()) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
                val buf = ByteBuffer.allocate(8).putLong(System.nanoTime()).array()
                writer?.sendControl(Proto.encode(Proto.PING, 0, buf))
            }
        }, "retrobit-ping").start()
    }

    fun disconnect() {
        running.set(false)
        closeSocket()
        decoder?.release()
        decoder = null
        audio?.release()
        audio = null
        jpegPull?.stop()
        jpegPull = null
        jpeg.value = null
        if (stats.value.phase != "error") stats.value = stats.value.copy(phase = "idle", fps = 0, pingMs = -1)
    }

    fun attachSurface(surface: Surface) {
        this.surface = surface
        decoder?.setSurface(surface)
    }

    fun detachSurface() {
        surface = null
        decoder?.setSurface(null)
    }

    fun requestKeyframe() {
        sendJson(JSONObject().put("type", "keyframe"), urgent = true)
    }

    fun sendPointer(id: Int, action: String, x: Float, y: Float) {
        val event = JSONObject()
            .put("type", "pointer")
            .put("action", action)
            .put("id", id)
            .put("x", x.toDouble())
            .put("y", y.toDouble())
        sendJson(event, urgent = action != "move")
    }

    fun sendKey(action: String, code: Int, meta: Int = 0, gamepad: Boolean = false) {
        val event = JSONObject()
            .put("type", "key")
            .put("action", action)
            .put("code", code)
            .put("meta", meta)
            .put("source", if (gamepad) "gamepad" else "keyboard")
        sendJson(event, urgent = true)
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        sendJson(JSONObject().put("type", "text").put("text", text), urgent = true)
    }

    fun sendNav(which: String) {
        sendJson(JSONObject().put("type", "nav").put("which", which), urgent = true)
    }

    private fun sendJson(event: JSONObject, urgent: Boolean) {
        synchronized(pending) { pending += event }
        flushHandler.removeCallbacks(flush)
        if (urgent) flushNow() else flushHandler.postDelayed(flush, 8)
    }

    private fun flushNow() {
        val batch = synchronized(pending) {
            if (pending.isEmpty()) return
            JSONArray().also { arr -> pending.forEach { arr.put(it) }; pending.clear() }
        }
        val json = JSONObject().put("events", batch).toString()
        writer?.sendControl(Proto.encodeUtf(Proto.INPUT, 0, json))
    }

    private fun startJpeg(host: String, port: Int, pin: String) {
        if (jpegPull != null || port <= 0) return
        jpegPull = JpegPull(host, port, pin) { bytes -> jpeg.value = bytes }.also { it.start() }
    }

    private fun ensureDecoder() {
        if (decoder == null) {
            val video = VideoDecoder()
            surface?.let { video.setSurface(it) }
            decoder = video
        }
        if (audio == null && stats.value.phase == "play") {
            audio = PcmAudio()
        }
    }

    private fun closeSocket() {
        try {
            writer?.close()
        } catch (_: Throwable) {
        }
        writer = null
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
    }

    companion object {
        private const val TAG = "RetroBitClient"
    }
}

private fun splitNals(data: ByteArray): List<ByteArray> {
    val starts = ArrayList<Int>()
    var i = 0
    while (i < data.size - 3) {
        val long = i + 3 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
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
        val end = if (index + 1 < starts.size) {
            var e = starts[index + 1]
            while (e > begin && data[e - 1] == 0.toByte()) e--
            e
        } else {
            data.size
        }
        data.copyOfRange(begin, end.coerceAtLeast(begin))
    }.filter { it.isNotEmpty() }
}

private fun stripStart(data: ByteArray): ByteArray {
    if (data.size > 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) {
        return data.copyOfRange(4, data.size)
    }
    if (data.size > 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
        return data.copyOfRange(3, data.size)
    }
    return data
}

class VideoDecoder {
    private val thread = HandlerThread("retrobit-dec").also { it.start() }
    private val handler = Handler(thread.looper)
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var configured = false
    private var csd: ByteArray? = null
    private var width = 1280
    private var height = 720

    fun setSurface(surface: Surface?) {
        handler.post {
            this.surface = surface
            if (surface != null && csd != null) configure(csd!!, width, height)
        }
    }

    fun configure(config: ByteArray, w: Int, h: Int) {
        handler.post {
            csd = config
            if (w > 0) width = w
            if (h > 0) height = h
            val target = surface ?: return@post
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            val nals = splitNals(config)
            val sps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
            val pps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps ?: stripStart(config)))
            if (pps != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            try {
                decoder.configure(format, target, null, 0)
            } catch (t: Throwable) {
                Log.w("RetroBitDec", "configure ${t.message}")
                try {
                    decoder.release()
                } catch (_: Throwable) {
                }
                val retry = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val plain = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
                plain.setByteBuffer("csd-0", ByteBuffer.wrap(sps ?: stripStart(config)))
                if (pps != null) plain.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                retry.configure(plain, target, null, 0)
                retry.start()
                codec = retry
                configured = true
                return@post
            }
            decoder.start()
            codec = decoder
            configured = true
        }
    }

    private var nextPts = 0L

    fun queue(data: ByteArray, keyframe: Boolean) {
        handler.post {
            val decoder = codec ?: return@post
            if (!configured) return@post
            try {
                val index = decoder.dequeueInputBuffer(8_000)
                if (index >= 0) {
                    val buf = decoder.getInputBuffer(index)
                    buf?.clear()
                    buf?.put(data)
                    val flags = if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    nextPts += 33_333
                    decoder.queueInputBuffer(index, 0, data.size, nextPts, flags)
                }
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val out = decoder.dequeueOutputBuffer(info, 0)
                    if (out >= 0) decoder.releaseOutputBuffer(out, true)
                    else break
                }
            } catch (t: Throwable) {
                Log.w("RetroBitDec", t.message ?: "decode")
                configured = false
            }
        }
    }

    fun release() {
        handler.post {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            codec = null
            configured = false
        }
        thread.quitSafely()
    }
}

class PcmAudio {
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var ready = false

    @Synchronized
    fun configure(payload: ByteArray) {
        try {
            releaseLocked()
            var csd = payload
            if (payload.size > 2) {
                val headerLen = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                if (headerLen in 2 until payload.size - 1) {
                    csd = payload.copyOfRange(2 + headerLen, payload.size)
                }
            }
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            decoder.configure(format, null, null, 0)
            decoder.start()
            codec = decoder
            val min = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack(
                AudioManager.STREAM_MUSIC,
                48000,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                min.coerceAtLeast(4096) * 2,
                AudioTrack.MODE_STREAM
            )
            track?.play()
            ready = true
        } catch (t: Throwable) {
            Log.w("RetroBitAudio", "client ${t.message}")
            ready = false
        }
    }

    @Synchronized
    fun queue(data: ByteArray) {
        val decoder = codec ?: return
        if (!ready) return
        try {
            val index = decoder.dequeueInputBuffer(4_000)
            if (index >= 0) {
                decoder.getInputBuffer(index)?.apply {
                    clear()
                    put(data)
                }
                decoder.queueInputBuffer(index, 0, data.size, System.nanoTime() / 1000, 0)
            }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val out = decoder.dequeueOutputBuffer(info, 0)
                if (out >= 0) {
                    val buf = decoder.getOutputBuffer(out)
                    if (buf != null && info.size > 0) {
                        val pcm = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.get(pcm)
                        track?.write(pcm, 0, pcm.size)
                    }
                    decoder.releaseOutputBuffer(out, false)
                } else break
            }
        } catch (_: Throwable) {
        }
    }

    fun release() {
        synchronized(this) { releaseLocked() }
    }

    private fun releaseLocked() {
        ready = false
        try {
            track?.stop()
        } catch (_: Throwable) {
        }
        try {
            track?.release()
        } catch (_: Throwable) {
        }
        track = null
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
}
