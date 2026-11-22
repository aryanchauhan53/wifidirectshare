package com.example.wifidirectshare

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

class FileTransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var transferManager: TransferManager
        private set
    private var receiverServer: ReceiverServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        transferManager = TransferManager(this)
        receiverServer = ReceiverServer(this, transferManager) { ip ->
            if (!transferManager.isGroupOwner) transferManager.remoteIp = ip
        }.also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val isGo = intent?.getBooleanExtra(EXTRA_IS_GROUP_OWNER, false) ?: false
        val goAddr = intent?.getStringExtra(EXTRA_GO_ADDRESS)
        transferManager.isGroupOwner = isGo
        if (!isGo && goAddr != null) {
            transferManager.remoteIp = goAddr
            registerWithGroupOwner(goAddr)
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val channelId = "wifidirect_transfer"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Wi-Fi Direct Transfer", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Wi-Fi Direct Share")
            .setContentText("Session active")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notif)
        }
    }

    private fun registerWithGroupOwner(goAddr: String) {
        scope.launch {
            var attempts = 0
            while (attempts < 5) {
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(goAddr, ReceiverServer.PORT), 3000)
                        s.getOutputStream().write(ReceiverServer.MSG_REGISTER)
                        s.getOutputStream().flush()
                    }
                    return@launch
                } catch (_: Exception) {
                    attempts++
                    Thread.sleep(1000)
                }
            }
        }
    }

    override fun onDestroy() {
        receiverServer?.stop()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_IS_GROUP_OWNER = "is_group_owner"
        const val EXTRA_GO_ADDRESS = "go_address"

        @Volatile var instance: FileTransferService? = null
            private set

        fun start(context: Context, isGroupOwner: Boolean, goAddress: String?) {
            val i = Intent(context, FileTransferService::class.java).apply {
                putExtra(EXTRA_IS_GROUP_OWNER, isGroupOwner)
                putExtra(EXTRA_GO_ADDRESS, goAddress)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }
    }
}
