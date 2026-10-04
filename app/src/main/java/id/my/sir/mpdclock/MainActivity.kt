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
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.telephony.TelephonyManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
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
    private lateinit var dividerTop: View
    private lateinit var rootFrame: FrameLayout

    // Tema aktif
    private var themeIdx = 0
    private lateinit var cur: ClockTheme

    private val handler = Handler(Looper.getMainLooper())
    private val localeId = Locale("in", "ID")
    private var lastDayKey = ""
    private var lastChimeKey = ""

    // Status badge jaringan: sinyal aktual & koneksi internet nyata.
    // Diperbarui oleh onSignalUpdate (tiap ada perubahan sinyal) dan
    // netCheckTask (cek HTTP generate_204 tiap 10 detik di thread latar).
    @Volatile private var netHasSignal = false
    @Volatile private var netGen: String? = null   // "5G"/"4G"/"3G"/"2G" terakhir yang terdeteksi
    @Volatile private var internetOk = false

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

    // Daftar nada beep yang bisa dipilih (ToneGenerator) + label Indonesianya
    private val toneOptions = listOf(
        ToneGenerator.TONE_CDMA_HIGH_L to "Tiiit tinggi panjang",
        ToneGenerator.TONE_CDMA_PIP to "Pip pendek",
        ToneGenerator.TONE_PROP_BEEP to "Beep",
        ToneGenerator.TONE_PROP_PROMPT to "Prompt",
        ToneGenerator.TONE_CDMA_ONE_MIN_BEEP to "Beep satu menit",
        ToneGenerator.TONE_SUP_DIAL to "Nada dial",
        ToneGenerator.TONE_SUP_BUSY to "Nada sibuk",
        ToneGenerator.TONE_DTMF_5 to "Nada DTMF"
    )

    private fun prefs() = getSharedPreferences("mpdclock", Context.MODE_PRIVATE)

    private fun beep(type: Int) {
        try {
            toneGen?.startTone(type, 650)
        } catch (_: Exception) {
        }
    }

    /** Tepat HH:00: beep sejumlah jam (format 12 jam) dengan nada pilihan, lalu pengumuman jam. */
    private fun hourlyChime(hour12: Int) {
        val toneHour = prefs().getInt("tone_hour", ToneGenerator.TONE_CDMA_HIGH_L)
        var delay = 0L
        repeat(hour12.coerceIn(1, 12)) {
            val d = delay
            handler.postDelayed({ beep(toneHour) }, d)
            delay += 950L
        }
        handler.postDelayed({ announceHour(hour12) }, delay + 300L)
    }

    /** Pengumuman jam sesuai mode: file suara bawaan (default), TTS sistem, atau mati. */
    private fun announceHour(hour12: Int) {
        when (prefs().getString("voice_mode", "file")) {
            "tts" -> speakTts("Sekarang pukul $hour12")
            "off" -> { /* diam */ }
            else -> playVoiceFile(hour12)
        }
    }

    /**
     * Putar file suara bawaan res/raw/pukul_<1-12>.mp3 via stream ALARM.
     * Tidak tergantung TTS sistem, jadi tetap bunyi di custom ROM yang
     * TTS-nya bermasalah.
     */
    private fun playVoiceFile(hour12: Int) {
        try {
            val resId = resources.getIdentifier("pukul_$hour12", "raw", packageName)
            if (resId == 0) return
            val afd = resources.openRawResourceFd(resId) ?: return
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.prepare()
            mp.setOnCompletionListener { it.release() }
            mp.start()
        } catch (_: Exception) {
        }
    }

    private fun speakTts(text: String) {
        try {
            // Paksa lewat stream ALARM agar tetap bunyi walau media dimute
            val params = Bundle().apply {
                putString(
                    TextToSpeech.Engine.KEY_PARAM_STREAM,
                    AudioManager.STREAM_ALARM.toString()
                )
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "hourly_chime")
        } catch (_: Exception) {
        }
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
                        30 -> beep(prefs().getInt("tone_half", ToneGenerator.TONE_CDMA_HIGH_L))
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
        tv.setTextColor(if (active) cur.text else cur.subText)
    }

    /**
     * Ikat semua view dari layout yang sedang aktif + pasang gesture.
     * Dipanggil tiap ganti layout (ganti tema).
     */
    private fun bindViews() {
        rootFrame = findViewById(R.id.rootFrame)
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
        dividerTop = findViewById(R.id.dividerTop)

        // Tekan lama jam -> buka daftar aplikasi di Pengaturan
        clockBox.setOnLongClickListener { openAppSettings(); true }
        // Gesture pada jam: ketuk 2x -> pengaturan, swipe kiri/kanan -> ganti tema
        val tapDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    openSettings()
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    val startX = e1?.x ?: return false
                    val dx = e2.x - startX
                    val dy = e2.y - (e1.y)
                    if (kotlin.math.abs(dx) > kotlin.math.abs(dy) &&
                        kotlin.math.abs(dx) > 120 &&
                        kotlin.math.abs(velocityX) > 200
                    ) {
                        // Swipe kiri -> tema berikutnya, swipe kanan -> sebelumnya
                        switchTheme(themeIdx + if (dx < 0) 1 else -1)
                        return true
                    }
                    return false
                }
            }
        )
        clockBox.setOnTouchListener { _, event ->
            tapDetector.onTouchEvent(event)
            false // jangan konsumsi agar long-press tetap berfungsi
        }
        // Tekan lama suhu -> tampilkan daftar thermal zones (diagnostik)
        tempText.setOnLongClickListener { showThermalZones(); true }
    }

    /** Terapkan warna tema aktif ke semua view. */
    private fun applyTheme() {
        val t = cur
        rootFrame.setBackgroundColor(t.bg)
        for (v in listOf(hourView, minView, secView)) {
            v.segmentOnColor = t.digitOn
            v.segmentOffColor = t.digitOff
            v.invalidate()
        }
        colonView.setTextColor(t.digitOn)
        sim1Bars.barOnColor = t.digitOn
        sim1Bars.barOffColor = t.digitOff
        sim1Bars.invalidate()
        sim2Bars.barOnColor = t.digitOn
        sim2Bars.barOffColor = t.digitOff
        sim2Bars.invalidate()
        for (tv in listOf(
            dlSpeed, ulSpeed, dataUsageText,
            sim1Label, sim1Dbm, sim1Band, sim2Label, sim2Dbm, sim2Band,
            dayNameText, dateDayText, dateMonthText, dateYearText
        )) tv.setTextColor(t.text)
        netBadge.setTextColor(t.text)
        tempText.setTextColor(t.text)
        netBadge.background = themedBadge(t)
        tempText.background = themedBadge(t)
        // Terapkan ulang status badge (hijau/merah/X) di atas styling tema
        refreshNetBadge()
        // Kotak tanggal: satu frame full-width (hanya ada di layout Klasik)
        (findViewById<View>(R.id.dateBox))?.let { box ->
            box.background = themedBox(t)
            (box as? ViewGroup)?.let { vg ->
                for (i in 0 until vg.childCount) {
                    (vg.getChildAt(i) as? TextView)?.setTextColor(t.text)
                }
            }
        }
        dividerTop.setBackgroundColor(t.divider)
        // Segarkan status AM/PM dengan warna tema
        val isAm = java.util.Calendar.getInstance().get(java.util.Calendar.AM_PM) ==
            java.util.Calendar.AM
        setAmPmActive(amText, isAm)
        setAmPmActive(pmText, !isAm)
    }

    /** Badge outline (untuk netBadge & tempText) mengikuti warna tema. */
    private fun themedBadge(t: ClockTheme): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(t.boxFill)
            setStroke(2, t.boxStroke)
            cornerRadius = 8f
        }

    /** Kotak tanggal mengikuti warna tema. */
    private fun themedBox(t: ClockTheme): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(t.boxFill)
            setStroke(3, t.boxStroke)
            cornerRadius = 12f
        }

    /** Ganti tema: pasang layout baru, bind ulang, terapkan warna. */
    private fun switchTheme(idx: Int) {
        themeIdx = (idx + THEMES.size) % THEMES.size
        cur = THEMES[themeIdx]
        prefs().edit().putInt("theme_idx", themeIdx).apply()
        setContentView(cur.layout)
        bindViews()
        applyTheme()
        // hideSystemBars() HARUS setelah setContentView (DecorView baru).
        hideSystemBars()
        Toast.makeText(this, "Tema: ${cur.name}", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Layar tetap menyala nonstop selama aplikasi tampil
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Muat tema tersimpan lalu pasang layout-nya
        themeIdx = prefs().getInt("theme_idx", 0).coerceIn(THEMES.indices)
        cur = THEMES[themeIdx]
        setContentView(cur.layout)
        // hideSystemBars() HARUS setelah setContentView: di Android 16,
        // window.insetsController butuh DecorView yang baru ada setelah ini.
        // Dipanggil sebelumnya -> NullPointerException (force close).
        hideSystemBars()
        bindViews()
        applyTheme()

        initChime()
        promptUsageAccessOnce()

        signalMonitor = SignalMonitor(this, ::onSignalUpdate)
        // Cek pembaruan aplikasi di GitHub (sekali per versi baru)
        UpdateManager.checkForUpdate(this, auto = true)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
        handler.post(cpuTempTask)
        trafficMonitor.start()
        ensurePhonePermission()
        // Cek koneksi internet nyata tiap 10 detik (badge hijau/merah)
        handler.removeCallbacks(netCheckTask)
        handler.post(netCheckTask)
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
        handler.removeCallbacks(netCheckTask)
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

        // Badge dinamis: ada sinyal = level/dBm/tipe jaringan/band terbaca
        // di salah satu SIM; label memakai generasi jaringan dari SIM
        // pertama yang terdeteksi tipenya (5G/4G/3G/2G asli).
        val sims = listOfNotNull(s1, s2)
        netHasSignal = sims.any {
            it.level >= 0 || it.dbm != Int.MIN_VALUE || it.netGen != "-" || it.band != "-"
        }
        sims.firstOrNull { it.netGen != "-" }?.netGen?.let { netGen = it }
        refreshNetBadge()
    }

    /**
     * Badge jaringan dinamis, dipanggil dari onSignalUpdate dan netCheckTask:
     * - tidak ada sinyal sama sekali -> "X" merah
     * - ada sinyal + internet tembus -> label tipe asli (5G/4G/3G/2G), hijau
     * - ada sinyal tapi internet tidak tembus -> label tipe asli, merah
     */
    private fun refreshNetBadge() {
        if (!::netBadge.isInitialized) return
        netBadge.text = if (netHasSignal) (netGen ?: "-") else "X"
        val connected = netHasSignal && internetOk
        val fill = if (connected) 0xFF2E7D32.toInt() else 0xFFC62828.toInt()
        (netBadge.background as? android.graphics.drawable.GradientDrawable)?.setColor(fill)
        netBadge.setTextColor(android.graphics.Color.WHITE)
    }

    /**
     * Uji koneksi internet nyata: HTTP ke generate_204 (cara standar Android
     * memastikan internet benar-benar tembus, bukan sekadar "ada jaringan").
     * Dijalankan di thread latar oleh netCheckTask tiap 10 detik.
     */
    private fun checkInternetReachable(): Boolean {
        return try {
            val cm = getSystemService(ConnectivityManager::class.java)
            if (cm.activeNetwork == null) return false
            val conn = java.net.URL("https://www.google.com/generate_204")
                .openConnection() as javax.net.ssl.HttpsURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            val code = conn.responseCode
            conn.disconnect()
            code == 204 || code == 200
        } catch (e: Exception) {
            false
        }
    }

    private val netCheckTask = object : Runnable {
        override fun run() {
            Thread {
                internetOk = checkInternetReachable()
                handler.post { refreshNetBadge() }
            }.apply { isDaemon = true }.start()
            handler.postDelayed(this, 10_000)
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

    /**
     * Popup pengaturan (dibuka dengan ketuk dua kali pada jam):
     * - nada beep lonceng tiap jam (:00), dengan tombol dengar contoh
     * - nada beep tiap setengah jam (:30), dengan tombol dengar contoh
     * - mode pengumuman jam: file suara bawaan / TTS sistem / mati
     * - versi aplikasi: kelola versi (update / rollback)
     */
    private fun openSettings() {
        val p = prefs()
        val toneLabels = toneOptions.map { it.second }
        val toneValues = toneOptions.map { it.first }

        fun sectionLabel(text: String, topPad: Int): TextView =
            TextView(this).apply {
                this.text = text
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, topPad, 0, 8)
            }

        fun toneRow(selected: Int): Pair<LinearLayout, Spinner> {
            val spinner = Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_item,
                    toneLabels
                ).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                setSelection(toneValues.indexOf(selected).takeIf { it >= 0 } ?: 0)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    spinner,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(Button(this@MainActivity).apply {
                    text = "Dengar"
                    setOnClickListener {
                        beep(toneValues[spinner.selectedItemPosition])
                    }
                })
            }
            return row to spinner
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }

        layout.addView(sectionLabel("Nada lonceng tiap jam (:00)", 0))
        val (rowHour, spHour) =
            toneRow(p.getInt("tone_hour", ToneGenerator.TONE_CDMA_HIGH_L))
        layout.addView(rowHour)

        layout.addView(sectionLabel("Nada tiap setengah jam (:30)", 32))
        val (rowHalf, spHalf) =
            toneRow(p.getInt("tone_half", ToneGenerator.TONE_CDMA_HIGH_L))
        layout.addView(rowHalf)

        layout.addView(sectionLabel("Pengumuman jam", 32))
        val rbFile = RadioButton(this).apply {
            id = View.generateViewId()
            text = "File suara bawaan (disarankan)"
        }
        val rbTts = RadioButton(this).apply {
            id = View.generateViewId()
            text = "TTS sistem"
        }
        val rbOff = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Mati"
        }
        val rg = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(rbFile)
            addView(rbTts)
            addView(rbOff)
            check(
                when (p.getString("voice_mode", "file")) {
                    "tts" -> rbTts.id
                    "off" -> rbOff.id
                    else -> rbFile.id
                }
            )
        }
        layout.addView(rg)
        layout.addView(TextView(this).apply {
            text = "File suara bawaan tidak tergantung TTS sistem, " +
                "jadi tetap bunyi di custom ROM yang TTS-nya bermasalah."
            setPadding(0, 8, 0, 0)
        })

        // --- Versi aplikasi ---
        layout.addView(sectionLabel("Versi aplikasi", 32))
        val installedVer = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        val verRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Terpasang: v$installedVer"
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            addView(Button(this@MainActivity).apply {
                text = "Kelola versi…"
                setOnClickListener { openVersionList() }
            })
        }
        layout.addView(verRow)

        val scroll = ScrollView(this).apply { addView(layout) }

        AlertDialog.Builder(this)
            .setTitle("Pengaturan")
            .setView(scroll)
            .setPositiveButton("Simpan") { _, _ ->
                p.edit()
                    .putInt("tone_hour", toneValues[spHour.selectedItemPosition])
                    .putInt("tone_half", toneValues[spHalf.selectedItemPosition])
                    .putString(
                        "voice_mode",
                        when (rg.checkedRadioButtonId) {
                            rbTts.id -> "tts"
                            rbOff.id -> "off"
                            else -> "file"
                        }
                    )
                    .apply()
                Toast.makeText(this, "Pengaturan suara disimpan", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Buka dialog daftar versi (update / rollback). */
    private fun openVersionList() = UpdateManager.showVersionList(this)

    /**
     * Tekan lama jam: buka daftar semua aplikasi di Pengaturan.
     * Ini jalan pintas membuka aplikasi lain karena aplikasi ini menjadi
     * launcher/Home sehingga tidak ada laci aplikasi.
     */
    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS))
            } catch (_: Exception) {
            }
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
