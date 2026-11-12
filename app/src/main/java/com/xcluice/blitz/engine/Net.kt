package com.xcluice.blitz.engine

import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Share codes for internet transfers. The relay only ever sees a hash of the code; the key never leaves the two phones. */
object NetCode {
    private const val ALPHA = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    fun generate(): String {
        val r = randomBytes(12)
        val raw = String(CharArray(12) { ALPHA[(r[it].toInt() and 0xFF) % ALPHA.length] })
        return raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8, 12)
    }

    /** Cleans up what the user typed. Returns the canonical code or null when it can't be one. */
    fun normalize(input: String): String? {
        val s = input.uppercase().filter { it.isLetterOrDigit() }
        if (s.length != 12 || s.any { it !in ALPHA }) return null
        return s.substring(0, 4) + "-" + s.substring(4, 8) + "-" + s.substring(8, 12)
    }

    private fun sha(tag: String, code: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest((tag + ":" + code).toByteArray(Charsets.UTF_8))

    fun room(code: String): String = sha("room", code).hex().substring(0, 32)

    fun key(code: String): ByteArray = sha("key", code).copyOf(16)
}

/** Thin client for the pipe relay. Every call is blocking; run on an IO thread. */
class Relay(private val base: String, private val track: (Closeable) -> Unit) {

    private fun open(path: String, method: String, readTimeout: Int): HttpURLConnection {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = readTimeout
        c.setRequestProperty("User-Agent", "XcluiceBlitz")
        track(Closeable { c.disconnect() })
        return c
    }

    /** Announces presence. Returns true when the other side has also checked in recently. */
    fun hello(room: String, role: String): Boolean {
        val c = open("/hi/$room/$role", "POST", 25_000)
        c.doOutput = true
        c.setFixedLengthStreamingMode(0)
        c.outputStream.close()
        if (c.responseCode != 200) throw IOException("Relay answered ${c.responseCode}")
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        return JSONObject(body).optBoolean("peer", false)
    }

    /** Waits for the other phone's matching upload and returns its byte stream. */
    fun download(room: String, ch: String, timeoutMs: Int = 110_000): InputStream {
        val c = open("/p/$room/$ch", "GET", timeoutMs)
        val code = c.responseCode
        if (code != 200) {
            c.disconnect()
            throw IOException(if (code == 408) "The other phone didn't respond in time" else "Relay answered $code")
        }
        return c.inputStream
    }

    /** Streams bytes to the other phone's matching download. [writer] fills the stream. */
    fun upload(room: String, ch: String, timeoutMs: Int = 110_000, writer: (OutputStream) -> Unit) {
        val c = open("/p/$room/$ch", "POST", timeoutMs)
        c.doOutput = true
        c.setChunkedStreamingMode(64 * 1024)
        c.setRequestProperty("Content-Type", "application/octet-stream")
        try {
            c.outputStream.use { writer(it) }
        } catch (e: IOException) {
            val code = try {
                c.responseCode
            } catch (_: Throwable) {
                -1
            }
            throw IOException(if (code == 408) "The other phone didn't respond in time" else (e.message ?: "Upload failed"))
        }
        val code = c.responseCode
        c.disconnect()
        if (code != 200) {
            throw IOException(if (code == 408) "The other phone didn't respond in time" else "Relay answered $code")
        }
    }

    companion object {
        const val DEFAULT_URL = "https://xcluice-blitz-relay.onrender.com"
    }
}
