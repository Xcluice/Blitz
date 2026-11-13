package com.xcluice.blitz.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.media.MediaScannerConnection
import androidx.core.content.FileProvider
import java.io.File
import java.io.RandomAccessFile
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
    private val legacy = Build.VERSION.SDK_INT < 29
    private val files = ArrayList<File>()
    private val rafs = ArrayList<RandomAccessFile>()

    private fun safe(name: String): String {
        val s = name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_").trim().trimStart('.')
        return (if (s.isEmpty()) "file" else s).take(180)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (f.exists()) {
            f = File(dir, "$base ($n)$ext")
            n++
        }
        return f
    }

    /** Android 8 and 9: no MediaStore.Downloads, write straight into the public Downloads folder. */
    @Suppress("DEPRECATION")
    private fun openLegacy() {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Xcluice Blitz")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Couldn't create the Xcluice Blitz folder in Downloads")
        for (m in metas) {
            val f = uniqueFile(dir, safe(m.name))
            val raf = RandomAccessFile(f, "rw")
            if (m.size > 0) raf.setLength(m.size)
            files.add(f)
            rafs.add(raf)
            chans.add(raf.channel)
        }
    }

    fun open() {
        if (legacy) {
            openLegacy()
            return
        }
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
    fun readData(ch: java.nio.channels.ReadableByteChannel, onBytes: (Long) -> Unit) {
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
            if (legacy) {
                try {
                    rafs[i].close()
                } catch (_: Throwable) {
                }
                val f = files[i]
                try {
                    MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf(metas[i].mime), null)
                } catch (_: Throwable) {
                }
                val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                out.add(Item(metas[i].name, metas[i].size, u, metas[i].mime))
            } else {
                try {
                    pfds[i].close()
                } catch (_: Throwable) {
                }
                val cv = ContentValues()
                cv.put(MediaStore.Downloads.IS_PENDING, 0)
                ctx.contentResolver.update(uris[i], cv, null, null)
                out.add(Item(metas[i].name, metas[i].size, uris[i], metas[i].mime))
            }
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
        for (r in rafs) {
            try {
                r.close()
            } catch (_: Throwable) {
            }
        }
        for (f in files) {
            try {
                f.delete()
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
