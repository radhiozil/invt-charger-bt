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
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * DEBUG build: connects to the INVT EVC16 charger over BLE, enables notify (0xC305)
 * and indicate (0xC306), polls the status query to 0xC303, and LOGS EVERY RAW
 * notification as hex so we can see exactly what the charger sends back.
 */
class MainActivity : ComponentActivity() {

    companion object {
        val SVC: UUID = uuid16(0xA002)
        val WRITE_NR: UUID = uuid16(0xC303)   // write-no-response (elicited a reply in nRF)
        val WRITE_WR: UUID = uuid16(0xC302)   // write-with-response
        val NOTIFY_CHAR: UUID = uuid16(0xC305)
        val INDICATE_CHAR: UUID = uuid16(0xC306)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val POLL_MS = 3000L
        const val NAME_PREFIX = "3UC"

        fun uuid16(v: Int): UUID =
            UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))
    }

    private lateinit var btAdapter: BluetoothAdapter
    private var gatt: BluetoothGatt? = null
    private var writeNr: BluetoothGattCharacteristic? = null
    private var writeWr: BluetoothGattCharacteristic? = null
    private val reasm = InvtProtocol.Reassembler()
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private var pollCount = 0

    private var statusText: TextView? = null
    private var stateBadge: TextView? = null
    private var grid: TextView? = null
    private var log: TextView? = null
    private var connectBtn: Button? = null
    private var ssidField: EditText? = null
    private var pwdField: EditText? = null

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
            text = "INVT EV Charger — BT (debug)"
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
        val startBtn = Button(this).apply { text = "Start"; setOnClickListener { sendCmd(true) } }
        val stopBtn = Button(this).apply { text = "Stop"; setOnClickListener { sendCmd(false) } }
        val probeBtn = Button(this).apply { text = "Probe"; setOnClickListener { probe() } }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(startBtn, lp()); addView(stopBtn, lp()); addView(probeBtn, lp())
        }
        // --- Set WiFi section ---
        val wifiLabel = TextView(this).apply { text = "Set charger's home WiFi:"; textSize = 13f; setPadding(0, pad, 0, 4) }
        ssidField = EditText(this).apply { hint = "WiFi name (SSID)" }
        pwdField = EditText(this).apply { hint = "WiFi password" }
        val setWifiBtn = Button(this).apply { text = "Set WiFi"; setOnClickListener { setWifi() } }
        val stationBtn = Button(this).apply { text = "Use WiFi mode"; setOnClickListener { setStationMode() } }
        val saveBtn = Button(this).apply { text = "Save"; setOnClickListener { saveFlash() } }
        val wifiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(setWifiBtn, lp()); addView(stationBtn, lp()); addView(saveBtn, lp())
        }

        log = TextView(this).apply { text = ""; textSize = 12f; setPadding(0, pad, 0, 0) }

        col.addView(title); col.addView(statusText); col.addView(connectBtn)
        col.addView(stateBadge); col.addView(grid); col.addView(btnRow)
        col.addView(wifiLabel); col.addView(ssidField); col.addView(pwdField); col.addView(wifiRow)
        col.addView(log)
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
        runOnUiThread { connectBtn?.text = "Connect" }
    }

    private fun hex(b: ByteArray): String = b.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }

    private val gattCb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setStatus("Discovering…"); g.requestMtu(185)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                setStatus("Disconnected")
                runOnUiThread { connectBtn?.text = "Connect" }
                main.removeCallbacks(pollRunnable)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            logln("MTU=$mtu"); g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SVC)
            if (svc == null) { setStatus("Service 0xA002 not found"); return }
            writeNr = svc.getCharacteristic(WRITE_NR)
            writeWr = svc.getCharacteristic(WRITE_WR)
            val notify = svc.getCharacteristic(NOTIFY_CHAR)
            if (notify != null) {
                g.setCharacteristicNotification(notify, true)
                val d = notify.getDescriptor(CCCD)
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)   // chain continues in onDescriptorWrite
            } else {
                setStatus("Notify char missing")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            val parent = d.characteristic.uuid
            if (parent == NOTIFY_CHAR) {
                logln("Notify on (status=$status)")
                // now enable indicate on 0xC306
                val ind = g.getService(SVC)?.getCharacteristic(INDICATE_CHAR)
                if (ind != null) {
                    g.setCharacteristicNotification(ind, true)
                    val id = ind.getDescriptor(CCCD)
                    id.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    g.writeDescriptor(id)
                } else {
                    ready()
                }
            } else if (parent == INDICATE_CHAR) {
                logln("Indicate on (status=$status)")
                ready()
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            handleRx(c.uuid, c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleRx(c.uuid, value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            // uncomment for very verbose: logln("TX ok ${short(c.uuid)} st=$status")
        }
    }

    private fun short(u: UUID): String = u.toString().substring(4, 8).uppercase()

    private fun ready() {
        setStatus("Connected")
        runOnUiThread { connectBtn?.text = "Disconnect" }
        logln("Ready — tap PROBE to sweep commands")
        // No auto-poll: we first want to see if there are unsolicited heartbeats.
    }

    private fun handleRx(uuid: UUID, bytes: ByteArray) {
        logln("RX ${short(uuid)}: ${hex(bytes)}")
        for ((cmd, payload) in reasm.feed(bytes)) {
            when (cmd) {
                InvtProtocol.CMD_READ_STATUS -> {
                    val d = InvtProtocol.parseStatus(payload)
                    if (d != null) showStatus(d)
                }
                0x10, 0x11, 0x31, 0x53 -> {
                    val ok = payload.isNotEmpty() && payload[0] == 0
                    logln(">>> ACK cmd=0x${cmd.toString(16)}: ${if (ok) "SUCCESS" else "fail/${payload.joinToString(",")}"}")
                }
                else -> logln("Frame cmd=0x${cmd.toString(16)} len=${payload.size}")
            }
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            writeTo(WRITE_NR, InvtProtocol.statusQuery())
            pollCount++
            main.postDelayed(this, POLL_MS)
        }
    }

    private fun xor(b: IntArray): Int { var x = 0; for (v in b) x = x xor (v and 0xFF); return x and 0xFF }
    private fun q6(cmd: Int): ByteArray {
        val f = intArrayOf(0x55, 0x06, 0x00, cmd); val x = xor(f)
        return (f + intArrayOf(x, 0xAA)).map { it.toByte() }.toByteArray()
    }
    private fun q7(cmd: Int): ByteArray {   // query with one 0x00 data byte (status style)
        val f = intArrayOf(0x55, 0x07, 0x00, cmd, 0x00); val x = xor(f)
        return (f + intArrayOf(x, 0xAA)).map { it.toByte() }.toByteArray()
    }

    /** Sweep every read command code one at a time so we can see which returns data. */
    private fun probe() {
        val cmds = listOf(0x01, 0x02, 0x04, 0x05, 0x06, 0x08, 0x60, 0x61)
        var delay = 0L
        for (c in cmds) {
            val f6 = q6(c)
            main.postDelayed({ logln("TX cmd=0x${c.toString(16)}"); writeTo(WRITE_NR, f6) }, delay)
            delay += 900
        }
        // status-style (len 7) for 0x61 as well
        val s = q7(0x61)
        main.postDelayed({ logln("TX cmd=0x61 (len7)"); writeTo(WRITE_NR, s) }, delay)
        toast("Sweeping commands — watch the log")
    }

    /** W_HOTSPOT_DATA (0x10): tell the charger which WiFi (SSID+password) to join. */
    private fun setWifi() {
        val ssid = ssidField?.text?.toString() ?: ""
        val pwd = pwdField?.text?.toString() ?: ""
        if (ssid.isEmpty()) { toast("Enter WiFi name"); return }
        val f = IntArray(70)
        f[0] = 0x55; f[1] = 0x46; f[2] = 0x00; f[3] = 0x10   // len=70, cmd=0x10
        for (n in 0 until 32) f[4 + n] = if (n < ssid.length) ssid[n].code and 0xFF else 0
        for (n in 0 until 32) f[36 + n] = if (n < pwd.length) pwd[n].code and 0xFF else 0
        f[68] = xor(f.copyOfRange(0, 68)); f[69] = 0xAA
        logln("TX Set WiFi: SSID='$ssid'")
        writeTo(WRITE_WR, f.map { it.toByte() }.toByteArray())
    }

    /** W_OFFLINE_CHARGE_FUN (0x31) = 0 → charger joins the configured WiFi (station mode). */
    private fun setStationMode() {
        val f = intArrayOf(0x55, 0x07, 0x00, 0x31, 0x00); val x = xor(f)
        val frame = (f + intArrayOf(x, 0xAA)).map { it.toByte() }.toByteArray()
        logln("TX Use-WiFi mode (offline-charge=0)")
        writeTo(WRITE_WR, frame)
    }

    /** W_PARAM_TO_FLASH (0x53): persist settings. */
    private fun saveFlash() {
        val frame = q6(0x53)
        logln("TX Save to flash")
        writeTo(WRITE_WR, frame)
    }

    @SuppressLint("MissingPermission")
    private fun writeTo(which: UUID, data: ByteArray) {
        val g = gatt ?: return
        val wc = g.getService(SVC)?.getCharacteristic(which) ?: return
        val type = if (which == WRITE_WR) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                   else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(wc, data, type)
        } else {
            @Suppress("DEPRECATION") run { wc.writeType = type; wc.value = data; g.writeCharacteristic(wc) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendCmd(start: Boolean) = writeTo(WRITE_NR, InvtProtocol.startStop(start))

    private fun showStatus(d: InvtProtocol.Status) = runOnUiThread {
        stateBadge?.text = "  ${d.gunStatusDesc}  "
        stateBadge?.setBackgroundColor(
            when (d.gunStatus) {
                2 -> 0xFF1D9E75.toInt(); 4 -> 0xFFE24B4A.toInt()
                0 -> 0xFF888780.toInt(); else -> 0xFF378ADD.toInt()
            }
        )
        stateBadge?.setTextColor(0xFF0B0D10.toInt())
        grid?.text = buildString {
            appendLine("Power     : ${"%.1f".format(d.powerKw)} kW")
            appendLine("Energy    : ${"%.2f".format(d.energyKwh)} kWh")
            appendLine("Voltage   : ${"%.1f".format(d.voltage)} V")
            appendLine("Current   : ${"%.1f".format(d.current)} A")
            appendLine("Cost      : ${"%.2f".format(d.cost)}")
            appendLine("Duration  : ${d.durationMin} min")
            appendLine("Gun ${d.gunOrder}  Fault ${d.faultCode}  CP/CC ${"%.1f".format(d.cpCcV)}V")
        }
        logln("DECODED ${d.gunStatusDesc} ${d.voltage}V ${d.current}A ${d.powerKw}kW ${d.energyKwh}kWh")
    }

    private fun setStatus(s: String) = runOnUiThread { statusText?.text = s }
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    private fun logln(s: String) = runOnUiThread {
        log?.text = "${fmt.format(Date())}  $s\n${log?.text}".take(4000)
    }

    override fun onDestroy() {
        super.onDestroy(); disconnect()
    }
}
