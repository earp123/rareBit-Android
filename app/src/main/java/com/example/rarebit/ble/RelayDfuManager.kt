package com.example.rarebit.ble

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.dfu.DfuProgressListenerAdapter
import no.nordicsemi.android.dfu.DfuServiceController
import no.nordicsemi.android.dfu.DfuServiceInitiator
import no.nordicsemi.android.dfu.DfuServiceListenerHelper
import java.io.File
import java.security.MessageDigest

/**
 * Relay OTA DFU over the OTAFIX bootloader's Nordic legacy DFU. Sibling of the
 * SMP [DfuManager] — Flag / Receiver / RXRLY never come through here.
 *
 * download → SHA-256 → trigger 0xA8 → await disconnect → 1530 scan → flash.
 *
 * Owns its own scope so the flash survives the detail screen.
 */
class RelayDfuManager(context: Context, private val bleManager: BleManager) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow<DfuState>(DfuState.Idle)
    val state: StateFlow<DfuState> = _state.asStateFlow()

    // Step text for the status line. DfuState is shared with the SMP flow and
    // can't express "waiting for update mode", so it travels separately.
    private val _stage = MutableStateFlow("")
    val stage: StateFlow<String> = _stage.asStateFlow()

    private var job: Job? = null
    private var controller: DfuServiceController? = null
    private var listener: DfuProgressListenerAdapter? = null

    /** True from start until Success/Error/cancel, including the library's upload. */
    val isActive: Boolean get() = job?.isActive == true || controller != null

    fun start(address: String, release: RelayReleaseInfo, pat: String) {
        cancel()
        job = scope.launch {
            try {
                run(address, release, pat)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun run(address: String, release: RelayReleaseInfo, pat: String) {
        _state.value = DfuState.Downloading
        step("Downloading ${release.tag}…")
        val zip = FirmwareRepository.downloadRelayZip(release, pat)

        val digest = withContext(Dispatchers.Default) { sha256Hex(zip) }
        if (digest != release.sha256) {
            Log.e(TAG, "SHA-256 mismatch: got $digest want ${release.sha256}")
            return fail("Downloaded package failed SHA-256 verification")
        }
        val file = withContext(Dispatchers.IO) {
            File(appContext.cacheDir, "relay-dfu.zip").apply { writeBytes(zip) }
        }
        Log.i(TAG, "package verified (${zip.size} bytes) ${release.tag}")

        step("Rebooting Relay into update mode…")
        when (val status = bleManager.writeDfuTrigger(address)) {
            0 -> Log.i(TAG, "trigger accepted")
            null -> return fail("Relay has no update trigger — firmware may predate OTA support")
            BleManager.TRIGGER_NO_RESPONSE -> return fail("Relay did not respond to the update trigger")
            0x03 -> return fail("Dock the Relay on USB power")
            0x13 -> return fail("Relay rejected the update trigger (wrong byte)")
            0x0D -> return fail("Relay rejected the update trigger (wrong length)")
            else -> return fail("Relay rejected the update trigger (ATT 0x%02X)".format(status))
        }

        // The disconnect is the success signal (~500 ms after the write response)
        step("Waiting for update mode…")
        val rebooted = withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
            bleManager.devices.first { list ->
                list.firstOrNull { it.address == address }?.isConnected != true
            }
        }
        if (rebooted == null) return fail("Relay did not reboot after the update trigger")

        val bootloader = bleManager.scanForLegacyDfuBootloader(BOOTLOADER_SCAN_TIMEOUT_MS)
            ?: return fail("Relay did not reappear in update mode")
        Log.i(TAG, "bootloader found at $bootloader")

        _state.value = DfuState.Uploading
        step("Flashing…")
        flash(bootloader, file)
    }

    private fun flash(bootloaderAddress: String, zip: File) {
        val l = object : DfuProgressListenerAdapter() {
            override fun onProgressChanged(
                deviceAddress: String, percent: Int, speed: Float,
                avgSpeed: Float, currentPart: Int, partsTotal: Int
            ) {
                _state.value = DfuState.Progress(percent)
            }

            override fun onDfuCompleted(deviceAddress: String) {
                Log.i(TAG, "flash complete")
                finish()
                step("Rebooting — reconnect to confirm")
                _state.value = DfuState.Success
            }

            override fun onDfuAborted(deviceAddress: String) {
                finish()
                fail("Update aborted")
            }

            override fun onError(deviceAddress: String, error: Int, errorType: Int, message: String?) {
                Log.e(TAG, "flash error $error (type $errorType): $message")
                finish()
                fail("Flashing failed: ${message ?: "error $error"}")
            }
        }
        listener = l
        DfuServiceListenerHelper.registerProgressListener(appContext, l)
        // Zip goes in unchanged — the library auto-detects legacy DFU. PRN on.
        controller = DfuServiceInitiator(bootloaderAddress)
            .setZip(Uri.fromFile(zip))
            .setKeepBond(false)
            .setForeground(false)
            .setDisableNotification(true)
            .setPacketsReceiptNotificationsEnabled(true)
            .start(appContext, RelayDfuService::class.java)
    }

    fun cancel() {
        job?.cancel()
        job = null
        controller?.abort()
        finish()
        _stage.value = ""
        _state.value = DfuState.Idle
    }

    private fun finish() {
        listener?.let { DfuServiceListenerHelper.unregisterProgressListener(appContext, it) }
        listener = null
        controller = null
    }

    private fun step(text: String) {
        Log.i(TAG, text)
        _stage.value = text
    }

    private fun fail(message: String) {
        Log.w(TAG, "error: $message")
        _stage.value = ""
        _state.value = DfuState.Error(message)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "RelayDfu"
        const val DISCONNECT_TIMEOUT_MS = 10_000L
        const val BOOTLOADER_SCAN_TIMEOUT_MS = 15_000L
    }
}
