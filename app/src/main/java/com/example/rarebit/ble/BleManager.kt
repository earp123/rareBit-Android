package com.example.rarebit.ble

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

    // Per-device GATT service lists; DeviceDetailFragment filters by its own address
    private val _gattServicesMap = MutableStateFlow<Map<String, List<GattServiceItem>>>(emptyMap())
    val gattServicesMap: StateFlow<Map<String, List<GattServiceItem>>> = _gattServicesMap.asStateFlow()

    private val _charValues = MutableSharedFlow<Pair<String, ByteArray>>(replay = 0)
    val charValues: SharedFlow<Pair<String, ByteArray>> = _charValues.asSharedFlow()

    // One GATT handle, read queue, and connect job per device address
    private val activeGatts  = LinkedHashMap<String, BluetoothGatt>()
    private val readQueues   = LinkedHashMap<String, ArrayDeque<BluetoothGattCharacteristic>>()
    private val connectJobs  = LinkedHashMap<String, Job>()
    private val deviceMap    = LinkedHashMap<String, BleDevice>()
    private val cfgNotifySubscribed = HashSet<String>()

    // ── SCAN ──────────────────────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val rawName = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!rawName.contains("rareBit", ignoreCase = true)) return
            val name = rawName.replace("rareBit", "", ignoreCase = true).trim()
            val address = result.device.address
            val existing = deviceMap[address]
            // Exact advertised names, mirroring iOS RareBitDeviceType.from()
            val deviceType = when (rawName) {
                NAME_FLAG     -> DeviceType.FLAG
                NAME_RECEIVER -> DeviceType.RECEIVER
                NAME_RELAY    -> DeviceType.RELAY
                else          -> DeviceType.UNKNOWN
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
        // Hardware-level filters (OR semantics). Flag/Receiver firmware doesn't
        // advertise any service UUIDs yet, so exact names cover them; the CFG
        // service UUID covers the Relay (and any future firmware that
        // advertises it). Bootloaders / third-party devices match neither.
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(CFG_SERVICE_UUID)).build(),
            ScanFilter.Builder().setDeviceName(NAME_FLAG).build(),
            ScanFilter.Builder().setDeviceName(NAME_RECEIVER).build(),
            ScanFilter.Builder().setDeviceName(NAME_RELAY).build()
        )
        scanner?.startScan(filters, settings, scanCallback)
        scope.launch {
            delay(10_000)
            stopScan()
        }
    }

    fun stopScan() {
        if (!_isScanning.value) return
        scanner?.stopScan(scanCallback)
        _isScanning.value = false
    }

    // ── GATT ──────────────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
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
            val address = gatt.device.address
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
            mainHandler.post {
                _gattServicesMap.value = _gattServicesMap.value.toMutableMap().also { it[address] = services }
            }

            val hasConfigService = gatt.services.any { it.uuid == CFG_SERVICE_UUID }
            updateDevice(address) { it.copy(isDfuOnly = !hasConfigService) }

            // Build per-device auto-read queue
            val queue = ArrayDeque<BluetoothGattCharacteristic>()
            gatt.services.forEach { svc ->
                svc.characteristics.forEach { char ->
                    val autoRead = char.uuid == BATTERY_LEVEL_UUID ||
                                   char.uuid == FW_CHAR_UUID ||
                                   char.uuid == CFG_CHAR_UUID
                    val readable = char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
                    if (autoRead && readable) queue.addLast(char)
                }
            }
            readQueues[address] = queue
            queue.removeFirstOrNull()?.let { gatt.readCharacteristic(it) }
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
            characteristic.value?.let { parseCharValue(gatt.device.address, characteristic.uuid, it) }
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            parseCharValue(gatt.device.address, characteristic.uuid, value)
        }

        // Note: write/descriptor callbacks must NOT touch the read queue — it is
        // advanced only by handleCharRead, one entry per completed read.
    }

    private fun handleGattDisconnect(gatt: BluetoothGatt) {
        val address = gatt.device.address
        readQueues.remove(address)
        cfgNotifySubscribed.remove(address)
        gatt.close()
        activeGatts.remove(address)
        mainHandler.post {
            _gattServicesMap.value = _gattServicesMap.value.toMutableMap().also { it.remove(address) }
        }
        updateDevice(address) {
            it.copy(isConnected = false, batteryLevel = -1, configInterval = -1,
                    firmwareVersion = "", hasUpdate = false,
                    configByte = -1, shortPressDelay = -1, shortPressEnabled = false)
        }
    }

    private fun handleCharRead(gatt: BluetoothGatt, uuid: UUID, value: ByteArray) {
        val address = gatt.device.address
        parseCharValue(address, uuid, value)
        val next = readQueues[address]?.removeFirstOrNull()
        if (next != null) {
            gatt.readCharacteristic(next)
        } else if (cfgNotifySubscribed.add(address)) {
            // Read queue drained — the GATT is idle, safe to write the CCC
            // descriptor. Live CFG updates (battery, config echoes) from here on.
            setNotification(address, CFG_SERVICE_UUID, CFG_CHAR_UUID, true)
        }
    }

    // Shared by reads and notifications so both update device state
    private fun parseCharValue(address: String, uuid: UUID, value: ByteArray) {
        updateCharValue(address, uuid.toString(), value)
        if (value.isEmpty()) return
        when (uuid) {
            BATTERY_LEVEL_UUID -> {
                val level = value[0].toInt() and 0xFF
                updateDevice(address) { it.copy(batteryLevel = level) }
            }
            FW_CHAR_UUID -> {
                val b = value[0].toInt() and 0xFF
                val version = "${(b shr 4)}.${b and 0x0F}"
                updateDevice(address) { it.copy(firmwareVersion = version) }
            }
            CFG_CHAR_UUID -> applyConfigByte(address, value[0].toInt() and 0xFF)
        }
    }

    // CFG byte layout (iOS parity): bits7-6 battery, bits5-2 delay ×20ms,
    // bit0 short-press enable
    private fun applyConfigByte(address: String, byte: Int) {
        updateDevice(address) {
            it.copy(
                configByte = byte,
                configInterval = byte shr 6,
                shortPressDelay = (byte shr 2) and 0x0F,
                shortPressEnabled = (byte and 0x01) != 0
            )
        }
    }

    // ── CONFIG WRITES ─────────────────────────────────────────────────────────
    // iOS-parity invariants: never write without a device-reported base byte,
    // modify only the target bits, skip no-op writes, update UI optimistically.

    fun setShortPressEnabled(address: String, enabled: Boolean) {
        writeConfigBits(address) { base ->
            if (enabled) base or 0b0000_0001 else base and 0b1111_1110
        }
    }

    fun setShortPressDelay(address: String, delay: Int) {
        val d = delay.coerceIn(0, 15)
        writeConfigBits(address) { base -> (base and 0b1100_0011) or (d shl 2) }
    }

    private fun writeConfigBits(address: String, transform: (Int) -> Int) {
        val base = deviceMap[address]?.configByte ?: return
        if (base < 0) return  // no device-reported byte yet — never write 0x00
        val newByte = transform(base) and 0xFF
        if (newByte == base) return
        applyConfigByte(address, newByte)
        writeCharacteristic(address, CFG_SERVICE_UUID, CFG_CHAR_UUID, byteArrayOf(newByte.toByte()))
    }

    fun connect(device: BleDevice) {
        stopScan()
        if (activeGatts.containsKey(device.address)) return  // already connected or connecting
        doConnect(device)
    }

    private fun doConnect(device: BleDevice) {
        connectJobs[device.address]?.cancel()
        connectJobs[device.address] = scope.launch {
            delay(200)
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.bluetoothDevice.connectGatt(
                    appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE
                )
            } else {
                @Suppress("DEPRECATION")
                device.bluetoothDevice.connectGatt(appContext, false, gattCallback)
            }
            activeGatts[device.address] = gatt
            connectJobs.remove(device.address)
        }
    }

    fun disconnect(address: String) {
        connectJobs[address]?.cancel()
        connectJobs.remove(address)
        readQueues.remove(address)
        activeGatts[address]?.disconnect()
        // close() called in STATE_DISCONNECTED callback
    }

    fun readCharacteristic(address: String, serviceUuid: UUID, charUuid: UUID) {
        val gatt = activeGatts[address] ?: return
        val char = gatt.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return
        gatt.readCharacteristic(char)
    }

    fun writeCharacteristic(address: String, serviceUuid: UUID, charUuid: UUID, value: ByteArray) {
        val gatt = activeGatts[address] ?: return
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

    fun setNotification(address: String, serviceUuid: UUID, charUuid: UUID, enable: Boolean) {
        val gatt = activeGatts[address] ?: return
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

    fun setHasUpdate(address: String, hasUpdate: Boolean) {
        updateDevice(address) { it.copy(hasUpdate = hasUpdate) }
    }

    fun clearDisconnectedDevices() {
        val iter = deviceMap.iterator()
        while (iter.hasNext()) {
            if (!iter.next().value.isConnected) iter.remove()
        }
        mainHandler.post { _devices.value = deviceMap.values.sortedBy { it.name } }
    }

    fun scheduleScan(delayMs: Long) {
        scope.launch {
            delay(delayMs)
            startScan()
        }
    }

    fun cleanup() {
        stopScan()
        connectJobs.values.forEach { it.cancel() }
        connectJobs.clear()
        readQueues.clear()
        activeGatts.values.forEach { it.disconnect(); it.close() }
        activeGatts.clear()
        scope.cancel()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updateDevice(address: String, update: (BleDevice) -> BleDevice) {
        val existing = deviceMap[address] ?: return
        deviceMap[address] = update(existing)
        mainHandler.post { _devices.value = deviceMap.values.sortedBy { it.name } }
    }

    private fun updateCharValue(address: String, uuid: String, value: ByteArray) {
        mainHandler.post {
            val updated = _gattServicesMap.value.toMutableMap()
            updated[address] = updated[address]?.map { service ->
                service.copy(characteristics = service.characteristics.map { char ->
                    if (char.uuid.toString().equals(uuid, ignoreCase = true))
                        char.copy(value = value)
                    else char
                })
            } ?: emptyList()
            _gattServicesMap.value = updated
        }
        scope.launch { _charValues.emit(uuid to value) }
    }

    // ── GATT name tables ──────────────────────────────────────────────────────

    companion object {
        // Exact advertised names (match iOS RareBitDeviceType raw values)
        const val NAME_FLAG     = "rareBit PRO Flag"
        const val NAME_RECEIVER = "rareBit PRO Receiver"
        const val NAME_RELAY    = "rareBit Relay"

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
