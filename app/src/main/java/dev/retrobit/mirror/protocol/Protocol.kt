package dev.retrobit.mirror.protocol

import java.io.EOFException
import java.io.InputStream

object Proto {
    const val VERSION = 1
    const val MAGIC0: Byte = 0x52
    const val MAGIC1: Byte = 0x42

    const val HELLO = 1
    const val WELCOME = 2
    const val VIDEO_CONFIG = 10
    const val VIDEO = 11
    const val AUDIO_CONFIG = 12
    const val AUDIO = 13
    const val INPUT = 20
    const val PING = 30
    const val PONG = 31
    const val STATE = 40

    const val FLAG_KEY = 1
    const val FLAG_JPEG = 2

    const val UDP = 28729
    const val TCP = 28730
    const val HTTP = 28731

    fun encode(type: Int, flags: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(10 + payload.size)
        out[0] = MAGIC0
        out[1] = MAGIC1
        out[2] = VERSION.toByte()
        out[3] = type.toByte()
        out[4] = ((flags ushr 8) and 0xFF).toByte()
        out[5] = (flags and 0xFF).toByte()
        val n = payload.size
        out[6] = ((n ushr 24) and 0xFF).toByte()
        out[7] = ((n ushr 16) and 0xFF).toByte()
        out[8] = ((n ushr 8) and 0xFF).toByte()
        out[9] = (n and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 10, n)
        return out
    }

    fun encodeUtf(type: Int, flags: Int, text: String): ByteArray {
        return encode(type, flags, text.toByteArray(Charsets.UTF_8))
    }
}

data class Frame(val type: Int, val flags: Int, val payload: ByteArray)

class FrameParser {
    private var buf = ByteArray(64 * 1024)
    private var size = 0

    fun append(src: ByteArray, off: Int = 0, len: Int = src.size - off) {
        if (len <= 0) return
        ensure(size + len)
        System.arraycopy(src, off, buf, size, len)
        size += len
    }

    fun poll(): Frame? {
        while (size >= 10) {
            if (buf[0] != Proto.MAGIC0 || buf[1] != Proto.MAGIC1 || (buf[2].toInt() and 0xFF) != Proto.VERSION) {
                System.arraycopy(buf, 1, buf, 0, size - 1)
                size -= 1
                continue
            }
            val type = buf[3].toInt() and 0xFF
            val flags = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
            val length = ((buf[6].toInt() and 0xFF) shl 24) or
                ((buf[7].toInt() and 0xFF) shl 16) or
                ((buf[8].toInt() and 0xFF) shl 8) or
                (buf[9].toInt() and 0xFF)
            if (length > 6_000_000) {
                size = 0
                return null
            }
            if (size < 10 + length) return null
            val payload = ByteArray(length)
            if (length > 0) System.arraycopy(buf, 10, payload, 0, length)
            val remain = size - 10 - length
            if (remain > 0) System.arraycopy(buf, 10 + length, buf, 0, remain)
            size = remain
            return Frame(type, flags, payload)
        }
        return null
    }

    fun drain(): List<Frame> {
        val out = ArrayList<Frame>(4)
        while (true) {
            out += poll() ?: break
        }
        return out
    }

    private fun ensure(n: Int) {
        if (buf.size >= n) return
        var cap = buf.size
        while (cap < n) cap *= 2
        buf = buf.copyOf(cap)
    }
}

fun InputStream.readFully(dst: ByteArray, len: Int = dst.size) {
    var off = 0
    while (off < len) {
        val n = read(dst, off, len - off)
        if (n < 0) throw EOFException()
        off += n
    }
}

fun pinEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
    return diff == 0
}
