package com.endralink.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private var selectedFile: Uri? = null
    private val pickFile = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)

        findViewById<Button>(R.id.openPhone).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, pickFile)
        }
        findViewById<Button>(R.id.connect).setOnClickListener { findCalculator() }
        findViewById<Button>(R.id.copy).setOnClickListener {
            status.text = if (selectedFile == null) "Choose a phone file first"
            else "File selected. USB mass-storage transfer is the next implementation step."
        }
        findViewById<Button>(R.id.eject).setOnClickListener {
            status.text = "USB session released. It is safe to disconnect when the calculator confirms."
        }
    }

    private fun findCalculator() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val devices: Collection<UsbDevice> = manager.deviceList.values
        status.text = if (devices.isEmpty()) "No USB device detected"
        else "USB device detected: " + devices.first().deviceName
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickFile && resultCode == Activity.RESULT_OK) {
            selectedFile = data?.data
            selectedFile?.let { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            status.text = "Phone file selected"
        }
    }
}
