package com.denni.jamdigital

import android.net.TrafficStats
import android.os.Handler
import android.os.Looper

/**
 * Mengukur kecepatan trafik data (download/upload) secara realtime.
 * Memakai TrafficStats dan menghitung selisih byte tiap 1 detik,
 * sehingga angkanya sesuai dengan trafik aktual di perangkat.
 * Diutamakan trafik seluler (paket data); bila tidak didukung
 * perangkat, otomatis memakai total trafik.
 */
class TrafficMonitor(
    private val onUpdate: (rxBytesPerSec: Long, txBytesPerSec: Long) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var lastRx = -1L
    private var lastTx = -1L
    private var useTotal = false

    private val task = object : Runnable {
        override fun run() {
            if (!running) return
            val rx: Long
            val tx: Long
            if (useTotal) {
                rx = TrafficStats.getTotalRxBytes()
                tx = TrafficStats.getTotalTxBytes()
            } else {
                rx = TrafficStats.getMobileRxBytes()
                tx = TrafficStats.getMobileTxBytes()
                if (rx == TrafficStats.UNSUPPORTED.toLong()) {
                    useTotal = true
                }
            }
            if (rx >= 0 && tx >= 0 && lastRx >= 0 && lastTx >= 0 && rx >= lastRx) {
                onUpdate(rx - lastRx, (tx - lastTx).coerceAtLeast(0))
            }
            lastRx = rx
            lastTx = tx
            handler.postDelayed(this, 1000)
        }
    }

    fun start() {
        if (running) return
        running = true
        lastRx = -1
        lastTx = -1
        useTotal = false
        handler.post(task)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(task)
    }
}
