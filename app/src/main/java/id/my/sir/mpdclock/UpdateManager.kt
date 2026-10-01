package id.my.sir.mpdclock

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Update & kelola versi aplikasi lewat GitHub Releases.
 *
 * - Cek otomatis saat aplikasi dibuka (sekali per versi baru).
 * - Daftar semua versi: install versi mana pun (update maupun rollback).
 * - Rollback = downgrade: Android memblokir downgrade biasa, jadi di HP
 *   root dipakai "pm install -d" diam-diam; di HP non-root dipandu manual
 *   (APK disalin ke folder Download, lalu uninstall + install ulang).
 */
object UpdateManager {

    private const val REPO = "denniramadianto/modemphone-digital-clock"
    private const val API = "https://api.github.com/repos/$REPO/releases"
    private val main = Handler(Looper.getMainLooper())

    data class Release(
        val tag: String, // "v2.0"
        val publishedAt: String, // "2026-10-02T..."
        val apkUrl: String,
        val notes: String
    )

    /** Ambil daftar release dari GitHub (thread latar). null = gagal. */
    fun fetchReleases(cb: (List<Release>?) -> Unit) {
        Thread {
            try {
                val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "mpdclock-updater")
                }
                val body = conn.inputStream.bufferedReader().readText()
                val arr = JSONArray(body)
                val out = mutableListOf<Release>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val assets = o.optJSONArray("assets") ?: continue
                    var apk: String? = null
                    for (j in 0 until assets.length()) {
                        val url = assets.getJSONObject(j)
                            .optString("browser_download_url")
                        if (url.endsWith(".apk")) {
                            apk = url
                            break
                        }
                    }
                    if (apk == null) continue
                    out.add(
                        Release(
                            tag = o.optString("tag_name"),
                            publishedAt = o.optString("published_at"),
                            apkUrl = apk,
                            notes = o.optString("body")
                        )
                    )
                }
                main.post { cb(out) }
            } catch (_: Exception) {
                main.post { cb(null) }
            }
        }.start()
    }

    /** Bandingkan versi semantik: >0 bila a lebih baru dari b. */
    fun compareVer(a: String, b: String): Int {
        val pa = a.trimStart('v', 'V').split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.trimStart('v', 'V').split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    fun installedVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /** Cek otomatis saat aplikasi dibuka; tawarkan update bila ada versi baru. */
    fun checkForUpdate(activity: Activity, auto: Boolean) {
        val ctx = activity.applicationContext
        fetchReleases { releases ->
            if (releases.isNullOrEmpty() || activity.isFinishing) return@fetchReleases
            val latest = releases.maxWithOrNull { x, y -> compareVer(x.tag, y.tag) }
                ?: return@fetchReleases
            val installed = installedVersion(ctx)
            if (compareVer(latest.tag, installed) <= 0) return@fetchReleases
            if (auto) {
                val prefs = ctx.getSharedPreferences("mpdclock", Context.MODE_PRIVATE)
                if (prefs.getBoolean("update_seen_${latest.tag}", false)) return@fetchReleases
                prefs.edit().putBoolean("update_seen_${latest.tag}", true).apply()
            }
            AlertDialog.Builder(activity)
                .setTitle("Update tersedia: ${latest.tag}")
                .setMessage(
                    "Versi baru ModemPhone Digital Clock tersedia.\n\n" +
                        "Terpasang: v$installed → Baru: ${latest.tag}\n\n" +
                        latest.notes.take(300)
                )
                .setPositiveButton("Update") { _, _ ->
                    downloadAndInstall(activity, latest)
                }
                .setNegativeButton("Nanti", null)
                .show()
        }
    }

    /** Dialog daftar semua versi: install versi mana pun (update / rollback). */
    fun showVersionList(activity: Activity) {
        val loading = AlertDialog.Builder(activity)
            .setTitle("Memuat daftar versi…")
            .setView(ProgressBar(activity).apply { isIndeterminate = true })
            .setCancelable(false)
            .create()
        loading.show()
        fetchReleases { releases ->
            loading.dismiss()
            if (activity.isFinishing) return@fetchReleases
            if (releases == null) {
                Toast.makeText(
                    activity,
                    "Gagal memuat daftar versi (periksa internet)",
                    Toast.LENGTH_LONG
                ).show()
                return@fetchReleases
            }
            val installed = installedVersion(activity)
            val sorted = releases.sortedWith { x, y -> -compareVer(x.tag, y.tag) }
            val list = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 16, 48, 16)
            }
            for (r in sorted) {
                val cmp = compareVer(r.tag, installed)
                val status = when {
                    cmp == 0 -> "\n(Terpasang)"
                    cmp > 0 -> "\n(Baru)"
                    else -> "\n(Lebih lama)"
                }
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 12, 0, 12)
                    addView(TextView(activity).apply {
                        text = "${r.tag}\n${r.publishedAt.take(10)}$status"
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                    })
                    if (cmp != 0) addView(Button(activity).apply {
                        text = if (cmp > 0) "Update" else "Rollback"
                        setOnClickListener { downloadAndInstall(activity, r) }
                    })
                }
                list.addView(row)
            }
            AlertDialog.Builder(activity)
                .setTitle("Kelola versi")
                .setView(ScrollView(activity).apply { addView(list) })
                .setPositiveButton("Muat ulang") { _, _ -> showVersionList(activity) }
                .setNegativeButton("Tutup", null)
                .show()
        }
    }

    /** Download APK dengan progress bar, lalu install / rollback. */
    fun downloadAndInstall(activity: Activity, r: Release) {
        val installed = installedVersion(activity)
        val isRollback = compareVer(r.tag, installed) < 0

        val bar = ProgressBar(
            activity, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            setPadding(48, 24, 48, 24)
        }
        val dlg = AlertDialog.Builder(activity)
            .setTitle(if (isRollback) "Mengunduh ${r.tag} (rollback)…" else "Mengunduh ${r.tag}…")
            .setView(bar)
            .setCancelable(false)
            .create()
        dlg.show()

        Thread {
            try {
                val dir = File(activity.getExternalFilesDir(null), "updates")
                    .apply { mkdirs() }
                val out = File(dir, "mpdclock-${r.tag}.apk")
                val conn = (URL(r.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "mpdclock-updater")
                    instanceFollowRedirects = true
                }
                val total = conn.contentLength
                conn.inputStream.use { inp ->
                    out.outputStream().use { oup ->
                        val buf = ByteArray(32768)
                        var done = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            oup.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                main.post { bar.progress = pct }
                            }
                        }
                    }
                }
                main.post {
                    dlg.dismiss()
                    installApk(activity, out, isRollback)
                }
            } catch (e: Exception) {
                main.post {
                    dlg.dismiss()
                    Toast.makeText(
                        activity, "Download gagal: ${e.message}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun installApk(activity: Activity, apk: File, isRollback: Boolean) {
        // Android 8+: wajib izin "install aplikasi tidak dikenal" untuk aplikasi ini
        if (Build.VERSION.SDK_INT >= 26 &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            AlertDialog.Builder(activity)
                .setTitle("Izin install diperlukan")
                .setMessage(
                    "Aktifkan \"Izinkan dari sumber ini\" untuk ModemPhone " +
                        "Digital Clock di layar berikutnya, lalu ulangi."
                )
                .setPositiveButton("Buka pengaturan") { _, _ ->
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        )
                    )
                }
                .setNegativeButton("Batal", null)
                .show()
            return
        }

        // Rollback = downgrade. Di HP root: "pm install -d" diam-diam.
        if (isRollback && tryRootDowngrade(apk)) {
            Toast.makeText(activity, "Rollback berhasil", Toast.LENGTH_LONG).show()
            return
        }
        // HP non-root: Android memblokir downgrade langsung -> pandu manual.
        // APK disalin dulu ke folder Download agar tidak ikut terhapus
        // saat aplikasi di-uninstall.
        if (isRollback) {
            val saved = copyToDownloads(activity, apk)
            AlertDialog.Builder(activity)
                .setTitle("Rollback manual diperlukan")
                .setMessage(
                    "Android tidak mengizinkan downgrade langsung di HP non-root.\n\n" +
                        (if (saved) "APK ${apk.name} sudah disalin ke folder Download.\n\n"
                        else "Gagal menyalin APK ke Download — download manual dari GitHub Releases.\n\n") +
                        "Langkahnya:\n" +
                        "1. Uninstall aplikasi ini\n" +
                        "2. Buka file APK di folder Download untuk install ulang\n\n" +
                        "Catatan: pengaturan (tema & suara) akan kembali default."
                )
                .setPositiveButton("Uninstall sekarang") { _, _ ->
                    activity.startActivity(
                        Intent(
                            Intent.ACTION_DELETE,
                            Uri.parse("package:${activity.packageName}")
                        )
                    )
                }
                .setNegativeButton("Batal", null)
                .show()
            return
        }

        try {
            val uri = Uri.parse("content://id.my.sir.mpdclock.apkprovider/${apk.name}")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                activity, "Gagal membuka installer: ${e.message}", Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Downgrade diam-diam via root ("pm install -d"). True bila sukses. */
    private fun tryRootDowngrade(apk: File): Boolean {
        return try {
            val p = Runtime.getRuntime()
                .exec(arrayOf("su", "-c", "pm install -d -r ${apk.absolutePath}"))
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    /** Salin APK ke folder Download publik (tetap ada setelah uninstall). */
    private fun copyToDownloads(ctx: Context, apk: File): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, apk.name)
                    put(
                        MediaStore.Downloads.MIME_TYPE,
                        "application/vnd.android.package-archive"
                    )
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS
                    )
                }
                val uri = ctx.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return false
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                }
                true
            } else {
                @Suppress("DEPRECATION")
                val dest = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    ),
                    apk.name
                )
                apk.copyTo(dest, overwrite = true)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
