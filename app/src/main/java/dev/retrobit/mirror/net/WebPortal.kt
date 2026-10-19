package dev.retrobit.mirror.net

import android.content.Context
import android.util.Log
import dev.retrobit.mirror.capture.JpegPump
import dev.retrobit.mirror.host.HostRuntime
import dev.retrobit.mirror.host.HostSession
import dev.retrobit.mirror.input.InputHub
import dev.retrobit.mirror.protocol.Proto
import dev.retrobit.mirror.protocol.pinEquals
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WebPortal(
    private val context: Context,
    private val session: HostSession,
    private val jpeg: JpegPump
) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val assets = HashMap<String, Pair<String, ByteArray>>()

    @Volatile
    var port = Proto.HTTP
        private set

    @Volatile
    var running = false
        private set

    fun start(preferred: Int = Proto.HTTP): Int {
        loadAssets()
        val socket = ServerSocket()
        socket.reuseAddress = true
        var bound = -1
        for (candidate in preferred until preferred + 12) {
            try {
                socket.bind(InetSocketAddress(candidate))
                bound = candidate
                break
            } catch (_: Throwable) {
            }
        }
        if (bound < 0) error("Port web penuh")
        server = socket
        port = bound
        running = true
        Thread({
            while (running) {
                try {
                    val client = socket.accept()
                    pool.execute { handle(client) }
                } catch (t: Throwable) {
                    if (running) Log.w(TAG, "accept ${t.message}")
                }
            }
        }, "retrobit-http").start()
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

    private fun loadAssets() {
        fun put(name: String, type: String) {
            val bytes = context.assets.open("web/$name").use { it.readBytes() }
            assets[name] = type to bytes
        }
        put("index.html", "text/html; charset=utf-8")
        put("app.js", "text/javascript; charset=utf-8")
        put("style.css", "text/css; charset=utf-8")
    }

    private fun handle(socket: Socket) {
        socket.tune()
        socket.soTimeout = 12_000
        try {
            val input = socket.getInputStream()
            val output = BufferedOutputStream(socket.getOutputStream())
            val parsed = readHead(input) ?: return
            val (requestLine, headers) = parsed
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]
            val path = target.substringBefore("?")
            val query = query(target)
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (length in 1..65536) {
                val buf = ByteArray(length)
                input.readFully(buf)
                String(buf, Charsets.UTF_8)
            } else {
                ""
            }
            if (headers["upgrade"]?.equals("websocket", true) == true && path == "/ws") {
                upgrade(socket, input, output, headers, query["token"])
                return
            }
            when {
                method == "GET" && (path == "/" || path == "/index.html") -> write(output, 200, "OK", assets.getValue("index.html"))
                method == "GET" && path == "/app.js" -> write(output, 200, "OK", assets.getValue("app.js"))
                method == "GET" && path == "/style.css" -> write(output, 200, "OK", assets.getValue("style.css"))
                method == "POST" && path == "/api/auth" -> auth(output, body)
                method == "GET" && path == "/api/state" -> {
                    if (!session.tokenOk(query["token"])) {
                        write(output, 401, "Unauthorized", """{"ok":false}""".toByteArray(), "application/json")
                    } else {
                        write(output, 200, "OK", session.stateJson().toByteArray(), "application/json")
                    }
                }
                method == "GET" && path == "/frame.jpg" -> {
                    if (!session.tokenOk(query["token"])) {
                        write(output, 401, "Unauthorized", ByteArray(0), "text/plain")
                    } else {
                        jpeg.retain()
                        try {
                            val frame = waitJpeg()
                            if (frame == null) write(output, 503, "Unavailable", ByteArray(0), "text/plain")
                            else write(output, 200, "OK", frame, "image/jpeg")
                        } finally {
                            jpeg.release()
                        }
                    }
                }
                method == "GET" && path == "/stream.mjpeg" -> {
                    if (!session.tokenOk(query["token"])) {
                        write(output, 401, "Unauthorized", ByteArray(0), "text/plain")
                    } else {
                        streamMjpeg(output)
                    }
                }
                else -> write(output, 404, "Not Found", "tidak ada".toByteArray(), "text/plain")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "http ${t.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun auth(output: OutputStream, body: String) {
        val pin = try {
            JSONObject(body).optString("pin")
        } catch (_: Throwable) {
            ""
        }
        if (!pinEquals(pin, session.pin)) {
            write(output, 401, "Unauthorized", """{"ok":false,"error":"pin"}""".toByteArray(), "application/json")
            return
        }
        val token = session.issueToken()
        val json = JSONObject().put("ok", true).put("token", token).put("name", session.hostName).toString()
        write(output, 200, "OK", json.toByteArray(), "application/json")
    }

    private fun waitJpeg(): ByteArray? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 1500) {
            val frame = jpeg.latest.get()
            if (frame != null) return frame
            Thread.sleep(40)
        }
        return jpeg.latest.get()
    }

    private fun streamMjpeg(output: OutputStream) {
        jpeg.retain()
        try {
            val head = "HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
            output.write(head.toByteArray(Charsets.US_ASCII))
            output.flush()
            var last = -1
            var idle = 0
            while (running && idle < 400) {
                val gen = jpeg.generation
                val frame = jpeg.latest.get()
                if (frame != null && gen != last) {
                    val part = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                    output.write(part.toByteArray(Charsets.US_ASCII))
                    output.write(frame)
                    output.write("\r\n".toByteArray())
                    output.flush()
                    last = gen
                    idle = 0
                } else {
                    idle++
                }
                Thread.sleep(50)
            }
        } finally {
            jpeg.release()
        }
    }

    private fun upgrade(socket: Socket, input: InputStream, output: OutputStream, headers: Map<String, String>, token: String?) {
        if (!session.tokenOk(token)) {
            write(output, 401, "Unauthorized", ByteArray(0), "text/plain")
            return
        }
        val key = headers["sec-websocket-key"] ?: return
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.US_ASCII))
        )
        val response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
        output.write(response.toByteArray(Charsets.US_ASCII))
        output.flush()
        socket.soTimeout = 0
        val alive = AtomicBoolean(true)
        val writer = MuxWriter(output) {
            alive.set(false)
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
        fun text(json: String) {
            writer.sendControl(wsFrame(0x1, json.toByteArray(Charsets.UTF_8)))
        }
        fun binary(data: ByteArray, keyframe: Boolean) {
            writer.sendVideo(wsFrame(0x2, data), keyframe)
        }
        val sink = WebSink(::text, ::binary)
        session.addSink(sink)
        HostRuntime.update { it.copy(clients = it.clients + "Browser") }
        session.publishState()
        text(session.welcome(true))
        session.requestKey()
        try {
            while (alive.get() && running && !socket.isClosed) {
                val frame = readWs(input) ?: break
                val opcode = frame.first
                if (opcode == 0x8) break
                if (opcode == 0x9) {
                    writer.sendControl(wsFrame(0xA, frame.second))
                    continue
                }
                if (opcode == 0x1) {
                    val textBody = String(frame.second, Charsets.UTF_8)
                    val obj = try {
                        JSONObject(textBody)
                    } catch (_: Throwable) {
                        null
                    }
                    when (obj?.optString("type")) {
                        "ping" -> text(JSONObject().put("type", "pong").put("t", obj.optLong("t")).toString())
                        "keyframe" -> session.requestKey()
                        "view" -> InputHub.handlePayload(textBody)
                        "input" -> InputHub.handlePayload(textBody)
                        else -> if (obj?.has("events") == true) InputHub.handlePayload(textBody)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ws ${t.message}")
        } finally {
            alive.set(false)
            session.removeSink(sink)
            HostRuntime.update { it.copy(clients = it.clients.filterNot { name -> name == "Browser" }) }
            writer.close()
        }
    }

    private fun readHead(input: InputStream): Pair<String, Map<String, String>>? {
        val raw = ByteArrayOutputStream()
        while (raw.size() < 16384) {
            val b = input.read()
            if (b < 0) return null
            raw.write(b)
            val n = raw.size()
            if (n >= 4) {
                val arr = raw.toByteArray()
                if (arr[n - 4] == '\r'.code.toByte() && arr[n - 3] == '\n'.code.toByte() &&
                    arr[n - 2] == '\r'.code.toByte() && arr[n - 1] == '\n'.code.toByte()
                ) {
                    break
                }
            }
        }
        val text = raw.toString(Charsets.ISO_8859_1.name())
        val head = text.substringBefore("\r\n\r\n")
        val lines = head.split("\r\n")
        if (lines.isEmpty()) return null
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) headers[lines[i].substring(0, idx).trim().lowercase()] = lines[i].substring(idx + 1).trim()
        }
        return lines[0] to headers
    }

    private fun query(target: String): Map<String, String> {
        val q = target.substringAfter("?", "")
        if (q.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        q.split("&").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                out[java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8")] =
                    java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            }
        }
        return out
    }

    private fun write(output: OutputStream, code: Int, reason: String, body: Pair<String, ByteArray>) {
        write(output, code, reason, body.second, body.first)
    }

    private fun write(output: OutputStream, code: Int, reason: String, body: ByteArray, type: String = "application/octet-stream") {
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun readWs(input: InputStream): Pair<Int, ByteArray>? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) return null
        val opcode = b0 and 0x0F
        var len = b1 and 0x7F
        val masked = b1 and 0x80 != 0
        if (len == 126) {
            val hi = input.read()
            val lo = input.read()
            if (hi < 0 || lo < 0) return null
            len = (hi shl 8) or lo
        } else if (len == 127) {
            var value = 0L
            repeat(8) {
                val b = input.read()
                if (b < 0) return null
                value = (value shl 8) or b.toLong()
            }
            if (value > 2_000_000) return null
            len = value.toInt()
        }
        val mask = ByteArray(4)
        if (masked) input.readFully(mask)
        val payload = ByteArray(len)
        if (len > 0) input.readFully(payload)
        if (masked) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
        }
        return opcode to payload
    }

    private fun wsFrame(opcode: Int, data: ByteArray): ByteArray {
        val header = when {
            data.size <= 125 -> byteArrayOf((0x80 or opcode).toByte(), data.size.toByte())
            data.size <= 65535 -> byteArrayOf(
                (0x80 or opcode).toByte(),
                126.toByte(),
                (data.size shr 8).toByte(),
                data.size.toByte()
            )
            else -> byteArrayOf(
                (0x80 or opcode).toByte(),
                127.toByte(),
                0, 0, 0, 0,
                (data.size shr 24).toByte(),
                (data.size shr 16).toByte(),
                (data.size shr 8).toByte(),
                data.size.toByte()
            )
        }
        val out = ByteArray(header.size + data.size)
        System.arraycopy(header, 0, out, 0, header.size)
        System.arraycopy(data, 0, out, header.size, data.size)
        return out
    }

    private fun InputStream.readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = read(buf, off, buf.size - off)
            if (n < 0) throw java.io.EOFException()
            off += n
        }
    }

    companion object {
        private const val TAG = "RetroBitWeb"
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
