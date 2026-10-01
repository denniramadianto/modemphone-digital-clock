package id.my.sir.mpdclock

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.telephony.TelephonyManager
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var hourView: SegmentDisplayView
    private lateinit var minView: SegmentDisplayView
    private lateinit var secView: SegmentDisplayView
    private lateinit var colonView: TextView
    private lateinit var clockBox: LinearLayout
    private lateinit var amText: TextView
    private lateinit var pmText: TextView
    private lateinit var ledDown: View
    private lateinit var ledUp: View
    private lateinit var tempText: TextView
    private lateinit var dayNameText: TextView
    private lateinit var dateDayText: TextView
    private lateinit var dateMonthText: TextView
    private lateinit var dateYearText: TextView
    private lateinit var sim1Label: TextView
    private lateinit var sim1Bars: SignalBarsView
    private lateinit var sim1Dbm: TextView
    private lateinit var sim1Band: TextView
    private lateinit var sim2Label: TextView
    private lateinit var sim2Bars: SignalBarsView
    private lateinit var sim2Dbm: TextView
    private lateinit var sim2Band: TextView
    private lateinit var netBadge: TextView
    private lateinit var dlSpeed: TextView
    private lateinit var ulSpeed: TextView
    private lateinit var dataUsageText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val localeId = Locale("in", "ID")
    private var lastDayKey = ""
    private var lastChimeKey = ""

    private var tts: TextToSpeech? = null
    private var toneGen: ToneGenerator? = null

    private val dayNames = arrayOf(
        "MINGGU", "SENIN", "SELASA", "RABU", "KAMIS", "JUMAT", "SABTU"
    )

    private lateinit var signalMonitor: SignalMonitor
    private val trafficMonitor = TrafficMonitor { rx, tx ->
        dlSpeed.text = "↓ ${formatSpeed(rx, localeId)}"
        ulSpeed.text = "↑ ${formatSpeed(tx, localeId)}"
        // LED hijau (download) & kuning (upload) menyala saat ada trafik
        ledDown.alpha = if (rx > 1024) 1f else 0.25f
        ledUp.alpha = if (tx > 1024) 1f else 0.25f
        dataUsageText.text = "Data ${formatDataUsage()}"
    }

    // Suhu CPU dari thermal zone (/sys/class/thermal). Perangkat ini tanpa baterai
    // (bypass stepdown) + root Magisk, jadi suhu baterai tidak bermakna.
    // Dibaca tiap 5 detik di thread latar; coba baca langsung, fallback via su.
    private var thermalUseSu: Boolean? = null

    private val cpuTempTask = object : Runnable {
        override fun run() {
            Thread {
                val t = readCpuTemp()
                handler.post {
                    tempText.text = if (t != null) {
                        String.format(localeId, "%.1f°C", t)
                    } else {
                        "--°C"
                    }
                }
            }.start()
            handler.postDelayed(this, 5000)
        }
    }

    private fun readCpuTemp(): Double? {
        return try {
            val zones = java.io.File("/sys/class/thermal")
                .listFiles { f -> f.name.startsWith("thermal_zone") }
                ?: return null
            if (zones.isEmpty()) return null
            if (thermalUseSu == null) {
                thermalUseSu = try {
                    java.io.File(zones[0], "temp").readText()
                    false
                } catch (_: Exception) {
                    true
                }
            }
            var cpuBest: Double? = null
            var otherBest: Double? = null
            for (zone in zones) {
                val type = readThermalFile(java.io.File(zone, "type"))
                    ?.trim()?.lowercase() ?: continue
                val raw = readThermalFile(java.io.File(zone, "temp"))
                    ?.trim()?.toLongOrNull() ?: continue
                if (raw <= 0 || raw > 200_000) continue
                val c = raw / 1000.0
                if ("cpu" in type) {
                    if (cpuBest == null || c > cpuBest) cpuBest = c
                } else if ("battery" !in type) {
                    if (otherBest == null || c > otherBest) otherBest = c
                }
            }
            cpuBest ?: otherBest
        } catch (_: Exception) {
            null
        }
    }

    private fun readThermalFile(f: java.io.File): String? {
        return try {
            if (thermalUseSu == true) {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat ${f.absolutePath}"))
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                out.ifBlank { null }
            } else {
                f.readText()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Total pemakaian paket data seluler: bulanan (tidak reset saat restart). */
    private var cachedMonthlyBytes = -1L
    private var cachedMonthlyAt = 0L

    private fun formatDataUsage(): String {
        val bytes = try {
            if (hasUsageAccess()) monthlyMobileBytesCached() else -1L
        } catch (_: Exception) {
            -1L
        }.let { if (it >= 0) it else legacyTrafficBytes() }
        val totalGb = bytes.toDouble() / (1024 * 1024 * 1024)
        return String.format(localeId, "%.2f GB", totalGb)
    }

    /** Fallback: TrafficStats (akumulasi sejak boot, reset saat restart). */
    private fun legacyTrafficBytes(): Long {
        var rx = TrafficStats.getMobileRxBytes()
        var tx = TrafficStats.getMobileTxBytes()
        if (rx == TrafficStats.UNSUPPORTED.toLong()) {
            rx = TrafficStats.getTotalRxBytes()
            tx = TrafficStats.getTotalTxBytes()
        }
        return rx.coerceAtLeast(0) + tx.coerceAtLeast(0)
    }

    /** Cek izin khusus "Akses penggunaan" (Usage Access). */
    private fun hasUsageAccess(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Total byte seluler dari tanggal 1 bulan berjalan sampai sekarang,
     * via NetworkStatsManager (data sistem, bertahan setelah restart).
     * Hasil di-cache 60 detik agar query tidak tiap detik.
     */
    private fun monthlyMobileBytesCached(): Long {
        val now = System.currentTimeMillis()
        if (cachedMonthlyBytes >= 0 && now - cachedMonthlyAt < 60_000) {
            return cachedMonthlyBytes
        }
        val v = monthlyMobileBytes()
        cachedMonthlyBytes = v
        cachedMonthlyAt = now
        return v
    }

    private fun monthlyMobileBytes(): Long {
        return try {
            val nsm = getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            val cal = Calendar.getInstance()
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            val end = System.currentTimeMillis()
            // querySummaryForDevice berubah tipe kembalian di API 31:
            // API 23-30 -> NetworkStats (iterasi bucket), API 31+ -> NetworkStats.Bucket.
            // Dipanggil via reflection agar satu APK aman di semua versi Android.
            val m = NetworkStatsManager::class.java.getMethod(
                "querySummaryForDevice",
                Int::class.javaPrimitiveType, String::class.java,
                Long::class.javaPrimitiveType, Long::class.javaPrimitiveType
            )
            when (val result = m.invoke(nsm, ConnectivityManager.TYPE_MOBILE, null, start, end)) {
                is NetworkStats.Bucket -> {
                    (if (result.rxBytes > 0) result.rxBytes else 0L) +
                        (if (result.txBytes > 0) result.txBytes else 0L)
                }
                is NetworkStats -> {
                    var total = 0L
                    val bucket = NetworkStats.Bucket()
                    try {
                        while (result.hasNextBucket()) {
                            result.getNextBucket(bucket)
                            if (bucket.rxBytes > 0) total += bucket.rxBytes
                            if (bucket.txBytes > 0) total += bucket.txBytes
                        }
                    } finally {
                        try {
                            result.close()
                        } catch (_: Exception) {
                        }
                    }
                    total
                }
                else -> -1L
            }
        } catch (_: Exception) {
            -1L
        }
    }

    /**
     * Minta izin "Akses penggunaan" sekali saja per instalasi.
     * Tanpa izin ini, angka Data memakai TrafficStats (reset tiap restart).
     */
    private fun promptUsageAccessOnce() {
        if (hasUsageAccess()) return
        val prefs = getSharedPreferences("mpdclock", Context.MODE_PRIVATE)
        if (prefs.getBoolean("usage_prompted", false)) return
        prefs.edit().putBoolean("usage_prompted", true).apply()
        try {
            AlertDialog.Builder(this)
                .setTitle("Akses penggunaan")
                .setMessage(
                    "Agar angka \"Data\" menampilkan pemakaian bulan berjalan " +
                        "dan tidak kembali ke 0 setiap HP direstart, aktifkan " +
                        "\"Akses penggunaan\" untuk ModemPhone Digital Clock di layar berikutnya."
                )
                .setPositiveButton("Buka Pengaturan") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    } catch (_: Exception) {
                    }
                }
                .setNegativeButton("Nanti", null)
                .show()
        } catch (_: Exception) {
        }
    }

    // ---------- Lonceng jam (beep + suara) ----------

    private val REQ_TTS_DATA = 9001

    private fun initChime() {
        try {
            // Volume maksimal: bunyi "tiiiit" keras dan melengking
            toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (_: Exception) {
            toneGen = null
        }
        // Cek dulu data suara TTS; kalau belum ada, arahkan user menginstalnya.
        // Tanpa data suara, pengucapan jam tidak akan bunyi sama sekali.
        try {
            startActivityForResult(
                Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA),
                REQ_TTS_DATA
            )
        } catch (_: Exception) {
            createTts()
        }
    }

    private fun createTts() {
        if (tts != null) return
        try {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = pickTtsLocale() ?: Locale.getDefault()
                }
            }
        } catch (_: Exception) {
            tts = null
        }
    }

    @Deprecated("dipakai untuk hasil cek data suara TTS")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TTS_DATA) {
            if (resultCode == TextToSpeech.Engine.CHECK_VOICE_DATA_PASS) {
                createTts()
            } else {
                Toast.makeText(
                    this,
                    "Data suara belum terpasang — mengarahkan ke instalasi suara Google",
                    Toast.LENGTH_LONG
                ).show()
                try {
                    startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                } catch (_: Exception) {
                }
                createTts()
            }
        }
    }

    /** Pilih locale TTS Indonesia yang benar-benar didukung mesin suara. */
    private fun pickTtsLocale(): Locale? {
        val t = tts ?: return null
        val candidates = listOf(
            Locale("id", "ID"),
            Locale("in", "ID"),
            Locale.getDefault(),
            Locale.US
        )
        for (loc in candidates) {
            val avail = try {
                t.isLanguageAvailable(loc)
            } catch (_: Exception) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (avail == TextToSpeech.LANG_AVAILABLE ||
                avail == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                avail == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            ) {
                return loc
            }
        }
        return null
    }

    private fun beep() {
        try {
            // TONE_CDMA_HIGH_L: nada tinggi panjang "tiiiit", 650ms, volume maksimal
            toneGen?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 650)
        } catch (_: Exception) {
        }
    }

    /** Tepat HH:00: beep sejumlah jam (format 12 jam), lalu bicara "Sekarang pukul N". */
    private fun hourlyChime(hour12: Int) {
        var delay = 0L
        repeat(hour12.coerceIn(1, 12)) {
            handler.postDelayed({ beep() }, delay)
            delay += 950L
        }
        handler.postDelayed({
            try {
                // Paksa lewat stream ALARM agar tetap bunyi walau media dimute
                val params = Bundle().apply {
                    putString(
                        TextToSpeech.Engine.KEY_PARAM_STREAM,
                        AudioManager.STREAM_ALARM.toString()
                    )
                }
                tts?.speak(
                    "Sekarang pukul $hour12",
                    TextToSpeech.QUEUE_FLUSH,
                    params,
                    "hourly_chime"
                )
            } catch (_: Exception) {
            }
        }, delay + 300L)
    }

    private val tick = object : Runnable {
        override fun run() {
            val cal = Calendar.getInstance()

            // Jam format 12 jam + AM/PM
            var h = cal.get(Calendar.HOUR)
            if (h == 0) h = 12
            hourView.displayText = "%02d".format(h)
            minView.displayText = "%02d".format(cal.get(Calendar.MINUTE))
            secView.displayText = "%02d".format(cal.get(Calendar.SECOND))
            val isAm = cal.get(Calendar.AM_PM) == Calendar.AM
            setAmPmActive(amText, isAm)
            setAmPmActive(pmText, !isAm)

            // Titik dua berdetak mengikuti detik (menyala-mati tiap detik)
            colonView.visibility =
                if (cal.get(Calendar.SECOND) % 2 == 0) View.VISIBLE else View.INVISIBLE

            // Lonceng tiap jam: tepat HH:00 -> beep sejumlah jam + bicara,
            // tepat HH:30 -> beep 1x
            val minute = cal.get(Calendar.MINUTE)
            if (cal.get(Calendar.SECOND) == 0) {
                val chimeKey = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), minute)
                if (chimeKey != lastChimeKey) {
                    lastChimeKey = chimeKey
                    when (minute) {
                        0 -> {
                            var h12 = cal.get(Calendar.HOUR)
                            if (h12 == 0) h12 = 12
                            hourlyChime(h12)
                        }
                        30 -> beep()
                    }
                }
            }

            // Kotak kalender (diperbarui saat hari berganti)
            val dayKey = "%04d-%02d-%02d".format(
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            )
            if (dayKey != lastDayKey) {
                lastDayKey = dayKey
                dayNameText.text = dayNames[cal.get(Calendar.DAY_OF_WEEK) - 1]
                dateDayText.text = "%02d".format(cal.get(Calendar.DAY_OF_MONTH))
                dateMonthText.text = "%02d".format(cal.get(Calendar.MONTH) + 1)
                dateYearText.text = "%04d".format(cal.get(Calendar.YEAR))
            }

            // selaraskan ke detik berikutnya agar jam selalu tepat
            handler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
    }

    private fun setAmPmActive(tv: TextView, active: Boolean) {
        tv.setTextColor(if (active) Color.BLACK else Color.parseColor("#D8D8D8"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Layar tetap menyala nonstop selama aplikasi tampil
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)
        // hideSystemBars() HARUS setelah setContentView: di Android 16,
        // window.insetsController butuh DecorView yang baru ada setelah ini.
        // Dipanggil sebelumnya -> NullPointerException (force close).
        hideSystemBars()

        hourView = findViewById(R.id.hourView)
        minView = findViewById(R.id.minView)
        secView = findViewById(R.id.secView)
        colonView = findViewById(R.id.colonView)
        clockBox = findViewById(R.id.clockBox)
        amText = findViewById(R.id.amText)
        pmText = findViewById(R.id.pmText)
        ledDown = findViewById(R.id.ledDown)
        ledUp = findViewById(R.id.ledUp)
        tempText = findViewById(R.id.tempText)
        dayNameText = findViewById(R.id.dayNameText)
        dateDayText = findViewById(R.id.dateDayText)
        dateMonthText = findViewById(R.id.dateMonthText)
        dateYearText = findViewById(R.id.dateYearText)
        sim1Label = findViewById(R.id.sim1Label)
        sim1Bars = findViewById(R.id.sim1Bars)
        sim1Dbm = findViewById(R.id.sim1Dbm)
        sim1Band = findViewById(R.id.sim1Band)
        sim2Label = findViewById(R.id.sim2Label)
        sim2Bars = findViewById(R.id.sim2Bars)
        sim2Dbm = findViewById(R.id.sim2Dbm)
        sim2Band = findViewById(R.id.sim2Band)
        netBadge = findViewById(R.id.netBadge)
        dlSpeed = findViewById(R.id.dlSpeed)
        ulSpeed = findViewById(R.id.ulSpeed)
        dataUsageText = findViewById(R.id.dataUsageText)

        initChime()
        promptUsageAccessOnce()

        // Tekan lama jam -> buka info aplikasi di Pengaturan
        clockBox.setOnLongClickListener { openAppSettings(); true }
        // Tekan lama suhu -> tampilkan daftar thermal zones (diagnostik)
        tempText.setOnLongClickListener { showThermalZones(); true }

        signalMonitor = SignalMonitor(this, ::onSignalUpdate)
        updateNetBadge()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
        handler.post(cpuTempTask)
        trafficMonitor.start()
        ensurePhonePermission()
        // Terapkan ulang tiap kembali: sistem/MIUI sering menghapus flag
        // fullscreen saat fokus berpindah (dialog, Toast, dsb).
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(cpuTempTask)
        trafficMonitor.stop()
        signalMonitor.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        try {
            toneGen?.release()
        } catch (_: Exception) {
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun ensurePhonePermission() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.READ_PHONE_STATE)
        }
        // Info band (CellInfo) oleh Android dikategorikan data lokasi
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (need.isEmpty()) {
            signalMonitor.start()
        } else {
            requestPermissions(need.toTypedArray(), REQ_PHONE_STATE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PHONE_STATE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                signalMonitor.start()
            } else {
                Toast.makeText(
                    this,
                    "Izin telepon ditolak — info sinyal tidak dapat ditampilkan",
                    Toast.LENGTH_LONG
                ).show()
                sim1Label.text = "SIM 1"
                sim1Dbm.text = "izin ditolak"
                sim2Label.text = "SIM 2"
                sim2Dbm.text = "izin ditolak"
            }
        }
    }

    private fun onSignalUpdate(signals: List<SignalMonitor.SimSignal>) {
        // Cocokkan berdasarkan slot SIM, bukan urutan list: kalau yang aktif
        // cuma slot 2, datanya tidak boleh tampil di label SIM 1.
        val s1 = signals.firstOrNull { it.label == "SIM 1" }
        val s2 = signals.firstOrNull { it.label == "SIM 2" }

        if (s1 != null) {
            sim1Label.text = "${s1.label} • ${s1.carrier}"
            sim1Bars.level = signalBars6(s1)
            sim1Dbm.text = formatDbm(s1.dbm)
            sim1Band.text = formatBand(s1)
        } else {
            sim1Label.text = "SIM 1 • tidak ada SIM"
            sim1Bars.level = -1
            sim1Dbm.text = "-"
            sim1Band.text = "Band -"
        }

        if (s2 != null) {
            sim2Label.text = "${s2.label} • ${s2.carrier}"
            sim2Bars.level = signalBars6(s2)
            sim2Dbm.text = formatDbm(s2.dbm)
            sim2Band.text = formatBand(s2)
        } else {
            sim2Label.text = "SIM 2 • tidak ada SIM"
            sim2Bars.level = -1
            sim2Dbm.text = "-"
            sim2Band.text = "Band -"
        }

        // badge memakai generasi jaringan dari SIM pertama yang terdeteksi jaringannya
        val gen = listOfNotNull(s1, s2).firstOrNull { it.netGen != "-" }?.netGen
        if (gen != null) netBadge.text = gen
    }

    private fun updateNetBadge() {
        try {
            val tm = getSystemService(TelephonyManager::class.java)
            netBadge.text = when (tm.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                else -> "4G"
            }
        } catch (e: Exception) {
            netBadge.text = "4G"
        }
    }

    private fun formatDbm(dbm: Int): String =
        if (dbm == Int.MIN_VALUE) "-" else "$dbm dBm"

    /**
     * Bar sinyal 0..6 dipetakan dari dBm (lebih granular dari level 0..4 bawaan).
     * -1 = belum diketahui. Fallback: skala level bawaan bila dBm tak terbaca.
     */
    private fun signalBars6(s: SignalMonitor.SimSignal): Int {
        if (s.dbm != Int.MIN_VALUE) {
            return when {
                s.dbm >= -75 -> 6
                s.dbm >= -85 -> 5
                s.dbm >= -95 -> 4
                s.dbm >= -105 -> 3
                s.dbm >= -115 -> 2
                s.dbm >= -125 -> 1
                else -> 0
            }
        }
        return if (s.level in 0..4) (s.level * 6) / 4 else -1
    }

    /** Tekan lama jam: buka halaman info aplikasi di Pengaturan. */
    private fun openAppSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        } catch (_: Exception) {
        }
    }

    /** Tekan lama suhu: tampilkan semua thermal zones + suhunya (diagnostik). */
    private fun showThermalZones() {
        Thread {
            val sb = StringBuilder()
            try {
                val zones = java.io.File("/sys/class/thermal")
                    .listFiles { f -> f.name.startsWith("thermal_zone") }
                    ?.sortedBy { it.name } ?: emptyList()
                for (zone in zones) {
                    val type = readThermalFile(java.io.File(zone, "type"))?.trim() ?: "?"
                    val raw = readThermalFile(java.io.File(zone, "temp"))
                        ?.trim()?.toLongOrNull()
                    val temp = if (raw != null && raw > 0 && raw <= 200_000) {
                        String.format(localeId, "%.1f°C", raw / 1000.0)
                    } else {
                        "?"
                    }
                    sb.append("${zone.name} [$type]: $temp\n")
                }
            } catch (_: Exception) {
            }
            val text = sb.toString().trim().ifBlank { "tidak terbaca" }
            handler.post {
                try {
                    AlertDialog.Builder(this)
                        .setTitle("Thermal zones")
                        .setMessage(text)
                        .setPositiveButton("Tutup", null)
                        .show()
                } catch (_: Exception) {
                }
            }
        }.start()
    }

    private fun formatBand(s: SignalMonitor.SimSignal): String {
        if (s.band == "-") return "Band -"
        val tech = when (s.netGen) {
            "4G" -> "LTE"
            "5G" -> "NR"
            else -> s.netGen
        }
        return if (tech == "-") s.band else "$tech ${s.band}"
    }

    companion object {
        private const val REQ_PHONE_STATE = 1001

        fun formatSpeed(bytesPerSec: Long, locale: Locale): String = when {
            bytesPerSec < 1024 -> "$bytesPerSec B/s"
            bytesPerSec < 1024L * 1024L ->
                String.format(locale, "%.1f KB/s", bytesPerSec / 1024.0)
            else ->
                String.format(locale, "%.2f MB/s", bytesPerSec / (1024.0 * 1024.0))
        }
    }
}
