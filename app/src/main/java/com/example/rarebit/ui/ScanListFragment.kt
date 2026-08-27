package com.example.rarebit.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rarebit.MainActivity
import com.example.rarebit.R
import com.example.rarebit.ble.BleDevice
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ScanListFragment : Fragment() {

    private val bleManager get() = (requireActivity() as MainActivity).bleManager

    private lateinit var recyclerView: RecyclerView
    private lateinit var scanButton: Button
    private lateinit var adapter: DeviceCardAdapter

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (bleManager.bluetoothAdapter?.isEnabled == true) {
            startScanIfPermitted()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startScanIfPermitted()
    }

    override fun onResume() {
        super.onResume()
        bleManager.clearDisconnectedDevices()
    }

    override fun onPause() {
        super.onPause()
        bleManager.stopScan()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_scan_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recyclerView)
        scanButton = view.findViewById(R.id.scanButton)
        val optionsButton: Button = view.findViewById(R.id.optionsButton)
        optionsButton.setOnClickListener { anchor ->
            val menu = PopupMenu(requireContext(), anchor)
            menu.menu.apply {
                add(0, 1, 0, "rareBit Official")
                add(0, 2, 1, "User Manual")
                add(0, 3, 2, "Smartwatch")
                addSubMenu(0, 4, 3, "Buy PRO Sets").apply {
                    add(0, 41, 0, "The Top Ref")
                    add(0, 42, 1, "RefsNeedLoveToo")
                }
                add(0, 5, 4, "Support")
            }
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1  -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.rarebitofficial.com")))
                    2  -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.rarebitofficial.com/order")))
                    3  -> { /* coming soon */ }
                    41 -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://thetopref.com/collections/beep-flags/products/rarebit-beep-flags")))
                    42 -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://refsneedlovetoo.com/collections/referee-gear/products/next-generation-buzzer-flags-rarebit-pro-set")))
                    5  -> startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:reply@rarebit.biz")))
                }
                true
            }
            menu.show()
        }

        adapter = DeviceCardAdapter { device -> navigateToDetail(device) }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        recyclerView.clipChildren = false
        recyclerView.clipToPadding = false

        // Seed adapter immediately so there's no empty-list flash on navigate-back
        adapter.submitList(bleManager.devices.value)

        scanButton.setOnClickListener {
            if (bleManager.isScanning.value) {
                bleManager.stopScan()
            } else {
                refreshScan()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            bleManager.devices.collectLatest { devices ->
                adapter.submitList(devices)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            bleManager.isScanning.collectLatest { scanning ->
                scanButton.text = if (scanning)
                    getString(R.string.stop_scanning)
                else
                    getString(R.string.find_devices)
            }
        }
    }

    private fun refreshScan() {
        bleManager.clearDisconnectedDevices()
        checkBluetoothAndScan()
    }

    private fun checkBluetoothAndScan() {
        val adapter = bleManager.bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        startScanIfPermitted()
    }

    private fun startScanIfPermitted() {
        if (!hasRequiredPermissions()) {
            requestPermissions()
            return
        }
        bleManager.startScan()
    }

    private fun hasRequiredPermissions(): Boolean {
        val perms = requiredPermissions()
        return perms.all {
            ContextCompat.checkSelfPermission(requireContext(), it) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(requiredPermissions())
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun navigateToDetail(device: BleDevice) {
        bleManager.connect(device)
        val bundle = android.os.Bundle().apply {
            putString("deviceAddress", device.address)
        }
        findNavController().navigate(R.id.action_scanList_to_detail, bundle)
    }
}
