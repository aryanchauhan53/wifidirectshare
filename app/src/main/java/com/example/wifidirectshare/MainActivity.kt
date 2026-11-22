package com.example.wifidirectshare

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.wifidirectshare.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var wifiDirect: WifiDirectManager
    private val peerAdapter = PeerAdapter(::onPeerClicked)
    private val transferAdapter = TransferAdapter()

    private var connected = false
    private var isGroupOwner = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { r ->
        if (!r.values.all { it }) Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show()
    }

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val mgr = FileTransferService.instance?.transferManager
        if (mgr == null) { setStatus("Service not running"); return@registerForActivityResult }
        uris.forEach { uri ->
            val name = queryDisplayName(uri) ?: "file"
            val size = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            mgr.enqueueOutgoing(uri, name, size)
        }
        setStatus("Queued ${uris.size} file(s)")
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val on = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    setStatus(if (on) "Wi-Fi Direct: on" else "Wi-Fi Direct: off")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (connected) return
                    wifiDirect.requestPeers { peers ->
                        runOnUiThread {
                            peerAdapter.submit(peers)
                            setStatus("Peers: ${peers.size}")
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    wifiDirect.requestConnectionInfo { info ->
                        if (info != null && info.groupFormed) onConnected(info) else onDisconnected()
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
        binding.transfersList.layoutManager = LinearLayoutManager(this)
        binding.transfersList.adapter = transferAdapter

        binding.discoverButton.setOnClickListener {
            binding.peersList.visibility = View.VISIBLE
            wifiDirect.discoverPeers { ok -> setStatus(if (ok) "Discovering..." else "Discovery failed") }
        }
        binding.stopButton.setOnClickListener {
            wifiDirect.stopDiscovery()
            setStatus("Discovery stopped")
        }
        binding.sendButton.isEnabled = false
        binding.sendButton.setOnClickListener { filePicker.launch(arrayOf("*/*")) }

        requestPermissionsAndGo()
        watchService()
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        registerReceiver(receiver, f)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }

    private fun requestPermissionsAndGo() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
            perms += Manifest.permission.POST_NOTIFICATIONS
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT <= 28) perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun onPeerClicked(device: WifiP2pDevice) {
        setStatus("Connecting to ${device.deviceName}...")
        wifiDirect.connect(device) { ok -> setStatus(if (ok) "Connect requested" else "Connect failed") }
    }

    private fun onConnected(info: WifiP2pInfo) {
        if (connected) return
        connected = true
        isGroupOwner = info.isGroupOwner
        val role = if (isGroupOwner) "Group Owner" else "Client"
        setStatus("Connected as $role")
        binding.peersList.visibility = View.GONE
        binding.discoverButton.isEnabled = false
        binding.sendButton.isEnabled = true
        FileTransferService.start(this, isGroupOwner, info.groupOwnerAddress.hostAddress)
    }

    private fun onDisconnected() {
        if (!connected) return
        connected = false
        isGroupOwner = false
        setStatus("Disconnected")
        binding.peersList.visibility = View.VISIBLE
        binding.discoverButton.isEnabled = true
        binding.sendButton.isEnabled = false
        stopService(Intent(this, FileTransferService::class.java))
    }

    private fun watchService() {
        lifecycleScope.launch {
            var current: StateFlow<List<TransferView>>? = null
            var job: Job? = null
            while (isActive) {
                val mgr = FileTransferService.instance?.transferManager
                if (mgr != null && current !== mgr.transfers) {
                    job?.cancel()
                    current = mgr.transfers
                    job = launch {
                        mgr.transfers.collect { list ->
                            runOnUiThread { transferAdapter.submit(list) }
                        }
                    }
                }
                delay(300)
            }
        }
    }

    private fun queryDisplayName(uri: android.net.Uri): String? {
        var n: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) n = c.getString(i)
        }
        return n
    }

    private fun setStatus(text: String) {
        runOnUiThread { binding.statusText.text = text }
    }
}
