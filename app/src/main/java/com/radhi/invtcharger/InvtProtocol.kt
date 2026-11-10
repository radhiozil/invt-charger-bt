package com.radhi.invtcharger

import java.util.Calendar
import java.util.TimeZone

/**
 * INVT charger BLE protocol — extracted from the INVT Charge app v2.4.2 (iOS IPA,
 * pages/home/pile-info). This is the protocol the app actually uses over Bluetooth.
 *
 * Frame:  0x55 | lenLo | lenHi | protocolVersion(1) | cmd | payload... | xor | 0xAA
 *   NOTE: command is at byte 4 (byte 3 is protocolVersion). Length is total frame
 *   length, little-endian at bytes 1..2. xor is over all bytes before it.
 *
 * Characteristics (service 0xA002):  write -> 0xC304,  notify <- 0xC305
 *
 * Flow:  handshake (cmd 0) once, then poll status (cmd 1) repeatedly.
 *
 * Commands (pile-info cmdFrameType):
 *   0  HANDSHAKE_AND_CONNECT   payload = unix-ts(LE32) + tz[sign,hours,mins]
 *   1  R_CHARGER_STATUS_DATAS  query payload = [0x00]; reply parsed below
 *   19 START_OR_STOP_CHARGE
 *
 * Status reply payload (bytes counted from the first payload byte, index 5 in frame):
 *   [0]      wifiConnStatus
 *   [1]      gunNumber
 *   [2..5]   accumulated capacity  LE32 /100  -> lifetime total kWh (meter)
 *   [6]      gun insert status
 *   [7]      charging status (0 idle / 1 connected / 2 charging / 3 done)
 *   [8]      stop reason
 *   [9]      fault code
 *   [10..11] voltage  LE16 /10   V
 *   [12..13] current  LE16 /10   A
 *   [14..17] charged capacity  LE32 /100  -> this-session kWh
 *   [18..21] charged bill      LE32 /100  -> this-session cost
 *   [22..23] duration LE16  minutes
 *   [24..25] power    LE16        (0.1 kW units in app view)
 *   [26..27] green-energy capacity LE16 /100
 */
object InvtProtocol {
    const val HEADER = 0x55
    const val TAIL = 0xAA
    const val PROTO_VER = 0x01
    const val CMD_HANDSHAKE = 0x00
    const val CMD_STATUS = 0x01
    const val CMD_START_STOP = 19
    const val CMD_RECORDS_COUNT = 16   // R_CHARGE_RECORDS_COUNT
    const val CMD_RECORDS_DATA = 17    // R_CHARGE_RECORDS_DATAS
    const val CMD_MAX_CURRENT = 5      // RW_CHARGER_MAX_CURRENT (read/write amps)
    const val CUR_MIN = 6
    const val CUR_MAX = 32             // EVC16-AW7KGF1W(US): 6..32 A

    val CHARGE_STATUS = mapOf(
        0 to "Idle", 1 to "Connected", 2 to "Charging", 3 to "Finished", 4 to "Fault"
    )

    private fun xor(b: IntArray): Int { var x = 0; for (v in b) x = x xor (v and 0xFF); return x and 0xFF }
    private fun le16(a: Int, b: Int) = (b and 0xFF shl 8) or (a and 0xFF)
    private fun le32(a: Int, b: Int, c: Int, d: Int) =
        ((d and 0xFF) shl 24) or ((c and 0xFF) shl 16) or ((b and 0xFF) shl 8) or (a and 0xFF)

    /** Build a frame with command at byte 4 and a protocol-version byte at 3. */
    private fun frame(cmd: Int, payload: IntArray): ByteArray {
        val total = 5 + payload.size + 2          // header(5) + payload + xor + tail
        val f = IntArray(total)
        f[0] = HEADER; f[1] = total and 0xFF; f[2] = (total shr 8) and 0xFF
        f[3] = PROTO_VER; f[4] = cmd
        for (i in payload.indices) f[5 + i] = payload[i]
        f[total - 2] = xor(f.copyOfRange(0, total - 2))
        f[total - 1] = TAIL
        return f.map { it.toByte() }.toByteArray()
    }

    /** Handshake: current unix time (LE32 seconds) + timezone [sign, hours, mins]. */
    fun handshake(): ByteArray {
        val now = (System.currentTimeMillis() / 1000L)
        val tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000.0 / 3600.0 // hours
        val sign = if (tz < 0) 0 else 1
        val h = Math.abs(tz).toInt()
        val m = Math.round((Math.abs(tz) - h) * 60).toInt()
        val p = intArrayOf(
            (now and 0xFF).toInt(), ((now shr 8) and 0xFF).toInt(),
            ((now shr 16) and 0xFF).toInt(), ((now shr 24) and 0xFF).toInt(),
            sign, h, m
        )
        return frame(CMD_HANDSHAKE, p)
    }

    /** Status query: one zero payload byte. */
    fun statusQuery(): ByteArray = frame(CMD_STATUS, intArrayOf(0x00))

