package com.example.wifidirectshare

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

enum class TransferDirection { INCOMING, OUTGOING }
enum class TransferStatus { QUEUED, RUNNING, DONE, FAILED }

data class TransferView(
    val id: String,
    val name: String,
    val direction: TransferDirection,
    val status: TransferStatus,
    val bytesDone: Long,
    val bytesTotal: Long,
    val error: String?
) {
    val progress: Int
        get() = if (bytesTotal <= 0) 0 else ((bytesDone * 100) / bytesTotal).toInt().coerceIn(0, 100)
}

class TransferManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<OutgoingJob>(Channel.UNLIMITED)
    private val jobs = mutableMapOf<String, LiveJob>()

    private val _transfers = MutableStateFlow<List<TransferView>>(emptyList())
    val transfers: StateFlow<List<TransferView>> = _transfers.asStateFlow()

    @Volatile var remoteIp: String? = null
    @Volatile var isGroupOwner: Boolean = false

    private class LiveJob(
        val id: String,
        val name: String,
        val direction: TransferDirection,
        val total: Long
    ) {
        val bytesDone = AtomicLong(0)
        @Volatile var status: TransferStatus = TransferStatus.QUEUED
        @Volatile var error: String? = null
    }

    private class OutgoingJob(val id: String, val name: String, val uri: Uri, val total: Long)

    init {
        repeat(WORKERS) {
            scope.launch {
                for (job in queue) runOutgoing(job)
            }
        }
        scope.launch {
            while (isActive) {
                delay(150)
                publish()
            }
        }
    }

    fun enqueueOutgoing(uri: Uri, name: String, size: Long) {
        val id = UUID.randomUUID().toString()
        synchronized(jobs) { jobs[id] = LiveJob(id, name, TransferDirection.OUTGOING, size) }
        queue.trySend(OutgoingJob(id, name, uri, size))
        publish()
    }

    fun startIncoming(name: String, total: Long): String {
        val id = UUID.randomUUID().toString()
        synchronized(jobs) { jobs[id] = LiveJob(id, name, TransferDirection.INCOMING, total) }
        publish()
        return id
    }

    fun updateIncomingProgress(id: String, bytes: Long) {
        synchronized(jobs) { jobs[id]?.bytesDone?.set(bytes) }
    }

    fun finishIncoming(id: String, error: String?) {
        synchronized(jobs) { jobs[id] }?.let {
            it.status = if (error == null) TransferStatus.DONE else TransferStatus.FAILED
            it.error = error
        }
        publish()
    }

    private suspend fun runOutgoing(job: OutgoingJob) {
        val live = synchronized(jobs) { jobs[job.id] } ?: return
        live.status = TransferStatus.RUNNING
        publish()
        val peer = awaitPeer()
        if (peer == null) {
            live.status = TransferStatus.FAILED
            live.error = "No peer connected"
            publish()
            return
        }
        try {
            val socket = Socket().apply {
                connect(InetSocketAddress(peer, ReceiverServer.PORT), 5000)
                soTimeout = 15000
            }
            socket.use { s ->
                val out = BufferedOutputStream(s.getOutputStream())
                val nameBytes = job.name.toByteArray(Charsets.UTF_8)
                writeLong(out, nameBytes.size.toLong())
                out.write(nameBytes)
                writeLong(out, job.total)
                context.contentResolver.openInputStream(job.uri)?.use { input ->
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        total += r
                        live.bytesDone.set(total)
                    }
                }
                out.flush()
            }
            live.status = TransferStatus.DONE
            live.bytesDone.set(job.total)
        } catch (e: Exception) {
            live.status = TransferStatus.FAILED
            live.error = e.message
        }
        publish()
    }

    private suspend fun awaitPeer(): String? {
        repeat(50) {
            remoteIp?.let { return it }
            delay(100)
        }
        return null
    }

    private fun publish() {
        val snap = synchronized(jobs) {
            jobs.values.map { live ->
                TransferView(live.id, live.name, live.direction, live.status,
                    live.bytesDone.get(), live.total, live.error)
            }
        }
        _transfers.value = snap
    }

    private fun writeLong(out: OutputStream, v: Long) {
        for (i in 7 downTo 0) out.write(((v shr (i * 8)) and 0xFF).toInt())
    }

    companion object { const val WORKERS = 3 }
}
