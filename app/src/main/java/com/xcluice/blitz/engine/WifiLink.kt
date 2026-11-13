package com.xcluice.blitz.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.channels.SocketChannel
import kotlin.coroutines.resume

private const val OK = -1

internal fun dialOnce(ip: String, port: Int, timeoutMs: Int): SocketChannel {
    val ch = SocketChannel.open()
    try {
        ch.socket().sendBufferSize = Proto.SOCK_BUF
        ch.socket().receiveBufferSize = Proto.SOCK_BUF
        ch.socket().tcpNoDelay = true
        ch.socket().connect(InetSocketAddress(ip, port), timeoutMs)
        return ch
    } catch (e: Throwable) {
        try {
            ch.close()
        } catch (_: Throwable) {
        }
        throw e
    }
}

private suspend fun p2pAction(block: (WifiP2pManager.ActionListener) -> Unit): Int {
    val r = withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine<Int> { c ->
            block(object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (c.isActive) c.resume(OK)
                }

                override fun onFailure(reason: Int) {
                    if (c.isActive) c.resume(reason)
                }
            })
        }
    }
    return r ?: -2
}

private fun WifiP2pManager.removeAndClose(c: WifiP2pManager.Channel) {
    try {
        removeGroup(c, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                try {
                    c.close()
                } catch (_: Throwable) {
                }
            }

            override fun onFailure(reason: Int) {
                try {
                    c.close()
                } catch (_: Throwable) {
                }
            }
        })
    } catch (_: Throwable) {
    }
}

/** Receiver side: becomes the Wi-Fi Direct group owner on 5 GHz (falls back to auto band). */
@SuppressLint("MissingPermission")
class P2pHost(private val ctx: Context) {
    private val mgr = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private var ch: WifiP2pManager.Channel? = null

    /** The credentials the group really uses. On Android 8 and 9 the system picks them. */
    @Volatile var ssid: String? = null
    @Volatile var pass: String? = null

    private fun cfg(ssid: String, pass: String, band: Int): WifiP2pConfig =
        WifiP2pConfig.Builder()
            .setNetworkName(ssid)
            .setPassphrase(pass)
            .setGroupOperatingBand(band)
            .build()

