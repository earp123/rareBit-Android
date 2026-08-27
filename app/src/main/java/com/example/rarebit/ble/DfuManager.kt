package com.example.rarebit.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import io.runtime.mcumgr.ble.McuMgrBleTransport
import io.runtime.mcumgr.dfu.FirmwareUpgradeCallback
import io.runtime.mcumgr.dfu.FirmwareUpgradeController
import io.runtime.mcumgr.dfu.mcuboot.FirmwareUpgradeManager
import io.runtime.mcumgr.exception.McuMgrException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class DfuState {
    object Idle : DfuState()
    object Downloading : DfuState()
    object Uploading : DfuState()
    data class Progress(val percent: Int) : DfuState()
    object Success : DfuState()
    data class Error(val message: String) : DfuState()
}

class DfuManager(private val context: Context) {

    private val _state = MutableStateFlow<DfuState>(DfuState.Idle)
    val state: StateFlow<DfuState> = _state.asStateFlow()

    private var upgradeManager: FirmwareUpgradeManager? = null
    private var transport: McuMgrBleTransport? = null

    suspend fun downloadAndUpgrade(device: BluetoothDevice, assetApiUrl: String, pat: String) {
        _state.value = DfuState.Downloading
        val imageData = try {
            FirmwareRepository.downloadFirmware(assetApiUrl, pat)
        } catch (e: Exception) {
            _state.value = DfuState.Error(e.message ?: "Download failed")
            return
        }
        startUpgrade(device, imageData)
    }

    fun startUpgrade(device: BluetoothDevice, imageData: ByteArray) {
        cancel()

        val t = McuMgrBleTransport(context, device)
        transport = t

        val callback = object : FirmwareUpgradeCallback<FirmwareUpgradeManager.State> {
            override fun onUpgradeStarted(controller: FirmwareUpgradeController) {
                _state.value = DfuState.Uploading
            }

            override fun onStateChanged(
                prevState: FirmwareUpgradeManager.State,
                newState: FirmwareUpgradeManager.State
            ) {}

            override fun onUploadProgressChanged(bytesSent: Int, imageSize: Int, timeElapsed: Long) {
                val pct = if (imageSize > 0) (bytesSent * 100) / imageSize else 0
                _state.value = DfuState.Progress(pct)
            }

            override fun onUpgradeCompleted() {
                _state.value = DfuState.Success
            }

            override fun onUpgradeFailed(
                lastState: FirmwareUpgradeManager.State,
                error: McuMgrException
            ) {
                _state.value = DfuState.Error(error.message ?: "Unknown error")
            }

            override fun onUpgradeCanceled(lastState: FirmwareUpgradeManager.State) {
                _state.value = DfuState.Idle
            }
        }

        val mgr = FirmwareUpgradeManager(t, callback)
        upgradeManager = mgr

        // Confirm-only, matching iOS: single reboot, no revert-if-unconfirmed
        // failure mode (TEST_AND_CONFIRM reverts the image if the phone fails
        // to reconnect after the device resets).
        mgr.setMode(FirmwareUpgradeManager.Mode.CONFIRM_ONLY)
        mgr.setCallbackOnUiThread(true)

        val settings = FirmwareUpgradeManager.Settings.Builder()
            .setEstimatedSwapTime(3000)
            .build()

        try {
            mgr.start(imageData, settings)
        } catch (e: McuMgrException) {
            _state.value = DfuState.Error(e.message ?: "Failed to start upgrade")
        }
    }

    fun cancel() {
        upgradeManager?.cancel()
        upgradeManager = null
        transport?.close()
        transport = null
        _state.value = DfuState.Idle
    }
}
