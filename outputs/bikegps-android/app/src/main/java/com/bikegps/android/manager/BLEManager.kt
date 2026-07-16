package com.bikegps.android.manager

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*

/**
 * Manages BLE scanning, connection, and data transmission to the ESP32.
 *
 * Nordic UART Service (NUS) UUIDs:
 *   Service: 6E400001-B5A3-F393-E0A9-E50E24DCCA9E
 *   RX (phone→ESP, write): 6E400002-B5A3-F393-E0A9-E50E24DCCA9E
 *   TX (ESP→phone, notify): 6E400003-B5A3-F393-E0A9-E50E24DCCA9E
 */
class BLEManager(private val context: Context) {

    companion object {
        val NUS_SERVICE = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_TX = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        const val DEVICE_NAME = "BikeGPS"
    }

    // ── Observable state ──────────────────────────────────────────────
    val status: StateFlow<ConnectionStatus> get() = _status
    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)

    sealed class ConnectionStatus {
        data object Idle : ConnectionStatus()
        data object Scanning : ConnectionStatus()
        data object Connecting : ConnectionStatus()
        data object Connected : ConnectionStatus()
        data class Failed(val reason: String) : ConnectionStatus()
        data object Disconnected : ConnectionStatus()
    }

    // ── Private ───────────────────────────────────────────────────────
    private var bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
    private var bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var scanCallback: ScanCallback? = null
    private var scanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Public API ────────────────────────────────────────────────────

    fun startScan() {
        if (_status.value is ConnectionStatus.Connected ||
            _status.value is ConnectionStatus.Scanning) return

        _status.value = ConnectionStatus.Scanning

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = device.name
                val scanRecord = result.scanRecord
                val uuids = scanRecord?.serviceUuids ?: emptyList()

                // Match by name or NUS service UUID
                val nameMatch = name == DEVICE_NAME
                val uuidMatch = uuids.any { it.uuid == NUS_SERVICE }
                        || result.scanRecord?.manufacturerSpecificData != null // fallback

                if (nameMatch || uuidMatch) {
                    bluetoothLeScanner?.stopScan(this)
                    _status.value = ConnectionStatus.Connecting
                    connect(device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                _status.value = ConnectionStatus.Failed("Scan failed: $errorCode")
            }
        }

        scanCallback = callback

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(NUS_SERVICE)).build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        try {
            bluetoothLeScanner?.startScan(filters, settings, callback)
            // Fallback: scan without filters if no results in 5s
            scanJob = scope.launch {
                delay(5000)
                if (_status.value is ConnectionStatus.Scanning) {
                    bluetoothLeScanner?.stopScan(callback)
                    bluetoothLeScanner?.startScan(callback)
                }
            }
        } catch (e: SecurityException) {
            _status.value = ConnectionStatus.Failed("BLE permission denied")
        }
    }

    fun disconnect() {
        scanJob?.cancel()
        scanCallback?.let { bluetoothLeScanner?.stopScan(it) }
        scanCallback = null
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        rxCharacteristic = null
        _status.value = ConnectionStatus.Disconnected
    }

    /**
     * Sends JSON data to ESP32 over BLE NUS.
     * Handles fragmentation for payloads larger than MTU.
     */
    fun send(data: ByteArray) {
        val gatt = gatt ?: return
        val rx = rxCharacteristic ?: return
        if (_status.value !is ConnectionStatus.Connected) return

        try {
            val mtu = 512
            val chunkSize = maxOf(64, mtu - 14) // reserve for "PKT:NNN/NNN:" prefix

            if (data.size <= chunkSize) {
                gatt.writeCharacteristic(
                    rx, data,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                )
            } else {
                // Fragment
                var offset = 0
                var fragIdx = 1
                val total = (data.size + chunkSize - 1) / chunkSize

                while (offset < data.size) {
                    val end = minOf(offset + chunkSize, data.size)
                    val prefix = "PKT:$fragIdx/$total:".toByteArray()
                    val packet = prefix + data.sliceArray(offset until end)

                    gatt.writeCharacteristic(
                        rx, packet,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    )

                    offset += (end - offset)
                    fragIdx++
                    Thread.sleep(6) // small gap to avoid RX buffer overflow
                }
            }
        } catch (e: SecurityException) {
            _status.value = ConnectionStatus.Failed("BLE permission denied")
        }
    }

    // ── Private: connect ──────────────────────────────────────────────

    private fun connect(device: BluetoothDevice) {
        _status.value = ConnectionStatus.Connecting
        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        this@BLEManager.gatt = null
                        rxCharacteristic = null
                        if (_status.value is ConnectionStatus.Connected) {
                            _status.value = ConnectionStatus.Disconnected
                            // Auto-reconnect
                            scope.launch {
                                delay(2000)
                                startScan()
                            }
                        } else {
                            _status.value = ConnectionStatus.Disconnected
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    _status.value = ConnectionStatus.Failed("Service discovery failed")
                    return
                }
                val service = gatt.getService(NUS_SERVICE) ?: run {
                    _status.value = ConnectionStatus.Failed("NUS service not found")
                    return
                }
                rxCharacteristic = service.getCharacteristic(NUS_RX)
                val txChar = service.getCharacteristic(NUS_TX)

                if (rxCharacteristic == null) {
                    _status.value = ConnectionStatus.Failed("RX characteristic not found")
                    return
                }

                // Enable notifications on TX characteristic (ESP→phone)
                txChar?.let { char ->
                    try {
                        gatt.setCharacteristicNotification(char, true)
                    } catch (_: SecurityException) {}
                }

                // Request larger MTU
                gatt.requestMtu(512)

                _status.value = ConnectionStatus.Connected
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                // MTU updated, used by send() via maximumWriteValueLength
            }
        })
    }

    fun onDestroy() {
        disconnect()
        scope.cancel()
    }
}
