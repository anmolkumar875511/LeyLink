package com.leylink.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.os.ParcelUuid
import com.leylink.core.model.NodeId

/**
 * Advertises our service UUID (so scanners can filter on it) and, in the scan response, our nodeId
 * (so the peer knows who we are before connecting).
 */
@SuppressLint("MissingPermission") // permissions are checked by the app before the mesh starts
internal class BleAdvertiser(
    private val adapter: BluetoothAdapter,
    private val selfId: NodeId,
) {
    private var callback: AdvertiseCallback? = null
    private var beacon = false
    private var digest = 0

    /** Changes what the scan response says (beacon flag, digest) by restarting the advertisement. */
    @Synchronized
    fun update(beacon: Boolean, digest: Int) {
        if (beacon == this.beacon && digest == this.digest) return
        this.beacon = beacon
        this.digest = digest
        if (callback != null) {
            stop()
            start()
        }
    }

    @Synchronized
    fun start(): Boolean {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return false
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0) // advertise until stopped
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(BleConstants.COMPANY_ID, BleConstants.advertPayload(selfId, beacon, digest))
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                logd("advertising started")
            }

            override fun onStartFailure(errorCode: Int) {
                logw("advertising failed, error $errorCode")
            }
        }
        callback = cb
        advertiser.startAdvertising(settings, data, scanResponse, cb)
        return true
    }

    @Synchronized
    fun stop() {
        val cb = callback ?: return
        adapter.bluetoothLeAdvertiser?.stopAdvertising(cb)
        callback = null
    }
}
