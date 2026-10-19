package dev.retrobit.mirror.net

import android.util.Log
import dev.retrobit.mirror.host.HostRuntime
import dev.retrobit.mirror.host.HostSession
import dev.retrobit.mirror.host.StreamSink
import dev.retrobit.mirror.input.InputHub
import dev.retrobit.mirror.protocol.FrameParser
import dev.retrobit.mirror.protocol.Proto
import dev.retrobit.mirror.protocol.pinEquals
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

class NativeServer(private val session: HostSession) {
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newCachedThreadPool()

    @Volatile
    var port = Proto.TCP
        private set

    @Volatile
    var running = false
        private set

    fun start(preferred: Int = Proto.TCP): Int {
        val socket = ServerSocket()
        socket.reuseAddress = true
        var bound = -1
        for (candidate in preferred until preferred + 12) {
            try {
                socket.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("0.0.0.0"), candidate))
                bound = candidate
                break
            } catch (_: Throwable) {
            }
        }
        if (bound < 0) error("Port native penuh")
        server = socket
        port = bound
        running = true
        acceptThread = Thread({
            while (running) {
                try {
                    val client = socket.accept()
                    pool.execute { greet(client) }
                } catch (t: Throwable) {
                    if (running) Log.w(TAG, "accept ${t.message}")
                }
            }
        }, "retrobit-tcp").also { it.start() }
        return bound
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Throwable) {
        }
        pool.shutdownNow()
    }

    private fun greet(socket: Socket) {
        var sink: TcpSink? = null
        try {
            socket.tune()
            socket.soTimeout = 12_000
            val parser = FrameParser()
            val scratch = ByteArray(16 * 1024)
            val input = socket.getInputStream()
            val writer = MuxWriter(socket.getOutputStream()) { socket.close() }
            var hello: JSONObject? = null
            val deadline = System.currentTimeMillis() + 8_000
            while (hello == null && System.currentTimeMillis() < deadline && running) {
                val n = try {
                    input.read(scratch)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (n < 0) break
                parser.append(scratch, 0, n)
                for (frame in parser.drain()) {
                    if (frame.type == Proto.HELLO) {
                        hello = JSONObject(String(frame.payload, Charsets.UTF_8))
                        break
                    }
                }
            }
            val pin = hello?.optString("pin").orEmpty()
            val name = hello?.optString("name").orEmpty().ifBlank { "Klien" }.take(40)
            if (hello == null || !pinEquals(pin, session.pin)) {
                writer.sendControl(Proto.encodeUtf(Proto.WELCOME, 0, session.welcome(false, "pin")))
                Thread.sleep(80)
                writer.close()
                socket.close()
                return
            }
            writer.sendControl(Proto.encodeUtf(Proto.WELCOME, 0, session.welcome(true)))
            writer.sendControl(Proto.encodeUtf(Proto.STATE, 0, session.stateJson()))
            sink = TcpSink(name, writer)
            session.addSink(sink)
            HostRuntime.session?.requestKey()
            noteClients()
            socket.soTimeout = 10_000
            while (running && !socket.isClosed) {
                val n = try {
                    input.read(scratch)
                } catch (_: SocketTimeoutException) {
                    break
                }
                if (n < 0) break
                parser.append(scratch, 0, n)
                for (frame in parser.drain()) {
                    when (frame.type) {
                        Proto.INPUT -> InputHub.handlePayload(String(frame.payload, Charsets.UTF_8))
                        Proto.PING -> writer.sendControl(Proto.encode(Proto.PONG, 0, frame.payload))
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "client ${t.message}")
        } finally {
            if (sink != null) session.removeSink(sink)
            noteClients()
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun noteClients() {
        val names = session.sinks.mapNotNull { (it as? TcpSink)?.name }
        val web = session.sinks.count { it is WebSink }
        val all = names + List(web) { "Browser" }
        HostRuntime.update { it.copy(clients = all) }
        session.publishState()
    }

    private class TcpSink(val name: String, private val writer: MuxWriter) : StreamSink {
        override fun onVideoConfig(width: Int, height: Int, csd: ByteArray) {
            writer.sendControl(Proto.encode(Proto.VIDEO_CONFIG, Proto.FLAG_KEY, csd))
        }

        override fun onVideo(data: ByteArray, keyframe: Boolean, ptsUs: Long) {
            val flags = if (keyframe) Proto.FLAG_KEY else 0
            writer.sendVideo(Proto.encode(Proto.VIDEO, flags, data), keyframe)
        }

        override fun onAudioConfig(csd: ByteArray, sampleRate: Int, channels: Int) {
            val header = JSONObject().put("rate", sampleRate).put("ch", channels).toString().toByteArray()
            val payload = ByteArray(2 + header.size + csd.size)
            payload[0] = (header.size shr 8).toByte()
            payload[1] = header.size.toByte()
            System.arraycopy(header, 0, payload, 2, header.size)
            System.arraycopy(csd, 0, payload, 2 + header.size, csd.size)
            writer.sendControl(Proto.encode(Proto.AUDIO_CONFIG, 0, payload))
        }

        override fun onAudio(data: ByteArray, ptsUs: Long) {
            writer.sendControl(Proto.encode(Proto.AUDIO, 0, data))
        }

        override fun onState(json: String) {
            writer.sendControl(Proto.encodeUtf(Proto.STATE, 0, json))
        }
    }

    companion object {
        private const val TAG = "RetroBitTcp"
    }
}

class WebSink(private val sendText: (String) -> Unit, private val sendBinary: (ByteArray, Boolean) -> Unit) : StreamSink {
    override fun onVideoConfig(width: Int, height: Int, csd: ByteArray) {
        val packet = ByteArray(1 + csd.size)
        packet[0] = 1
        System.arraycopy(csd, 0, packet, 1, csd.size)
        sendBinary(packet, true)
    }

    override fun onVideo(data: ByteArray, keyframe: Boolean, ptsUs: Long) {
        val packet = ByteArray(1 + data.size)
        packet[0] = if (keyframe) 2 else 3
        System.arraycopy(data, 0, packet, 1, data.size)
        sendBinary(packet, keyframe)
    }

    override fun onAudioConfig(csd: ByteArray, sampleRate: Int, channels: Int) = Unit
    override fun onAudio(data: ByteArray, ptsUs: Long) = Unit

    override fun onState(json: String) {
        sendText(json)
    }
}