    /** Start (1) or stop (0) charging. */
    fun startStop(start: Boolean): ByteArray = frame(CMD_START_STOP, intArrayOf(if (start) 1 else 0))

    /** Ask the charger how many stored charging records it holds. */
    fun recordsCountQuery(): ByteArray = frame(CMD_RECORDS_COUNT, intArrayOf(0x00))

    /** Ask the charger for one stored record by index (LE16). */
    fun recordsDataQuery(index: Int): ByteArray =
        frame(CMD_RECORDS_DATA, intArrayOf(index and 0xFF, (index shr 8) and 0xFF))

    /** Ask the charger for its configured max charging current. */
    fun maxCurrentQuery(): ByteArray = frame(CMD_MAX_CURRENT, intArrayOf(0x00))

    /** Set the charger's max charging current (amps). Payload: write-flag + amps*10 LE16. */
    fun setMaxCurrent(amps: Int): ByteArray {
        val v = amps * 10
        return frame(CMD_MAX_CURRENT, intArrayOf(1, v and 0xFF, (v shr 8) and 0xFF))
    }

    /** Parse a max-current reply -> amps, or null (write-ack / failure / short). */
    fun parseMaxCurrent(e: IntArray): Double? {
        if (e.size < 4) return null      // write-ack replies are shorter; ignore
        if ((e[1] and 0xFF) != 0) return null  // non-zero status = not a value
        return le16(e[2], e[3]) / 10.0
    }

    /** Parse a records-count reply payload -> number of stored records. */
    fun parseRecordsCount(e: IntArray): Int? {
        if (e.size < 2) return null
        return le16(e[0], e[1])
    }

    /** One stored charging record read from the charger's own memory. */
    data class Record(
        val index: Int, val gun: String, val startEpochSec: Long,
        val totalKwh: Double, val electricityFee: Double, val serviceFee: Double,
        val durationMin: Int, val stopReason: Int, val fault: Int
    ) {
        val totalFee: Double get() = electricityFee + serviceFee
    }

    /** Parse a single charge-record reply payload (needs >= 43 bytes). */
    fun parseRecord(e: IntArray): Record? {
        if (e.size < 43) return null
        return Record(
            index = le16(e[0], e[1]),
            gun = if (e[2] == 0) "A" else "B",
            startEpochSec = le32(e[3], e[4], e[5], e[6]).toLong() and 0xFFFFFFFFL,
            totalKwh = le32(e[7], e[8], e[9], e[10]) / 100.0,
            electricityFee = le32(e[31], e[32], e[33], e[34]) / 100.0,
            serviceFee = le32(e[35], e[36], e[37], e[38]) / 100.0,
            durationMin = le16(e[39], e[40]),
            stopReason = e[41] and 0xFF,
            fault = e[42] and 0xFF
        )
    }

    data class Status(
        val chargingStatus: Int, val statusDesc: String, val fault: Int,
        val voltage: Double, val current: Double, val powerKw: Double,
        val sessionKwh: Double, val lifetimeKwh: Double,
        val cost: Double, val durationMin: Int
    )

    /** Parse an R_CHARGER_STATUS_DATAS reply payload (needs >= 28 bytes). */
    fun parseStatus(e: IntArray): Status? {
        if (e.size < 28) return null
        val st = e[7] and 0xFF
        return Status(
            chargingStatus = st,
            statusDesc = CHARGE_STATUS[st] ?: "Status $st",
            fault = e[9] and 0xFF,
            lifetimeKwh = le32(e[2], e[3], e[4], e[5]) / 100.0,
            voltage = le16(e[10], e[11]) / 10.0,
            current = le16(e[12], e[13]) / 10.0,
            sessionKwh = le32(e[14], e[15], e[16], e[17]) / 100.0,
            cost = le32(e[18], e[19], e[20], e[21]) / 100.0,
            durationMin = le16(e[22], e[23]),
            powerKw = le16(e[24], e[25]) / 10.0
        )
    }

    /** Reassemble BLE notifications into complete frames; returns (cmd, payload). */
    class Reassembler {
        private val buf = ArrayList<Int>()
        fun feed(chunk: ByteArray): List<Pair<Int, IntArray>> {
            for (b in chunk) buf.add(b.toInt() and 0xFF)
            val out = ArrayList<Pair<Int, IntArray>>()
            while (true) {
                if (buf.isEmpty()) break
                if (buf[0] != HEADER) {
                    val idx = buf.indexOf(HEADER)
                    if (idx < 0) { buf.clear(); break }
                    repeat(idx) { buf.removeAt(0) }
                }
                if (buf.size < 7) break
                val total = le16(buf[1], buf[2])
                if (total < 7 || total > 1024) { buf.removeAt(0); continue }
                if (buf.size < total) break
                val f = IntArray(total) { buf[it] }
                repeat(total) { buf.removeAt(0) }
                if (f[total - 1] != TAIL) continue
                if (xor(f.copyOfRange(0, total - 2)) != f[total - 2]) continue
                val cmd = f[4] and 0x7F
                out.add(cmd to f.copyOfRange(5, total - 2))   // payload from byte 5
            }
            return out
        }
    }
}
