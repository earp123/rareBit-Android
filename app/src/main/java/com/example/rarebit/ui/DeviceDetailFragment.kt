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
import androidx.appcompat.widget.SwitchCompat
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

    private var pendingRelease: ReleaseInfo? = null
    private var versionFetchStarted = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_device_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val titleCard: MaterialCardView = view.findViewById(R.id.titleCard)
        val titleDeviceName: TextView = view.findViewById(R.id.titleDeviceName)
        val titleDeviceIcon: ImageView = view.findViewById(R.id.titleDeviceIcon)
        val dfuCard: MaterialCardView = view.findViewById(R.id.dfuCard)
        val settingsCard: MaterialCardView = view.findViewById(R.id.settingsCard)
        val batteryValue: TextView = view.findViewById(R.id.batteryValue)
        val firmwareValue: TextView = view.findViewById(R.id.firmwareValue)
        val alertToggle: SwitchCompat = view.findViewById(R.id.alertToggle)
        val delaySlider: Slider = view.findViewById(R.id.delaySlider)
        val delayValue: TextView = view.findViewById(R.id.delayValue)
        val infoCard: MaterialCardView = view.findViewById(R.id.infoCard)
        val infoChevron: ImageView = view.findViewById(R.id.infoChevron)
        val infoDetail: View = view.findViewById(R.id.infoDetail)
        val dfuButton: Button = view.findViewById(R.id.dfuButton)
        val dfuProgress: ProgressBar = view.findViewById(R.id.dfuProgress)
        val dfuStatusText: TextView = view.findViewById(R.id.dfuStatusText)
        val loadingOverlay: View = view.findViewById(R.id.loadingOverlay)
        val loadingStatusText: TextView = view.findViewById(R.id.loadingStatusText)
        val scrollContent: NestedScrollView = view.findViewById(R.id.scrollContent)

        view.findViewById<ImageButton>(R.id.backButton).setOnClickListener {
            findNavController().navigateUp()
        }

        delaySlider.addOnChangeListener { _, value, _ ->
            delayValue.text = value.toInt().toString()
        }

        infoCard.setOnClickListener {
            val expanding = infoDetail.visibility == View.GONE
            infoDetail.visibility = if (expanding) View.VISIBLE else View.GONE
            infoChevron.rotation = if (expanding) 90f else 0f
        }

        dfuButton.setOnClickListener {
            val device = bleManager.devices.value.firstOrNull { it.address == deviceAddress }
                ?: return@setOnClickListener
            val release = pendingRelease
            if (release == null) {
                dfuStatusText.text = "No firmware URL — check PAT / network"
                return@setOnClickListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                dfuManager.downloadAndUpgrade(
                    device.bluetoothDevice, release.assetApiUrl, BuildConfig.GITHUB_PAT
                )
            }
        }

        // Observe DFU state
        viewLifecycleOwner.lifecycleScope.launch {
            dfuManager.state.collectLatest { state ->
                when (state) {
                    is DfuState.Idle -> {
                        dfuStatusText.text = ""
                        dfuProgress.visibility = View.GONE
                        dfuButton.isEnabled = true
                    }
                    is DfuState.Downloading -> {
                        dfuStatusText.text = "Downloading…"
                        dfuProgress.visibility = View.VISIBLE
                        dfuProgress.isIndeterminate = true
                        dfuButton.isEnabled = false
                    }
                    is DfuState.Uploading -> {
                        dfuStatusText.text = "Uploading…"
                        dfuProgress.visibility = View.VISIBLE
                        dfuProgress.isIndeterminate = true
                        dfuButton.isEnabled = false
                    }
                    is DfuState.Progress -> {
                        dfuStatusText.text = "${state.percent}%"
                        dfuProgress.visibility = View.VISIBLE
                        dfuProgress.isIndeterminate = false
                        dfuProgress.progress = state.percent
                        dfuButton.isEnabled = false
                    }
                    is DfuState.Success -> {
                        val device = bleManager.devices.value.firstOrNull { it.address == deviceAddress }
                        pendingRelease?.let { release ->
                            device?.let { d ->
                                FirmwareRepository.saveInstalledVersion(
                                    requireContext(), d.deviceType, release.version
                                )
                            }
                        }
                        if (isAdded) findNavController().navigateUp()
                    }
                    is DfuState.Error -> {
                        dfuStatusText.text = "Error: ${state.message}"
                        dfuProgress.visibility = View.GONE
                        dfuButton.isEnabled = true
                    }
                }
            }
        }

        // Observe device + services — drive loading state machine and keep UI current
        viewLifecycleOwner.lifecycleScope.launch {
            combine(bleManager.devices, bleManager.gattServices) { devices, services ->
                (devices.firstOrNull { it.address == deviceAddress }) to services
            }.collectLatest { (device, services) ->
                if (device == null) return@collectLatest

                titleDeviceName.text = device.name
                titleDeviceIcon.setImageResource(
                    when (device.deviceType) {
                        DeviceType.FLAG     -> R.drawable.ic_device_flag
                        DeviceType.RECEIVER -> R.drawable.ic_device_receiver
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
                            var release: com.example.rarebit.ble.ReleaseInfo? = null
                            var fetchError: String? = null
                            try {
                                release = FirmwareRepository.fetchReleaseInfo(
                                    device.deviceType, BuildConfig.GITHUB_PAT
                                )
                                // DFU-only devices with unknown type fall back to FLAG firmware
                                if (release == null && device.isDfuOnly) {
                                    release = FirmwareRepository.fetchReleaseInfo(
                                        DeviceType.FLAG, BuildConfig.GITHUB_PAT
                                    )
                                }
                            } catch (e: Exception) {
                                fetchError = e.message ?: e.javaClass.simpleName
                            }
                            pendingRelease = release

                            val installed = FirmwareRepository.getInstalledVersion(
                                requireContext(), device.deviceType
                            )
                            val hasUpdate = release != null &&
                                (installed == null || FirmwareRepository.isNewerVersion(release.version, installed))

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
