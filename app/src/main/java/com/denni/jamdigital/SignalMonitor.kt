package com.denni.jamdigital

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrength
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import java.util.concurrent.Executor

/**
 * Memantau kekuatan sinyal tiap SIM secara realtime memakai TelephonyManager.
 * Untuk setiap langganan aktif dibuat TelephonyManager per-subscriptionId
 * sehingga SIM 1 dan SIM 2 terbaca terpisah sesuai kondisi aktual perangkat.
 * Juga membaca band sel utama (mis. "B40" / "n78") lewat CellInfo.
 */
class SignalMonitor(
    private val context: Context,
    private val onUpdate: (List<SimSignal>) -> Unit
) {

    data class SimSignal(
        val label: String,    // "SIM 1" / "SIM 2"
        val carrier: String,  // nama operator
        val level: Int,       // 0..4, -1 = belum diketahui
        val dbm: Int,         // dBm, Int.MIN_VALUE = tidak diketahui
        val netGen: String,   // "5G"/"4G"/"3G"/"2G"
        val band: String      // "B40"/"n78", "-" = tidak diketahui
    )

    private val telephony: TelephonyManager =
        context.getSystemService(TelephonyManager::class.java)
    private val mainExecutor: Executor = Executor { Handler(Looper.getMainLooper()).post(it) }
    private val handler = Handler(Looper.getMainLooper())
    private val unregisterCallbacks = mutableListOf<() -> Unit>()
    private var bandRunnable: Runnable? = null

    @SuppressLint("MissingPermission")
    fun start() {
        stop()
        val subs: List<android.telephony.SubscriptionInfo> = try {
            val sm = context.getSystemService(SubscriptionManager::class.java)
            sm.activeSubscriptionInfoList ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val targets = subs.filter { it.simSlotIndex in 0..1 }
            .sortedBy { it.simSlotIndex }
            .take(2)

        if (targets.isEmpty()) {
            onUpdate(emptyList())
            return
        }

        val results = mutableMapOf<Int, SimSignal>()
        val bandRefreshers = mutableListOf<() -> Unit>()
        fun publish() {
            onUpdate(results.values.sortedBy { it.label })
        }

        for (info in targets) {
            try {
            val label = "SIM ${info.simSlotIndex + 1}"
            val carrier = info.carrierName?.toString()?.trim().takeIf { !it.isNullOrEmpty() } ?: "-"
            val tm = telephony.createForSubscriptionId(info.subscriptionId)

            val update: (SignalStrength) -> Unit = { ss ->
                val level = try { ss.level } catch (e: Exception) { -1 }
                val dbm = extractDbm(ss)
                val netGen = try { netGen(tm.dataNetworkType) } catch (e: Exception) { "-" }
                val prev = results[info.simSlotIndex]
                // Band dibaca ulang di sini juga agar ikut terisi/terbaru
                val band = extractBand(tm).takeIf { it != "-" } ?: prev?.band ?: "-"
                results[info.simSlotIndex] = SimSignal(label, carrier, level, dbm, netGen, band)
                mainExecutor.execute { publish() }
            }

            // Band di-refresh tiap ada perubahan sinyal DAN berkala (tiap 15 dtk).
            // Sengaja TIDAK memakai onCellInfoChanged: di sebagian perangkat
            // (mis. Xiaomi Android 10) framework mengirim cellInfo = null dan
            // Kotlin melempar NPE pada parameter non-null sebelum kode berjalan.
            val refreshBand: () -> Unit = {
                val band = extractBand(tm)
                val cur = results[info.simSlotIndex]
                if (cur != null && cur.band != band) {
                    results[info.simSlotIndex] = cur.copy(band = band)
                    mainExecutor.execute { publish() }
                }
            }

            if (Build.VERSION.SDK_INT >= 31) {
                val cb = object : TelephonyCallback(),
                    TelephonyCallback.SignalStrengthsListener {
                    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                        update(signalStrength)
                    }
                }
                tm.registerTelephonyCallback(mainExecutor, cb)
                unregisterCallbacks.add {
                    try { tm.unregisterTelephonyCallback(cb) } catch (e: Exception) { /* abaikan */ }
                }
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                        update(signalStrength)
                    }
                }
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
                unregisterCallbacks.add {
                    try {
                        @Suppress("DEPRECATION")
                        tm.listen(listener, PhoneStateListener.LISTEN_NONE)
                    } catch (e: Exception) { /* abaikan */ }
                }
            }

            bandRefreshers.add(refreshBand)
            results[info.simSlotIndex] =
                SimSignal(label, carrier, -1, Int.MIN_VALUE, "-", extractBand(tm))
            } catch (e: Exception) {
                // Satu SIM bermasalah jangan menggugurkan semuanya
                results[info.simSlotIndex] =
                    SimSignal("SIM ${info.simSlotIndex + 1}", "-", -1, Int.MIN_VALUE, "-", "-")
            }
        }
        // Refresh band berkala agar ikut terbaru saat pindah sel
        val periodic = object : Runnable {
            override fun run() {
                bandRefreshers.forEach { try { it() } catch (e: Exception) { /* abaikan */ } }
                handler.postDelayed(this, 15_000)
            }
        }
        bandRunnable = periodic
        handler.postDelayed(periodic, 15_000)
        publish()
    }

    fun stop() {
        bandRunnable?.let { handler.removeCallbacks(it) }
        bandRunnable = null
        unregisterCallbacks.forEach { try { it() } catch (e: Exception) { /* abaikan */ } }
        unregisterCallbacks.clear()
    }

    private fun extractDbm(ss: SignalStrength): Int {
        // getCellSignalStrengths() hanya ada sejak API 29
        if (Build.VERSION.SDK_INT < 29) return Int.MIN_VALUE
        return try {
            val strengths: List<CellSignalStrength> = ss.cellSignalStrengths
            strengths.firstOrNull { it.dbm != Int.MAX_VALUE }?.dbm ?: Int.MIN_VALUE
        } catch (e: Exception) {
            Int.MIN_VALUE
        }
    }

    /**
     * Band sel utama yang sedang dipakai, mis. "B40" (LTE) atau "n78" (NR).
     * Butuh izin lokasi; tanpa izin mengembalikan "-".
     * Catatan: Android tidak mengekspos carrier aggregation (mis. "B40+B1")
     * ke aplikasi biasa, jadi yang tampil hanya band utama.
     */
    private fun extractBand(tm: TelephonyManager): String {
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                val infos = tm.allCellInfo ?: return earfcnBand(tm)
                val reg = infos.firstOrNull { it.isRegistered } ?: return "-"
                when (reg) {
                    is CellInfoLte -> {
                        val bands = (reg.cellIdentity as CellIdentityLte).bands
                        if (bands.isNotEmpty()) "B" + bands.joinToString("+B")
                        else earfcnBand(tm)
                    }
                    is CellInfoNr -> {
                        val bands = (reg.cellIdentity as CellIdentityNr).bands
                        if (bands.isNotEmpty()) "n" + bands.joinToString("+n") else "-"
                    }
                    else -> "-"
                }
            } else {
                earfcnBand(tm)
            }
        } catch (e: SecurityException) {
            "-" // izin lokasi belum diberikan
        } catch (e: Exception) {
            "-"
        }
    }

    /** Fallback API 24-29: petakan EARFCN sel LTE terdaftar ke nomor band. */
    private fun earfcnBand(tm: TelephonyManager): String {
        if (Build.VERSION.SDK_INT < 24) return "-"
        return try {
            val infos = tm.allCellInfo ?: return "-"
            val reg = infos.firstOrNull { it.isRegistered } as? CellInfoLte ?: return "-"
            val earfcn = (reg.cellIdentity as CellIdentityLte).earfcn
            lteBandFromEarfcn(earfcn)?.let { "B$it" } ?: "-"
        } catch (e: SecurityException) {
            "-"
        } catch (e: Exception) {
            "-"
        }
    }

    /** Rentang EARFCN downlink -> nomor band LTE (yang umum dipakai). */
    private fun lteBandFromEarfcn(earfcn: Int): Int? = when (earfcn) {
        in 0..599 -> 1
        in 600..1199 -> 2
        in 1200..1949 -> 3
        in 1950..2399 -> 4
        in 2400..2649 -> 5
        in 2750..3449 -> 7
        in 3450..3799 -> 8
        in 6150..6449 -> 20
        in 9210..9659 -> 28
        in 37750..38249 -> 38
        in 38250..38649 -> 39
        in 38650..39649 -> 40
        in 39650..41589 -> 41
        else -> null
    }

    private fun netGen(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G"
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
        else -> "-"
    }
}
