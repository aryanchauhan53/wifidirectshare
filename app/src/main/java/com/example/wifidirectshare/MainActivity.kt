package com.example.wifidirectshare

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.wifidirectshare.databinding.ActivityMainBinding
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var wifiDirect: WifiDirectManager
    private val peerAdapter = PeerAdapter(::onPeerClicked)
    private var pickedFileUri: Uri? = null
    private var groupOwnerAddress: InetAddress? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) appendLog("Permissions granted")
        else Toast.makeText(this, "Permissions required for Wi-Fi Direct", Toast.LENGTH_LONG).show()
    }

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            pickedFileUri = uri
            appendLog("Selected: ${queryDisplayName(uri) ?: "file"}")
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    appendLog("Wi-Fi Direct enabled: $enabled")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    wifiDirect.requestPeers { peers ->
                        runOnUiThread {
                            peerAdapter.submit(peers)
                            appendLog("Peers found: ${peers.size}")
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    wifiDirect.requestConnectionInfo { info: WifiP2pInfo? ->
                        if (info != null && info.groupFormed) {
                            groupOwnerAddress = info.groupOwnerAddress
                            val role = if (info.isGroupOwner) "GROUP OWNER" else "CLIENT"
                            runOnUiThread {
                                appendLog("Connected as $role @ ${info.groupOwnerAddress.hostAddress}")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wifiDirect = WifiDirectManager(this)

        binding.peersList.layoutManager = LinearLayoutManager(this)
        binding.peersList.adapter = peerAdapter

        binding.discoverButton.setOnClickListener { startDiscovery() }
        binding.stopButton.setOnClickListener {
            wifiDirect.stopDiscovery()
            appendLog("Discovery stopped")
        }
        binding.startServerButton.setOnClickListener { startReceiveServer() }
        binding.pickFileButton.setOnClickListener { filePicker.launch("*/*") }
        binding.sendButton.setOnClickListener {
            val uri = pickedFileUri
            if (uri == null) appendLog("Pick a file first") else sendFile(uri)
        }

        requestRuntimePermissions()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        registerReceiver(receiver, filter)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
            perms += Manifest.permission.POST_NOTIFICATIONS
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startDiscovery() {
        wifiDirect.discoverPeers { ok ->
            runOnUiThread { appendLog(if (ok) "Discovery started" else "Discovery failed") }
        }
    }

    private fun onPeerClicked(device: WifiP2pDevice) {
        appendLog("Connecting to ${device.deviceName}...")
        wifiDirect.connect(device) { ok ->
            runOnUiThread { appendLog(if (ok) "Connect request sent" else "Connect failed") }
        }
    }

    private fun startReceiveServer() {
        wifiDirect.requestConnectionInfo { info ->
            if (info != null && info.groupFormed) {
                groupOwnerAddress = info.groupOwnerAddress
                FileTransferService.startReceiver(this)
                appendLog("Receive server started on this device")
            } else {
                runOnUiThread { appendLog("Connect to a peer first") }
            }
        }
    }

    private fun sendFile(uri: Uri) {
        val addr = groupOwnerAddress ?: run { appendLog("No connection"); return }
        val name = queryDisplayName(uri) ?: "file"
        Thread {
            try {
                Socket(addr, FileTransferService.PORT).use { s ->
                    val out = s.getOutputStream()
                    val nameBytes = name.toByteArray(Charsets.UTF_8)
                    writeLong(out, nameBytes.size.toLong())
                    out.write(nameBytes)
                    val size = contentResolver.openFileDescriptor(uri, "r")!!.statSize
                    writeLong(out, size)
                    contentResolver.openInputStream(uri)!!.use { it.copyTo(out, 8192) }
                    out.flush()
                    runOnUiThread { appendLog("Sent $name ($size bytes)") }
                }
            } catch (e: Exception) {
                runOnUiThread { appendLog("Send failed: ${e.message}") }
            }
        }.start()
    }

    private fun writeLong(out: OutputStream, value: Long) {
        for (i in 7 downTo 0) out.write(((value shr (i * 8)) and 0xFF).toInt())
    }

    private fun queryDisplayName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
        return name
    }

    private fun appendLog(line: String) {
        runOnUiThread { binding.logText.append("\n$line") }
    }
}
