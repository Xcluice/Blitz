package com.xcluice.blitz.engine

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class Phase { IDLE, PREPARING, WAITING, CONNECTING, TRANSFERRING, DONE, ERROR }

enum class Role { SEND, RECEIVE }

data class Item(
    val name: String,
    val size: Long,
    val uri: Uri? = null,
    val mime: String = "*/*"
)

/** Something to send: where to read it from, and what to call it on the other side. */
class SendItem(val uri: Uri, val meta: FileMeta)

data class UiState(
    val phase: Phase = Phase.IDLE,
    val role: Role? = null,
    val qr: String? = null,
    val status: String = "",
    val total: Long = 0,
    val done: Long = 0,
    val speed: Double = 0.0,
    val peak: Double = 0.0,
    val eta: Long = -1,
    val elapsedMs: Long = 0,
    val items: List<Item> = emptyList(),
    val encrypt: Boolean = true,
    val error: String? = null
)

private class Chunk(val file: Int, val off: Long, val len: Int)

/** App-wide transfer engine. One session at a time; UI observes [state]. */
object Blitz {
    private lateinit var app: Context
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var progressJob: Job? = null
    private var gen = 0
    private val counter = AtomicLong(0)
    private val sockets = CopyOnWriteArrayList<Closeable>()
    private var host: P2pHost? = null
    private var client: P2pClient? = null
    private var rxSession: RxSession? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    private fun mutate(f: (UiState) -> UiState) {
        _state.update(f)
    }

    val busy: Boolean get() = job?.isActive == true

    // ---------- public API ----------

    fun startReceive() {
        if (busy) return
        val g = ++gen
        _state.value = UiState(phase = Phase.PREPARING, role = Role.RECEIVE, status = "Opening a 5 GHz link…")
        job = scope.launch {
            try {
                receiveFlow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (g == gen) fail(e)
            } finally {
                if (g == gen) cleanup()
            }
        }
    }

