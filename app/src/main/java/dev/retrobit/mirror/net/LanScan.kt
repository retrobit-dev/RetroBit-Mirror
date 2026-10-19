package dev.retrobit.mirror.net

import android.util.Log
import dev.retrobit.mirror.util.NetInfo
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Finds a host when UDP broadcast is blocked but unicast still works. */
class LanScan(private val onHost: (name: String, host: String, tcp: Int, http: Int) -> Unit) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({
            while (running.get()) {
                try {
                    sweep()
                } catch (t: Throwable) {
                    Log.w(TAG, "scan ${t.message}")
                }
                try {
                    Thread.sleep(12_000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "retrobit-scan").also { it.start() }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun sweep() {
        val local = NetInfo.addresses().firstOrNull { it.kind == "wifi" || it.kind == "hotspot" } ?: return
        val prefix = local.ip.substringBeforeLast('.', "")
        if (prefix.isBlank()) return
        val pool = Executors.newFixedThreadPool(24)
        try {
            for (host in 1..254) {
                if (!running.get()) break
                val ip = "$prefix.$host"
                if (ip == local.ip) continue
                pool.execute {
                    if (!running.get()) return@execute
                    for (port in 28731..28736) {
                        val found = probe(ip, port) ?: continue
                        onHost(found.first, ip, found.second, port)
                        return@execute
                    }
                }
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(8, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun probe(ip: String, port: Int): Pair<String, Int>? {
        val conn = URL("http://$ip:$port/api/hello").openConnection() as HttpURLConnection
        conn.connectTimeout = 160
        conn.readTimeout = 160
        conn.instanceFollowRedirects = false
        return try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            val json = JSONObject(body)
            if (!json.optBoolean("ok")) return null
            val name = json.optString("name", "GT 30 Pro").ifBlank { "GT 30 Pro" }
            val tcp = json.optInt("tcp", 28730)
            name to tcp
        } catch (_: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "RetroBitScan"
    }
}
