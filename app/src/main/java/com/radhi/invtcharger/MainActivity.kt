package com.radhi.invtcharger

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Single-screen BLE tracker for the INVT EVC16 charger.
 * Scans for the charger, connects, enables notifications on 0xC305,
 * writes the status-query frame to 0xC303 every few seconds, reassembles
 * fragments, decodes, shows live values, and logs each session to CSV.
 */
class MainActivity : ComponentActivity() {

    companion object {
        // 16-bit UUIDs expand into the Bluetooth base UUID.
        val SVC: UUID = uuid16(0xA002)
        val WRITE_CHAR: UUID = uuid16(0xC303)   // write-no-response (confirmed replying)
        val WRITE_CHAR_ALT: UUID = uuid16(0xC302) // write-with-response fallback
        val NOTIFY_CHAR: UUID = uuid16(0xC305)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val POLL_MS = 3000L
        const val NAME_PREFIX = "3UC"   // charger advertises e.g. 3UC243403423

        fun uuid16(v: Int): UUID =
            UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))
    }

    private lateinit var btAdapter: BluetoothAdapter
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val reasm = InvtProtocol.Reassembler()
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // session tracking
    private var sess: MutableMap<String, Any>? = null

    // UI
    private lateinit var statusText: TextView
    private lateinit var stateBadge: TextView
    private lateinit var grid: TextView
    private lateinit var log: TextView
    private lateinit var connectBtn: Button

    private val needPerms: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = mgr.adapter
        setContentView(buildUi())
        requestPermsThen { }
    }

    private fun buildUi(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val title = TextView(this).apply {
            text = "INVT EV Charger — Bluetooth"
            textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        statusText = TextView(this).apply { text = "Not connected"; textSize = 14f; setPadding(0, pad / 2, 0, pad / 2) }
        connectBtn = Button(this).apply {
            text = "Connect"
            setOnClickListener { if (gatt == null) startScan() else disconnect() }
        }
        stateBadge = TextView(this).apply {
            text = "—"; textSize = 16f; setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        grid = TextView(this).apply { text = ""; textSize = 15f; setPadding(0, pad / 2, 0, pad / 2) }
        val startBtn = Button(this).apply { text = "Start charging"; setOnClickListener { sendCmd(true) } }
        val stopBtn = Button(this).apply { text = "Stop charging"; setOnClickListener { sendCmd(false) } }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(startBtn, lp()); addView(stopBtn, lp())
        }
        log = TextView(this).apply { text = ""; textSize = 12f; setPadding(0, pad, 0, 0) }

        col.addView(title); col.addView(statusText); col.addView(connectBtn)
        col.addView(stateBadge); col.addView(grid); col.addView(btnRow); col.addView(log)
        root.addView(col)
        return root
    }

    private fun lp() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun requestPermsThen(after: () -> Unit) {
        val missing = needPerms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) after()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        logln(if (r.all { it == PackageManager.PERMISSION_GRANTED }) "Permissions granted" else "Permissions denied — app needs Bluetooth")
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        requestPermsThen {
            if (!btAdapter.isEnabled) { toast("Turn on Bluetooth"); return@requestPermsThen }
            setStatus("Scanning…")
            val scanner = btAdapter.bluetoothLeScanner
            val cb = object : ScanCallback() {
                override fun onScanResult(type: Int, result: ScanResult) {
                    val dev = result.device
                    val name = try { dev.name } catch (e: SecurityException) { null } ?: return
                    if (name.startsWith(NAME_PREFIX)) {
                        scanner.stopScan(this)
                        logln("Found $name")
                        connect(dev)
                    }
                }
                override fun onScanFailed(code: Int) { setStatus("Scan failed ($code)") }
            }
            scanner.startScan(cb)
            // stop scanning after 15s if nothing found
            main.postDelayed({ try { scanner.stopScan(cb) } catch (_: Exception) {} }, 15000)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(dev: BluetoothDevice) {
        setStatus("Connecting…")
        gatt = dev.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        main.removeCallbacks(pollRunnable)
        gatt?.disconnect(); gatt?.close(); gatt = null
        setStatus("Not connected")
        runOnUiThread { connectBtn.text = "Connect" }
    }

    private val gattCb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setStatus("Discovering services…")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                setStatus("Disconnected")
                runOnUiThread { connectBtn.text = "Connect" }
                main.removeCallbacks(pollRunnable)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SVC)
            if (svc == null) { setStatus("Charger service not found"); return }
            writeChar = svc.getCharacteristic(WRITE_CHAR) ?: svc.getCharacteristic(WRITE_CHAR_ALT)
            val notify = svc.getCharacteristic(NOTIFY_CHAR)
            if (writeChar == null || notify == null) { setStatus("Characteristics missing"); return }
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD)
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            setStatus("Connected")
            runOnUiThread { connectBtn.text = "Disconnect" }
            main.post(pollRunnable)   // begin polling
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            handleBytes(c.value ?: return)
        }

        // Android 13+ delivers bytes via this overload
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleBytes(value)
        }
    }

    private fun handleBytes(bytes: ByteArray) {
        val frames = reasm.feed(bytes)
        for ((cmd, payload) in frames) {
            if (cmd == InvtProtocol.CMD_READ_STATUS) {
                val d = InvtProtocol.parseStatus(payload) ?: continue
                updateSession(d)
                showStatus(d)
            }
        }
    }

    private val pollRunnable = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            val wc = writeChar; val g = gatt
            if (wc != null && g != null) {
                val q = InvtProtocol.statusQuery()
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(wc, q, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                } else {
                    @Suppress("DEPRECATION")
                    wc.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION") run { wc.value = q; g.writeCharacteristic(wc) }
                }
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendCmd(start: Boolean) {
        val wc = writeChar; val g = gatt ?: return
        val f = InvtProtocol.startStop(start)
        if (wc == null) return
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(wc, f, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION") run { wc.value = f; g.writeCharacteristic(wc) }
        }
        toast(if (start) "Start sent" else "Stop sent")
    }

    // ---- session logging ----
    @Suppress("UNCHECKED_CAST")
    private fun updateSession(d: InvtProtocol.Status) {
        if (d.gunStatus == 2) {
            if (sess == null) {
                sess = mutableMapOf(
                    "start" to dayFmt.format(Date()),
                    "energy" to d.energyKwh, "cost" to d.cost,
                    "dur" to d.durationMin, "peakP" to d.powerKw, "peakA" to d.current
                )
            } else {
                val s = sess!!
                s["energy"] = maxOf(s["energy"] as Double, d.energyKwh)
                s["cost"] = maxOf(s["cost"] as Double, d.cost)
                s["dur"] = maxOf(s["dur"] as Int, d.durationMin)
                s["peakP"] = maxOf(s["peakP"] as Double, d.powerKw)
                s["peakA"] = maxOf(s["peakA"] as Double, d.current)
            }
        } else if (sess != null) {
            val s = sess!!
            val line = listOf(
                s["start"], dayFmt.format(Date()), s["dur"],
                String.format("%.2f", s["energy"]), String.format("%.2f", s["cost"]),
                String.format("%.1f", s["peakP"]), String.format("%.1f", s["peakA"])
            ).joinToString(",")
            writeCsv(line)
            logln("Session logged: ${s["energy"]} kWh")
            sess = null
        }
    }

    private fun writeCsv(line: String) {
        try {
            val f = File(getExternalFilesDir(null), "invt_sessions.csv")
            if (!f.exists()) f.writeText("start,end,duration_min,energy_kwh,cost,peak_kw,peak_a\n")
            f.appendText("$line\n")
        } catch (e: Exception) { logln("CSV error: ${e.message}") }
    }

    // ---- UI helpers ----
    private fun showStatus(d: InvtProtocol.Status) = runOnUiThread {
        stateBadge.text = "  ${d.gunStatusDesc}  "
        stateBadge.setBackgroundColor(
            when (d.gunStatus) {
                2 -> 0xFF1D9E75.toInt(); 4 -> 0xFFE24B4A.toInt()
                0 -> 0xFF888780.toInt(); else -> 0xFF378ADD.toInt()
            }
        )
        stateBadge.setTextColor(0xFF0B0D10.toInt())
        grid.text = buildString {
            appendLine("Power     : ${"%.1f".format(d.powerKw)} kW")
            appendLine("Energy    : ${"%.2f".format(d.energyKwh)} kWh")
            appendLine("Voltage   : ${"%.1f".format(d.voltage)} V")
            appendLine("Current   : ${"%.1f".format(d.current)} A")
            appendLine("Cost      : ${"%.2f".format(d.cost)}")
            appendLine("Duration  : ${d.durationMin} min")
            appendLine("Gun       : ${d.gunOrder}   Fault: ${d.faultCode}")
            appendLine("CP/CC     : ${"%.1f".format(d.cpCcV)} V")
        }
        setStatus("Connected · ${fmt.format(Date())}")
    }

    private fun setStatus(s: String) = runOnUiThread { statusText.text = s }
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    private fun logln(s: String) = runOnUiThread {
        log.text = "${fmt.format(Date())}  $s\n${log.text}".take(2000)
    }

    override fun onDestroy() {
        super.onDestroy(); disconnect()
    }
}
