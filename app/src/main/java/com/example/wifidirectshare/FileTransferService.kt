package com.example.wifidirectshare

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.util.concurrent.Executors

class FileTransferService : Service() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification())
        executor.execute {
            try {
                ServerSocket(PORT).use { server ->
                    val socket = server.accept()
                    socket.use { s ->
                        val input = s.getInputStream()
                        val nameLen = readLong(input)
                        val nameBytes = ByteArray(nameLen.toInt())
                        readFully(input, nameBytes)
                        val name = String(nameBytes, Charsets.UTF_8)
                        val size = readLong(input)

                        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                        if (!dir.exists()) dir.mkdirs()
                        val out = File(dir, sanitize(name))
                        FileOutputStream(out).use { fos ->
                            val buf = ByteArray(8192)
                            var remaining = size
                            while (remaining > 0) {
                                val r = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                if (r < 0) break
                                fos.write(buf, 0, r)
                                remaining -= r
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildNotification(): android.app.Notification {
        val channelId = "wifidirect_transfer"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Wi-Fi Direct Transfer", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Wi-Fi Direct Share")
            .setContentText("Waiting for incoming file...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
    }

    private fun readLong(input: InputStream): Long {
        var result = 0L
        for (i in 0 until 8) result = (result shl 8) or (input.read().toLong() and 0xFF)
        return result
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "received_file" }

    companion object {
        const val PORT = 8988
        fun startReceiver(context: Context) {
            val i = Intent(context, FileTransferService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }
    }
}
