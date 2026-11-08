package com.xcluice.blitz.engine

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

class Peer(val address: String, val name: String, val avatar: Int, val seen: Long)

class Request(val name: String, val avatar: Int, val count: Int, val total: Long, val first: String)

/**
 * Bluetooth LE discovery. The receiver advertises its name and avatar and runs a tiny GATT server.
 * A sender picks it from the list and writes a request; the Wi-Fi Direct credentials are handed back
 * only after the receiver taps Accept. The data itself never goes over Bluetooth.
 */
@SuppressLint("MissingPermission")
object Nearby {
    private val SERVICE: UUID = UUID.fromString("b1172e00-7a5c-4f3e-9d10-5b1172e00001")
    private val REQ: UUID = UUID.fromString("b1172e00-7a5c-4f3e-9d10-5b1172e00002")
    private val PAIR: UUID = UUID.fromString("b1172e00-7a5c-4f3e-9d10-5b1172e00003")
    private const val COMPANY = 0xFFFF

    val peers = MutableStateFlow<List<Peer>>(emptyList())
    val incoming = MutableStateFlow<Request?>(null)
    val hostError = MutableStateFlow<String?>(null)
    val scanError = MutableStateFlow<String?>(null)

    private fun adapter(ctx: Context) =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    // ---------------------------------------------------------------- receiver

    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advCb: AdvertiseCallback? = null
    private var pairing: ByteArray = ByteArray(0)
    private var decided: ByteArray? = null
    private var pendingDevice: BluetoothDevice? = null
    private var pendingReq = 0
    private var pendingOffset = 0
    private val lock = Any()

