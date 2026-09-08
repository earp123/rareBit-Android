package com.example.rarebit.ble

import android.bluetooth.BluetoothDevice

enum class DeviceType { FLAG, RECEIVER, RELAY, UNKNOWN }

// Battery diagnostic characteristic (23220005-…), Flag/Receiver >= 2.0 only.
// Absent on fielded 1.9/1.8/10.0 and on the Relay — null is the normal case.
data class BatteryDiag(
    val raw: Int,
    val mv: Int,            // -1 = ADC read failed
    val errno: Int,         // 0 = OK
    val level: Int,         // 0 LOW / 1 MID / 2 HIGH / 3 FULL (mirrors CFG bits 7-6)
    val docked: Boolean,
    val statHigh: Boolean,
    val senseFault: Boolean,
    val count: Int
) {
    val readFailed: Boolean get() = mv == -1 || errno != 0

    companion object {
        /** 9 bytes little-endian; returns null if the payload is short. */
        fun parse(v: ByteArray): BatteryDiag? {
            if (v.size < 9) return null
            fun i16(lo: Int) = ((v[lo].toInt() and 0xFF) or (v[lo + 1].toInt() shl 8)).toShort().toInt()
            val flags = v[7].toInt() and 0xFF
            return BatteryDiag(
                raw = i16(0),
                mv = i16(2),
                errno = i16(4),
                level = v[6].toInt() and 0xFF,
                docked = flags and 0x01 != 0,
                statHigh = flags and 0x02 != 0,
                senseFault = flags and 0x04 != 0,
                count = v[8].toInt() and 0xFF
            )
        }
    }
}

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
    val isDfuOnly: Boolean = false,
    val batteryDiag: BatteryDiag? = null
) {
    val address: String get() = bluetoothDevice.address

    // Battery-only glow, matching iOS: full=Green, high=Cyan, mid=Blue, low=Red,
    // unknown=Yellow. Updates are shown as a badge, never as the glow color.
    // A faulted or unreadable sense path outranks the CFG bits: those report
    // LOW forever, which would show a red glow that isn't true.
    val glowState: GlowState
        get() = when {
            batteryDiag?.senseFault == true -> GlowState.YELLOW
            batteryDiag?.readFailed == true -> GlowState.YELLOW
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
