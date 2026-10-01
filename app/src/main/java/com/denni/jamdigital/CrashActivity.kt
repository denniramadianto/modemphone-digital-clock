package com.denni.jamdigital

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Menampilkan laporan crash agar bisa di-screenshot / disalin ke pengembang. */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val laporan = intent.getStringExtra("laporan") ?: "Tidak ada detail."

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val judul = TextView(this).apply {
            text = "Aplikasi berhenti (laporan error)"
            textSize = 18f
            setTextColor(0xFF000000.toInt())
            gravity = Gravity.CENTER
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val isi = TextView(this).apply {
            text = laporan
            textSize = 12f
            setTextColor(0xFF000000.toInt())
            setTextIsSelectable(true)
        }
        scroll.addView(isi)
        val salin = Button(this).apply { text = "Salin laporan" }
        salin.setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("laporan-crash", laporan))
            Toast.makeText(this, "Laporan disalin", Toast.LENGTH_SHORT).show()
        }
        root.addView(judul)
        root.addView(scroll)
        root.addView(salin)
        setContentView(root)
    }
}
