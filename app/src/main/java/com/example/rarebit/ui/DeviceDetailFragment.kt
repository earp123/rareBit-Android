package com.example.rarebit.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.rarebit.BuildConfig
import com.example.rarebit.MainActivity
import com.example.rarebit.R
import com.example.rarebit.ble.DeviceType
import com.example.rarebit.ble.DfuState
import com.example.rarebit.ble.FirmwareRepository
import com.example.rarebit.ble.GlowState
import com.example.rarebit.ble.ReleaseInfo
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class DeviceDetailFragment : Fragment() {

    private val deviceAddress: String by lazy {
        arguments?.getString("deviceAddress") ?: ""
    }
    private val bleManager get() = (requireActivity() as MainActivity).bleManager
    private val dfuManager get() = (requireActivity() as MainActivity).dfuManager

    private enum class ActiveDfu { NONE, RELAY, RESTORE }

    private var pendingRelease: ReleaseInfo? = null
    private var pendingRelayRelease: ReleaseInfo? = null
    private var versionFetchStarted = false
    private var activeDfu = ActiveDfu.NONE
    private var deviceWasConnected = false
    private var dfuStartedByThisFragment = false
    private var userSliding = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_device_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val titleCard: MaterialCardView = view.findViewById(R.id.titleCard)
        val titleDeviceName: TextView = view.findViewById(R.id.titleDeviceName)
        val titleFirmwareLabel: TextView = view.findViewById(R.id.titleFirmwareLabel)
        val titleDeviceIcon: ImageView = view.findViewById(R.id.titleDeviceIcon)
        val dfuCard: MaterialCardView = view.findViewById(R.id.dfuCard)
        val settingsCard: MaterialCardView = view.findViewById(R.id.settingsCard)
        val batteryValue: TextView = view.findViewById(R.id.batteryValue)
        val firmwareValue: TextView = view.findViewById(R.id.firmwareValue)
        val alertToggle: MaterialSwitch = view.findViewById(R.id.alertToggle)
        val delaySlider: Slider = view.findViewById(R.id.delaySlider)
        val delayValue: TextView = view.findViewById(R.id.delayValue)
        val delayLabel: View = view.findViewById(R.id.delayLabel)
        val delayRow: View = view.findViewById(R.id.delayRow)
        val infoCard: MaterialCardView = view.findViewById(R.id.infoCard)
        val infoChevron: ImageView = view.findViewById(R.id.infoChevron)
        val infoDetail: View = view.findViewById(R.id.infoDetail)
        val dfuButton: Button = view.findViewById(R.id.dfuButton)
        val dfuProgress: ProgressBar = view.findViewById(R.id.dfuProgress)
        val dfuStatusText: TextView = view.findViewById(R.id.dfuStatusText)
        val loadingOverlay: View = view.findViewById(R.id.loadingOverlay)
        val loadingStatusText: TextView = view.findViewById(R.id.loadingStatusText)
        val scrollContent: NestedScrollView = view.findViewById(R.id.scrollContent)
        val relayCard: MaterialCardView = view.findViewById(R.id.relayCard)
        val relayChevron: ImageView = view.findViewById(R.id.relayChevron)
        val relayDetail: View = view.findViewById(R.id.relayDetail)
        val relayButton: Button = view.findViewById(R.id.relayButton)
        val relayProgress: ProgressBar = view.findViewById(R.id.relayProgress)
        val relayStatusText: TextView = view.findViewById(R.id.relayStatusText)
        val restoreCard: MaterialCardView = view.findViewById(R.id.restoreCard)
        val restoreChevron: ImageView = view.findViewById(R.id.restoreChevron)
        val restoreDetail: View = view.findViewById(R.id.restoreDetail)
        val restoreButton: Button = view.findViewById(R.id.restoreButton)
        val restoreProgress: ProgressBar = view.findViewById(R.id.restoreProgress)
        val restoreStatusText: TextView = view.findViewById(R.id.restoreStatusText)

        // Reset any terminal DFU state left over from a previous session so the
        // stale Success/Error value doesn't immediately trigger navigation in this fragment.
        dfuManager.state.value.let {
            if (it is DfuState.Success || it is DfuState.Error) dfuManager.cancel()
        }

        view.findViewById<ImageButton>(R.id.backButton).setOnClickListener {
            findNavController().navigateUp()
        }

        // Slider is the raw GATT field (0-15); display converts to ms (×20)
        delaySlider.addOnChangeListener { _, value, _ ->
            delayValue.text = "${value.toInt() * 20}"
        }
        delaySlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { userSliding = true }
            override fun onStopTrackingTouch(slider: Slider) {
                userSliding = false
                bleManager.setShortPressDelay(deviceAddress, slider.value.toInt())
            }
        })

        alertToggle.setOnCheckedChangeListener { button, isChecked ->
            // isPressed distinguishes user taps from programmatic state sync
            if (button.isPressed) bleManager.setShortPressEnabled(deviceAddress, isChecked)
        }

        infoCard.setOnClickListener {
            val expanding = infoDetail.visibility == View.GONE
            infoDetail.visibility = if (expanding) View.VISIBLE else View.GONE
            infoChevron.rotation = if (expanding) 90f else 0f
        }

        relayCard.setOnClickListener {
            val expanding = relayDetail.visibility == View.GONE
            relayDetail.visibility = if (expanding) View.VISIBLE else View.GONE
            relayChevron.rotation = if (expanding) 90f else 0f
        }

        relayButton.setOnClickListener {
            val device = bleManager.devices.value.firstOrNull { it.address == deviceAddress }
                ?: return@setOnClickListener
            val release = pendingRelayRelease
            if (release == null) {
                relayStatusText.text = "No firmware URL — check PAT / network"
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Flash Relay Firmware?")
                .setMessage(
                    "This requires a smartwatch. Flashing Relay firmware will replace the " +
                    "Receiver firmware, and the device will no longer vibrate when alerted. " +
                    "Do you want to continue?"
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue") { _, _ ->
                    activeDfu = ActiveDfu.RELAY
                    dfuStartedByThisFragment = true
                    viewLifecycleOwner.lifecycleScope.launch {
                        dfuManager.downloadAndUpgrade(
                            device.bluetoothDevice, release.assetApiUrl, BuildConfig.GITHUB_PAT
                        )
                    }
                }
                .show()
        }

        restoreCard.setOnClickListener {
            val expanding = restoreDetail.visibility == View.GONE
            restoreDetail.visibility = if (expanding) View.VISIBLE else View.GONE
            restoreChevron.rotation = if (expanding) 90f else 0f
        }

        restoreButton.setOnClickListener {
            val device = bleManager.devices.value.firstOrNull { it.address == deviceAddress }
                ?: return@setOnClickListener
            val release = pendingRelease
            if (release == null) {
                restoreStatusText.text = "No firmware URL — check PAT / network"
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Revert to Receiver Firmware?")
                .setMessage(
                    "Reverting to Receiver firmware will restore vibration alerts but " +
                    "remove smartwatch functionality. Do you want to continue?"
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue") { _, _ ->
                    activeDfu = ActiveDfu.RESTORE
                    dfuStartedByThisFragment = true
                    viewLifecycleOwner.lifecycleScope.launch {
                        dfuManager.downloadAndUpgrade(
                            device.bluetoothDevice, release.assetApiUrl, BuildConfig.GITHUB_PAT
                        )
                    }
                }
                .show()
        }

        dfuButton.setOnClickListener {
            val device = bleManager.devices.value.firstOrNull { it.address == deviceAddress }
                ?: return@setOnClickListener
            val release = pendingRelease
            if (release == null) {
                dfuStatusText.text = "No firmware URL — check PAT / network"
                return@setOnClickListener
            }
            dfuStartedByThisFragment = true
            viewLifecycleOwner.lifecycleScope.launch {
                dfuManager.downloadAndUpgrade(
                    device.bluetoothDevice, release.assetApiUrl, BuildConfig.GITHUB_PAT
                )
            }
        }

        // Observe DFU state — routed by activeDfu enum
        viewLifecycleOwner.lifecycleScope.launch {
            dfuManager.state.collectLatest { state ->
                when (activeDfu) {
                    ActiveDfu.RELAY -> when (state) {
                        is DfuState.Idle -> {
                            relayStatusText.text = ""
                            relayProgress.visibility = View.GONE
                            relayButton.isEnabled = true
                            activeDfu = ActiveDfu.NONE
                        }
                        is DfuState.Downloading -> {
                            relayStatusText.text = "Downloading…"
                            relayProgress.visibility = View.VISIBLE
                            relayProgress.isIndeterminate = true
                            relayButton.isEnabled = false
                        }
                        is DfuState.Uploading -> {
                            relayStatusText.text = "Uploading…"
                            relayProgress.visibility = View.VISIBLE
                            relayProgress.isIndeterminate = true
                            relayButton.isEnabled = false
                        }
                        is DfuState.Progress -> {
                            relayStatusText.text = "${state.percent}%"
                            relayProgress.visibility = View.VISIBLE
                            relayProgress.isIndeterminate = false
                            relayProgress.progress = state.percent
                            relayButton.isEnabled = false
                        }
                        is DfuState.Success -> {
                            activeDfu = ActiveDfu.NONE
                            relayStatusText.text = "Rebooting…"
                            relayButton.isEnabled = false
                            bleManager.scheduleScan(20_000)
                            if (isAdded) findNavController().navigateUp()
                        }
                        is DfuState.Error -> {
                            relayStatusText.text = "Error: ${state.message}"
                            relayProgress.visibility = View.GONE
                            relayButton.isEnabled = true
                            activeDfu = ActiveDfu.NONE
                        }
                    }
                    ActiveDfu.RESTORE -> when (state) {
                        is DfuState.Idle -> {
                            restoreStatusText.text = ""
                            restoreProgress.visibility = View.GONE
                            restoreButton.isEnabled = true
                            activeDfu = ActiveDfu.NONE
                        }
                        is DfuState.Downloading -> {
                            restoreStatusText.text = "Downloading…"
                            restoreProgress.visibility = View.VISIBLE
                            restoreProgress.isIndeterminate = true
                            restoreButton.isEnabled = false
                        }
                        is DfuState.Uploading -> {
                            restoreStatusText.text = "Uploading…"
                            restoreProgress.visibility = View.VISIBLE
                            restoreProgress.isIndeterminate = true
                            restoreButton.isEnabled = false
                        }
                        is DfuState.Progress -> {
                            restoreStatusText.text = "${state.percent}%"
                            restoreProgress.visibility = View.VISIBLE
                            restoreProgress.isIndeterminate = false
                            restoreProgress.progress = state.percent
                            restoreButton.isEnabled = false
                        }
                        is DfuState.Success -> {
                            activeDfu = ActiveDfu.NONE
                            restoreStatusText.text = "Rebooting…"
                            restoreButton.isEnabled = false
                            bleManager.scheduleScan(20_000)
                            if (isAdded) findNavController().navigateUp()
                        }
                        is DfuState.Error -> {
                            restoreStatusText.text = "Error: ${state.message}"
                            restoreProgress.visibility = View.GONE
                            restoreButton.isEnabled = true
                            activeDfu = ActiveDfu.NONE
                        }
                    }
                    ActiveDfu.NONE -> when (state) {
                        is DfuState.Idle -> {
                            dfuStatusText.text = ""
                            dfuProgress.visibility = View.GONE
                            dfuButton.isEnabled = true
                        }
                        is DfuState.Downloading -> {
                            if (!dfuStartedByThisFragment) return@collectLatest
                            dfuStatusText.text = "Downloading…"
                            dfuProgress.visibility = View.VISIBLE
                            dfuProgress.isIndeterminate = true
                            dfuButton.isEnabled = false
                        }
                        is DfuState.Uploading -> {
                            if (!dfuStartedByThisFragment) return@collectLatest
                            dfuStatusText.text = "Uploading…"
                            dfuProgress.visibility = View.VISIBLE
                            dfuProgress.isIndeterminate = true
                            dfuButton.isEnabled = false
                        }
                        is DfuState.Progress -> {
                            if (!dfuStartedByThisFragment) return@collectLatest
                            dfuStatusText.text = "${state.percent}%"
                            dfuProgress.visibility = View.VISIBLE
                            dfuProgress.isIndeterminate = false
                            dfuProgress.progress = state.percent
                            dfuButton.isEnabled = false
                        }
                        is DfuState.Success -> {
                            if (!dfuStartedByThisFragment) return@collectLatest
                            bleManager.setHasUpdate(deviceAddress, false)
                            dfuStatusText.text = "Rebooting…"
                            dfuButton.isEnabled = false
                            bleManager.scheduleScan(20_000)
                            if (isAdded) findNavController().navigateUp()
                        }
                        is DfuState.Error -> {
                            if (!dfuStartedByThisFragment) return@collectLatest
                            dfuStatusText.text = "Error: ${state.message}"
                            dfuProgress.visibility = View.GONE
                            dfuButton.isEnabled = true
                        }
                    }
                }
            }
        }

        // Observe device + services — drive loading state machine and keep UI current
        viewLifecycleOwner.lifecycleScope.launch {
            combine(bleManager.devices, bleManager.gattServicesMap) { devices, servicesMap ->
                (devices.firstOrNull { it.address == deviceAddress }) to (servicesMap[deviceAddress] ?: emptyList())
            }.collectLatest { (device, services) ->
                if (device?.isConnected == true) deviceWasConnected = true

                if (deviceWasConnected && (device == null || !device.isConnected)) {
                    bleManager.startScan()
                    if (isAdded) findNavController().navigateUp()
                    return@collectLatest
                }

                if (device == null) return@collectLatest

                titleDeviceName.text = device.name

                // Receivers run either Receiver firmware (v1.x) or Relay
                // firmware (v10.x) — say which, right on the title card.
                if (device.deviceType == DeviceType.RECEIVER && device.firmwareVersion.isNotEmpty()) {
                    val relayFw = !FirmwareRepository.isNewerVersion("10.0", device.firmwareVersion)
                    titleFirmwareLabel.text = if (relayFw)
                        "Relay firmware v${device.firmwareVersion}"
                    else
                        "Receiver firmware v${device.firmwareVersion}"
                    titleFirmwareLabel.setTextColor(
                        if (relayFw) Color.parseColor("#FFFF8C00") else Color.parseColor("#FF888888")
                    )
                    titleFirmwareLabel.visibility = View.VISIBLE
                } else {
                    titleFirmwareLabel.visibility = View.GONE
                }

                titleDeviceIcon.setImageResource(
                    when (device.deviceType) {
                        DeviceType.FLAG     -> R.drawable.ic_device_flag
                        DeviceType.RECEIVER -> R.drawable.ic_device_receiver
                        DeviceType.RELAY    -> R.drawable.ic_relay
                        DeviceType.UNKNOWN  -> R.drawable.ic_device_unknown
                    }
                )
                val strokeColor = if (device.isConnected) glowColor(device.glowState)
                                  else Color.parseColor("#333333")
                titleCard.strokeColor = strokeColor
                titleCard.strokeWidth = if (device.isConnected) 6 else 0

                batteryValue.text = when {
                    device.batteryLevel > 95   -> "FULL"
                    device.batteryLevel >= 0   -> "${device.batteryLevel}%"
                    device.configInterval == 0 -> "LOW"
                    device.configInterval == 1 -> "MID"
                    device.configInterval == 2 -> "HIGH"
                    device.configInterval == 3 -> "FULL"
                    else                       -> "--"
                }
                firmwareValue.text = if (device.firmwareVersion.isNotEmpty())
                    "Firmware: ${device.firmwareVersion}" else "Firmware: --"

                // Sync settings controls from the device-reported CFG byte.
                // Optimistic writes update the same state, so no flicker.
                if (alertToggle.isChecked != device.shortPressEnabled && !alertToggle.isPressed) {
                    alertToggle.isChecked = device.shortPressEnabled
                }
                if (device.shortPressDelay >= 0 && !userSliding &&
                    delaySlider.value.toInt() != device.shortPressDelay) {
                    delaySlider.value = device.shortPressDelay.toFloat()
                }

                // Delay slider is a Flag-only control (iOS parity)
                val showDelay = device.deviceType == DeviceType.FLAG
                delayLabel.visibility = if (showDelay) View.VISIBLE else View.GONE
                delayRow.visibility = if (showDelay) View.VISIBLE else View.GONE

                // Update loading status text while still in loading state
                if (!versionFetchStarted) {
                    loadingStatusText.text = when {
                        !device.isConnected -> "Connecting…"
                        services.isEmpty()  -> "Loading…"
                        else                -> "Checking for updates…"
                    }
                }

                // Decide when to start the GitHub fetch (only once)
                if (!versionFetchStarted) {
                    val readyToFetch = when {
                        !device.isConnected -> false
                        device.isDfuOnly    -> services.isNotEmpty()
                        else                -> device.firmwareVersion.isNotEmpty()
                    }
                    if (readyToFetch) {
                        versionFetchStarted = true
                        loadingStatusText.text = "Checking for updates…"
                        launch {
                            var release: ReleaseInfo? = null
                            try {
                                release = FirmwareRepository.fetchReleaseInfo(
                                    device.deviceType, BuildConfig.GITHUB_PAT
                                )
                            } catch (e: Exception) {
                                android.util.Log.w("DeviceDetail", "Release fetch failed", e)
                            }
                            pendingRelease = release

                            // Device is the source of truth: compare the release against
                            // the FW-version characteristic (iOS parity). Unreadable FWV
                            // on an updatable device type = old firmware, offer recovery.
                            val hasUpdate = release != null &&
                                (device.firmwareVersion.isEmpty() ||
                                 FirmwareRepository.isNewerVersion(release.version, device.firmwareVersion))
                            bleManager.setHasUpdate(device.address, hasUpdate)

                            val showDfu = device.isDfuOnly || hasUpdate
                            dfuCard.visibility = if (showDfu) View.VISIBLE else View.GONE

                            val configVis = if (device.isDfuOnly) View.GONE else View.VISIBLE
                            settingsCard.visibility = configVis
                            infoCard.visibility = configVis

                            dfuButton.text = when {
                                device.isDfuOnly -> "Install Firmware"
                                release != null  -> "Update to v${release.version}"
                                else             -> "Update Firmware"
                            }

                            // Relay card: RECEIVER with firmware < 10.x → flash RX_RLY
                            // Restore card: RECEIVER with firmware >= 10.x → restore normal RX
                            val isReceiver = device.deviceType == DeviceType.RECEIVER &&
                                device.firmwareVersion.isNotEmpty()
                            val belowRelay = isReceiver &&
                                FirmwareRepository.isNewerVersion("10.0", device.firmwareVersion)
                            val aboveRelay = isReceiver && !belowRelay

                            if (belowRelay) {
                                try {
                                    pendingRelayRelease = FirmwareRepository.fetchRelayReleaseInfo(
                                        BuildConfig.GITHUB_PAT
                                    )
                                } catch (_: Exception) { }
                                relayCard.visibility = if (pendingRelayRelease != null) View.VISIBLE else View.GONE
                            } else {
                                relayCard.visibility = View.GONE
                            }

                            restoreCard.visibility =
                                if (aboveRelay && pendingRelease != null) View.VISIBLE else View.GONE

                            loadingOverlay.visibility = View.GONE
                            scrollContent.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }

    private fun glowColor(state: GlowState): Int = when (state) {
        GlowState.GREEN  -> Color.parseColor("#39FF14")
        GlowState.CYAN   -> Color.parseColor("#00CFFF")
        GlowState.BLUE   -> Color.parseColor("#007AFF")
        GlowState.YELLOW -> Color.parseColor("#FFC300")
        GlowState.RED    -> Color.parseColor("#FF3B30")
    }
}
