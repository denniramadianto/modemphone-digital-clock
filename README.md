# Jam Digital 7Seg

Aplikasi jam digital Android (mode **landscape**) dengan tampilan seven-segment,
konsepnya seperti `shg2.apk` ditambah fitur pemantauan sinyal & trafik data.

## Fitur

- **Jam digital seven-segment** lengkap dengan **detik** (`HH:MM:SS`), digambar
  langsung via custom view (tanpa file font eksternal) — presisi & tajam di semua ukuran layar.
- **Tanggal & hari format Indonesia** di bagian atas, contoh: `Kamis, 01 Oktober 2026`.
- **Sinyal SIM 1 & SIM 2 realtime**: bar sinyal 4 tingkat + nilai dBm + nama operator,
  dibaca langsung dari `TelephonyManager` per-SIM (bukan dummy).
- **Logo 4G/5G + kecepatan paket data**: badge generasi jaringan aktual + kecepatan
  **download (↓)** dan **upload (↑)** yang dihitung dari `TrafficStats` tiap 1 detik —
  sesuai trafik asli di perangkat.
- Background putih, tulisan seven-segment hitam. Layar selalu menyala & fullscreen
  (immersive) — cocok untuk jam meja.

## Izin yang dibutuhkan

- `READ_PHONE_STATE` — untuk membaca kekuatan sinyal & info jaringan tiap SIM.
  Aplikasi akan meminta izin ini saat pertama dibuka. Tanpa izin, info sinyal
  tidak dapat ditampilkan (jam & trafik tetap jalan).

## Cara build (Android Studio)

1. Buka Android Studio → **Open** → pilih folder `jam-digital-7seg`.
2. Tunggu Gradle sync selesai (butuh internet saat pertama kali).
3. **Run** ke HP (aktifkan USB debugging) atau **Build → Build APK(s)**.

APK debug akan ada di `app/build/outputs/apk/debug/app-debug.apk`.

## Cara install APK di HP

1. Salin file `.apk` ke HP.
2. Buka file-nya → izinkan **"Install aplikasi yang tidak dikenal"** bila diminta.
3. Buka aplikasi → setujui izin telepon → putar HP ke landscape (aplikasi otomatis landscape).

## Catatan teknis

- Sinyal: `TelephonyManager.createForSubscriptionId()` + `TelephonyCallback`
  (API 31+) / `PhoneStateListener` (API 26–30) per slot SIM.
- Trafik: `TrafficStats.getMobileRxBytes()/getMobileTxBytes()` per detik
  (fallback ke total bila perangkat tidak mendukung pemisahan trafik seluler).
- `minSdk 26` (Android 8.0), `targetSdk 34`.
