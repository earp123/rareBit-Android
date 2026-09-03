package com.example.rarebit.ble

import android.bluetooth.BluetoothDevice

enum class DeviceType { FLAG, RECEIVER, RELAY, UNKNOWN }

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
    val configByte: Int = -1,           // raw device-reported CFG byte (write base)
    val shortPressEnabled: Boolean = false,
    val shortPressDelay: Int = -1,      // bits 5-2, ×20 ms (0-15); -1 = not read
    val isDfuOnly: Boolean = false
) {
    val address: String get() = bluetoothDevice.address

    // Battery-only glow, matching iOS: full=Green, high=Cyan, mid=Blue, low=Red,
    // unknown=Yellow. Updates are shown as a badge, never as the glow color.
    val glowState: GlowState
        get() = when {
            configInterval == 0 -> GlowState.RED
            configInterval == 1 -> GlowState.BLUE
            configInterval == 2 -> GlowState.CYAN
            configInterval == 3 -> GlowState.GREEN
            batteryLevel > 95   -> GlowState.GREEN
            batteryLevel > 75   -> GlowState.CYAN
            batteryLevel >= 25  -> GlowState.BLUE
            batteryLevel >= 0   -> GlowState.RED
            else                -> GlowState.YELLOW
        }
}
