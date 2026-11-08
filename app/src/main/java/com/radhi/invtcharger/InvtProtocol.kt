package com.radhi.invtcharger

/**
 * INVT EV Charger (EVC16) local protocol — same frames over BLE as over WiFi/TCP.
 * Reverse-engineered from the official app v2.4.0.
 *
 * Frame: 0x55 | len_lo | len_hi | cmd | payload... | xor(all preceding) | 0xAA
 *
 * Status query (cmd 0x61): 55 07 00 61 00 33 AA
 * Status reply payload (after stripping 4-byte header + 2-byte trailer), LE:
 *   [0] gunOrder 0=A 1=B
 *   [1] gunStatus 0 Free /1 Plugged /2 Charging /3 Finished /4 Fault
 *   [2] faultCode
 *   [3..4]  voltage  LE16 /10   V
 *   [5..6]  current  LE16 /10   A
 *   [7..10] energy   LE32 /100  kWh
 *   [11..14] cost    LE32 /100
 *   [15..16] duration LE16      min
 *   [17..18] cp_cc   LE16 /10   V
 *   [19..20] power   LE16 /10   kW
 *
 * BLE characteristics (service 0xA002):
 *   write  -> 0xC303 (write-no-response)  [0xC302 write-with-response also works]
 *   notify <- 0xC305
 */
object InvtProtocol {
    const val HEADER = 0x55
    const val TAIL = 0xAA
    const val CMD_READ_STATUS = 0x61
    const val CMD_START_STOP = 0x60

    val GUN_STATUS = mapOf(
        0 to "Free", 1 to "Plugged in", 2 to "Charging",
        3 to "Finished", 4 to "Fault"
    )

    private fun xor(b: IntArray): Int {
        var x = 0
        for (v in b) x = x xor (v and 0xFF)
        return x and 0xFF
    }

    fun statusQuery(): ByteArray {
        val f = intArrayOf(HEADER, 0x07, 0x00, CMD_READ_STATUS, 0x00)
        val x = xor(f)
        return (f + intArrayOf(x, TAIL)).map { it.toByte() }.toByteArray()
    }

    fun startStop(start: Boolean): ByteArray {
        val f = intArrayOf(HEADER, 0x07, 0x00, CMD_START_STOP, if (start) 1 else 0)
        val x = xor(f)
        return (f + intArrayOf(x, TAIL)).map { it.toByte() }.toByteArray()
    }

    data class Status(
        val gunOrder: String, val gunStatus: Int, val gunStatusDesc: String,
        val faultCode: Int, val voltage: Double, val current: Double,
        val energyKwh: Double, val cost: Double, val durationMin: Int,
        val cpCcV: Double, val powerKw: Double
    )

    private fun le16(a: Int, b: Int) = (b and 0xFF shl 8) or (a and 0xFF)
    private fun le32(a: Int, b: Int, c: Int, d: Int) =
        ((d and 0xFF) shl 24) or ((c and 0xFF) shl 16) or ((b and 0xFF) shl 8) or (a and 0xFF)

    /** Decode a status payload (inner bytes, header+trailer already stripped). */
    fun parseStatus(t: IntArray): Status? {
        if (t.size < 21) return null
        val st = t[1] and 0xFF
        return Status(
            gunOrder = if (t[0].toInt() == 0) "A" else "B",
            gunStatus = st,
            gunStatusDesc = GUN_STATUS[st] ?: "Unknown",
            faultCode = t[2] and 0xFF,
            voltage = le16(t[3], t[4]) / 10.0,
            current = le16(t[5], t[6]) / 10.0,
            energyKwh = le32(t[7], t[8], t[9], t[10]) / 100.0,
            cost = le32(t[11], t[12], t[13], t[14]) / 100.0,
            durationMin = le16(t[15], t[16]),
            cpCcV = le16(t[17], t[18]) / 10.0,
            powerKw = le16(t[19], t[20]) / 10.0
        )
    }

    /**
     * Reassembles BLE notification fragments into complete frames.
     * BLE splits long frames across ~20-byte notifications, so we buffer and
     * emit each complete, checksum-valid frame. Returns list of (cmd, payload).
     */
    class Reassembler {
        private val buf = ArrayList<Int>()

        fun feed(chunk: ByteArray): List<Pair<Int, IntArray>> {
            for (b in chunk) buf.add(b.toInt() and 0xFF)
            val out = ArrayList<Pair<Int, IntArray>>()
            while (true) {
                if (buf.isEmpty()) break
                // sync to header
                if (buf[0] != HEADER) {
                    val idx = buf.indexOf(HEADER)
                    if (idx < 0) { buf.clear(); break }
                    repeat(idx) { buf.removeAt(0) }
                }
                if (buf.size < 6) break
                val total = le16(buf[1], buf[2])
                if (total < 6 || total > 512) { buf.removeAt(0); continue }
                if (buf.size < total) break
                val frame = IntArray(total) { buf[it] }
                repeat(total) { buf.removeAt(0) }
                if (frame[total - 1] != TAIL) continue
                if (xor(frame.copyOfRange(0, total - 2)) != frame[total - 2]) continue
                val cmd = frame[3] and 0x7F
                val payload = frame.copyOfRange(4, total - 2)
                out.add(cmd to payload)
            }
            return out
        }
    }
}
