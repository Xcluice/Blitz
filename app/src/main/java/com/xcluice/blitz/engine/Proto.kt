package com.xcluice.blitz.engine

import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.security.SecureRandom

object Proto {
    const val MAGIC = 0x424C5A31
    const val MODE_CONTROL = 0
    const val MODE_DATA = 1
    const val CHUNK = 1 shl 20
    const val STREAMS = 4
    const val TAG = 16
    const val SOCK_BUF = 4 shl 20
    const val HELLO = 26
}

data class FileMeta(val name: String, val size: Long, val mime: String)

class Pairing(
    val ssid: String,
    val pass: String,
    val ip: String,
    val port: Int,
    val token: ByteArray,
    val key: ByteArray
) {
    fun toUri(): String =
        "blitz://pair?s=" + Uri.encode(ssid) +
            "&p=" + Uri.encode(pass) +
            "&i=" + ip +
            "&o=" + port +
            "&t=" + token.hex() +
            "&k=" + key.hex()

    companion object {
        fun parse(text: String?): Pairing? {
            if (text == null) return null
            return try {
                val u = Uri.parse(text.trim())
                if (u.scheme != "blitz") return null
                val ssid = u.getQueryParameter("s") ?: return null
                val pass = u.getQueryParameter("p") ?: return null
                val ip = u.getQueryParameter("i") ?: return null
                val port = u.getQueryParameter("o")?.toInt() ?: return null
                val token = u.getQueryParameter("t")?.unhex() ?: return null
                val key = u.getQueryParameter("k")?.unhex() ?: return null
                if (token.size != 16 || key.size != 16) return null
                Pairing(ssid, pass, ip, port, token, key)
            } catch (e: Exception) {
                null
            }
        }
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

fun String.unhex(): ByteArray =
    ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }

private val rng = SecureRandom()

fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

fun randomAlnum(n: Int): String {
    val a = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"
    return String(CharArray(n) { a[rng.nextInt(a.length)] })
}

internal fun java.nio.channels.ReadableByteChannel.readFully(b: ByteBuffer) {
    while (b.hasRemaining()) {
        if (read(b) < 0) throw EOFException("Connection closed")
    }
}

internal fun java.nio.channels.WritableByteChannel.writeFully(b: ByteBuffer) {
    while (b.hasRemaining()) write(b)
}

internal fun nonce(fileIndex: Int, offset: Long): ByteArray {
    val b = ByteBuffer.allocate(12)
    b.putInt(fileIndex)
    b.putLong(offset)
    return b.array()
}

internal fun encodeManifest(metas: List<FileMeta>): ByteArray {
    val bo = ByteArrayOutputStream()
    val d = DataOutputStream(bo)
    d.writeInt(metas.size)
    for (m in metas) {
        d.writeUTF(m.name.take(200))
        d.writeLong(m.size)
        d.writeUTF(m.mime.take(100))
    }
    d.flush()
    return bo.toByteArray()
}

internal fun decodeManifest(bytes: ByteArray): List<FileMeta> {
    val d = DataInputStream(ByteArrayInputStream(bytes))
    val n = d.readInt()
    require(n in 0..10000) { "Bad manifest" }
    val out = ArrayList<FileMeta>(n)
    for (i in 0 until n) {
        val name = d.readUTF()
        val size = d.readLong()
        val mime = d.readUTF()
        out.add(FileMeta(name, size, mime))
    }
    return out
}
