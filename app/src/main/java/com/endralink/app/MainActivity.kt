package com.endralink.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.usb.*
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.endralink.app.storage.Fat16Volume
import com.endralink.app.storage.UsbStorageSession
import java.util.concurrent.Executors
import java.util.Locale

/** USB connection and read-only FAT16 browser; storage writes are not implemented. */
class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var progress: ProgressBar
    private lateinit var connect: Button
    private lateinit var disconnect: Button
    private lateinit var usb: UsbManager
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var connection: UsbDeviceConnection? = null
    private var activeDevice: UsbDevice? = null
    private var pendingDevice: UsbDevice? = null
    private var permissionIntent: PendingIntent? = null
    @Volatile private var generation = 0
    @Volatile private var destroyed = false
    private var busy = false
    private var storage: UsbStorageSession? = null
    private val folderStack = mutableListOf<Pair<String, Int>>()
    private val permissionAction get() = packageName + ".USB_PERMISSION"

    /** Re-check actual USB permission; never trust permission flags from an incoming intent. */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == permissionAction) {
                if (intent.data?.lastPathSegment != generation.toString()) return
                val device = pendingDevice ?: return
                permissionIntent?.cancel()
                permissionIntent = null
                pendingDevice = null
                if (!isAttached(device)) {
                    closeSession("USB device disconnected. Reconnect and tap Connect.")
                } else if (usb.hasPermission(device)) {
                    openDevice(device)
                } else {
                    setBusy(false)
                    status.text = "USB permission denied. Tap Connect to try again."
                }
            } else if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                checkAttachment()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.page)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        usb = getSystemService(UsbManager::class.java)
        status = findViewById(R.id.status)
        details = findViewById(R.id.deviceDetails)
        progress = findViewById(R.id.progress)
        connect = findViewById(R.id.connect)
        disconnect = findViewById(R.id.eject)
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        findViewById<TextView>(R.id.version).text = "0.1.4 • READ-ONLY PREVIEW"
        findViewById<Button>(R.id.copy).isEnabled = false
        disconnect.isEnabled = false
        findViewById<Button>(R.id.openPhone).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, 1001)
        }
        connect.setOnClickListener { findCalculator() }
        findViewById<Button>(R.id.browse).setOnClickListener {
            folderStack.clear()
            browseDirectory(0)
        }
        findViewById<Button>(R.id.upFolder).setOnClickListener {
            if (folderStack.isNotEmpty()) folderStack.removeAt(folderStack.lastIndex)
            browseDirectory(folderStack.lastOrNull()?.second ?: 0)
        }
        disconnect.setOnClickListener { closeSession("EndraLink USB connection closed. No files were changed.") }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002)
        }
    }

    /** Require a SCSI Bulk-Only mass-storage interface and both bulk endpoint directions. */
    private fun storageInterface(device: UsbDevice): UsbInterface? =
        (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { intf ->
            intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                intf.interfaceSubclass == 6 && intf.interfaceProtocol == 80 &&
                (0 until intf.endpointCount).map { intf.getEndpoint(it) }.let { endpoints ->
                    endpoints.any { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN } &&
                        endpoints.any { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
                }
        }

    /** User selects the intended device; the first USB device is not assumed to be an fx-CG50. */
    private fun findCalculator() {
        val devices = usb.deviceList.values.sortedBy { it.deviceName }
        if (devices.isEmpty()) {
            status.text = "No USB device detected. Check the cable and USB adapter."
            details.text = ""
            return
        }
        val candidates = devices.filter { storageInterface(it) != null }
        if (candidates.isEmpty()) {
            status.text = "USB detected, but no compatible mass-storage interface. Select USB Flash mode on the fx-CG50, then try again."
            details.text = devices.joinToString("\n") { describe(it) }
            return
        }
        AlertDialog.Builder(this).setTitle("Select your fx-CG50 USB device")
            .setItems(candidates.map { describe(it) }.toTypedArray()) { _, index -> requestAccess(candidates[index]) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Immutable package-scoped callback preserves our request identity, including on Android 14+. */
    private fun requestAccess(device: UsbDevice) {
        if (!isAttached(device)) {
            status.text = "USB device disconnected. Reconnect and try again."
            return
        }
        generation++
        details.text = describe(device)
        if (usb.hasPermission(device)) {
            openDevice(device)
            return
        }
        pendingDevice = device
        setBusy(true)
        status.text = "Waiting for Android USB permission. Tap Allow in the system prompt."
        val callback = Intent(permissionAction).setPackage(packageName)
            .setData(Uri.parse("endralink://usb-permission/" + generation))
        val result = PendingIntent.getBroadcast(this, generation, callback,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        permissionIntent = result
        try {
            usb.requestPermission(device, result)
        } catch (e: RuntimeException) {
            closeSession("Unable to request USB permission: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    /** Open off the UI thread; do not claim interfaces, detach drivers, or write storage. */
    private fun openDevice(device: UsbDevice) {
        val request = generation
        activeDevice = device
        setBusy(true)
        status.text = "Opening USB connection…"
        worker.execute {
            var opened: UsbDeviceConnection? = null
            var error: String? = null
            try {
                if (usb.hasPermission(device) && isAttached(device)) opened = usb.openDevice(device)
                if (opened == null) error = "Could not open USB device. Reconnect and try again."
            } catch (e: RuntimeException) {
                error = "USB connection failed: " + (e.message ?: e.javaClass.simpleName)
            }
            val result = opened
            val failure = error
            main.post {
                if (destroyed || request != generation || !isAttached(device)) {
                    result?.close()
                    if (!destroyed && request == generation) closeSession("USB device disconnected.")
                } else if (result == null) {
                    closeSession(failure ?: "USB connection failed.")
                } else {
                    connection = result
                    setBusy(false)
                    connect.isEnabled = false
                    disconnect.isEnabled = true
                    status.text = "USB connection open"
                    details.text = describe(device) + "\nPermission granted. Tap Browse calculator to read FAT16 storage."
                }
            }
        }
    }

    private fun describe(device: UsbDevice): String =
        (device.productName ?: "USB device") + " • %04X:%04X\n".format(device.vendorId, device.productId) + device.deviceName

    private fun isAttached(device: UsbDevice): Boolean = usb.deviceList[device.deviceName]?.let {
        it.deviceId == device.deviceId && it.vendorId == device.vendorId && it.productId == device.productId
    } == true

    private fun setBusy(busy: Boolean) {
        this.busy = busy
        progress.isIndeterminate = true
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        connect.isEnabled = !busy && connection == null
        disconnect.isEnabled = busy || connection != null
        findViewById<Button>(R.id.browse).isEnabled = !busy && connection != null
        findViewById<Button>(R.id.upFolder).isEnabled = !busy && folderStack.isNotEmpty()
    }

    /** Release this app's handle and ignore stale callbacks; this is not filesystem eject. */
    private fun closeSession(message: String) {
        generation++
        permissionIntent?.cancel()
        permissionIntent = null
        pendingDevice = null
        val closingStorage = storage
        val closingConnection = connection
        storage = null
        worker.execute {
            try { closingStorage?.close() } finally { closingConnection?.close() }
        }
        connection = null
        activeDevice = null
        folderStack.clear()
        findViewById<LinearLayout>(R.id.fileList).removeAllViews()
        findViewById<View>(R.id.browserPanel).visibility = View.GONE
        setBusy(false)
        status.text = message
    }

    private fun checkAttachment() {
        val tracked = activeDevice ?: pendingDevice ?: return
        if (!isAttached(tracked)) closeSession("USB device disconnected. Reconnect and tap Connect.")
    }

    /** Initialize a read-only session on demand and serialize all sector reads with cleanup. */
    private fun browseDirectory(cluster: Int) {
        if (busy) return
        val handle = connection ?: return
        val device = activeDevice ?: return
        val intf = storageInterface(device) ?: return
        val request = generation
        val previous = storage
        setBusy(true)
        findViewById<LinearLayout>(R.id.fileList).removeAllViews()
        status.text = "Reading calculator storage…"
        worker.execute {
            var session = previous
            try {
                if (session == null) session = UsbStorageSession(handle, intf) {
                    destroyed || generation != request
                }
                val opened = session
                val entries = opened.volume.list(cluster)
                main.post {
                    if (destroyed || generation != request) {
                        if (previous == null) opened.close()
                    } else {
                        storage = opened
                        showDirectory(opened.volume, entries)
                    }
                }
            } catch (e: Exception) {
                if (previous == null) session?.close()
                main.post {
                    if (!destroyed && generation == request) {
                        closeSession("Storage read stopped: " + (e.message ?: e.javaClass.simpleName))
                    }
                }
            }
        }
    }

    /** Show read-only folder navigation; file taps display metadata rather than starting transfers. */
    private fun showDirectory(volume: Fat16Volume, entries: List<Fat16Volume.Entry>) {
        setBusy(false)
        status.text = "FAT16 storage ready"
        details.text = "Read-only access • " + entries.size + " items in this folder"
        findViewById<View>(R.id.browserPanel).visibility = View.VISIBLE
        findViewById<TextView>(R.id.storageInfo).text =
            "FAT16 • " + String.format(Locale.ROOT, "%.1f MiB", volume.capacityBytes / 1048576.0) +
                if (volume.label.isNotBlank()) " • " + volume.label else ""
        findViewById<TextView>(R.id.folderPath).text = "/" + folderStack.joinToString("/") { it.first }
        val list = findViewById<LinearLayout>(R.id.fileList)
        list.removeAllViews()
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "This folder is empty."
                setTextColor(ContextCompat.getColor(context, R.color.muted))
                setPadding(dp(8), dp(16), dp(8), dp(16))
            })
        }
        if (entries.size > 200) {
            list.addView(TextView(this).apply {
                text = "Showing the first 200 of " + entries.size + " items in this preview."
                setTextColor(ContextCompat.getColor(context, R.color.muted))
            })
        }
        entries.take(200).forEach { entry ->
            list.addView(TextView(this).apply {
                text = entry.name + if (entry.directory) "  /" else "\n" + entry.size + " bytes"
                textSize = 16f
                minHeight = dp(56)
                setTextColor(ContextCompat.getColor(context, if (entry.directory) R.color.cyan else R.color.silver))
                setPadding(dp(8), dp(14), dp(8), dp(14))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (!busy) {
                        if (entry.directory) {
                            if (folderStack.size >= 32 || folderStack.any { it.second == entry.cluster }) {
                                status.text = "Folder navigation limit reached."
                            } else {
                                folderStack.add(entry.name to entry.cluster)
                                browseDirectory(entry.cluster)
                            }
                        } else {
                            AlertDialog.Builder(this@MainActivity).setTitle(entry.name)
                                .setMessage(entry.size.toString() + " bytes\n\nRead-only preview. File transfers are not enabled.")
                                .setPositiveButton("OK", null).show()
                        }
                    }
                }
            })
            list.addView(View(this).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.outline))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        if (::usb.isInitialized) checkAttachment()
    }

    override fun onDestroy() {
        destroyed = true
        closeSession("USB connection closed.")
        unregisterReceiver(receiver)
        worker.shutdown()
        super.onDestroy()
    }

    @Deprecated("Legacy file picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1001 || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        var name = "Selected file"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0) ?: name
            }
        } catch (_: RuntimeException) { /* Some providers omit display names. */ }
        findViewById<TextView>(R.id.progressText).text = "Selected: " + name
    }
}
