package com.example.wifidirectshare

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream

object DownloadSaver {

    fun save(context: Context, name: String, input: InputStream, size: Long,
             onProgress: (Long) -> Unit): Uri? =
        if (Build.VERSION.SDK_INT >= 29) saveMediaStore(context, name, input, size, onProgress)
        else saveLegacy(name, input, size, onProgress)

    private fun saveMediaStore(context: Context, name: String, input: InputStream,
                               size: Long, onProgress: (Long) -> Unit): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, guessMime(name))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                stream(input, out, size, onProgress)
            } ?: return null
            context.contentResolver.update(uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(name: String, input: InputStream, size: Long,
                           onProgress: (Long) -> Unit): Uri? {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, uniqueName(dir, name))
        file.outputStream().use { stream(input, it, size, onProgress) }
        return Uri.fromFile(file)
    }

    private fun stream(input: InputStream, out: OutputStream, maxBytes: Long,
                       onProgress: (Long) -> Unit) {
        val buf = ByteArray(8192)
        var total = 0L
        while (total < maxBytes) {
            val want = minOf(buf.size.toLong(), maxBytes - total).toInt()
            val r = input.read(buf, 0, want)
            if (r < 0) break
            out.write(buf, 0, r)
            total += r
            onProgress(total)
        }
        out.flush()
    }

    private fun guessMime(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "txt" -> "text/plain"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }

    private fun uniqueName(dir: File, name: String): String {
        var candidate = name
        var i = 1
        while (File(dir, candidate).exists()) {
            val dot = name.lastIndexOf('.')
            candidate = if (dot > 0) "${name.substring(0, dot)} ($i)${name.substring(dot)}" else "$name ($i)"
            i++
        }
        return candidate
    }
}
