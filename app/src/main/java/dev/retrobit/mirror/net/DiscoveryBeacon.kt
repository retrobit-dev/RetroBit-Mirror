package dev.retrobit.mirror.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dev.retrobit.mirror.protocol.Proto
import dev.retrobit.mirror.util.Locks
import dev.retrobit.mirror.util.NetInfo
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class DiscoveryBeacon(
    private val context: Context,
    private val name: String,
    private val tcpPort: Int,
    private val httpPort: Int
) {
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private var nsd: NsdManager? = null
    private var registered = false

    @Volatile
    var running = false
        private set

    private val registration = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            registered = true
        }

        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "nsd register $errorCode")
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) {
            registered = false
        }

        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
    }

    fun start() {
        if (running) return
        running = true
        val lock = Locks.multicast(context)
        try {
            lock.acquire()
        } catch (_: Throwable) {
        }
        socket = DatagramSocket().apply { broadcast = true }
        thread = Thread({
            val payload = "RETROBIT1\t${name.replace('\t', ' ')}\t$tcpPort\t$httpPort\n".toByteArray(Charsets.UTF_8)
            while (running) {
                try {
                    send(payload, "255.255.255.255")
                    val ip = NetInfo.ipv4()
                    val sub = ip?.let { NetInfo.subnetBroadcast(it) }
                    if (sub != null) send(payload, sub)
                } catch (t: Throwable) {
                    Log.w(TAG, "beacon ${t.message}")
                }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
            }
            try {
                lock.release()
            } catch (_: Throwable) {
            }
        }, "retrobit-beacon").also { it.start() }
        try {
            nsd = context.getSystemService(NsdManager::class.java)
            val info = NsdServiceInfo().apply {
                serviceName = "RetroBit-$name"
                serviceType = "_retrobit._tcp."
                port = tcpPort
            }
            nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
        } catch (t: Throwable) {
            Log.w(TAG, "nsd ${t.message}")
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        if (registered) {
            try {
                nsd?.unregisterService(registration)
            } catch (_: Throwable) {
            }
        }
    }

    private fun send(payload: ByteArray, host: String) {
        val packet = DatagramPacket(payload, payload.size, InetAddress.getByName(host), Proto.UDP)
        socket?.send(packet)
    }

    companion object {
        private const val TAG = "RetroBitDisco"
    }
}

class DiscoveryListener(private val context: Context, private val onHost: (name: String, host: String, tcp: Int, http: Int) -> Unit) {
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private var nsd: NsdManager? = null

    @Volatile
    var running = false
        private set

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            try {
                @Suppress("DEPRECATION")
                nsd?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        onHost(info.serviceName ?: "RetroBit", host, info.port, Proto.HTTP)
                    }
                })
            } catch (_: Throwable) {
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        val lock = Locks.multicast(context)
        try {
            lock.acquire()
        } catch (_: Throwable) {
        }
        thread = Thread({
            try {
                val udp = DatagramSocket(Proto.UDP)
                socket = udp
                udp.soTimeout = 1000
                val buf = ByteArray(512)
                while (running) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        udp.receive(packet)
                        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
                        val parts = text.split('\t')
                        if (parts.size >= 4 && parts[0] == "RETROBIT1") {
                            val tcp = parts[2].toIntOrNull() ?: continue
                            val http = parts[3].toIntOrNull() ?: Proto.HTTP
                            val host = packet.address?.hostAddress ?: continue
                            onHost(parts[1], host, tcp, http)
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                    }
                }
            } catch (t: Throwable) {
                Log.w("RetroBitDisco", "listen ${t.message}")
            } finally {
                try {
                    lock.release()
                } catch (_: Throwable) {
                }
            }
        }, "retrobit-listen").also { it.start() }
        try {
            nsd = context.getSystemService(NsdManager::class.java)
            nsd?.discoverServices("_retrobit._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (_: Throwable) {
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        thread?.interrupt()
        try {
            nsd?.stopServiceDiscovery(discovery)
        } catch (_: Throwable) {
        }
    }
}