    fun startSend(items: List<SendItem>, pairing: Pairing, encrypt: Boolean) {
        if (busy || items.isEmpty()) return
        val g = ++gen
        _state.value = UiState(
            phase = Phase.PREPARING,
            role = Role.SEND,
            status = "Getting ready…",
            encrypt = encrypt
        )
        job = scope.launch {
            try {
                sendFlow(items, pairing, encrypt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (g == gen) fail(e)
            } finally {
                if (g == gen) cleanup()
            }
        }
    }

    /** Cancel whatever is running and return to the home screen. */
    fun reset() {
        gen++
        job?.cancel()
        job = null
        cleanup()
        _state.value = UiState()
    }

    fun describe(uri: Uri): FileMeta {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        try {
            app.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {
        }
        if (size < 0) {
            size = try {
                app.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
        var mime = app.contentResolver.getType(uri)
        if (mime == null) {
            val ext = MimeTypeMap.getFileExtensionFromUrl(name)
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
        }
        return FileMeta(name, size, mime ?: "application/octet-stream")
    }

    // ---------- receive ----------

    private suspend fun receiveFlow() = coroutineScope {
        acquireLocks()
        KeepAliveService.start(app)
        val token = randomBytes(16)
        val key = randomBytes(16)

        val server = ServerSocketChannel.open()
        sockets.add(server)
        server.socket().receiveBufferSize = Proto.SOCK_BUF
        server.bind(InetSocketAddress(0))
        val port = server.socket().localPort

        val ssid = "DIRECT-bz-" + randomAlnum(4)
        val pass = randomAlnum(10)
        val h = P2pHost(app)
        host = h
        val err = h.start(ssid, pass)
        if (err != null) throw IOException(err)

        val pairing = Pairing(ssid, pass, h.ip(), port, token, key)
        mutate { it.copy(phase = Phase.WAITING, qr = pairing.toUri(), status = "Waiting for the sender to scan…") }

        val finished = CompletableDeferred<Unit>()
        launch {
            while (isActive) {
                val ch = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                sockets.add(ch)
                launch {
                    try {
                        serve(ch, pairing, finished)
                    } catch (e: Throwable) {
                        if (e !is CancellationException) finished.completeExceptionally(e)
                    }
                }
            }
        }
        try {
            finished.await()
        } finally {
            closeAll()
        }
    }

    private suspend fun serve(ch: SocketChannel, p: Pairing, finished: CompletableDeferred<Unit>) {
        ch.socket().tcpNoDelay = true
        val hs = ByteBuffer.allocate(Proto.HELLO)
        ch.readFully(hs)
        val magic = hs.getInt(0)
        val tok = ByteArray(16)
        for (i in 0 until 16) tok[i] = hs.get(4 + i)
        val mode = hs.get(20).toInt()
        val flags = hs.get(21).toInt()
        if (magic != Proto.MAGIC || !MessageDigest.isEqual(tok, p.token)) {
            ch.writeFully(ByteBuffer.wrap(byteArrayOf(0)))
            ch.close()
            return
        }
        ch.writeFully(ByteBuffer.wrap(byteArrayOf(1)))
        if (mode == Proto.MODE_CONTROL) {
            serveControl(ch, p, (flags and 1) != 0, finished)
        } else {
            serveData(ch)
        }
    }

    private suspend fun serveControl(
        ch: SocketChannel,
        p: Pairing,
        encrypted: Boolean,
        finished: CompletableDeferred<Unit>
    ) {
        val lenBuf = ByteBuffer.allocate(4)
        ch.readFully(lenBuf)
        val n = lenBuf.getInt(0)
        require(n in 4..(8 shl 20)) { "Bad manifest" }
        val body = ByteBuffer.allocate(n)
        ch.readFully(body)
        val metas = decodeManifest(body.array())

        val rx = RxSession(app, metas, encrypted, p.key)
        rxSession = rx
        rx.open()
        counter.set(0)
        mutate {
            it.copy(
                phase = Phase.TRANSFERRING,
                total = rx.total,
                done = 0,
                items = metas.map { m -> Item(m.name, m.size, null, m.mime) },
                status = "Receiving…",
                encrypt = encrypted
            )
        }
        val t0 = startProgress()
        ch.writeFully(ByteBuffer.wrap(byteArrayOf(1)))
        if (rx.total == 0L) rx.result.complete(true)

        val ok = rx.result.await()
        var waited = 0
        while (rx.activeData.get() > 0 && waited < 3000) {
            delay(20)
            waited += 20
        }
        if (!ok) throw IOException("Transfer incomplete")
        val items = rx.finish()
        ch.writeFully(ByteBuffer.wrap(byteArrayOf(1)))
        progressJob?.cancel()
        val elapsed = (System.nanoTime() - t0) / 1_000_000
        mutate {
            it.copy(
                phase = Phase.DONE,
                done = it.total,
                items = items,
                elapsedMs = elapsed,
                speed = 0.0,
                eta = 0,
                status = "Received"
            )
        }
        finished.complete(Unit)
    }

    private fun serveData(ch: SocketChannel) {
        val rx = rxSession ?: throw IOException("No active session")
        rx.activeData.incrementAndGet()
        try {
            rx.readData(ch) { n -> counter.addAndGet(n) }
        } finally {
            rx.activeData.decrementAndGet()
        }
    }

    // ---------- send ----------

    private suspend fun sendFlow(items: List<SendItem>, p: Pairing, encrypt: Boolean) = coroutineScope {
        acquireLocks()
        KeepAliveService.start(app)

        val metas = items.map { it.meta }
        val total = metas.sumOf { it.size }
        mutate {
            it.copy(
                phase = Phase.CONNECTING,
                total = total,
                items = metas.map { m -> Item(m.name, m.size, null, m.mime) },
                status = "Joining the receiver's Wi-Fi Direct group…"
            )
        }

        val c = P2pClient(app)
        client = c
        val ctl = c.join(p) { msg -> mutate { s -> s.copy(status = msg) } }
            ?: throw IOException(
                "Couldn't join the receiver's network. Keep both phones close, make sure Wi-Fi and Location are on, then try again."
            )
        sockets.add(ctl)

        val flags = if (encrypt) 1 else 0
        hello(ctl, p, Proto.MODE_CONTROL, flags, 0)
        val body = encodeManifest(metas)
        val mb = ByteBuffer.allocate(4 + body.size)
        mb.putInt(body.size)
        mb.put(body)
        mb.flip()
        ctl.writeFully(mb)
        val ack = ByteBuffer.allocate(1)
        ctl.readFully(ack)
        if (ack.get(0).toInt() != 1) throw IOException("The receiver refused the transfer")

        counter.set(0)
        mutate { it.copy(phase = Phase.TRANSFERRING, status = "Sending at full speed…") }
        val t0 = startProgress()

        val queue = ConcurrentLinkedQueue<Chunk>()
        for (i in metas.indices) {
            val size = metas[i].size
            var off = 0L
            while (off < size) {
                val len = minOf(Proto.CHUNK.toLong(), size - off).toInt()
                queue.add(Chunk(i, off, len))
                off += len
            }
        }
        val pfds = CopyOnWriteArrayList<ParcelFileDescriptor>()
        val files: List<Lazy<FileChannel>> = items.map { si ->
            lazy {
                val pfd = app.contentResolver.openFileDescriptor(si.uri, "r")
                    ?: throw IOException("Couldn't open a file to send")
                pfds.add(pfd)
                FileInputStream(pfd.fileDescriptor).channel
            }
        }
        val n = maxOf(1, minOf(Proto.STREAMS, queue.size))
        val latch = CountDownLatch(n)
        val keySpec = SecretKeySpec(p.key, "AES")
        try {
            val workers = (0 until n).map { sid ->
                async { sendWorker(sid, p, flags, queue, files, latch, keySpec) }
            }
            workers.awaitAll()
            val done = ByteBuffer.allocate(1)
            ctl.readFully(done)
            if (done.get(0).toInt() != 1) throw IOException("The receiver reported an incomplete transfer")
        } finally {
            closeAll()
            for (pfd in pfds) {
                try {
                    pfd.close()
                } catch (_: Throwable) {
                }
            }
        }
        progressJob?.cancel()
        val elapsed = (System.nanoTime() - t0) / 1_000_000
        mutate {
            it.copy(
                phase = Phase.DONE,
                done = it.total,
                elapsedMs = elapsed,
                speed = 0.0,
                eta = 0,
                status = "Sent"
            )
        }
    }

    private fun hello(ch: SocketChannel, p: Pairing, mode: Int, flags: Int, sid: Int) {
        val b = ByteBuffer.allocate(Proto.HELLO)
        b.putInt(Proto.MAGIC)
        b.put(p.token)
        b.put(mode.toByte())
        b.put(flags.toByte())
        b.putInt(sid)
        b.flip()
        ch.writeFully(b)
        val r = ByteBuffer.allocate(1)
        ch.readFully(r)
        if (r.get(0).toInt() != 1) throw IOException("The receiver rejected this code. Scan a fresh QR.")
    }

    private fun sendWorker(
        sid: Int,
        p: Pairing,
        flags: Int,
        queue: ConcurrentLinkedQueue<Chunk>,
        files: List<Lazy<FileChannel>>,
        latch: CountDownLatch,
        keySpec: SecretKeySpec
    ) {
        var made: SocketChannel? = null
        try {
            val s = dialOnce(p.ip, p.port, 5000)
            made = s
            sockets.add(s)
            hello(s, p, Proto.MODE_DATA, flags, sid)
        } finally {
            latch.countDown()
        }
        if (!latch.await(20, TimeUnit.SECONDS)) throw IOException("Streams failed to connect")
        val c = made ?: throw IOException("Stream failed to connect")

        val encrypt = (flags and 1) != 0
        val hdrArr = ByteArray(16)
        val hdr = ByteBuffer.wrap(hdrArr)
        val plain = if (encrypt) ByteBuffer.allocateDirect(Proto.CHUNK) else null
        val enc = if (encrypt) ByteBuffer.allocateDirect(Proto.CHUNK + Proto.TAG) else null
        val cipher = if (encrypt) Cipher.getInstance("AES/GCM/NoPadding") else null

        while (true) {
            val ck = queue.poll() ?: break
            hdr.clear()
            hdr.putInt(ck.file)
            hdr.putLong(ck.off)
            hdr.putInt(ck.len)
            hdr.flip()
            val fc = files[ck.file].value
            if (cipher == null) {
                c.writeFully(hdr)
                var pos = ck.off
                var left = ck.len.toLong()
                while (left > 0) {
                    val sent = fc.transferTo(pos, left, c)
                    if (sent <= 0) throw IOException("A source file ended early")
                    pos += sent
                    left -= sent
                    counter.addAndGet(sent)
                }
            } else {
                plain!!.clear()
                plain.limit(ck.len)
                var pos = ck.off
                while (plain.hasRemaining()) {
                    val r = fc.read(plain, pos)
                    if (r < 0) throw IOException("A source file ended early")
                    pos += r
                }
                plain.flip()
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce(ck.file, ck.off)))
                cipher.updateAAD(hdrArr)
                enc!!.clear()
                cipher.doFinal(plain, enc)
                enc.flip()
                c.writeFully(hdr)
                c.writeFully(enc)
                counter.addAndGet(ck.len.toLong())
            }
        }
        hdr.clear()
        hdr.putInt(-1)
        hdr.putLong(0L)
        hdr.putInt(0)
        hdr.flip()
        c.writeFully(hdr)
    }

    // ---------- shared plumbing ----------

    /** Starts the speed/ETA sampler and returns the start timestamp (nanoTime). */
    private fun startProgress(): Long {
        progressJob?.cancel()
        val t0 = System.nanoTime()
        progressJob = scope.launch {
            var last = counter.get()
            var lastT = t0
            var speed = 0.0
            while (isActive) {
                delay(250)
                val now = System.nanoTime()
                val cur = counter.get()
                val dt = (now - lastT) / 1e9
                val inst = if (dt > 0) (cur - last) / dt else 0.0
                speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4
                val sp = speed
                mutate {
                    it.copy(
                        done = cur,
                        speed = sp,
                        peak = maxOf(it.peak, sp),
                        eta = if (sp > 1.0) ((it.total - cur) / sp).toLong() else -1L,
                        elapsedMs = (now - t0) / 1_000_000
                    )
                }
                last = cur
                lastT = now
            }
        }
        return t0
    }

    private fun fail(e: Throwable) {
        val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        _state.value = _state.value.copy(phase = Phase.ERROR, error = msg, speed = 0.0)
    }

    private fun closeAll() {
        for (s in sockets) {
            try {
                s.close()
            } catch (_: Throwable) {
            }
        }
        sockets.clear()
    }

    private fun cleanup() {
        closeAll()
        progressJob?.cancel()
        progressJob = null
        val rx = rxSession
        rxSession = null
        if (rx != null && !rx.finished) rx.abort()
        host?.stop()
        host = null
        client?.release()
        client = null
        try {
            wifiLock?.release()
        } catch (_: Throwable) {
        }
        try {
            wakeLock?.release()
        } catch (_: Throwable) {
        }
        wifiLock = null
        wakeLock = null
        KeepAliveService.stop(app)
    }

    private fun acquireLocks() {
        try {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wl = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "blitz")
            wl.setReferenceCounted(false)
            wl.acquire()
            wifiLock = wl
        } catch (_: Throwable) {
        }
        try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val w = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "blitz:transfer")
            w.setReferenceCounted(false)
            w.acquire(60 * 60 * 1000L)
            wakeLock = w
        } catch (_: Throwable) {
        }
    }
}
