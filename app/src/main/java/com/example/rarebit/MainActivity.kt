package com.example.rarebit

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.example.rarebit.ble.BleManager
import com.example.rarebit.ble.DfuManager

class MainActivity : AppCompatActivity() {

    val bleManager: BleManager by lazy { BleManager(this) }
    val dfuManager: DfuManager by lazy { DfuManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on while scanning / connected
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.cleanup()
    }
}
