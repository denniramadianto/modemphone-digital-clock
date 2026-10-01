package com.denni.jamdigital

import android.app.Application
import android.content.Intent
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * Menangkap crash yang tidak tertangani lalu menampilkannya di CrashActivity
 * agar penyebab force close bisa dilaporkan (screenshot) oleh pengguna.
 */
class CrashApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                var cause = throwable.cause
                while (cause != null) {
                    sw.append("\nCaused by: ")
                    cause.printStackTrace(PrintWriter(sw))
                    cause = cause.cause
                }
                val report = "Thread: ${thread.name}\n" +
                    "Model: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                    "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n\n" +
                    sw.toString()
                // Simpan ke file agar bisa dibaca ulang
                try {
                    val f = File(getExternalFilesDir(null), "crash-terakhir.txt")
                    f.writeText(report)
                } catch (_: Exception) { }
                val i = Intent(this, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra("laporan", report)
                }
                startActivity(i)
            } catch (_: Exception) {
                defaultHandler?.uncaughtException(thread, throwable)
            }
            // Beri waktu CrashActivity tampil, lalu matikan proses yang rusak
            try { Thread.sleep(1500) } catch (_: Exception) { }
            Process.killProcess(Process.myPid())
            exitProcess(2)
        }
    }
}
