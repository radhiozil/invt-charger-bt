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
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

/**
 * INVT charger — reads live kWh over Bluetooth using the INVT Charge app's own
 * protocol: handshake (cmd 0) then poll status (cmd 1) on characteristic 0xC304,
 * notifications on 0xC305. Shows lifetime total kWh and this-session kWh.
 */
class MainActivity : ComponentActivity() {

    companion object {
        val SVC: UUID = uuid16(0xA002)
        val WRITE_CHAR: UUID = uuid16(0xC304)
        val NOTIFY_CHAR: UUID = uuid16(0xC305)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val POLL_MS = 3000L
        const val NAME_PREFIX = "3UC"
        fun uuid16(v: Int): UUID =
            UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))
    }

    private lateinit var btAdapter: BluetoothAdapter
    private var gatt: BluetoothGatt? = null
    private val reasm = InvtProtocol.Reassembler()
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var statusText: TextView? = null
    private var badge: TextView? = null
    private var lifeKwh: TextView? = null
    private var sessKwh: TextView? = null
    private var grid: TextView? = null
    private var log: TextView? = null
    private var connectBtn: Button? = null

    private val needPerms: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = mgr.adapter
        setContentView(buildUi())
        requestPermsThen {}
    }

    private fun buildUi(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val title = TextView(this).apply { text = "INVT Charger — kWh"; textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        statusText = TextView(this).apply { text = "Not connected"; textSize = 14f; setPadding(0, pad / 2, 0, pad / 2) }
        connectBtn = Button(this).apply { text = "Connect"; setOnClickListener { if (gatt == null) startScan() else disconnect() } }
        badge = TextView(this).apply { text = "—"; textSize = 16f; setPadding(pad / 2, pad / 3, pad / 2, pad / 3); setTypeface(typeface, android.graphics.Typeface.BOLD) }

        val lifeLbl = TextView(this).apply { text = "LIFETIME TOTAL"; textSize = 11f; setPadding(0, pad, 0, 0) }
        lifeKwh = TextView(this).apply { text = "—  kWh"; textSize = 30f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        val sessLbl = TextView(this).apply { text = "THIS SESSION"; textSize = 11f; setPadding(0, pad / 2, 0, 0) }
        sessKwh = TextView(this).apply { text = "—  kWh"; textSize = 24f; setTypeface(typeface, android.graphics.Typeface.BOLD) }

        grid = TextView(this).apply { text = ""; textSize = 15f; setPadding(0, pad, 0, 0); typeface = android.graphics.Typeface.MONOSPACE }
        val startBtn = Button(this).apply { text = "Start"; setOnClickListener { send(InvtProtocol.startStop(true)); toast("Start sent") } }
        val stopBtn = Button(this).apply { text = "Stop"; setOnClickListener { send(InvtProtocol.startStop(false)); toast("Stop sent") } }
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(startBtn, lp()); addView(stopBtn, lp()) }
        log = TextView(this).apply { text = ""; textSize = 11f; setPadding(0, pad, 0, 0) }

        col.addView(title); col.addView(statusText); col.addView(connectBtn); col.addView(badge)
        col.addView(lifeLbl); col.addView(lifeKwh); col.addView(sessLbl); col.addView(sessKwh)
        col.addView(grid); col.addView(btnRow); col.addView(log)
        root.addView(col); return root
    }

    private fun lp() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun requestPermsThen(after: () -> Unit) {
        val missing = needPerms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) after() else ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }
    override fun onRequestPermissionsResult(rc: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        logln(if (r.all { it == PackageManager.PERMISSION_GRANTED }) "Permissions granted" else "Permissions denied")
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        requestPermsThen {
            if (!btAdapter.isEnabled) { toast("Turn on Bluetooth"); return@requestPermsThen }
            setStatus("Scanning…")
            val scanner = btAdapter.bluetoothLeScanner
            val cb = object : ScanCallback() {
                override fun onScanResult(type: Int, result: ScanResult) {
                    val name = try { result.device.name } catch (e: SecurityException) { null } ?: return
                    if (name.startsWith(NAME_PREFIX)) { scanner.stopScan(this); logln("Found $name"); connect(result.device) }
                }
                override fun onScanFailed(code: Int) { setStatus("Scan failed ($code)") }
            }
            scanner.startScan(cb)
            main.postDelayed({ try { scanner.stopScan(cb) } catch (_: Exception) {} }, 15000)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(dev: BluetoothDevice) { setStatus("Connecting…"); gatt = dev.connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE) }

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        main.removeCallbacks(poll)
        gatt?.disconnect(); gatt?.close(); gatt = null
        setStatus("Not connected"); runOnUiThread { connectBtn?.text = "Connect" }
    }

    private fun hex(b: ByteArray) = b.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }

    private val cb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) { setStatus("Discovering…"); g.requestMtu(185) }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                setStatus("Disconnected"); runOnUiThread { connectBtn?.text = "Connect" }; main.removeCallbacks(poll)
            }
        }
        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { logln("MTU=$mtu"); g.discoverServices() }
        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SVC) ?: run { setStatus("Service 0xA002 not found"); return }
            val notify = svc.getCharacteristic(NOTIFY_CHAR) ?: run { setStatus("Notify char missing"); return }
            g.setCharacteristicNotification(notify, true)
            val d = notify.getDescriptor(CCCD); d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            setStatus("Handshaking…"); runOnUiThread { connectBtn?.text = "Disconnect" }
            logln("Notify on — sending handshake")
            send(InvtProtocol.handshake())
            main.postDelayed({ main.post(poll) }, 700)   // begin polling after handshake
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { rx(c.value ?: return) }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { rx(value) }
    }

    private fun rx(bytes: ByteArray) {
        logln("RX ${hex(bytes)}")
        for ((cmd, payload) in reasm.feed(bytes)) {
            when (cmd) {
                InvtProtocol.CMD_HANDSHAKE -> { setStatus("Connected"); logln("Handshake OK") }
                InvtProtocol.CMD_STATUS -> InvtProtocol.parseStatus(payload)?.let { show(it) }
                else -> logln("cmd=$cmd len=${payload.size}")
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() { send(InvtProtocol.statusQuery()); main.postDelayed(this, POLL_MS) }
    }

    @SuppressLint("MissingPermission")
    private fun send(data: ByteArray) {
        val g = gatt ?: return
        val wc = g.getService(SVC)?.getCharacteristic(WRITE_CHAR) ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(wc, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION") run { wc.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; wc.value = data; g.writeCharacteristic(wc) }
        }
    }

    private fun show(d: InvtProtocol.Status) = runOnUiThread {
        badge?.text = "  ${d.statusDesc}  "
        badge?.setBackgroundColor(when (d.chargingStatus) { 2 -> 0xFF1D9E75.toInt(); 4 -> 0xFFE24B4A.toInt(); 0 -> 0xFF888780.toInt(); else -> 0xFF378ADD.toInt() })
        badge?.setTextColor(0xFF0B0D10.toInt())
        lifeKwh?.text = "${"%.2f".format(d.lifetimeKwh)}  kWh"
        sessKwh?.text = "${"%.2f".format(d.sessionKwh)}  kWh"
        grid?.text = buildString {
            appendLine("Voltage : ${"%.1f".format(d.voltage)} V")
            appendLine("Current : ${"%.1f".format(d.current)} A")
            appendLine("Power   : ${"%.1f".format(d.powerKw)} kW")
            appendLine("Duration: ${d.durationMin} min")
            appendLine("Cost    : ${"%.2f".format(d.cost)}")
            appendLine("Fault   : ${d.fault}")
        }
        setStatus("Connected · ${fmt.format(Date())}")
    }

    private fun setStatus(s: String) = runOnUiThread { statusText?.text = s }
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    private fun logln(s: String) = runOnUiThread { log?.text = "${fmt.format(Date())}  $s\n${log?.text}".take(2500) }

    override fun onDestroy() { super.onDestroy(); disconnect() }
}
