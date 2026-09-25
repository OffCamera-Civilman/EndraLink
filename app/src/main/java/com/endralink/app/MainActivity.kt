package com.endralink.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.usb.*
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.widget.doAfterTextChanged
import com.endralink.app.storage.Fat16Volume
import com.endralink.app.storage.UsbStorageSession
import java.util.concurrent.Executors
import java.util.Locale

/** USB connection, FAT16 browser, and Android-to-calculator file transfer. */
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
    private var ejecting = false
    private var transferring = false
    private var pendingTransferAfterConnect = false
    private var transferTotalItems = 0
    private var storage: UsbStorageSession? = null
    private val transferChannelId = "endralink_transfers"
    private val transferNotificationId = 4102
    private data class PendingItem(val uri: Uri, val name: String, val directory: Boolean)
    private data class PhoneEntry(val uri: Uri, val name: String, val directory: Boolean, val size: Long)
    private val selectedFiles = mutableListOf<PendingItem>()
    private var phoneTreeUri: Uri? = null
    private val phoneStack = mutableListOf<Pair<String, Uri>>()
    private val folderStack = mutableListOf<Pair<String, Int>>()
    private val permissionAction get() = packageName + ".USB_PERMISSION"

    /** Re-check actual USB permission; never trust permission flags from an incoming intent. */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            DebugLog.event("USB_BROADCAST", "action=" + intent.action +
                " callbackId=" + intent.data?.lastPathSegment + " expectedId=" + generation +
                " grantedExtra=" + intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            if (intent.action == permissionAction) {
                if (intent.data?.lastPathSegment != generation.toString()) {
                    DebugLog.event("PERMISSION_CALLBACK_IGNORED", "request identity mismatch")
                    return
                }
                val device = pendingDevice ?: run {
                    DebugLog.event("PERMISSION_CALLBACK_IGNORED", "no pending device")
                    return
                }
                finishPermission(device)
            } else if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                checkAttachment()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.start(this)
        DebugLog.event("ACTIVITY_CREATE", "restored=" + (savedInstanceState != null))
        setContentView(R.layout.activity_main)
        findViewById<ArtworkView>(R.id.homeArtwork).configure(R.drawable.hydra_home, 0f, 0.725f)
        findViewById<ArtworkView>(R.id.workspaceHeader).configure(R.drawable.circuit_workspace, 0.07f, 0.225f)
        findViewById<ArtworkView>(R.id.workspaceFooter).configure(R.drawable.circuit_workspace, 0.67f, 1f)
        val navigation = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                findViewById<View>(R.id.workspacePage).visibility = View.GONE
                findViewById<View>(R.id.homePage).visibility = View.VISIBLE
                isEnabled = false
                DebugLog.event("NAVIGATION", "home")
            }
        }
        onBackPressedDispatcher.addCallback(this, navigation)
        findViewById<Button>(R.id.homeFx).setOnClickListener {
            findViewById<View>(R.id.homePage).visibility = View.GONE
            findViewById<View>(R.id.workspacePage).visibility = View.VISIBLE
            navigation.isEnabled = true
            DebugLog.event("NAVIGATION", "fx_cg50")
        }
        findViewById<Button>(R.id.homeBack).setOnClickListener { navigation.handleOnBackPressed() }
        if (savedInstanceState?.getBoolean("workspace") == true) findViewById<Button>(R.id.homeFx).performClick()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = false
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.page)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        usb = getSystemService(UsbManager::class.java)
        status = findViewById(R.id.status)
        status.doAfterTextChanged { DebugLog.event("STATUS", it.toString()) }
        details = findViewById(R.id.deviceDetails)
        progress = findViewById(R.id.progress)
        connect = findViewById(R.id.connect)
        disconnect = findViewById(R.id.eject)
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(permissionAction)
            addDataScheme("endralink")
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, receiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        val appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.9"
        findViewById<TextView>(R.id.version).text = appVersion + " • FILE TRANSFER"
        findViewById<TextView>(R.id.homeVersion).text = "EndraLink " + appVersion + " • YOUR CALCULATOR. CONNECTED."
        findViewById<Button>(R.id.copy).isEnabled = false
        disconnect.isEnabled = false
        createTransferNotificationChannel()
        findViewById<Button>(R.id.openPhone).setOnClickListener {
            if (hasFullStorageAccess()) {
                DebugLog.event("TAP", "phone_storage_root")
                openFullStorageRoot()
            } else {
                DebugLog.event("TAP", "choose_phone_tree")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                }, 1004)
            }
        }
        findViewById<Button>(R.id.phoneUp).setOnClickListener {
            if (phoneStack.size > 1) {
                phoneStack.removeAt(phoneStack.lastIndex)
                renderPhoneDirectory()
            }
        }
        findViewById<Button>(R.id.clearQueue).setOnClickListener {
            selectedFiles.clear()
            renderLocalQueue()
            renderPhoneDirectory()
        }
        connect.setOnClickListener { DebugLog.event("TAP", "connect"); findCalculator() }
        findViewById<Button>(R.id.browse).setOnClickListener {
            DebugLog.event("TAP", "browse_calculator")
            folderStack.clear()
            browseDirectory(0)
        }
        findViewById<Button>(R.id.upFolder).setOnClickListener {
            DebugLog.event("TAP", "parent_folder")
            if (folderStack.isNotEmpty()) folderStack.removeAt(folderStack.lastIndex)
            browseDirectory(folderStack.lastOrNull()?.second ?: 0)
        }
        findViewById<Button>(R.id.collapseBrowser).setOnClickListener {
            val body = findViewById<View>(R.id.browserBody)
            val collapsed = body.visibility == View.VISIBLE
            body.visibility = if (collapsed) View.GONE else View.VISIBLE
            findViewById<Button>(R.id.collapseBrowser).text = if (collapsed) "Expand" else "Collapse"
            DebugLog.event("BROWSER_COLLAPSE", "collapsed=" + collapsed)
        }
        findViewById<Button>(R.id.newCalcFile).setOnClickListener {
            promptCreateCalculatorItem(false)
        }
        findViewById<Button>(R.id.newCalcFolder).setOnClickListener {
            promptCreateCalculatorItem(true)
        }
        findViewById<Button>(R.id.copy).setOnClickListener {
            confirmTransfer()
        }
        disconnect.setOnClickListener {
            DebugLog.event("TAP", "eject")
            ejectCalculator()
        }
        findViewById<Button>(R.id.exportLog).setOnClickListener { requestLogExport() }
        restorePhoneAccess()
        requestInitialPermissionsIfNeeded()
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
        DebugLog.event("USB_ENUMERATE", "count=" + devices.size)
        devices.forEach { logDevice("USB_DEVICE", it) }
        if (devices.isEmpty()) {
            status.text = "No USB device detected. Check the cable and USB adapter."
            details.text = ""
            return
        }
        val candidates = devices.filter { storageInterface(it) != null }
        DebugLog.event("USB_CANDIDATES", "massStorageCount=" + candidates.size)
        if (candidates.isEmpty()) {
            status.text = "USB detected, but no compatible mass-storage interface. Select USB Flash mode on the fx-CG50, then try again."
            details.text = devices.joinToString("\n") { describe(it) }
            return
        }
        AlertDialog.Builder(this).setTitle("Select your fx-CG50 USB device")
            .setItems(candidates.map { describe(it) }.toTypedArray()) { _, index -> requestAccess(candidates[index]) }
            .setNegativeButton("Cancel") { _, _ -> DebugLog.event("TAP", "device_selection_cancel") }.show()
    }

    /** Immutable package-scoped callback preserves our request identity, including on Android 14+. */
    private fun requestAccess(device: UsbDevice) {
        logDevice("DEVICE_SELECTED", device)
        if (!isAttached(device)) {
            status.text = "USB device disconnected. Reconnect and try again."
            return
        }
        generation++
        details.text = describe(device)
        if (usb.hasPermission(device)) {
            DebugLog.event("PERMISSION_ALREADY_GRANTED")
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
            DebugLog.event("PERMISSION_REQUEST", "id=" + generation)
            usb.requestPermission(device, result)
            val observedRequest = generation
            listOf(2000L, 10000L, 30000L).forEach { delay ->
                main.postDelayed({
                    if (!destroyed && generation == observedRequest && pendingDevice != null) {
                        logDevice("PERMISSION_CHECK_" + delay + "MS", device)
                        if (usb.hasPermission(device)) finishPermission(device)
                        else if (delay == 30000L) closeSession("USB permission was not received. Tap Connect to try again.")
                    }
                }, delay)
            }
        } catch (e: RuntimeException) {
            DebugLog.event("PERMISSION_REQUEST_ERROR", error = e)
            closeSession("Unable to request USB permission: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun finishPermission(device: UsbDevice) {
        if (pendingDevice == null) return
        logDevice("PERMISSION_RESULT", device)
        permissionIntent?.cancel()
        permissionIntent = null
        pendingDevice = null
        if (!isAttached(device)) closeSession("USB device disconnected. Reconnect and tap Connect.")
        else if (usb.hasPermission(device)) openDevice(device)
        else closeSession("USB permission denied. Tap Connect to try again.")
    }

    /** Open off the UI thread; do not claim interfaces, detach drivers, or write storage. */
    private fun openDevice(device: UsbDevice) {
        logDevice("USB_OPEN_BEGIN", device)
        val request = generation
        activeDevice = device
        setBusy(true)
        status.text = "Opening USB connection…"
        worker.execute {
            var opened: UsbDeviceConnection? = null
            var error: String? = null
            try {
                if (usb.hasPermission(device) && isAttached(device)) opened = usb.openDevice(device)
                DebugLog.event("USB_OPEN_RESULT", "handleOpened=" + (opened != null))
                if (opened == null) error = "Could not open USB device. Reconnect and try again."
            } catch (e: RuntimeException) {
                DebugLog.event("USB_OPEN_ERROR", error = e)
                error = "USB connection failed: " + (e.message ?: e.javaClass.simpleName)
            }
            val result = opened
            val failure = error
            main.post {
                if (destroyed || request != generation || !isAttached(device)) {
                    DebugLog.event("USB_OPEN_DISCARDED", "request=" + request + " current=" + generation + " destroyed=" + destroyed)
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
                    details.text = describe(device) + "\nPermission granted. Select files and transfer directly, or Browse calculator to choose a folder."
                    if (pendingTransferAfterConnect && selectedFiles.isNotEmpty()) {
                        pendingTransferAfterConnect = false
                        main.post { prepareStorageForTransfer(false) }
                    }
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
        DebugLog.event("BUSY_STATE", "busy=" + busy + " transferring=" + transferring + " handleOpen=" + (connection != null))
        this.busy = busy
        progress.isIndeterminate = true
        progress.visibility = if (busy && !transferring) View.VISIBLE else View.GONE
        connect.isEnabled = !busy && connection == null
        disconnect.isEnabled = !busy && !ejecting && connection != null
        findViewById<Button>(R.id.browse).isEnabled = !busy && connection != null
        findViewById<Button>(R.id.upFolder).isEnabled = !busy && folderStack.isNotEmpty()
        findViewById<Button>(R.id.collapseBrowser).isEnabled = !busy
        findViewById<Button>(R.id.newCalcFile).isEnabled = !busy && storage != null
        findViewById<Button>(R.id.newCalcFolder).isEnabled = !busy && storage != null
        findViewById<Button>(R.id.openPhone).isEnabled = !busy
        findViewById<Button>(R.id.phoneUp).isEnabled = !busy && phoneStack.size > 1
        findViewById<Button>(R.id.clearQueue).isEnabled = !busy && selectedFiles.isNotEmpty()
        findViewById<Button>(R.id.exportLog).isEnabled = !busy
        findViewById<Button>(R.id.homeBack).isEnabled = !busy
        findViewById<Button>(R.id.copy).isEnabled = !busy && selectedFiles.isNotEmpty()
        findViewById<ProgressBar>(R.id.transferProgress).visibility = if (transferring) View.VISIBLE else View.GONE
    }

    private fun setTransferMode(active: Boolean) {
        transferring = active
        findViewById<View>(R.id.browserPanel).visibility = if (active) View.GONE else
            if (storage != null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.browse).visibility = if (active) View.GONE else View.VISIBLE
        findViewById<View>(R.id.connect).visibility = if (active) View.GONE else View.VISIBLE
        findViewById<View>(R.id.eject).visibility = if (active) View.GONE else View.VISIBLE
        findViewById<View>(R.id.exportLog).visibility = if (active) View.GONE else View.VISIBLE
        findViewById<View>(R.id.homeBack).visibility = if (active) View.GONE else View.VISIBLE
        findViewById<View>(R.id.workspaceHeader).visibility = if (active) View.GONE else View.VISIBLE
        findViewById<View>(R.id.workspaceFooter).visibility = if (active) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.openPhone).visibility = if (active) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.phoneUp).visibility = if (active) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.clearQueue).visibility = if (active) View.GONE else View.VISIBLE
        findViewById<View>(R.id.phoneFileBrowser).visibility = if (active) View.GONE else View.VISIBLE
        findViewById<View>(R.id.localFileList).visibility = if (active) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.copy).visibility = if (active) View.GONE else View.VISIBLE
        findViewById<ProgressBar>(R.id.transferProgress).visibility = if (active) View.VISIBLE else View.GONE
    }

    /** Send a real eject request before releasing ownership; report only confirmed outcomes. */
    private fun ejectCalculator() {
        if (ejecting) return
        if (busy) {
            closeSession("USB operation cancelled. Use Android storage settings to eject if still connected.")
            return
        }
        val session = storage
        val handle = connection
        val device = activeDevice
        if (session == null || handle == null || device == null) {
            closeSession("App connection closed. To eject storage, use Android's system eject or connect and browse first.")
            return
        }
        if (device.vendorId != 0x07cf || device.productId != 0x6102) {
            closeSession("App connection closed. Use Android's system eject for this device.")
            return
        }
        val request = generation
        ejecting = true
        setBusy(true)
        status.text = "Requesting calculator eject…"
        worker.execute {
            var message = "Eject command accepted. Wait for the calculator to leave USB mode before unplugging."
            try {
                session.eject()
            } catch (e: Exception) {
                DebugLog.event("EJECT_ERROR", error = e)
                message = "Eject could not be confirmed. Use Android's system eject if the calculator remains in USB mode."
            } finally {
                try {
                    session.close()
                } catch (e: Exception) {
                    DebugLog.event("EJECT_RELEASE_ERROR", error = e)
                    message = "USB cleanup could not be confirmed. Use Android's system eject."
                }
                try {
                    handle.close()
                    DebugLog.event("USB_HANDLE_CLOSED")
                } catch (e: Exception) {
                    DebugLog.event("USB_HANDLE_CLOSE_ERROR", error = e)
                    message = "USB cleanup could not be confirmed. Use Android's system eject."
                }
            }
            val outcome = message
            main.post {
                if (!destroyed && generation == request) {
                    storage = null
                    connection = null
                    closeSession(outcome)
                }
            }
        }
    }

    /** Release this app's handle and ignore stale callbacks; this is not filesystem eject. */
    private fun closeSession(message: String) {
        DebugLog.event("SESSION_CLOSE", "id=" + generation + " reason=" + message)
        generation++
        ejecting = false
        pendingTransferAfterConnect = false
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
        details.text = ""
    }

    private fun checkAttachment() {
        val tracked = activeDevice ?: pendingDevice ?: return
        if (ejecting) {
            DebugLog.event("EJECT_ATTACHMENT", "attached=" + isAttached(tracked))
            return
        }
        if (!isAttached(tracked)) closeSession("USB device disconnected. Reconnect and tap Connect.")
    }

    /** Initialize a read-only session on demand and serialize all sector reads with cleanup. */
    private fun browseDirectory(cluster: Int, directAccess: Boolean = false) {
        DebugLog.event("BROWSE_BEGIN", "cluster=" + cluster + " busy=" + busy +
            " handleOpen=" + (connection != null) + " storageOpen=" + (storage != null))
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
                if (session == null) session = UsbStorageSession(handle, intf, directAccess) {
                    destroyed || generation != request
                }
                val opened = session
                val entries = opened.volume.list(cluster)
                DebugLog.event("BROWSE_RESULT", "entryCount=" + entries.size + " capacityBytes=" + opened.volume.capacityBytes)
                main.post {
                    if (destroyed || generation != request) {
                        if (previous == null) opened.close()
                    } else {
                        storage = opened
                        showDirectory(opened.volume, entries)
                    }
                }
            } catch (e: Exception) {
                DebugLog.event("BROWSE_ERROR", error = e)
                if (previous == null) session?.close()
                main.post {
                    if (!destroyed && generation == request) {
                        if (e is UsbStorageSession.InterfaceBusyException && !directAccess &&
                            device.vendorId == 0x07cf && device.productId == 0x6102) {
                            setBusy(false)
                            status.text = "USB permission granted, but calculator storage is busy."
                            details.text = "Storage is not ready to browse."
                            AlertDialog.Builder(this)
                                .setTitle("Use direct calculator access?")
                                .setMessage("Android's USB driver may be holding the calculator. Close other USB apps. If Android mounted the calculator, safely eject it in Android first and wait for any transfers to finish.\n\nDirect access detaches that driver so EndraLink can manage calculator storage for browsing and file operations.")
                                .setPositiveButton("Use direct access") { _, _ ->
                                    if (!destroyed && generation == request && isAttached(device)) {
                                        DebugLog.event("DIRECT_ACCESS_CONFIRMED")
                                        browseDirectory(cluster, true)
                                    }
                                }
                                .setNegativeButton("Cancel") { _, _ -> DebugLog.event("DIRECT_ACCESS_CANCELLED") }
                                .show()
                        } else closeSession("Storage read stopped: " + (e.message ?: e.javaClass.simpleName))
                    }
                }
            }
        }
    }

    /** Show read-only folder navigation; file taps display metadata rather than starting transfers. */
    private fun showDirectory(volume: Fat16Volume, entries: List<Fat16Volume.Entry>) {
        setBusy(false)
        status.text = "FAT16 storage ready"
        details.text = "FAT16 access • " + entries.size + " items in this folder"
        findViewById<View>(R.id.browserPanel).visibility = View.VISIBLE
        val freeBytes = runCatching { volume.freeBytes() }.getOrDefault(-1L)
        findViewById<TextView>(R.id.storageInfo).text =
            "FAT16 • " + (if (freeBytes >= 0) formatMiB(freeBytes) + " free / " else "") +
                formatMiB(volume.capacityBytes) +
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
                    DebugLog.event("TAP", "entry directory=" + entry.directory + " cluster=" + entry.cluster)
                    if (!busy) {
                        if (entry.directory) {
                            if (folderStack.size >= 32 || folderStack.any { it.second == entry.cluster }) {
                                status.text = "Folder navigation limit reached."
                            } else {
                                folderStack.add(entry.name to entry.cluster)
                                browseDirectory(entry.cluster)
                            }
                        } else {
                            showCalculatorEntryMenu(volume, entry)
                        }
                    }
                }
                setOnLongClickListener {
                    if (!busy) showCalculatorEntryMenu(volume, entry)
                    true
                }
            })
            list.addView(View(this).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.outline))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })
        }
    }

    private fun showCalculatorEntryMenu(volume: Fat16Volume, entry: Fat16Volume.Entry) {
        val actions = if (entry.directory)
            arrayOf("Open", "Copy folder to phone", "Rename", "Delete empty folder")
        else
            arrayOf("Copy to phone", "Rename", "Delete")
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(actions) { _, which ->
                if (entry.directory) {
                    when (which) {
                        0 -> {
                            if (folderStack.size < 32 && folderStack.none { it.second == entry.cluster }) {
                                folderStack.add(entry.name to entry.cluster)
                                browseDirectory(entry.cluster)
                            }
                        }
                        1 -> copyCalculatorEntryToPhone(volume, entry)
                        2 -> promptRenameCalculatorEntry(entry)
                        3 -> confirmDeleteCalculatorEntry(entry)
                    }
                } else {
                    when (which) {
                        0 -> copyCalculatorEntryToPhone(volume, entry)
                        1 -> promptRenameCalculatorEntry(entry)
                        2 -> confirmDeleteCalculatorEntry(entry)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptRenameCalculatorEntry(entry: Fat16Volume.Entry) {
        val input = EditText(this).apply {
            setText(entry.name)
            setSelection(text.length)
            singleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle("Rename " + if (entry.directory) "folder" else "file")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString()
                val session = storage ?: return@setPositiveButton
                val parent = folderStack.lastOrNull()?.second ?: 0
                val request = generation
                setBusy(true)
                worker.execute {
                    try {
                        session.volume.renameEntry(parent, entry.name, newName)
                        main.post {
                            if (!destroyed && generation == request) {
                                setBusy(false)
                                status.text = "Renamed to " + newName
                                browseDirectory(parent)
                            }
                        }
                    } catch (e: Exception) {
                        main.post {
                            if (!destroyed && generation == request) {
                                setBusy(false)
                                status.text = "Rename failed"
                                details.text = e.message ?: e.javaClass.simpleName
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteCalculatorEntry(entry: Fat16Volume.Entry) {
        AlertDialog.Builder(this)
            .setTitle("Delete " + entry.name + "?")
            .setMessage(if (entry.directory)
                "Only empty folders can be deleted. This cannot be undone."
                else "This file will be permanently removed from the calculator.")
            .setPositiveButton("Delete") { _, _ ->
                val session = storage ?: return@setPositiveButton
                val parent = folderStack.lastOrNull()?.second ?: 0
                val request = generation
                setBusy(true)
                worker.execute {
                    try {
                        session.volume.deleteEntry(parent, entry.name)
                        main.post {
                            if (!destroyed && generation == request) {
                                setBusy(false)
                                status.text = "Deleted " + entry.name
                                browseDirectory(parent)
                            }
                        }
                    } catch (e: Exception) {
                        main.post {
                            if (!destroyed && generation == request) {
                                setBusy(false)
                                status.text = "Delete failed"
                                details.text = e.message ?: e.javaClass.simpleName
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptCreateCalculatorItem(directory: Boolean) {
        val input = EditText(this).apply {
            hint = if (directory) "Folder name" else "File name"
            singleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle(if (directory) "New calculator folder" else "New calculator file")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString()
                val session = storage ?: return@setPositiveButton
                val parent = folderStack.lastOrNull()?.second ?: 0
                val request = generation
                setBusy(true)
                worker.execute {
                    try {
                        if (directory) session.volume.ensureDirectory(parent, name)
                        else session.volume.writeFile(parent, name, ByteArray(0), false)
                        main.post {
                            if (!destroyed && generation == request) {
                                setBusy(false)
                                status.text = "Created " + name
                                browseDirectory(parent)
                            }
                        }
                    } catch (e: Exception) {
                        main.post {
                            if (!destroyed && generation == request) {
                                setBusy(false)
                                status.text = "Create failed"
                                details.text = e.message ?: e.javaClass.simpleName
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyCalculatorEntryToPhone(volume: Fat16Volume, entry: Fat16Volume.Entry) {
        if (phoneStack.isEmpty()) {
            status.text = "Choose or grant phone storage access first."
            return
        }
        val targetFolder = phoneStack.last().second
        val request = generation
        transferring = true
        setTransferMode(true)
        setBusy(true)
        status.text = "Copying from calculator…"
        details.text = "Do not disconnect the calculator."
        worker.execute {
            val counts = intArrayOf(0, 0)
            try {
                val total = countCalculatorItems(volume, entry)
                transferTotalItems = total
                main.post { transferNotification(0, total, "Calculator → phone: " + entry.name) }
                copyCalculatorEntryRecursive(volume, entry, targetFolder, counts, intArrayOf(0), request)
                main.post {
                    if (!destroyed && generation == request) {
                        transferring = false
                        setTransferMode(false)
                        setBusy(false)
                        status.text = "Copy to phone complete"
                        details.text = counts[0].toString() + " files and " + counts[1] + " folders copied."
                        transferNotification(total, total,
                            counts[0].toString() + " files • " + counts[1] + " folders", true)
                        renderPhoneDirectory()
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (!destroyed && generation == request) {
                        transferring = false
                        setTransferMode(false)
                        setBusy(false)
                        status.text = "Copy to phone failed"
                        details.text = e.message ?: e.javaClass.simpleName
                        transferNotificationFailed(e.message ?: "Calculator → phone copy failed")
                    }
                }
            }
        }
    }

    private fun countCalculatorItems(volume: Fat16Volume, entry: Fat16Volume.Entry, depth: Int = 0): Int {
        if (depth > 32) throw java.io.IOException("Folder nesting exceeds the 32-level safety limit.")
        if (!entry.directory) return 1
        var count = 1
        volume.list(entry.cluster).forEach {
            count += countCalculatorItems(volume, it, depth + 1)
            if (count > 10000) throw java.io.IOException("Copy exceeds the 10,000-item safety limit.")
        }
        return count
    }

    private fun copyCalculatorEntryRecursive(
        volume: Fat16Volume,
        entry: Fat16Volume.Entry,
        phoneParent: Uri,
        counts: IntArray,
        visited: IntArray,
        request: Int,
        depth: Int = 0
    ) {
        if (depth > 32) throw java.io.IOException("Folder nesting exceeds the 32-level safety limit.")
        visited[0]++
        if (visited[0] > 10000) throw java.io.IOException("Copy exceeds the 10,000-item safety limit.")
        main.post {
            if (!destroyed && generation == request)
                transferNotification(visited[0] - 1, transferTotalItems, "Calculator → phone: " + entry.name)
        }

        if (entry.directory) {
            val dest = ensurePhoneDirectory(phoneParent, entry.name)
            counts[1]++
            volume.list(entry.cluster).forEach {
                copyCalculatorEntryRecursive(volume, it, dest, counts, visited, request, depth + 1)
            }
        } else {
            val data = volume.readFile(folderClusterForEntry(entry), entry.name)
            writePhoneFile(phoneParent, entry.name, data)
            counts[0]++
        }
        main.post {
            if (!destroyed && generation == request)
                transferNotification(visited[0], transferTotalItems, "Calculator → phone: " + entry.name)
        }
    }

    private fun folderClusterForEntry(entry: Fat16Volume.Entry): Int {
        // readFile needs the entry's parent directory; use current folder for a top-level selection.
        return folderStack.lastOrNull()?.second ?: 0
    }

    private fun findPhoneChild(parent: Uri, name: String): PhoneEntry? =
        queryPhoneChildren(parent).firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun ensurePhoneDirectory(parent: Uri, name: String): Uri {
        val existing = findPhoneChild(parent, name)
        if (existing != null) {
            if (!existing.directory) throw java.io.IOException("A phone file named " + name + " already exists.")
            return existing.uri
        }
        if (parent.scheme == "file") {
            val dir = java.io.File(parent.path, name)
            if (!dir.exists() && !dir.mkdirs()) throw java.io.IOException("Could not create phone folder " + name)
            return Uri.fromFile(dir)
        }
        return DocumentsContract.createDocument(contentResolver, parent,
            DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw java.io.IOException("Could not create phone folder " + name)
    }

    private fun writePhoneFile(parent: Uri, name: String, data: ByteArray) {
        val existing = findPhoneChild(parent, name)
        if (existing != null && existing.directory)
            throw java.io.IOException("A phone folder named " + name + " already exists.")
        val target = if (existing != null) existing.uri else if (parent.scheme == "file") {
            Uri.fromFile(java.io.File(parent.path, name))
        } else {
            DocumentsContract.createDocument(contentResolver, parent, "application/octet-stream", name)
                ?: throw java.io.IOException("Could not create phone file " + name)
        }
        val stream = if (target.scheme == "file")
            target.path?.let { java.io.FileOutputStream(java.io.File(it), false) }
        else contentResolver.openOutputStream(target, "wt")
        stream?.use { it.write(data) } ?: throw java.io.IOException("Could not write phone file " + name)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun formatMiB(bytes: Long): String = String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002) {
            requestAllFilesAccessIfNeeded()
        } else if (requestCode == 1005 && hasFullStorageAccess()) {
            openFullStorageRoot()
        }
    }

    override fun onResume() {
        super.onResume()
        DebugLog.event("ACTIVITY_RESUME", "pending=" + (pendingDevice != null) + " busy=" + busy)
        pendingDevice?.let {
            logDevice("PERMISSION_ON_RESUME", it)
            if (usb.hasPermission(it)) finishPermission(it)
        }
        if (hasFullStorageAccess() && (phoneStack.isEmpty() || phoneStack.firstOrNull()?.first != "Internal storage")) {
            openFullStorageRoot()
        }
        if (::usb.isInitialized) checkAttachment()
    }

    override fun onDestroy() {
        DebugLog.event("ACTIVITY_DESTROY", "finishing=" + isFinishing + " changingConfig=" + isChangingConfigurations)
        destroyed = true
        closeSession("USB connection closed.")
        unregisterReceiver(receiver)
        worker.shutdown()
        super.onDestroy()
    }

    @Deprecated("Legacy picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        DebugLog.event("PICKER_RESULT", "request=" + requestCode + " result=" + resultCode)
        if (requestCode == 1003) {
            if (resultCode == Activity.RESULT_OK) data?.data?.let { exportLog(it) }
            return
        }
        if (requestCode != 1004 || resultCode != Activity.RESULT_OK) return
        val tree = data?.data ?: return
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { contentResolver.takePersistableUriPermission(tree, takeFlags) }
        getSharedPreferences("endralink", MODE_PRIVATE).edit().putString("phoneTree", tree.toString()).apply()
        openPhoneTree(tree)
    }

    private fun hasFullStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun restorePhoneAccess() {
        if (hasFullStorageAccess()) {
            openFullStorageRoot()
            return
        }
        val saved = getSharedPreferences("endralink", MODE_PRIVATE).getString("phoneTree", null) ?: return
        runCatching { openPhoneTree(Uri.parse(saved)) }.onFailure {
            getSharedPreferences("endralink", MODE_PRIVATE).edit().remove("phoneTree").apply()
        }
    }

    private fun openFullStorageRoot() {
        val root = Environment.getExternalStorageDirectory()
        phoneTreeUri = null
        phoneStack.clear()
        phoneStack.add("Internal storage" to Uri.fromFile(root))
        findViewById<Button>(R.id.openPhone).text = "Storage root"
        renderPhoneDirectory()
    }

    private fun requestInitialPermissionsIfNeeded() {
        val prefs = getSharedPreferences("endralink", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("askedNotifications", false)) {
            prefs.edit().putBoolean("askedNotifications", true).apply()
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002)
            return
        }
        requestAllFilesAccessIfNeeded()
    }

    private fun requestAllFilesAccessIfNeeded() {
        val prefs = getSharedPreferences("endralink", MODE_PRIVATE)
        if (hasFullStorageAccess() || prefs.getBoolean("askedAllFiles", false)) return
        prefs.edit().putBoolean("askedAllFiles", true).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:" + packageName)
                })
            } catch (_: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1005)
        }
    }

    private fun openPhoneTree(tree: Uri) {
        phoneTreeUri = tree
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        phoneStack.clear()
        phoneStack.add("Phone folder" to root)
        findViewById<Button>(R.id.openPhone).text = "Change phone folder"
        renderPhoneDirectory()
    }

    private fun queryPhoneChildren(folder: Uri): List<PhoneEntry> {
        if (folder.scheme == "file") {
            val dir = folder.path?.let { java.io.File(it) } ?: return emptyList()
            val children = dir.listFiles()?.toList() ?: emptyList()
            return children
                .filter { it.exists() && it.canRead() }
                .map { PhoneEntry(Uri.fromFile(it), it.name, it.isDirectory, if (it.isFile) it.length() else 0L) }
                .sortedWith(compareBy<PhoneEntry> { !it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
        val tree = phoneTreeUri ?: return emptyList()
        val id = DocumentsContract.getDocumentId(folder)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val result = mutableListOf<PhoneEntry>()
        contentResolver.query(children, cols, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                val childId = cursor.getString(idCol)
                val name = cursor.getString(nameCol) ?: childId
                val mime = cursor.getString(mimeCol)
                val child = DocumentsContract.buildDocumentUriUsingTree(tree, childId)
                result.add(PhoneEntry(child, name, mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L))
            }
        }
        return result.sortedWith(compareBy<PhoneEntry> { !it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun renderPhoneDirectory() {
        val current = phoneStack.lastOrNull()?.second ?: run {
            findViewById<TextView>(R.id.phonePath).text = "Grant a phone folder once, then browse it here."
            findViewById<LinearLayout>(R.id.phoneFileBrowser).removeAllViews()
            findViewById<Button>(R.id.phoneUp).isEnabled = false
            return
        }
        findViewById<TextView>(R.id.phonePath).text = "Loading " + phoneStack.joinToString("/") { it.first } + "…"
        worker.execute {
            try {
                val entries = queryPhoneChildren(current)
                main.post {
                    if (!destroyed && phoneStack.lastOrNull()?.second == current) {
                        findViewById<TextView>(R.id.phonePath).text = phoneStack.joinToString("/") { it.first }
                        findViewById<Button>(R.id.phoneUp).isEnabled = !busy && phoneStack.size > 1
                        val list = findViewById<LinearLayout>(R.id.phoneFileBrowser)
                        list.removeAllViews()
                        if (entries.isEmpty()) {
                            list.addView(TextView(this).apply {
                                text = "This phone folder is empty."
                                setTextColor(ContextCompat.getColor(context, R.color.muted))
                                setPadding(dp(4), dp(10), dp(4), dp(10))
                            })
                        }
                        entries.take(300).forEach { entry ->
                            val row = LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = android.view.Gravity.CENTER_VERTICAL
                            }
                            val check = CheckBox(this).apply {
                                isChecked = selectedFiles.any { it.uri == entry.uri }
                                setOnCheckedChangeListener { _, checked ->
                                    if (checked) {
                                        if (selectedFiles.none { it.uri == entry.uri })
                                            selectedFiles.add(PendingItem(entry.uri, entry.name, entry.directory))
                                    } else selectedFiles.removeAll { it.uri == entry.uri }
                                    renderLocalQueue()
                                }
                            }
                            row.addView(check)
                            row.addView(TextView(this).apply {
                                text = (if (entry.directory) "📁 " else "📄 ") + entry.name +
                                    if (!entry.directory && entry.size > 0) "\n" + entry.size + " bytes" else ""
                                setTextColor(ContextCompat.getColor(context, if (entry.directory) R.color.cyan else R.color.silver))
                                textSize = 15f
                                setPadding(dp(4), dp(10), dp(4), dp(10))
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                                setOnClickListener {
                                    if (entry.directory && !busy) {
                                        phoneStack.add(entry.name to entry.uri)
                                        renderPhoneDirectory()
                                    } else if (!busy) {
                                        check.isChecked = !check.isChecked
                                    }
                                }
                            })
                            list.addView(row)
                        }
                    }
                }
            } catch (e: Exception) {
                DebugLog.event("PHONE_BROWSER_ERROR", error = e)
                main.post {
                    findViewById<TextView>(R.id.phonePath).text =
                        "Phone folder permission is unavailable. Tap Change phone folder."
                }
            }
        }
    }

    private fun renderLocalQueue() {
        val list = findViewById<LinearLayout>(R.id.localFileList)
        list.removeAllViews()
        findViewById<TextView>(R.id.progressText).text = when (selectedFiles.size) {
            0 -> "No files or folders selected"
            1 -> "1 item selected"
            else -> selectedFiles.size.toString() + " items selected"
        }
        selectedFiles.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = (if (item.directory) "📁 " else "📄 ") + item.name
                setTextColor(ContextCompat.getColor(context, R.color.silver))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(4), dp(8), dp(8), dp(8))
            })
            row.addView(Button(this).apply {
                text = "Remove"
                isAllCaps = false
                setOnClickListener {
                    if (!busy && index < selectedFiles.size) {
                        selectedFiles.removeAt(index)
                        renderLocalQueue()
                        renderPhoneDirectory()
                    }
                }
            })
            list.addView(row)
        }
        findViewById<Button>(R.id.clearQueue).isEnabled = !busy && selectedFiles.isNotEmpty()
        findViewById<Button>(R.id.copy).isEnabled = !busy && selectedFiles.isNotEmpty()
    }

    private fun createTransferNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                transferChannelId,
                "EndraLink transfers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "File and folder transfer progress to connected calculators"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun transferNotification(progressValue: Int, total: Int, message: String, complete: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, transferChannelId)
            .setSmallIcon(R.drawable.endralink_logo)
            .setContentTitle(if (complete) "EndraLink transfer complete" else "EndraLink transferring")
            .setContentText(message)
            .setSubText("fx-CG50")
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(!complete)
            .setAutoCancel(complete)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (complete) builder.setProgress(0, 0, false)
        else if (total > 0) builder.setProgress(total, progressValue.coerceIn(0, total), false)
        else builder.setProgress(0, 0, true)

        runCatching { NotificationManagerCompat.from(this).notify(transferNotificationId, builder.build()) }
    }

    private fun transferNotificationFailed(message: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, transferChannelId)
            .setSmallIcon(R.drawable.endralink_logo)
            .setContentTitle("EndraLink transfer stopped")
            .setContentText(message)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(transferNotificationId, notification) }
    }

    private fun countPhoneItems(item: PendingItem, depth: Int = 0): Int {
        if (depth > 32) throw java.io.IOException("Folder nesting exceeds the 32-level safety limit.")
        if (!item.directory) return 1
        var count = 1
        queryPhoneChildren(item.uri).forEach { child ->
            count += countPhoneItems(PendingItem(child.uri, child.name, child.directory), depth + 1)
            if (count > 10000) throw java.io.IOException("Transfer exceeds the 10,000-item safety limit.")
        }
        return count
    }

    private fun confirmTransfer() {
        if (busy || selectedFiles.isEmpty()) return
        if (connection == null) {
            pendingTransferAfterConnect = true
            status.text = "Connecting to fx-CG50 for transfer…"
            details.text = "EndraLink will continue the transfer automatically after USB access is ready."
            findCalculator()
            return
        }
        val session = storage
        if (session == null) {
            prepareStorageForTransfer(false)
        } else {
            preflightTransfer(session)
        }
    }

    /** Initialize calculator storage automatically so Browse calculator is optional for root transfers. */
    private fun prepareStorageForTransfer(directAccess: Boolean) {
        if (busy) return
        val handle = connection ?: return
        val device = activeDevice ?: return
        val intf = storageInterface(device) ?: return
        val request = generation
        setBusy(true)
        status.text = "Preparing calculator storage…"
        details.text = "Browse is optional. EndraLink is opening the calculator root folder for transfer."
        worker.execute {
            try {
                val opened = UsbStorageSession(handle, intf, directAccess) {
                    destroyed || generation != request
                }
                val free = opened.volume.freeBytes()
                main.post {
                    if (destroyed || generation != request) {
                        opened.close()
                    } else {
                        storage = opened
                        setBusy(false)
                        status.text = "Calculator storage ready"
                        details.text = formatMiB(free) + " free / " + formatMiB(opened.volume.capacityBytes)
                        preflightTransfer(opened)
                    }
                }
            } catch (e: Exception) {
                DebugLog.event("TRANSFER_STORAGE_PREP_ERROR", "directAccess=" + directAccess, e)
                main.post {
                    if (!destroyed && generation == request) {
                        setBusy(false)
                        if (e is UsbStorageSession.InterfaceBusyException && !directAccess) {
                            AlertDialog.Builder(this)
                                .setTitle("Use direct calculator access?")
                                .setMessage("Android is currently holding the calculator storage. EndraLink can take direct USB access so you can transfer without opening Browse calculator first.")
                                .setPositiveButton("Use direct access") { _, _ -> prepareStorageForTransfer(true) }
                                .setNegativeButton("Cancel", null)
                                .show()
                        } else {
                            status.text = "Could not prepare calculator storage"
                            details.text = e.message ?: e.javaClass.simpleName
                        }
                    }
                }
            }
        }
    }

    private fun preflightTransfer(session: UsbStorageSession) {
        if (busy || selectedFiles.isEmpty()) return
        val items = selectedFiles.toList()
        val directoryCluster = folderStack.lastOrNull()?.second ?: 0
        val request = generation
        setBusy(true)
        status.text = "Checking selected items…"
        worker.execute {
            try {
                val conflicts = mutableListOf<String>()
                items.forEach { collectConflicts(session.volume, directoryCluster, it, it.name, 0, conflicts) }
                main.post {
                    if (!destroyed && generation == request) {
                        setBusy(false)
                        val destination = "/" + folderStack.joinToString("/") { it.first }
                        if (conflicts.isNotEmpty()) {
                            val names = conflicts.take(10).joinToString("\n") { "• " + it } +
                                if (conflicts.size > 10) "\n• …and " + (conflicts.size - 10) + " more" else ""
                            AlertDialog.Builder(this)
                                .setTitle("Overwrite existing files?")
                                .setMessage(conflicts.size.toString() + " file" +
                                    if (conflicts.size == 1) " already exists:\n\n" else "s already exist:\n\n" +
                                    names + "\n\nDestination: " + destination +
                                    "\n\nExisting folders will be merged. Only matching files will be replaced.")
                                .setPositiveButton("Overwrite files") { _, _ -> transferSelectedItems(true) }
                                .setNegativeButton("Cancel", null)
                                .show()
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle("Transfer selected items?")
                                .setMessage(items.size.toString() + " selected item" +
                                    if (items.size == 1) "" else "s" + "\n\nDestination: " + destination +
                                    "\n\nFolders will be copied recursively.")
                                .setPositiveButton("Transfer") { _, _ -> transferSelectedItems(false) }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                }
            } catch (e: Exception) {
                DebugLog.event("TRANSFER_PREFLIGHT_ERROR", error = e)
                main.post {
                    if (!destroyed && generation == request) {
                        setBusy(false)
                        status.text = "Could not check destination"
                        details.text = e.message ?: e.javaClass.simpleName
                    }
                }
            }
        }
    }

    private fun collectConflicts(
        volume: Fat16Volume,
        targetCluster: Int,
        item: PendingItem,
        path: String,
        depth: Int,
        conflicts: MutableList<String>
    ) {
        if (depth > 32) throw java.io.IOException("Folder nesting exceeds the 32-level safety limit.")
        val existing = volume.findEntry(targetCluster, item.name)
        if (item.directory) {
            if (existing != null && !existing.directory)
                throw java.io.IOException("Cannot copy folder " + path + " because a file with that name already exists.")
            if (existing != null) {
                queryPhoneChildren(item.uri).forEach { child ->
                    collectConflicts(volume, existing.cluster,
                        PendingItem(child.uri, child.name, child.directory),
                        path + "/" + child.name, depth + 1, conflicts)
                }
            }
        } else {
            if (existing != null && existing.directory)
                throw java.io.IOException("Cannot copy file " + path + " because a folder with that name already exists.")
            if (existing != null) conflicts.add(path)
        }
        if (conflicts.size > 4096) throw java.io.IOException("Too many filename conflicts in one transfer.")
    }

    private fun transferSelectedItems(overwriteConflicts: Boolean) {
        if (busy || selectedFiles.isEmpty()) return
        val session = storage ?: return
        val items = selectedFiles.toList()
        val directoryCluster = folderStack.lastOrNull()?.second ?: 0
        val request = generation
        transferring = true
        setTransferMode(true)
        setBusy(true)
        status.text = "Transferring selected items…"
        details.text = "Do not disconnect the calculator."
        worker.execute {
            val completed = intArrayOf(0, 0) // files, folders
            val visited = intArrayOf(0)
            var currentName = ""
            try {
                transferTotalItems = items.sumOf { countPhoneItems(it) }
                main.post {
                    transferNotification(0, transferTotalItems, "Preparing " + transferTotalItems + " items…")
                }
                items.forEach { item ->
                    currentName = item.name
                    transferPhoneItem(session.volume, directoryCluster, item, item.name,
                        overwriteConflicts, 0, completed, visited, request)
                }

                val free = session.volume.freeBytes()
                main.post {
                    if (!destroyed && generation == request) {
                        transferring = false
                        setTransferMode(false)
                        setBusy(false)
                        status.text = "Transfer complete"
                        details.text = completed[0].toString() + " files and " + completed[1] +
                            " folders transferred successfully."
                        findViewById<TextView>(R.id.progressText).text =
                            "✓ Transfer complete: " + completed[0] + " files, " + completed[1] + " folders"
                        findViewById<TextView>(R.id.storageInfo).text =
                            "FAT16 • " + formatMiB(free) + " free / " + formatMiB(session.volume.capacityBytes) +
                                if (session.volume.label.isNotBlank()) " • " + session.volume.label else ""
                        selectedFiles.clear()
                        renderLocalQueue()
                        renderPhoneDirectory()
                        transferNotification(transferTotalItems, transferTotalItems,
                            completed[0].toString() + " files • " + completed[1] + " folders", true)
                        Toast.makeText(this, "Transfer complete", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                DebugLog.event("TRANSFER_ERROR", "files=" + completed[0] + " folders=" + completed[1] +
                    " current=" + currentName, e)
                main.post {
                    if (!destroyed && generation == request) {
                        transferring = false
                        setTransferMode(false)
                        setBusy(false)
                        status.text = "Transfer stopped"
                        details.text = completed[0].toString() + " files and " + completed[1] +
                            " folders completed. " + (e.message ?: e.javaClass.simpleName)
                        findViewById<TextView>(R.id.progressText).text = "Transfer stopped at " + currentName
                        transferNotificationFailed("Stopped at " + currentName)
                        renderLocalQueue()
                        renderPhoneDirectory()
                    }
                }
            }
        }
    }

    private fun openPhoneInput(uri: Uri): java.io.InputStream? =
        if (uri.scheme == "file") uri.path?.let { java.io.File(it).inputStream() }
        else contentResolver.openInputStream(uri)

    private fun transferPhoneItem(
        volume: Fat16Volume,
        targetCluster: Int,
        item: PendingItem,
        path: String,
        overwriteConflicts: Boolean,
        depth: Int,
        completed: IntArray,
        visited: IntArray,
        request: Int
    ) {
        if (depth > 32) throw java.io.IOException("Folder nesting exceeds the 32-level safety limit.")
        visited[0]++
        if (visited[0] > 10000) throw java.io.IOException("Transfer exceeds the 10,000-item safety limit.")
        main.post {
            if (!destroyed && generation == request) {
                transferNotification(visited[0] - 1, transferTotalItems, path)
            }
        }

        if (item.directory) {
            val destination = volume.ensureDirectory(targetCluster, item.name)
            completed[1]++
            main.post {
                if (!destroyed && generation == request) {
                    transferNotification(visited[0], transferTotalItems, path)
                }
            }
            val children = queryPhoneChildren(item.uri)
            children.forEach { child ->
                transferPhoneItem(volume, destination,
                    PendingItem(child.uri, child.name, child.directory),
                    path + "/" + child.name, overwriteConflicts, depth + 1,
                    completed, visited, request)
            }
        } else {
            main.post {
                if (!destroyed && generation == request) {
                    findViewById<TextView>(R.id.progressText).text =
                        "Transferring: " + path + "\n" + completed[0] + " files completed"
                }
            }
            val bytes = openPhoneInput(item.uri)?.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > 64 * 1024 * 1024)
                        throw java.io.IOException(path + " exceeds the 64 MiB per-file safety limit.")
                    buffer.write(chunk, 0, n)
                }
                buffer.toByteArray()
            } ?: throw java.io.IOException("Could not open " + path)
            volume.writeFile(targetCluster, item.name, bytes, overwriteConflicts)
            completed[0]++
            main.post {
                if (!destroyed && generation == request) {
                    transferNotification(visited[0], transferTotalItems, path)
                }
            }
            DebugLog.event("TRANSFER_FILE_SUCCESS", "bytes=" + bytes.size + " depth=" + depth)
        }
    }

    /** Log numeric USB identity and endpoint layout, never serial numbers or user file names. */
    private fun logDevice(event: String, device: UsbDevice) {
        val description = runCatching {
            "vid=%04X pid=%04X".format(device.vendorId, device.productId) +
                " deviceId=" + device.deviceId + " path=" + device.deviceName +
                " permission=" + usb.hasPermission(device) + " attached=" + isAttached(device) +
                " interfaces=" + (0 until device.interfaceCount).joinToString(";") { i ->
                    val intf = device.getInterface(i)
                    "id=" + intf.id + ",class=" + intf.interfaceClass +
                        ",subclass=" + intf.interfaceSubclass + ",protocol=" + intf.interfaceProtocol +
                        ",endpoints=" + (0 until intf.endpointCount).joinToString(",") { j ->
                            val endpoint = intf.getEndpoint(j)
                            endpoint.address.toString() + "/" + endpoint.type + "/" + endpoint.maxPacketSize
                        }
                }
        }.getOrElse { "USB diagnostic read failed: " + it.javaClass.simpleName }
        DebugLog.event(event, description)
    }

    /** Export through Android's Save dialog; remains available when a USB attempt is stuck. */
    private fun requestLogExport() {
        DebugLog.event("TAP", "export_debug_log")
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "EndraLink-debug-" + System.currentTimeMillis() + ".txt")
            }, 1003)
        } catch (e: ActivityNotFoundException) {
            DebugLog.event("EXPORT_PICKER_ERROR", error = e)
            Toast.makeText(this, "No Android file-saving app is available.", Toast.LENGTH_LONG).show()
        }
    }

    /** Use a separate worker so a blocked USB read cannot prevent saving diagnostics. */
    private fun exportLog(destination: Uri) {
        DebugLog.event("EXPORT_BEGIN")
        val exportContext = applicationContext
        Thread({
            try {
                val text = DebugLog.snapshot()
                val stream = exportContext.contentResolver.openOutputStream(destination, "wt")
                    ?: throw java.io.IOException("Could not open the selected output file")
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                DebugLog.event("EXPORT_SUCCESS")
                main.post { Toast.makeText(exportContext, "Debug log saved. Attach the text file in chat.", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                DebugLog.event("EXPORT_ERROR", error = e)
                main.post { Toast.makeText(exportContext, "Could not save the debug log. Please try another location.", Toast.LENGTH_LONG).show() }
            }
        }, "EndraLink-log-export").start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("workspace", findViewById<View>(R.id.workspacePage).visibility == View.VISIBLE)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        DebugLog.event("ACTIVITY_PAUSE")
        super.onPause()
    }
}
