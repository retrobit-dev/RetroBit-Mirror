package dev.retrobit.mirror.net

import java.io.OutputStream
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MuxWriter(private val output: OutputStream, private val onDead: () -> Unit) {
    private val control = ArrayBlockingQueue<ByteArray>(48)
    private val video = ArrayDeque<ByteArray>()
    private val alive = AtomicBoolean(true)
    private val thread = Thread(::loop, "retrobit-write").also {
        it.isDaemon = true
        it.start()
    }

    fun sendControl(frame: ByteArray): Boolean {
        if (!alive.get()) return false
        if (!control.offer(frame)) {
            control.poll()
            return control.offer(frame)
        }
        return true
    }

    fun sendVideo(frame: ByteArray, keyframe: Boolean) {
        if (!alive.get()) return
        synchronized(video) {
            if (keyframe) {
                video.clear()
            } else if (video.size >= 3) {
                video.removeFirst()
            }
            video.addLast(frame)
        }
    }

    fun close() {
        if (!alive.compareAndSet(true, false)) return
        thread.interrupt()
    }

    private fun loop() {
        try {
            while (alive.get()) {
                var frame = control.poll(8, TimeUnit.MILLISECONDS)
                if (frame == null) {
                    frame = synchronized(video) { if (video.isEmpty()) null else video.removeFirst() }
                }
                if (frame == null) continue
                output.write(frame)
                output.flush()
            }
        } catch (_: Throwable) {
            alive.set(false)
            onDead()
        }
    }
}

fun Socket.tune() {
    tcpNoDelay = true
    keepAlive = true
    sendBufferSize = 256 * 1024
    receiveBufferSize = 64 * 1024
}
