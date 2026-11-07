package com.xcluice.blitz.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import kotlinx.coroutines.CompletableDeferred
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One incoming transfer. Files are pre-allocated in Downloads/Xcluice Blitz and written in place by offset. */
class RxSession(
    private val ctx: Context,
    val metas: List<FileMeta>,
    private val encrypted: Boolean,
    key: ByteArray
) {
    val total: Long = metas.sumOf { it.size }
    val result = CompletableDeferred<Boolean>()
    val activeData = AtomicInteger(0)

    @Volatile
    var finished = false

    private val received = AtomicLong(0)
    private val keySpec = SecretKeySpec(key, "AES")
    private val uris = ArrayList<Uri>()
    private val pfds = ArrayList<ParcelFileDescriptor>()
    private val chans = ArrayList<FileChannel>()

    private fun safe(name: String): String {
        val s = name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_").trim().trimStart('.')
        return (if (s.isEmpty()) "file" else s).take(180)
    }

    fun open() {
        val cr = ctx.contentResolver
        for (m in metas) {
            val cv = ContentValues()
            cv.put(MediaStore.Downloads.DISPLAY_NAME, safe(m.name))
            cv.put(MediaStore.Downloads.MIME_TYPE, m.mime)
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Xcluice Blitz")
            cv.put(MediaStore.Downloads.IS_PENDING, 1)
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw IOException("Couldn't create ${m.name} in Downloads")
            uris.add(uri)
            val pfd = cr.openFileDescriptor(uri, "rw")
                ?: throw IOException("Couldn't open ${m.name} for writing")
            pfds.add(pfd)
            if (m.size > 0) Os.ftruncate(pfd.fileDescriptor, m.size)
            chans.add(FileOutputStream(pfd.fileDescriptor).channel)
        }
    }

    /** Runs on a data connection until the sender's end frame. Blocking. */
    fun readData(ch: SocketChannel, onBytes: (Long) -> Unit) {
        try {
            val hdrArr = ByteArray(16)
            val hdr = ByteBuffer.wrap(hdrArr)
            val plain = ByteBuffer.allocateDirect(Proto.CHUNK)
            val enc = if (encrypted) ByteBuffer.allocateDirect(Proto.CHUNK + Proto.TAG) else null
            val cipher = if (encrypted) Cipher.getInstance("AES/GCM/NoPadding") else null
            while (true) {
                hdr.clear()
                ch.readFully(hdr)
                val fi = hdr.getInt(0)
                val off = hdr.getLong(4)
                val len = hdr.getInt(12)
                if (fi < 0) break
                if (fi >= metas.size || len < 0 || len > Proto.CHUNK || off < 0 || off + len > metas[fi].size) {
                    throw IOException("Corrupt frame")
                }
                val fc = chans[fi]
                plain.clear()
                if (cipher == null) {
                    plain.limit(len)
                    ch.readFully(plain)
                    plain.flip()
                } else {
                    enc!!.clear()
                    enc.limit(len + Proto.TAG)
                    ch.readFully(enc)
                    enc.flip()
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce(fi, off)))
                    cipher.updateAAD(hdrArr)
                    cipher.doFinal(enc, plain)
                    plain.flip()
                }
                var pos = off
                while (plain.hasRemaining()) {
                    pos += fc.write(plain, pos)
                }
                onBytes(len.toLong())
                if (received.addAndGet(len.toLong()) >= total) result.complete(true)
            }
        } catch (e: Throwable) {
            result.completeExceptionally(e)
            throw e
        }
    }

    fun finish(): List<Item> {
        finished = true
        val out = ArrayList<Item>()
        for (i in metas.indices) {
            try {
                chans[i].close()
            } catch (_: Throwable) {
            }
            try {
                pfds[i].close()
            } catch (_: Throwable) {
            }
            val cv = ContentValues()
            cv.put(MediaStore.Downloads.IS_PENDING, 0)
            ctx.contentResolver.update(uris[i], cv, null, null)
            out.add(Item(metas[i].name, metas[i].size, uris[i], metas[i].mime))
        }
        return out
    }

    fun abort() {
        finished = true
        for (c in chans) {
            try {
                c.close()
            } catch (_: Throwable) {
            }
        }
        for (p in pfds) {
            try {
                p.close()
            } catch (_: Throwable) {
            }
        }
        for (u in uris) {
            try {
                ctx.contentResolver.delete(u, null, null)
            } catch (_: Throwable) {
            }
        }
    }
}
