package id.my.sir.mpdclock

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Sinkronisasi jam sistem via NTP + set waktu lewat su (butuh root).
 *
 * Latar belakang: di HP ini jam sistem sering meleset 4-5 menit karena waktu
 * NITZ dari operator tidak akurat, sementara Android hanya sinkron ulang
 * saat trigger tertentu (bukan kontinu). Kelas ini mengambil waktu akurat
 * dari server NTP (SNTP manual via UDP 123, tanpa library tambahan) lalu
 * mengatur jam sistem lewat `su`, dan mematikan "waktu otomatis" bawaan
 * Android agar NITZ yang jelek tidak menimpa hasil koreksi.
 */
class NtpSync(private val context: Context) {

    data class Result(
        val ok: Boolean,
        val detail: String,      // ringkasan untuk ditampilkan di UI
        val correctedByMs: Long  // besar koreksi (0 bila tidak dikoreksi)
    )

    private val handler = Handler(Looper.getMainLooper())
    private var periodic: Runnable? = null

    /** Hasil cek root di-cache; null = belum dicek. */
    var hasRoot: Boolean? = null
        private set

    fun checkRoot(): Boolean {
        hasRoot?.let { return it }
        val ok = runSu("echo ok").first == 0
        hasRoot = ok
        return ok
    }

    /**
     * Query SNTP sederhana. Kembalikan estimasi waktu benar (epoch millis)
     * atau null bila gagal. Koreksi setengah round-trip agar akurat.
     */
    fun queryNtp(host: String = "time.google.com", timeoutMs: Int = 5000): Long? {
        return try {
            val addr = InetAddress.getByName(host)
            val buf = ByteArray(48)
            buf[0] = 0x1B // LI=0, VN=4, Mode=3 (client)
            val sock = DatagramSocket()
            try {
                sock.soTimeout = timeoutMs
                sock.send(DatagramPacket(buf, buf.size, addr, 123))
                val t1 = System.currentTimeMillis()
                sock.receive(DatagramPacket(buf, buf.size))
                val t4 = System.currentTimeMillis()
                // Transmit timestamp server: byte 40..47 (detik sejak 1900)
                val sec = ((buf[40].toLong() and 0xFF) shl 24) or
                    ((buf[41].toLong() and 0xFF) shl 16) or
                    ((buf[42].toLong() and 0xFF) shl 8) or
                    (buf[43].toLong() and 0xFF)
                val frac = ((buf[44].toLong() and 0xFF) shl 24) or
                    ((buf[45].toLong() and 0xFF) shl 16) or
                    ((buf[46].toLong() and 0xFF) shl 8) or
                    (buf[47].toLong() and 0xFF)
                if (sec == 0L) return null
                val serverMs = (sec - 2208988800L) * 1000 + (frac * 1000L) / 0x100000000L
                serverMs + (t4 - t1) / 2
            } finally {
                try { sock.close() } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Sinkronisasi penuh: query NTP, koreksi jam sistem via su bila selisih
     * melebihi ambang. Aman dipanggil dari thread latar.
     */
    fun syncNow(thresholdMs: Long = 30_000): Result {
        if (!checkRoot()) return Result(false, "Root tidak tersedia (su gagal)", 0)
        val ntp = queryNtp() ?: return Result(false, "Gagal menghubungi server NTP", 0)
        val drift = ntp - System.currentTimeMillis()
        if (abs(drift) <= thresholdMs) {
            return Result(true, "Jam sudah akurat (selisih ${fmtDur(drift)})", 0)
        }
        return if (setSystemTime(ntp)) {
            Result(true, "Dikoreksi ${fmtDur(drift)}", drift)
        } else {
            Result(false, "Gagal mengatur jam via su", drift)
        }
    }

    /**
     * Atur jam sistem lewat `date` sebagai root. Coba format UTC dulu,
     * fallback ke waktu lokal. Keberhasilan diverifikasi, bukan diasumsikan.
     */
    private fun setSystemTime(epochMillis: Long): Boolean {
        // Percobaan 1: UTC (hindari ambiguitas zona waktu), format
        // klasik `MMddHHmmYYYY.ss` yang diterima `date` saat set.
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            .apply { timeInMillis = epochMillis }
        val stampUtc = String.format(
            Locale.US, "%02d%02d%02d%02d%04d.%02d",
            utc.get(Calendar.MONTH) + 1, utc.get(Calendar.DAY_OF_MONTH),
            utc.get(Calendar.HOUR_OF_DAY), utc.get(Calendar.MINUTE),
            utc.get(Calendar.YEAR), utc.get(Calendar.SECOND)
        )
        if (runSu("date -u $stampUtc").first == 0 && verify(epochMillis)) return true
        // Percobaan 2: waktu lokal tanpa -u.
        val loc = Calendar.getInstance().apply { timeInMillis = epochMillis }
        val stampLoc = String.format(
            Locale.US, "%02d%02d%02d%02d%04d.%02d",
            loc.get(Calendar.MONTH) + 1, loc.get(Calendar.DAY_OF_MONTH),
            loc.get(Calendar.HOUR_OF_DAY), loc.get(Calendar.MINUTE),
            loc.get(Calendar.YEAR), loc.get(Calendar.SECOND)
        )
        return runSu("date $stampLoc").first == 0 && verify(epochMillis)
    }

    private fun verify(ntpMillis: Long): Boolean =
        abs(System.currentTimeMillis() - ntpMillis) < 8000

    /**
     * Nyalakan/matikan "waktu otomatis" bawaan Android. Dimatikan agar waktu
     * NITZ operator yang meleset tidak menimpa hasil koreksi NTP.
     */
    fun setAutoTimeEnabled(enabled: Boolean) {
        runSu("settings put global auto_time ${if (enabled) 1 else 0}")
    }

    /** Scheduler berkala di thread utama; query+set jalan di thread latar. */
    fun startPeriodic(intervalMs: Long = 30 * 60 * 1000L, onResult: (Result) -> Unit) {
        stopPeriodic()
        val r = object : Runnable {
            override fun run() {
                Thread {
                    val res = try {
                        syncNow()
                    } catch (e: Exception) {
                        Result(false, "Error: ${e.message}", 0)
                    }
                    handler.post { onResult(res) }
                }.apply { isDaemon = true }.start()
                handler.postDelayed(this, intervalMs)
            }
        }
        periodic = r
        handler.postDelayed(r, 15_000) // sinkron pertama 15 dtk setelah start
    }

    fun stopPeriodic() {
        periodic?.let { handler.removeCallbacks(it) }
        periodic = null
    }

    private fun runSu(cmd: String): Pair<Int, String> {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val code = p.waitFor()
            val out = try {
                p.inputStream.bufferedReader().readText()
            } catch (_: Exception) {
                ""
            }
            code to out
        } catch (e: Exception) {
            -1 to (e.message ?: "")
        }
    }

    private fun fmtDur(ms: Long): String {
        val s = abs(ms) / 1000
        val sign = if (ms < 0) "-" else "+"
        return when {
            s < 60 -> "$sign${s}d"
            s < 3600 -> "$sign${s / 60}m ${s % 60}d"
            else -> "$sign${s / 3600}j ${(s % 3600) / 60}m"
        }
    }
}
