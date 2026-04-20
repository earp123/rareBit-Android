package com.example.rarebit.ble

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.example.rarebit.BuildConfig
import java.util.UUID

data class GattServiceItem(
    val uuid: UUID,
    val name: String,
    val characteristics: List<GattCharItem>,
    val isExpanded: Boolean = true
)

@Suppress("ArrayInDataClass")
data class GattCharItem(
    val uuid: UUID,
    val name: String,
    val properties: Int,
    val value: ByteArray? = null
) {
    val canRead get() = properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
    val canWrite get() = properties and
            (BluetoothGattCharacteristic.PROPERTY_WRITE or
             BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    val canNotify get() = properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0

    val valueHex: String
        get() = value?.joinToString(" ") { "%02X".format(it) } ?: "--"

    val valueAscii: String
        get() = value?.let { bytes ->
            String(bytes, Charsets.ISO_8859_1).map { c ->
                if (c.code in 32..126) c else '.'
            }.joinToString("")
        } ?: "--"

    val valueDisplay: String
        get() = if (value != null) "$valueHex  |  $valueAscii" else "--"
}

class BleManager(context: Context) {

    private val appContext: Context = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val scanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _gattServices = MutableStateFlow<List<GattServiceItem>>(emptyList())
    val gattServices: StateFlow<List<GattServiceItem>> = _gattServices.asStateFlow()

    private val _charValues = MutableSharedFlow<Pair<String, ByteArray>>(replay = 0)
    val charValues: SharedFlow<Pair<String, ByteArray>> = _charValues.asSharedFlow()

    private var activeGatt: BluetoothGatt? = null
    private val deviceMap = LinkedHashMap<String, BleDevice>()
    private val readQueue = ArrayDeque<BluetoothGattCharacteristic>()

    // ── SCAN ──────────────────────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!name.contains("rareBit", ignoreCase = true)) return
            val address = result.device.address
            val existing = deviceMap[address]
            val deviceType = when {
                name.contains("flag", ignoreCase = true)     -> DeviceType.FLAG
                name.contains("receiver", ignoreCase = true) -> DeviceType.RECEIVER
                else                                         -> DeviceType.UNKNOWN
            }
            deviceMap[address] = existing?.copy(rssi = result.rssi)
                ?: BleDevice(bluetoothDevice = result.device, name = name, rssi = result.rssi, deviceType = deviceType)
            _devices.value = deviceMap.values.sortedBy { it.name }
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
        }
    }

    fun startScan() {
        if (_isScanning.value) return
        _isScanning.value = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(null, settings, scanCallback)
        scope.launch {
            kotlinx.coroutines.delay(10_000)
            stopScan()
        }
    }

    fun stopScan() {
        if (!_isScanning.value) return
        scanner?.stopScan(scanCallback)
        _isScanning.value = false
    }

    fun clearDisconnectedDevices() {
        deviceMap.entries.removeIf { !it.value.isConnected }
        _devices.value = deviceMap.values.sortedBy { it.name }
    }

    // ── GATT ──────────────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Error (incl. 133/GATT_ERROR) — treat as disconnection and clean up
                handleGattDisconnect(gatt)
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt.discoverServices()
                    updateDevice(gatt.device.address) { it.copy(isConnected = true) }
                }
                BluetoothProfile.STATE_DISCONNECTED -> handleGattDisconnect(gatt)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val services = gatt.services.map { service ->
                GattServiceItem(
                    uuid = service.uuid,
                    name = knownServiceName(service.uuid),
                    characteristics = service.characteristics.map { char ->
                        GattCharItem(
                            uuid = char.uuid,
                            name = knownCharName(char.uuid),
                            properties = char.properties
                        )
                    }
                )
            }
            mainHandler.post { _gattServices.value = services }

            val hasConfigService = gatt.services.any { it.uuid == CFG_SERVICE_UUID }
            updateDevice(gatt.device.address) { it.copy(isDfuOnly = !hasConfigService) }

            // Queue auto-reads: battery, firmware version, config
            readQueue.clear()
            gatt.services.forEach { svc ->
                svc.characteristics.forEach { char ->
                    val autoRead = char.uuid == BATTERY_LEVEL_UUID ||
                                   char.uuid == FW_CHAR_UUID ||
                                   char.uuid == CFG_CHAR_UUID
                    val readable = char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
                    if (autoRead && readable) readQueue.addLast(char)
                }
            }
            readQueue.removeFirstOrNull()?.let { gatt.readCharacteristic(it) }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (status != BluetoothGatt.GATT_SUCCESS) return
            characteristic.value?.let { handleCharRead(gatt, characteristic.uuid, it) }
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            handleCharRead(gatt, characteristic.uuid, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            characteristic.value?.let { updateCharValue(characteristic.uuid.toString(), it) }
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            updateCharValue(characteristic.uuid.toString(), value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            // Advance the op queue so a pending read/write after this one can proceed
            readQueue.removeFirstOrNull()?.let { gatt.readCharacteristic(it) }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            readQueue.removeFirstOrNull()?.let { gatt.readCharacteristic(it) }
        }
    }

    private fun handleGattDisconnect(gatt: BluetoothGatt) {
        readQueue.clear()
        gatt.close()
        if (gatt == activeGatt) {
            activeGatt = null
            mainHandler.post { _gattServices.value = emptyList() }
        }
        updateDevice(gatt.device.address) {
            it.copy(isConnected = false, batteryLevel = -1, configInterval = -1, firmwareVersion = "")
        }
    }

    private fun handleCharRead(gatt: BluetoothGatt, uuid: UUID, value: ByteArray) {
        updateCharValue(uuid.toString(), value)
        when (uuid) {
            BATTERY_LEVEL_UUID -> if (value.isNotEmpty()) {
                val level = value[0].toInt() and 0xFF
                updateDevice(gatt.device.address) { it.copy(batteryLevel = level) }
            }
            FW_CHAR_UUID -> if (value.isNotEmpty()) {
                val b = value[0].toInt() and 0xFF
                val version = "${(b shr 4)}.${b and 0x0F}"
                updateDevice(gatt.device.address) { it.copy(firmwareVersion = version) }
            }
            CFG_CHAR_UUID -> if (value.isNotEmpty()) {
                val interval = (value[0].toInt() and 0xFF) shr 6
                updateDevice(gatt.device.address) { it.copy(configInterval = interval) }
            }
        }
        // Advance the auto-read queue
        readQueue.removeFirstOrNull()?.let { gatt.readCharacteristic(it) }
    }

    fun connect(device: BleDevice) {
        readQueue.clear()
        activeGatt?.disconnect()
        activeGatt?.close()
        activeGatt = null
        // Give the BLE stack a moment to settle after close before opening a new GATT
        scope.launch {
            kotlinx.coroutines.delay(300)
            activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.bluetoothDevice.connectGatt(
                    appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE
                )
            } else {
                @Suppress("DEPRECATION")
                device.bluetoothDevice.connectGatt(appContext, false, gattCallback)
            }
        }
    }

    fun disconnect(address: String) {
        readQueue.clear()
        activeGatt?.disconnect()
        // close() is called in onConnectionStateChange STATE_DISCONNECTED
    }

    fun readCharacteristic(serviceUuid: UUID, charUuid: UUID) {
        val gatt = activeGatt ?: return
        val char = gatt.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return
        gatt.readCharacteristic(char)
    }

    fun writeCharacteristic(serviceUuid: UUID, charUuid: UUID, value: ByteArray) {
        val gatt = activeGatt ?: return
        val char = gatt.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            char.value = value
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    fun setNotification(serviceUuid: UUID, charUuid: UUID, enable: Boolean) {
        val gatt = activeGatt ?: return
        val char = gatt.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return
        gatt.setCharacteristicNotification(char, enable)
        val descriptor = char.getDescriptor(CLIENT_CHAR_CONFIG_UUID) ?: return
        val value = if (enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    fun cleanup() {
        stopScan()
        readQueue.clear()
        activeGatt?.disconnect()
        activeGatt?.close()
        activeGatt = null
        scope.cancel()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updateDevice(address: String, update: (BleDevice) -> BleDevice) {
        val existing = deviceMap[address] ?: return
        deviceMap[address] = update(existing)
        mainHandler.post { _devices.value = deviceMap.values.sortedBy { it.name } }
    }

    private fun updateCharValue(uuid: String, value: ByteArray) {
        mainHandler.post {
            _gattServices.value = _gattServices.value.map { service ->
                service.copy(characteristics = service.characteristics.map { char ->
                    if (char.uuid.toString().equals(uuid, ignoreCase = true))
                        char.copy(value = value)
                    else char
                })
            }
        }
        scope.launch { _charValues.emit(uuid to value) }
    }

    // ── GATT name tables ──────────────────────────────────────────────────────

    companion object {
        val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")
        val CLIENT_CHAR_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        // rareBit Config Service
        val CFG_SERVICE_UUID: UUID = UUID.fromString(BuildConfig.BLE_CFG_SERVICE_UUID)
        val CFG_CHAR_UUID: UUID    = UUID.fromString(BuildConfig.BLE_CFG_CHAR_UUID)
        val FW_CHAR_UUID: UUID     = UUID.fromString(BuildConfig.BLE_FW_CHAR_UUID)

        // SMP Service (MCUboot DFU)
        val SMP_SERVICE_UUID: UUID = UUID.fromString(BuildConfig.BLE_SMP_SERVICE_UUID)

        private val SERVICE_NAMES = mapOf(
            "0000180f-0000-1000-8000-00805f9b34fb" to "Battery Service",
            "0000180a-0000-1000-8000-00805f9b34fb" to "Device Information",
            "00001800-0000-1000-8000-00805f9b34fb" to "Generic Access",
            "00001801-0000-1000-8000-00805f9b34fb" to "Generic Attribute",
            "00001812-0000-1000-8000-00805f9b34fb" to "Human Interface Device",
            "00001809-0000-1000-8000-00805f9b34fb" to "Health Thermometer",
            BuildConfig.BLE_CFG_SERVICE_UUID.lowercase() to "rareBit Config",
            BuildConfig.BLE_SMP_SERVICE_UUID.lowercase() to "SMP"
        )

        private val CHAR_NAMES = mapOf(
            "00002a19-0000-1000-8000-00805f9b34fb" to "Battery Level",
            "00002a00-0000-1000-8000-00805f9b34fb" to "Device Name",
            "00002a01-0000-1000-8000-00805f9b34fb" to "Appearance",
            "00002a29-0000-1000-8000-00805f9b34fb" to "Manufacturer Name",
            "00002a24-0000-1000-8000-00805f9b34fb" to "Model Number",
            "00002a25-0000-1000-8000-00805f9b34fb" to "Serial Number",
            "00002a26-0000-1000-8000-00805f9b34fb" to "Firmware Revision",
            "00002a27-0000-1000-8000-00805f9b34fb" to "Hardware Revision",
            "00002a28-0000-1000-8000-00805f9b34fb" to "Software Revision",
            BuildConfig.BLE_CFG_CHAR_UUID.lowercase() to "Config",
            BuildConfig.BLE_FW_CHAR_UUID.lowercase() to "Firmware Version"
        )

        fun knownServiceName(uuid: UUID): String =
            SERVICE_NAMES[uuid.toString().lowercase()] ?: uuid.toString()

        fun knownCharName(uuid: UUID): String =
            CHAR_NAMES[uuid.toString().lowercase()] ?: uuid.toString()
    }
}
