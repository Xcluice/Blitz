package com.xcluice.blitz.engine

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/** What is sitting in Downloads/Xcluice Blitz right now, newest first. */
class DlList(val items: List<Item>, val modified: Map<String, Long>)

object Downloads {
    private const val DIR = "Xcluice Blitz"

    fun list(ctx: Context): DlList {
        val found = ArrayList<Triple<Long, Item, String>>()
        try {
            if (Build.VERSION.SDK_INT >= 29) queryStore(ctx, found) else scanFolder(ctx, found)
        } catch (_: Throwable) {
        }
        found.sortByDescending { it.first }
        val items = ArrayList<Item>()
        val dates = HashMap<String, Long>()
        for (f in found) {
            items.add(f.second)
            dates[f.second.name] = f.first
        }
        return DlList(items, dates)
    }

    private fun queryStore(ctx: Context, out: MutableList<Triple<Long, Item, String>>) {
        val proj = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.DATE_MODIFIED
        )
        val sel = MediaStore.Downloads.RELATIVE_PATH + " LIKE ? AND " + MediaStore.Downloads.IS_PENDING + " = 0"
        val args = arrayOf(Environment.DIRECTORY_DOWNLOADS + "/" + DIR + "%")
        ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, sel, args,
            MediaStore.Downloads.DATE_MODIFIED + " DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.startsWith(".")) continue
                val uri: Uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
                val mime = c.getString(3) ?: mimeOf(name)
                out.add(Triple(c.getLong(4) * 1000L, Item(name, c.getLong(2), uri, mime), name))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun scanFolder(ctx: Context, out: MutableList<Triple<Long, Item, String>>) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR)
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (!f.isFile || f.name.startsWith(".")) continue
            val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            out.add(Triple(f.lastModified(), Item(f.name, f.length(), u, mimeOf(f.name)), f.name))
        }
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "apk") return "application/vnd.android.package-archive"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