    suspend fun start(ssid: String, pass: String): String? {
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) return "Wi-Fi is off. Turn Wi-Fi on and try again."
        val c = mgr.initialize(ctx, Looper.getMainLooper(), null)
            ?: return "Wi-Fi Direct isn't available on this phone."
        ch = c
        p2pAction { mgr.removeGroup(c, it) }
        delay(300)
        if (Build.VERSION.SDK_INT < 29) {
            val lr = p2pAction { mgr.createGroup(c, it) }
            if (lr != OK) return "Couldn't start Wi-Fi Direct (code $lr). Turn on Wi-Fi and Location, then retry."
            for (i in 0 until 20) {
                val g = suspendCancellableCoroutine<android.net.wifi.p2p.WifiP2pGroup?> { cc ->
                    mgr.requestGroupInfo(c) { grp -> if (cc.isActive) cc.resume(grp) }
                }
                val n = g?.networkName
                val pw = g?.passphrase
                if (!n.isNullOrEmpty() && !pw.isNullOrEmpty()) {
                    this.ssid = n
                    this.pass = pw
                    return null
                }
                delay(250)
            }
            return "Couldn't read the Wi-Fi Direct group details. Turn Wi-Fi and Location off and on, then retry."
        }
        this.ssid = ssid
        this.pass = pass
        var r = p2pAction { mgr.createGroup(c, cfg(ssid, pass, WifiP2pConfig.GROUP_OWNER_BAND_5GHZ), it) }
        if (r != OK) {
            delay(400)
            r = p2pAction { mgr.createGroup(c, cfg(ssid, pass, WifiP2pConfig.GROUP_OWNER_BAND_AUTO), it) }
        }
        return if (r == OK) null
        else "Couldn't start Wi-Fi Direct (code $r). Turn on Wi-Fi and Location, then retry."
    }

    suspend fun ip(): String {
        for (i in 0 until 15) {
            try {
                val list = NetworkInterface.getNetworkInterfaces()
                if (list != null) {
                    for (ni in list) {
                        if (!ni.name.startsWith("p2p")) continue
                        for (addr in ni.inetAddresses) {
                            if (addr is Inet4Address) {
                                val h = addr.hostAddress
                                if (h != null) return h
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            delay(200)
        }
        return "192.168.49.1"
    }

    fun stop() {
        val c = ch ?: return
        ch = null
        mgr.removeAndClose(c)
    }
}

/** Sender side: joins the receiver's group, first via P2P credentials, then via a standard Wi-Fi request. */
@SuppressLint("MissingPermission")
class P2pClient(private val ctx: Context) {
    private val mgr = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var ch: WifiP2pManager.Channel? = null
    private var cb: ConnectivityManager.NetworkCallback? = null

    suspend fun join(p: Pairing, status: (String) -> Unit): SocketChannel? {
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) throw IOException("Wi-Fi is off. Turn Wi-Fi on and try again.")
        status("Joining the receiver's Wi-Fi Direct group…")
        if (Build.VERSION.SDK_INT < 29) {
            return if (tryLegacy(p)) dialUntil(p, 14_000) else null
        }
        if (tryP2p(p)) {
            val s = dialUntil(p, 14_000)
            if (s != null) return s
        }
        try {
            cm.bindProcessToNetwork(null)
        } catch (_: Throwable) {
        }
        bound = false
        status("Trying a standard Wi-Fi join. Approve the prompt if it appears.")
        if (trySpecifier(p)) {
            return dialUntil(p, 10_000)
        }
        return null
    }

    private var legacyNetId = -1

    /** Android 8 and 9: join the group's Wi-Fi network with the old WifiConfiguration API. */
    @Suppress("DEPRECATION")
    private suspend fun tryLegacy(p: Pairing): Boolean {
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val conf = WifiConfiguration()
            conf.SSID = "\"" + p.ssid + "\""
            conf.preSharedKey = "\"" + p.pass + "\""
            conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            val id = wm.addNetwork(conf)
            if (id == -1) return false
            legacyNetId = id
            wm.disconnect()
            if (!wm.enableNetwork(id, true)) return false
            wm.reconnect()
            val end = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < end) {
                currentCoroutineContext().ensureActive()
                val info = wm.connectionInfo
                if (info != null && info.networkId == id && info.ipAddress != 0) return true
                delay(300)
            }
            false
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    private suspend fun tryP2p(p: Pairing): Boolean {
        return try {
            val c = mgr.initialize(ctx, Looper.getMainLooper(), null) ?: return false
            ch = c
            val cfg = WifiP2pConfig.Builder()
                .setNetworkName(p.ssid)
                .setPassphrase(p.pass)
                .build()
            p2pAction { mgr.connect(c, cfg, it) } == OK
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun trySpecifier(p: Pairing): Boolean {
        return try {
            val spec = WifiNetworkSpecifier.Builder()
                .setSsid(p.ssid)
                .setWpa2Passphrase(p.pass)
                .build()
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build()
            val net = suspendCancellableCoroutine<Network?> { c ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (c.isActive) c.resume(network)
                    }

                    override fun onUnavailable() {
                        if (c.isActive) c.resume(null)
                    }
                }
                cb = callback
                cm.requestNetwork(req, callback, 20_000)
                c.invokeOnCancellation {
                    try {
                        cm.unregisterNetworkCallback(callback)
                    } catch (_: Throwable) {
                    }
                }
            }
            if (net == null) {
                false
            } else {
                cm.bindProcessToNetwork(net)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** The Wi-Fi Direct link as a Network, so traffic doesn't leak onto the phone's normal Wi-Fi. */
    @Suppress("DEPRECATION")
    private fun p2pNetwork(): Network? {
        return try {
            for (n in cm.allNetworks) {
                val name = cm.getLinkProperties(n)?.interfaceName
                if (name != null && name.startsWith("p2p")) return n
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private var bound = false

    private suspend fun dialUntil(p: Pairing, ms: Long): SocketChannel? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            currentCoroutineContext().ensureActive()
            if (!bound) {
                val n = p2pNetwork()
                if (n != null) {
                    try {
                        bound = cm.bindProcessToNetwork(n)
                    } catch (_: Throwable) {
                    }
                }
            }
            try {
                return dialOnce(p.ip, p.port, 1500)
            } catch (e: IOException) {
                delay(350)
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    fun release() {
        if (legacyNetId != -1) {
            try {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wm.removeNetwork(legacyNetId)
                wm.reconnect()
            } catch (_: Throwable) {
            }
            legacyNetId = -1
        }
        val callback = cb
        cb = null
        if (callback != null) {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: Throwable) {
            }
        }
        try {
            cm.bindProcessToNetwork(null)
        } catch (_: Throwable) {
        }
        bound = false
        val c = ch
        ch = null
        if (c != null) mgr.removeAndClose(c)
    }
}
