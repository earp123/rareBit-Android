package com.example.rarebit.ble

import android.bluetooth.BluetoothDevice

enum class DeviceType { FLAG, RECEIVER, UNKNOWN }

enum class GlowState { GREEN, CYAN, BLUE, YELLOW, RED }

// configInterval: top 2 bits of CFG characteristic (-1 = not yet read)
// 0b00=LOW(Red), 0b01=MID(Blue), 0b10=HIGH(Cyan), 0b11=FULL(Green)
data class BleDevice(
    val bluetoothDevice: BluetoothDevice,
    val name: String,
    val rssi: Int,
    val isConnected: Boolean = false,
    val batteryLevel: Int = -1,
    val hasUpdate: Boolean = false,
    val deviceType: DeviceType = DeviceType.UNKNOWN,
    val firmwareVersion: String = "",
    val configInterval: Int = -1,
    val isDfuOnly: Boolean = false
) {
    val address: String get() = bluetoothDevice.address

    val glowState: GlowState
        get() = when {
            hasUpdate         -> GlowState.YELLOW
            configInterval == 0 -> GlowState.RED
            configInterval == 1 -> GlowState.BLUE
            configInterval == 2 -> GlowState.CYAN
            configInterval == 3 -> GlowState.GREEN
            batteryLevel in 0..20  -> GlowState.RED
            batteryLevel in 21..60 -> GlowState.CYAN
            else                   -> GlowState.GREEN
        }
}
