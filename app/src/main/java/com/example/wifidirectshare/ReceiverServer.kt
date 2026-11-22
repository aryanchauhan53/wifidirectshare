package com.example.wifidirectshare

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

class ReceiverServer(
    private val context: Context,
    private val manager: TransferManager,
    private val onPeerRegistered: (String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    @Volatile private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        scope.launch {
            try {
                val s = ServerSocket(PORT)
                server = s
                while (isActive && !s.isClosed) {
                    val client = try { s.accept() } catch (e: Exception) { break }
                    launch { handle(client) }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private suspend fun handle(socket: Socket) = withContext(Dispatchers.IO) {
        socket.use { s ->
            val input = BufferedInputStream(s.getInputStream())
            when (input.read()) {
                MSG_REGISTER -> {
                    s.inetAddress.hostAddress?.let { onPeerRegistered(it) }
                }
                MSG_FILE -> {
                    val nameLen = readLong(input)
                    if (nameLen <= 0 || nameLen > 4096) return@use
                    val nameBytes = ByteArray(nameLen.toInt())
                    readFully(input, nameBytes)
                    val name = String(nameBytes, Charsets.UTF_8)
                    val size = readLong(input)
                    val id = manager.startIncoming(name, size)
                    try {
                        DownloadSaver.save(context, name, input, size) { done ->
                            manager.updateIncomingProgress(id, done)
                        }
                        manager.finishIncoming(id, null)
                    } catch (e: Exception) {
                        manager.finishIncoming(id, e.message ?: "failed")
                    }
                }
            }
        }
    }

    private fun readLong(input: InputStream): Long {
        var r = 0L
        for (i in 0 until 8) r = (r shl 8) or (input.read().toLong() and 0xFF)
        return r
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
    }

    companion object {
        const val PORT = 8988
        const val MSG_REGISTER = 0x02
        const val MSG_FILE = 0x01
    }
}