    fun host(ctx: Context, name: String, avatar: Int, pairingUri: String) {
        stopHost()
        hostError.value = null
        val ad = adapter(ctx)
        if (ad == null || !ad.isEnabled) {
            hostError.value = "Turn on Bluetooth so nearby phones can see you."
            return
        }
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        synchronized(lock) {
            pairing = pairingUri.toByteArray(Charsets.UTF_8)
            decided = null
            pendingDevice = null
        }
        incoming.value = null
        try {
            val srv = bm.openGattServer(ctx, object : BluetoothGattServerCallback() {
                override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
                    try {
                        startAdvertising(ad, name, avatar)
                    } catch (_: Throwable) {
                    }
                }

                override fun onCharacteristicWriteRequest(
                    device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                    preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
                ) {
                    var ok = false
                    try {
                    if (characteristic.uuid == REQ && value != null) {
                        val r = decodeRequest(value)
                        if (r != null) {
                            synchronized(lock) {
                                decided = null
                                pendingDevice = null
                            }
                            incoming.value = r
                            ok = true
                        }
                    }
                    } catch (_: Throwable) {
                    }
                    if (responseNeeded) {
                        try {
                        server?.sendResponse(
                            device, requestId,
                            if (ok) android.bluetooth.BluetoothGatt.GATT_SUCCESS
                            else android.bluetooth.BluetoothGatt.GATT_FAILURE,
                            0, null
                        )
                        } catch (_: Throwable) {
                        }
                    }
                }

                override fun onCharacteristicReadRequest(
                    device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic
                ) {
                    if (characteristic.uuid != PAIR) {
                        server?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_FAILURE, 0, null)
                        return
                    }
                    var answer: ByteArray? = null
                    synchronized(lock) {
                        answer = decided
                        if (answer == null) {
                            pendingDevice = device
                            pendingReq = requestId
                            pendingOffset = offset
                        }
                    }
                    val a = answer
                    if (a != null) respond(device, requestId, offset, a)
                }
            }) ?: run {
                hostError.value = "Bluetooth isn't available."
                return
            }
            server = srv
            val svc = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            svc.addCharacteristic(
                BluetoothGattCharacteristic(
                    REQ, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE
                )
            )
            svc.addCharacteristic(
                BluetoothGattCharacteristic(
                    PAIR, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ
                )
            )
            srv.addService(svc)
        } catch (e: Throwable) {
            hostError.value = "Couldn't start Bluetooth: ${e.message}"
        }
    }

    private fun respond(device: BluetoothDevice, requestId: Int, offset: Int, data: ByteArray) {
        val slice = if (offset >= data.size) ByteArray(0) else data.copyOfRange(offset, data.size)
        try {
            server?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, slice)
        } catch (_: Throwable) {
        }
    }

    private fun startAdvertising(ad: android.bluetooth.BluetoothAdapter, name: String, avatar: Int) {
        try {
            startAdvertisingUnsafe(ad, name, avatar)
        } catch (e: Throwable) {
            hostError.value = "Bluetooth advertising failed: ${e.javaClass.simpleName}"
        }
    }

    private fun startAdvertisingUnsafe(ad: android.bluetooth.BluetoothAdapter, name: String, avatar: Int) {
        val adv = ad.bluetoothLeAdvertiser
        if (adv == null) {
            hostError.value = "This phone can't advertise over Bluetooth LE."
            return
        }
        advertiser = adv
        val nameBytes = name.toByteArray(Charsets.UTF_8).let { if (it.size > 20) it.copyOf(20) else it }
        val payload = ByteArray(1 + nameBytes.size)
        payload[0] = avatar.toByte()
        System.arraycopy(nameBytes, 0, payload, 1, nameBytes.size)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE))
            .build()
        val scanResp = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(COMPANY, payload)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                hostError.value = "Bluetooth advertising failed (code $errorCode)."
            }
        }
        advCb = cb
        try {
            adv.startAdvertising(settings, data, scanResp, cb)
        } catch (e: Throwable) {
            hostError.value = "Bluetooth advertising failed."
        }
    }

    /** Receiver taps Accept or Decline. */
    fun decide(accept: Boolean) {
        val answer = if (accept) pairing else "N".toByteArray()
        var dev: BluetoothDevice? = null
        var req = 0
        var off = 0
        synchronized(lock) {
            decided = answer
            dev = pendingDevice
            req = pendingReq
            off = pendingOffset
            pendingDevice = null
        }
        incoming.value = null
        val d = dev
        if (d != null) respond(d, req, off, answer)
    }

    fun stopHost() {
        try {
            val a = advertiser
            val cb = advCb
            if (a != null && cb != null) a.stopAdvertising(cb)
        } catch (_: Throwable) {
        }
        advertiser = null
        advCb = null
        try {
            server?.close()
        } catch (_: Throwable) {
        }
        server = null
        synchronized(lock) {
            decided = null
            pendingDevice = null
            pairing = ByteArray(0)
        }
        incoming.value = null
    }

    // ---------------------------------------------------------------- sender

    private var scanner: BluetoothLeScanner? = null
    private var scanCb: ScanCallback? = null

    fun startScan(ctx: Context) {
        stopScan()
        scanError.value = null
        peers.value = emptyList()
        val ad = adapter(ctx)
        if (ad == null || !ad.isEnabled) {
            scanError.value = "Turn on Bluetooth to find nearby phones."
            return
        }
        val sc = ad.bluetoothLeScanner
        if (sc == null) {
            scanError.value = "Bluetooth scanning isn't available."
            return
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val rec = result.scanRecord ?: return
                val md = rec.getManufacturerSpecificData(COMPANY) ?: return
                if (md.isEmpty()) return
                val avatar = md[0].toInt() and 0xFF
                val name = String(md, 1, md.size - 1, Charsets.UTF_8)
                val addr = result.device.address
                val now = System.currentTimeMillis()
                val list = peers.value.filter { it.address != addr } + Peer(addr, name, avatar, now)
                peers.value = list.sortedBy { it.name.lowercase() }
            }

            override fun onScanFailed(errorCode: Int) {
                scanError.value = "Bluetooth scan failed (code $errorCode)."
            }
        }
        scanCb = cb
        scanner = sc
        try {
            sc.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                cb
            )
        } catch (e: Throwable) {
            scanError.value = "Couldn't start the Bluetooth scan."
        }
    }

    fun prune(maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        val cur = peers.value
        val keep = cur.filter { now - it.seen < maxAgeMs }
        if (keep.size != cur.size) peers.value = keep
    }

    fun stopScan() {
        val s = scanner
        val cb = scanCb
        if (s != null && cb != null) {
            try {
                s.stopScan(cb)
            } catch (_: Throwable) {
            }
        }
        scanner = null
        scanCb = null
    }

    /**
     * Asks [peer] to accept a transfer. Returns the pairing link when accepted,
     * "N" when declined, or null when the phone couldn't be reached.
     */
    @Suppress("DEPRECATION")
    suspend fun requestPairing(
        ctx: Context, peer: Peer, name: String, avatar: Int, count: Int, total: Long, first: String
    ): String? {
        val ad = adapter(ctx) ?: return null
        val dev = try {
            ad.getRemoteDevice(peer.address)
        } catch (_: Throwable) {
            return null
        }
        val done = CompletableDeferred<String?>()
        var gatt: BluetoothGatt? = null
        val req = encodeRequest(Request(name, avatar, count, total, first))
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!g.requestMtu(247)) g.discoverServices()
                } else {
                    done.complete(null)
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                g.discoverServices()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val ch = g.getService(SERVICE)?.getCharacteristic(REQ)
                if (ch == null) {
                    done.complete(null)
                    return
                }
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = req
                if (!g.writeCharacteristic(ch)) done.complete(null)
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                val pc = g.getService(SERVICE)?.getCharacteristic(PAIR)
                if (status != BluetoothGatt.GATT_SUCCESS || pc == null || !g.readCharacteristic(pc)) {
                    done.complete(null)
                }
            }

            override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                val v = ch.value
                done.complete(if (status == BluetoothGatt.GATT_SUCCESS && v != null) String(v, Charsets.UTF_8) else null)
            }
        }
        return try {
            gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
            withTimeoutOrNull(40_000) { done.await() }
        } finally {
            try {
                gatt?.disconnect()
                gatt?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun encodeRequest(r: Request): ByteArray {
        val bo = ByteArrayOutputStream()
        val o = DataOutputStream(bo)
        o.writeByte(r.avatar)
        o.writeUTF(r.name)
        o.writeInt(r.count)
        o.writeLong(r.total)
        o.writeUTF(r.first.take(40))
        return bo.toByteArray()
    }

    private fun decodeRequest(b: ByteArray): Request? = try {
        val i = DataInputStream(b.inputStream())
        val avatar = i.readUnsignedByte()
        val name = i.readUTF()
        val count = i.readInt()
        val total = i.readLong()
        val first = i.readUTF()
        Request(name, avatar, count, total, first)
    } catch (_: Throwable) {
        null
    }
}
