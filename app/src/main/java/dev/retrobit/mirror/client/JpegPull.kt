package dev.retrobit.mirror.client

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Polls the phone's JPEG frames when the tablet browser or decoder cannot show H.264. */
class JpegPull(
    private val host: String,
    private val port: Int,
    private val pin: String,
    private val onFrame: (ByteArray) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({
            try {
                val token = auth() ?: return@Thread
                while (running.get()) {
                    val frame = get("/frame.jpg?token=$token&t=${System.currentTimeMillis()}")
                    if (frame != null && frame.size > 32) onFrame(frame)
                    Thread.sleep(110)
                }
            } catch (_: Throwable) {
            }
        }, "retrobit-jpeg-pull").also { it.start() }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun auth(): String? {
        val conn = open("/api/auth")
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write("""{"pin":"$pin"}""".toByteArray()) }
        if (conn.responseCode != 200) return null
        val body = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        val token = JSONObject(body).optString("token")
        return token.ifBlank { null }
    }

    private fun get(path: String): ByteArray? {
        val conn = open(path)
        conn.requestMethod = "GET"
        if (conn.responseCode != 200) return null
        return conn.inputStream.use { it.readBytes() }
    }

    private fun open(path: String): HttpURLConnection {
        val conn = URL("http://$host:$port$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.useCaches = false
        return conn
    }
}
