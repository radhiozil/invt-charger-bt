package com.radhi.invtcharger

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * INVT charger — reads live kWh over Bluetooth with the INVT Charge app's own
 * protocol, auto-logs completed charging sessions, and pulls the charger's own
 * stored charge records (history) over BLE.
 */
class MainActivity : ComponentActivity() {

    companion object {
        val SVC: UUID = uuid16(0xA002)
        val WRITE_CHAR: UUID = uuid16(0xC304)
        val NOTIFY_CHAR: UUID = uuid16(0xC305)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val POLL_MS = 3000L
        const val NAME_PREFIX = "3UC"
        const val PREFS = "invt_log"
        const val KEY_RECORDS = "records"
        fun uuid16(v: Int): UUID =
            UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))
    }

    // ---- palette (ABB ChargerSync-style: light, clean, ABB red accent) ----
    private val cBg = 0xFFF4F4F6.toInt()         // page background (near-white)
    private val cCard = 0xFFFFFFFF.toInt()       // cards
    private val cCard2 = 0xFFECECEF.toInt()      // tiles / tracks / secondary surfaces
    private val cBorder = 0xFFE2E2E6.toInt()     // hairline card border
    private val cFg = 0xFF1A1A1A.toInt()         // near-black text
    private val cMuted = 0xFF70707A.toInt()      // secondary text
    private val cAccent = 0xFFFF000F.toInt()     // ABB red
    private val cGreen = 0xFF14A44D.toInt()      // charging (success)
    private val cBlue = 0xFF2B6CB0.toInt()       // finished / selected
    private val cRed = 0xFFFF000F.toInt()        // fault (ABB red)
    private val cAmber = 0xFFB9770A.toInt()      // scanning / handshaking

    // ---- fonts (Inter) ----
    private var fLight: Typeface? = null
    private var fReg: Typeface? = null
    private var fMed: Typeface? = null
    private var fSemi: Typeface? = null

    private lateinit var btAdapter: BluetoothAdapter
    private var gatt: BluetoothGatt? = null
    private val reasm = InvtProtocol.Reassembler()
    private val main = Handler(Looper.getMainLooper())
    private val tFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dFmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US)
    private var dens = 1f

    // views
    private var chip: TextView? = null
    private var connectBtn: TextView? = null
    private var liveTabBtn: TextView? = null
    private var histTabBtn: TextView? = null
    private var livePane: LinearLayout? = null
    private var histPane: LinearLayout? = null
    private var badge: TextView? = null
    private var lifeKwh: TextView? = null
    private var sessKwh: TextView? = null
    private var vV: TextView? = null; private var vA: TextView? = null
    private var vKw: TextView? = null; private var vDur: TextView? = null
    private var vCost: TextView? = null; private var vFault: TextView? = null
    private var updated: TextView? = null
    private var histList: LinearLayout? = null
    private var histStatus: TextView? = null
    private var chartMode = 1   // 0=week, 1=month, 2=year
    private var selectedBucketKey: Long? = null   // tapped bar -> filter the list to this bucket
    private var chart: BarChartView? = null
    private var chartTotal: TextView? = null
    private var wkBtn: TextView? = null; private var moBtn: TextView? = null; private var yrBtn: TextView? = null
    private var startBtn: TextView? = null
    private var stopBtn: TextView? = null

    // records pull state
    private var pendingIdx: Int = -1
    private var pendingTotal: Int = 0
    private var pulling = false
    private var lastChargingStatus = -1
    private var ratePerKwh = 0.0   // RM per kWh; 0 = use the charger's own cost figure

    private val needPerms: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        dens = resources.displayMetrics.density
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = mgr.adapter
        ratePerKwh = loadRate()
        fLight = ResourcesCompat.getFont(this, R.font.inter_light)
        fReg = ResourcesCompat.getFont(this, R.font.inter_regular)
        fMed = ResourcesCompat.getFont(this, R.font.inter_medium)
        fSemi = ResourcesCompat.getFont(this, R.font.inter_semibold)
        setContentView(buildUi())
        renderHistory()
        requestPermsThen {}
    }

    // ---------- small view helpers ----------
    private fun dp(v: Int) = (v * dens).toInt()

    private fun card(bg: Int = cCard, radius: Int = 18): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.background = GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat(); setColor(bg)
            setStroke(dp(1).coerceAtLeast(1), cBorder)
        }
        l.setPadding(dp(16), dp(14), dp(16), dp(14))
        return l
    }

    private fun lbl(t: String, color: Int = cMuted, size: Float = 11f, caps: Boolean = true) =
        TextView(this).apply {
            text = if (caps) t.uppercase() else t
            setTextColor(color); textSize = size
            letterSpacing = if (caps) 0.08f else 0f
            typeface = fMed ?: typeface
        }

    private fun value(t: String, color: Int = cFg, size: Float = 22f) =
        TextView(this).apply { text = t; setTextColor(color); textSize = size; typeface = fSemi ?: typeface }

    private fun pill(text: String, bg: Int, fg: Int): TextView = TextView(this).apply {
        this.text = text; setTextColor(fg); textSize = 13f; typeface = fMed ?: typeface
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(9), dp(16), dp(9))
        background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(bg) }
        isClickable = true; isFocusable = true
    }

    private fun mv(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(0, dp(top), 0, dp(bottom)) }

    // ---------- UI ----------
    private fun buildUi(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(cBg); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }

        // header row
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.invt_logo)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
            contentDescription = "INVT"
        }
        titleCol.addView(logo, LinearLayout.LayoutParams(WRAP_CONTENT, dp(26)))
        chip = TextView(this).apply {
            text = "● Not connected"; setTextColor(cMuted); textSize = 12.5f; setPadding(0, dp(2), 0, 0)
        }
        titleCol.addView(chip)
        header.addView(titleCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val gear = pill("⚙", cCard2, cFg).apply { setOnClickListener { showRateDialog() } }
        header.addView(gear, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) })
        connectBtn = pill("Connect", cAccent, cBg).apply { setOnClickListener { if (gatt == null) startScan() else disconnect() } }
        header.addView(connectBtn)
        root.addView(header, mv(0, 18))

        // tab switch
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(cCard2) }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        liveTabBtn = tab("Live") { showTab(true) }
        histTabBtn = tab("History") { showTab(false); if (gatt != null) pullRecords() }
        tabBar.addView(liveTabBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        tabBar.addView(histTabBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(tabBar, mv(0, 16))

        // ---- LIVE pane ----
        livePane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val statusCard = card()
        badge = TextView(this).apply {
            text = "—"; setTextColor(cBg); textSize = 15f; typeface = fMed ?: typeface
            gravity = Gravity.CENTER; setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(cMuted) }
        }
        statusCard.addView(badge, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        updated = TextView(this).apply { text = "Waiting for data…"; setTextColor(cMuted); textSize = 12f; setPadding(0, dp(10), 0, 0) }
        statusCard.addView(updated)
        livePane!!.addView(statusCard, mv(0, 12))

        // lifetime hero card
        val lifeCard = card(cCard)
        lifeCard.addView(lbl("Lifetime total (meter)"))
        lifeKwh = value("—", cAccent, 40f).apply { setPadding(0, dp(4), 0, 0) }
        lifeCard.addView(lifeKwh)
        lifeCard.addView(lbl("kWh", cMuted, 13f, false))
        livePane!!.addView(lifeCard, mv(0, 12))

        // session card
        val sessCard = card(cCard)
        sessCard.addView(lbl("This session"))
        sessKwh = value("—", cBlue, 30f).apply { setPadding(0, dp(4), 0, 0) }
        sessCard.addView(sessKwh)
        sessCard.addView(lbl("kWh", cMuted, 13f, false))
        livePane!!.addView(sessCard, mv(0, 12))

        // stats grid 2x3
        val g1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        vV = statTile(g1, "Voltage", "— V").also { }
        vA = statTile(g1, "Current", "— A")
        vKw = statTile(g1, "Power", "— kW")
        livePane!!.addView(g1, mv(0, 10))
        val g2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        vDur = statTile(g2, "Duration", "— min")
        vCost = statTile(g2, "Cost", "—")
        vFault = statTile(g2, "Fault", "0")
        livePane!!.addView(g2, mv(0, 14))

        // control buttons
        val ctl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startBtn = pill("▶  Start", cAccent, cBg).apply { setOnClickListener { send(InvtProtocol.startStop(true)); toast("Start sent") } }
        stopBtn = pill("■  Stop", cCard2, cFg).apply { setOnClickListener { send(InvtProtocol.startStop(false)); toast("Stop sent") } }
        ctl.addView(startBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        ctl.addView(stopBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        livePane!!.addView(ctl)

        root.addView(livePane)

        // ---- HISTORY pane ----
        histPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val histHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        histStatus = TextView(this).apply { text = "Charging records"; setTextColor(cMuted); textSize = 12.5f }
        histHead.addView(histStatus, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        histHead.addView(pill("↻ Refresh", cCard2, cFg).apply {
            setOnClickListener { if (gatt != null) pullRecords() else toast("Connect first") }
        })
        histPane!!.addView(histHead, mv(0, 12))

        // chart card
        val chartCard = card(cCard)
        val segBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(cCard2) }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        wkBtn = seg("Weekly") { chartMode = 0; selectedBucketKey = null; renderHistory() }
        moBtn = seg("Monthly") { chartMode = 1; selectedBucketKey = null; renderHistory() }
        yrBtn = seg("Yearly") { chartMode = 2; selectedBucketKey = null; renderHistory() }
        segBar.addView(wkBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        segBar.addView(moBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        segBar.addView(yrBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        chartCard.addView(segBar, mv(0, 12))
        chartTotal = value("—", cAccent, 22f)
        chartCard.addView(chartTotal)
        chartCard.addView(lbl("kWh in view", cMuted, 10f))
        chart = BarChartView(this).apply {
            onBarTap = { key -> selectedBucketKey = if (selectedBucketKey == key) null else key; renderHistory() }
        }
        chartCard.addView(chart, LinearLayout.LayoutParams(MATCH_PARENT, dp(170)).apply { topMargin = dp(10) })
        chartCard.addView(lbl("Tap a bar to see that period's sessions", cMuted, 10f).apply { setPadding(0, dp(8), 0, 0) })
        histPane!!.addView(chartCard, mv(0, 14))

        histList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        histPane!!.addView(histList)
        root.addView(histPane)

        scroll.addView(root)
        return scroll
    }

    private fun tab(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text; textSize = 14f; gravity = Gravity.CENTER
        typeface = fMed ?: typeface
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnClickListener { onClick() }
    }

    private fun seg(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text; textSize = 12.5f; gravity = Gravity.CENTER
        typeface = fMed ?: typeface
        setPadding(dp(6), dp(8), dp(6), dp(8))
        setOnClickListener { onClick() }
    }

    private fun statTile(row: LinearLayout, label: String, initial: String): TextView {
        val tile = card(cCard2, 14)
        tile.setPadding(dp(12), dp(12), dp(12), dp(12))
        tile.addView(lbl(label, cMuted, 10f))
        val v = value(initial, cFg, 16f).apply { setPadding(0, dp(3), 0, 0) }
        tile.addView(v)
        row.addView(tile, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(if (row.childCount == 0) 0 else dp(5), 0, dp(5), 0) })
        return v
    }

    private fun showTab(live: Boolean) {
        livePane?.visibility = if (live) View.VISIBLE else View.GONE
        histPane?.visibility = if (live) View.GONE else View.VISIBLE
        liveTabBtn?.setTextColor(if (live) cFg else cMuted)
        histTabBtn?.setTextColor(if (live) cMuted else cFg)
        liveTabBtn?.background = if (live) GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(cCard) } else null
        histTabBtn?.background = if (!live) GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(cCard) } else null
    }

    // ---------- permissions ----------
    private fun requestPermsThen(after: () -> Unit) {
        val missing = needPerms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) after() else ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }
    override fun onRequestPermissionsResult(rc: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
    }

    // ---------- BLE ----------
    @SuppressLint("MissingPermission")
    private fun startScan() {
        requestPermsThen {
            if (!btAdapter.isEnabled) { toast("Turn on Bluetooth"); return@requestPermsThen }
            setChip("Scanning…", cAmber)
            val scanner = btAdapter.bluetoothLeScanner
            val cb = object : ScanCallback() {
                override fun onScanResult(type: Int, result: ScanResult) {
                    val name = try { result.device.name } catch (e: SecurityException) { null } ?: return
                    if (name.startsWith(NAME_PREFIX)) { scanner.stopScan(this); connect(result.device) }
                }
                override fun onScanFailed(code: Int) { setChip("Scan failed ($code)", cRed) }
            }
            scanner.startScan(cb)
            main.postDelayed({ try { scanner.stopScan(cb) } catch (_: Exception) {} }, 15000)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(dev: BluetoothDevice) { setChip("Connecting…", cAmber); gatt = dev.connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE) }

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        main.removeCallbacks(poll)
        gatt?.disconnect(); gatt?.close(); gatt = null
        pulling = false
        setChip("● Not connected", cMuted)
        runOnUiThread { connectBtn?.text = "Connect"; connectBtn?.setTextColor(cBg); connectBtn?.background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(cAccent) } }
    }

    private val cb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) { setChip("Discovering…", cAmber); g.requestMtu(185) }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                setChip("● Disconnected", cRed); main.removeCallbacks(poll)
                runOnUiThread { connectBtn?.text = "Connect" }
            }
        }
        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }
        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SVC) ?: run { setChip("Service 0xA002 missing", cRed); return }
            val notify = svc.getCharacteristic(NOTIFY_CHAR) ?: run { setChip("Notify char missing", cRed); return }
            g.setCharacteristicNotification(notify, true)
            val d = notify.getDescriptor(CCCD); d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            setChip("Handshaking…", cAmber)
            runOnUiThread {
                connectBtn?.text = "Disconnect"
                connectBtn?.background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(cCard2) }
                connectBtn?.setTextColor(cFg)
            }
            send(InvtProtocol.handshake())
            main.postDelayed({ main.post(poll) }, 700)
            // auto-pull the charger's stored records on connect (background)
            main.postDelayed({ pullRecords() }, 1800)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { rx(c.value ?: return) }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { rx(value) }
    }

    private fun rx(bytes: ByteArray) {
        for ((cmd, payload) in reasm.feed(bytes)) {
            when (cmd) {
                InvtProtocol.CMD_HANDSHAKE -> setChip("● Connected", cAccent)
                InvtProtocol.CMD_STATUS -> InvtProtocol.parseStatus(payload)?.let { setChip("● Connected", cAccent); onStatus(it) }
                InvtProtocol.CMD_RECORDS_COUNT -> InvtProtocol.parseRecordsCount(payload)?.let { onRecordsCount(it) }
                InvtProtocol.CMD_RECORDS_DATA -> InvtProtocol.parseRecord(payload)?.let { onRecord(it) }
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

    // ---------- live status + auto-log ----------
    private fun onStatus(d: InvtProtocol.Status) = runOnUiThread {
        badge?.text = "  ${d.statusDesc}  "
        val bc = when (d.chargingStatus) { 2 -> cGreen; 4 -> cRed; 0 -> cMuted; 3 -> cBlue; else -> cBlue }
        badge?.background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(bc) }
        lifeKwh?.text = "%.2f".format(d.lifetimeKwh)
        sessKwh?.text = "%.2f".format(d.sessionKwh)
        vV?.text = "%.1f V".format(d.voltage)
        vA?.text = "%.1f A".format(d.current)
        vKw?.text = "%.1f kW".format(d.powerKw)
        vDur?.text = "${d.durationMin} min"
        vCost?.text = fmtMoney(costOf(d.sessionKwh, d.cost))
        vFault?.text = d.fault.toString()
        vFault?.setTextColor(if (d.fault != 0) cRed else cFg)
        updated?.text = "Updated ${tFmt.format(Date())}"

        // auto-log: a session that just finished (charging -> finished) with real energy
        if (lastChargingStatus == 2 && d.chargingStatus == 3 && d.sessionKwh > 0.0) {
            val rec = InvtProtocol.Record(
                index = -1, gun = "A",
                startEpochSec = (System.currentTimeMillis() / 1000L) - d.durationMin * 60L,
                totalKwh = d.sessionKwh, electricityFee = d.cost, serviceFee = 0.0,
                durationMin = d.durationMin, stopReason = 0, fault = d.fault
            )
            if (mergeRecords(listOf(rec))) { renderHistory(); toast("Session logged: %.2f kWh".format(d.sessionKwh)) }
        }
        lastChargingStatus = d.chargingStatus
    }

    // ---------- pull stored records from the charger ----------
    private fun pullRecords() {
        if (pulling || gatt == null) return
        pulling = true
        runOnUiThread { histStatus?.text = "Reading records from charger…" }
        send(InvtProtocol.recordsCountQuery())
        main.postDelayed({ if (pulling && pendingTotal == 0) { pulling = false; runOnUiThread { histStatus?.text = "No response — tap Refresh" } } }, 5000)
    }

    private fun onRecordsCount(count: Int) {
        pendingTotal = count
        if (count <= 0) { pulling = false; runOnUiThread { histStatus?.text = "Charger reports 0 stored records" }; return }
        pendingIdx = 0
        requestNextRecord()
    }

    private fun requestNextRecord() {
        if (pendingIdx < 0 || pendingIdx >= pendingTotal) {
            pulling = false
            runOnUiThread { histStatus?.text = "Charging records · synced ${tFmt.format(Date())}" }
            return
        }
        val sent = pendingIdx
        send(InvtProtocol.recordsDataQuery(sent))
        // safety: if this record's reply is lost, advance — but only if nothing else moved us on
        main.postDelayed({ if (pulling && pendingIdx == sent) { pendingIdx++; requestNextRecord() } }, 1500)
    }

    private fun onRecord(r: InvtProtocol.Record) {
        mergeRecords(listOf(r))
        renderHistory()
        if (pulling) { pendingIdx++; requestNextRecord() }   // advances past the pending timeout's guard
    }

    // ---------- storage (SharedPreferences JSON, dedup by start time) ----------
    private fun loadRecords(): MutableList<InvtProtocol.Record> {
        val out = ArrayList<InvtProtocol.Record>()
        try {
            val raw = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECORDS, "[]") ?: "[]"
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(InvtProtocol.Record(
                    index = o.optInt("idx", -1), gun = o.optString("gun", "A"),
                    startEpochSec = o.optLong("start"), totalKwh = o.optDouble("kwh"),
                    electricityFee = o.optDouble("efee"), serviceFee = o.optDouble("sfee"),
                    durationMin = o.optInt("dur"), stopReason = o.optInt("stop"), fault = o.optInt("fault")
                ))
            }
        } catch (_: Exception) {}
        return out
    }

    /** Merge new records, dedup by start time; returns true if anything was added/updated. */
    private fun mergeRecords(incoming: List<InvtProtocol.Record>): Boolean {
        val existing = loadRecords()
        val byStart = LinkedHashMap<Long, InvtProtocol.Record>()
        for (r in existing) byStart[r.startEpochSec] = r
        var changed = false
        for (r in incoming) {
            if (r.startEpochSec <= 0 || r.totalKwh <= 0.0) continue
            val prev = byStart[r.startEpochSec]
            if (prev == null || prev.totalKwh != r.totalKwh) { byStart[r.startEpochSec] = r; changed = true }
        }
        if (!changed) return false
        val arr = JSONArray()
        for (r in byStart.values) {
            arr.put(JSONObject().apply {
                put("idx", r.index); put("gun", r.gun); put("start", r.startEpochSec)
                put("kwh", r.totalKwh); put("efee", r.electricityFee); put("sfee", r.serviceFee)
                put("dur", r.durationMin); put("stop", r.stopReason); put("fault", r.fault)
            })
        }
        try { getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_RECORDS, arr.toString()).apply() } catch (_: Exception) {}
        return true
    }

    private fun renderHistory(): Unit = runOnUiThread {
        val list = histList ?: return@runOnUiThread
        list.removeAllViews()
        val all = loadRecords().filter { it.startEpochSec > 0 && it.totalKwh > 0 }
        if (all.isEmpty()) {
            val empty = card(cCard)
            empty.addView(lbl("No records yet", cMuted, 13f, false))
            val hint = TextView(this).apply { text = "Connect and tap Refresh to pull the charger's stored sessions. New sessions are logged automatically."; setTextColor(cMuted); textSize = 12f; setPadding(0, dp(6), 0, 0) }
            empty.addView(hint)
            list.addView(empty); renderChart(); return@runOnUiThread
        }

        val sel = selectedBucketKey
        val shown = (if (sel == null) all else all.filter { bucketOf(chartMode, it.startEpochSec).first == sel })
            .sortedByDescending { it.startEpochSec }

        // filter chip when a bar is selected
        if (sel != null) {
            val label = all.firstOrNull { bucketOf(chartMode, it.startEpochSec).first == sel }
                ?.let { bucketOf(chartMode, it.startEpochSec).second } ?: "Selected period"
            val chipCard = card(cCard2, 14).apply { setPadding(dp(14), dp(10), dp(14), dp(10)) }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = label; setTextColor(cFg); textSize = 13.5f; typeface = fMed ?: typeface
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "✕ Clear"; setTextColor(cBlue); textSize = 13f; typeface = fMed ?: typeface
                setOnClickListener { selectedBucketKey = null; renderHistory() }
            })
            chipCard.addView(row)
            list.addView(chipCard, mv(0, 12))
        }

        // summary (reflects the shown set)
        val totalKwh = shown.sumOf { it.totalKwh }
        val sum = card(cCard)
        sum.addView(lbl("${shown.size} session${if (shown.size == 1) "" else "s"} · total energy"))
        sum.addView(value("%.2f kWh".format(totalKwh), cAccent, 26f).apply { setPadding(0, dp(3), 0, 0) })
        if (ratePerKwh > 0) sum.addView(TextView(this).apply {
            text = "≈ ${fmtMoney(totalKwh * ratePerKwh)}  ·  ${fmtMoney(ratePerKwh)}/kWh"
            setTextColor(cMuted); textSize = 12.5f; setPadding(0, dp(6), 0, 0)
        })
        list.addView(sum, mv(0, 12))

        for (r in shown) list.addView(recordCard(r), mv(0, 10))
        renderChart()
    }

    // ---------- charts ----------
    private fun renderChart() = runOnUiThread {
        // highlight the active segment
        val sel = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(cCard) }
        wkBtn?.background = if (chartMode == 0) sel else null
        moBtn?.background = if (chartMode == 1) GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(cCard) } else null
        yrBtn?.background = if (chartMode == 2) GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(cCard) } else null
        wkBtn?.setTextColor(if (chartMode == 0) cFg else cMuted)
        moBtn?.setTextColor(if (chartMode == 1) cFg else cMuted)
        yrBtn?.setTextColor(if (chartMode == 2) cFg else cMuted)

        val data = bucketData(chartMode)
        chart?.setData(data, selectedBucketKey)
        chartTotal?.text = "%.2f".format(data.sumOf { it.third })
    }

    /** Which chart bucket a session falls in: (sortable key, display label). */
    private fun bucketOf(mode: Int, epochSec: Long): Pair<Long, String> {
        val c = Calendar.getInstance()
        c.timeInMillis = epochSec * 1000L
        return when (mode) {
            0 -> {
                c.firstDayOfWeek = Calendar.MONDAY
                c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                (c.timeInMillis / 1000L) to ("Week of " + SimpleDateFormat("d MMM", Locale.US).format(c.time))
            }
            1 -> {
                val y = c.get(Calendar.YEAR); val m = c.get(Calendar.MONTH)
                (y * 12L + m) to SimpleDateFormat("MMMM yyyy", Locale.US).format(c.time)
            }
            else -> { val y = c.get(Calendar.YEAR); y.toLong() to y.toString() }
        }
    }

    /** Short x-axis label for a bucket (compact form of bucketOf's label). */
    private fun bucketTick(mode: Int, epochSec: Long): String {
        val c = Calendar.getInstance(); c.timeInMillis = epochSec * 1000L
        return when (mode) {
            0 -> SimpleDateFormat("d/M", Locale.US).format(c.time)
            1 -> SimpleDateFormat("MMM ''yy", Locale.US).format(c.time)
            else -> c.get(Calendar.YEAR).toString()
        }
    }

    /** Aggregate stored sessions into chart buckets (newest at the right): (key, tick label, kWh). */
    private fun bucketData(mode: Int): List<Triple<Long, String, Double>> {
        val recs = loadRecords().filter { it.startEpochSec > 0 && it.totalKwh > 0 }
        if (recs.isEmpty()) return emptyList()
        val sum = java.util.TreeMap<Long, Double>()
        val tick = HashMap<Long, String>()
        for (r in recs) {
            val key = bucketOf(mode, r.startEpochSec).first
            sum[key] = (sum[key] ?: 0.0) + r.totalKwh
            tick[key] = bucketTick(mode, r.startEpochSec)
        }
        var entries = sum.entries.toList()            // ascending by key
        val cap = when (mode) { 0 -> 8; 1 -> 12; else -> 6 }
        if (entries.size > cap) entries = entries.takeLast(cap)
        return entries.map { Triple(it.key, tick[it.key] ?: "", it.value) }
    }

    /** Simple theme-aware, tappable bar chart; no external libraries. */
    inner class BarChartView(c: Context) : View(c) {
        private var data: List<Triple<Long, String, Double>> = emptyList()
        private var selectedKey: Long? = null
        var onBarTap: ((Long) -> Unit)? = null
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cAccent }
        private val barSel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cBlue }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cCard2 }
        private val trackSel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE3EBF6.toInt() }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cMuted; textAlign = Paint.Align.CENTER }
        private val valTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cFg; textAlign = Paint.Align.CENTER }
        // layout cached from onDraw for hit-testing
        private var lastGap = 0f; private var lastBarW = 0f

        init { isClickable = true; txt.typeface = fReg; valTxt.typeface = fMed }

        fun setData(d: List<Triple<Long, String, Double>>, selKey: Long?) { data = d; selectedKey = selKey; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            txt.textSize = 9f * dens; valTxt.textSize = 9f * dens
            if (data.isEmpty()) {
                canvas.drawText("No data yet", w / 2f, h / 2f, txt)
                return
            }
            val maxV = data.maxOf { it.third }.coerceAtLeast(0.001)
            val n = data.size
            val padBottom = dp(20).toFloat(); val padTop = dp(16).toFloat()
            val gap = dp(8).toFloat()
            val barW = ((w - gap * (n + 1)) / n).coerceAtLeast(dp(4).toFloat())
            lastGap = gap; lastBarW = barW
            // shrink x-axis labels so the widest one fits its slot
            val slot = barW + gap
            val widest = data.maxOf { txt.measureText(it.second) }
            if (widest > slot * 0.95f) txt.textSize = (9f * dens * (slot * 0.95f / widest)).coerceAtLeast(6.5f * dens)
            val base = h - padBottom
            val usable = base - padTop
            for (i in data.indices) {
                val (key, lab, v) = data[i]
                val selected = key == selectedKey
                val left = gap + i * (barW + gap)
                val right = left + barW
                val cx = (left + right) / 2f
                canvas.drawRoundRect(RectF(left, padTop, right, base), dp(4).toFloat(), dp(4).toFloat(), if (selected) trackSel else track)
                val barH = (usable * (v / maxV)).toFloat()
                val top = base - barH
                canvas.drawRoundRect(RectF(left, top, right, base), dp(4).toFloat(), dp(4).toFloat(), if (selected) barSel else bar)
                valTxt.color = if (selected) cBlue else cFg
                val vs = if (v >= 100) "%.0f".format(v) else "%.1f".format(v)
                canvas.drawText(vs, cx, (top - dp(4)).coerceAtLeast(padTop - dp(4).toFloat()), valTxt)
                txt.color = if (selected) cFg else cMuted
                canvas.drawText(lab, cx, h - dp(5), txt)
                txt.color = cMuted
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_UP && data.isNotEmpty() && lastBarW > 0f) {
                val slot = lastBarW + lastGap
                var i = ((e.x - lastGap) / slot).toInt()
                if (i < 0) i = 0; if (i >= data.size) i = data.size - 1
                onBarTap?.invoke(data[i].first)
                performClick()
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }

    private fun recordCard(r: InvtProtocol.Record): View {
        val c = card(cCard)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val date = if (r.startEpochSec > 0) dFmt.format(Date(r.startEpochSec * 1000L)) else "Unknown date"
        left.addView(value(date, cFg, 14f))
        val sub = buildString {
            append("${r.durationMin} min")
            if (r.gun.isNotEmpty()) append(" · Gun ${r.gun}")
            if (r.fault != 0) append(" · fault ${r.fault}")
        }
        left.addView(TextView(this).apply { text = sub; setTextColor(cMuted); textSize = 12f; setPadding(0, dp(2), 0, 0) })
        top.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val kwh = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        kwh.addView(value("%.2f".format(r.totalKwh), cAccent, 22f).apply { gravity = Gravity.END })
        kwh.addView(lbl("kWh", cMuted, 10f))
        top.addView(kwh)
        c.addView(top)
        val cost = costOf(r.totalKwh, r.totalFee)
        if (cost > 0) {
            c.addView(TextView(this).apply {
                text = "Cost  " + fmtMoney(cost); setTextColor(cMuted); textSize = 12f; setPadding(0, dp(8), 0, 0)
            })
        }
        return c
    }

    // ---------- cost / rate ----------
    private fun loadRate(): Double = try {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("rate", 0f).toDouble()
    } catch (_: Exception) { 0.0 }

    private fun saveRate(v: Double) {
        try { getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("rate", v.toFloat()).apply() } catch (_: Exception) {}
    }

    /** Cost from the user's tariff when set, otherwise the charger's own figure. */
    private fun costOf(kwh: Double, fallback: Double): Double = if (ratePerKwh > 0) kwh * ratePerKwh else fallback
    private fun fmtMoney(v: Double): String = "RM %.2f".format(v)

    private fun showRateDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "e.g. 0.57"
            if (ratePerKwh > 0) setText("%.2f".format(ratePerKwh))
            setTextColor(cFg); setHintTextColor(cMuted)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        box.addView(TextView(this).apply {
            text = "Cost per kWh (RM). Leave empty to use the charger's own figure."
            setTextColor(cMuted); textSize = 13f; setPadding(0, 0, 0, dp(10))
        })
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle("Electricity rate")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString().trim().toDoubleOrNull() ?: 0.0
                ratePerKwh = if (v > 0) v else 0.0
                saveRate(ratePerKwh)
                renderHistory()
                toast(if (ratePerKwh > 0) "Rate set to ${fmtMoney(ratePerKwh)}/kWh" else "Using charger's cost")
            }
            .setNeutralButton("Clear") { _, _ -> ratePerKwh = 0.0; saveRate(0.0); renderHistory(); toast("Rate cleared") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- misc ----------
    private fun setChip(s: String, color: Int) = runOnUiThread {
        val txt = if (s.startsWith("●")) s else "● $s"
        chip?.text = txt; chip?.setTextColor(color)
    }
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() { super.onDestroy(); disconnect() }
}
