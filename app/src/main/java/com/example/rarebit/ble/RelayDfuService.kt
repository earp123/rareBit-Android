package com.example.rarebit.ble

import android.app.Activity
import com.example.rarebit.BuildConfig
import com.example.rarebit.MainActivity
import no.nordicsemi.android.dfu.DfuBaseService

/** Nordic legacy DFU transport for the Relay's OTAFIX bootloader (driven by [RelayDfuManager]). */
class RelayDfuService : DfuBaseService() {
    // The initiator disables notifications, but the library still requires a target.
    override fun getNotificationTarget(): Class<out Activity> = MainActivity::class.java

    override fun isDebug(): Boolean = BuildConfig.DEBUG
}
