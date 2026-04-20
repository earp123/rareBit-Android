package com.example.rarebit.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_scan_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recyclerView)
        scanButton = view.findViewById(R.id.scanButton)

        adapter = DeviceCardAdapter { device -> navigateToDetail(device) }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        recyclerView.clipChildren = false
        recyclerView.clipToPadding = false

        scanButton.setOnClickListener {
            if (bleManager.isScanning.value) {
                bleManager.stopScan()
            } else {
                bleManager.clearDisconnectedDevices()
                checkBluetoothAndScan()
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
