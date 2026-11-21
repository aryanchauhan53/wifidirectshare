package com.example.wifidirectshare

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.Channel
import android.os.Looper

class WifiDirectManager(private val context: Context) {

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private var channel: Channel? = null

    init {
        if (manager != null) {
            channel = manager.initialize(context, Looper.getMainLooper(), null)
        }
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers(onResult: (Boolean) -> Unit) {
        if (manager == null || channel == null) { onResult(false); return }
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onResult(true)
            override fun onFailure(reason: Int) = onResult(false)
        })
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        if (manager == null || channel == null) return
        manager.stopPeerDiscovery(channel, null)
    }

    @SuppressLint("MissingPermission")
    fun requestPeers(onPeers: (List<WifiP2pDevice>) -> Unit) {
        if (manager == null || channel == null) { onPeers(emptyList()); return }
        manager.requestPeers(channel) { list -> onPeers(list.deviceList.toList()) }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice, onResult: (Boolean) -> Unit) {
        if (manager == null || channel == null) { onResult(false); return }
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = 0
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onResult(true)
            override fun onFailure(reason: Int) = onResult(false)
        })
    }

    @SuppressLint("MissingPermission")
    fun requestConnectionInfo(onInfo: (WifiP2pInfo?) -> Unit) {
        if (manager == null || channel == null) { onInfo(null); return }
        manager.requestConnectionInfo(channel) { info -> onInfo(info) }
    }
}
