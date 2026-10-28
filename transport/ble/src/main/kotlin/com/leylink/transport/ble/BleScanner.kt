package com.leylink.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid

/** Scans only for our service UUID. One long scan (Android throttles repeated scan restarts). */
@SuppressLint("MissingPermission") // permissions are checked by the app before the mesh starts
internal class BleScanner(
    private val adapter: BluetoothAdapter,
    private val onFound: (device: BluetoothDevice, advert: BleConstants.Advert, rssi: Int) -> Unit,
) {
    private var callback: ScanCallback? = null

    val isScanning: Boolean get() = callback != null

    fun start(): Boolean {
        if (callback != null) return true
        val scanner = adapter.bluetoothLeScanner ?: return false
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // foreground service: favour fast discovery
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handle(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { handle(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                logw("scan failed, error $errorCode")
            }
        }
        callback = cb
        scanner.startScan(listOf(filter), settings, cb)
        return true
    }

    fun stop() {
        val cb = callback ?: return
        adapter.bluetoothLeScanner?.stopScan(cb)
        callback = null
    }

    private fun handle(result: ScanResult) {
        val payload = result.scanRecord?.getManufacturerSpecificData(BleConstants.COMPANY_ID) ?: return
        val advert = BleConstants.parseAdvertPayload(payload) ?: return
        onFound(result.device, advert, result.rssi)
    }
}
