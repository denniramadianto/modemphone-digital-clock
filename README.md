# ModemPhone Digital Clock

Jam digital Android mode **landscape** dengan digit seven-segment besar —
dirancang sebagai **jam meja / jam modem**: dipasang di HP yang dijadikan
modem 24 jam (atau sekadar jam dinding digital), lengkap dengan pemantauan
sinyal, trafik data, dan suhu CPU secara realtime.

Tampilan: background putih, digit hitam, fullscreen imersif, layar selalu menyala.

---

## ✨ Fitur

### 🕰️ Jam & tanggal
- **Jam seven-segment besar** format 12 jam (`HH:MM:SS`) + indikator **AM/PM** —
  digit digambar langsung via custom view (`SegmentDisplayView`), tajam di semua
  ukuran layar tanpa file font eksternal.
- **Titik dua (:) berdetak** setiap detik; detik ditampilkan lebih kecil agar
  jam & menit tetap dominan.
- **Tanggal format Indonesia** di bar bawah, rata tengah: nama hari (font biasa,
  bold) + kotak tanggal/bulan/tahun berfont DSEG seven-segment.

### 📶 Sinyal SIM 1 & SIM 2 (realtime, bukan dummy)
- **6 bar sinyal** dipetakan dari dBm aktual (≥ -75 dBm = 6 bar penuh).
- Nilai **dBm**, **nama operator**, dan **band sel utama** (mis. `LTE B40`, `NR n78`).
- Dibaca per slot SIM via `TelephonyManager` (`TelephonyCallback` di API 31+,
  `PhoneStateListener` di bawahnya) — pemetaan berdasarkan **nomor slot**, jadi
  tetap benar walau yang aktif hanya slot SIM 2.
- **Badge generasi jaringan dinamis**: label mengikuti tipe jaringan asli
  (5G/4G/3G/2G), tampil **"X"** kalau tidak ada sinyal sama sekali, berwarna
  **hijau** jika internet benar-benar tembus (cek HTTP `generate_204` tiap
  10 detik) dan **merah** jika tidak.

### 📊 Trafik & pemakaian data
- Kecepatan **download (↓)** dan **upload (↑)** dihitung tiap 1 detik dari
  `TrafficStats`.
- **LED hijau/kuning** menyala saat ada aktivitas download/upload.
- **Data X.XX GB**: total pemakaian data seluler **bulanan** (tanggal 1 s.d.
  sekarang, tidak reset saat HP restart) via `NetworkStatsManager`.
  Butuh izin *Akses penggunaan* (diminta sekali saat pertama dibuka);
  bila belum diberikan, fallback ke `TrafficStats`.

### 🌡️ Suhu CPU
- Dibaca dari `/sys/class/thermal` tiap 5 detik di thread latar — dibuat untuk
  HP **tanpa baterai** (bypass/step-down + root Magisk) yang suhunya tidak bisa
  dibaca dari sensor baterai.
- Mengambil nilai tertinggi dari zona bertipe `cpu`; tampil `--°C` bila gagal.
- **Tekan-lama kotak suhu** → dialog diagnostik berisi daftar semua thermal
  zone + suhunya masing-masing.

### 🔔 Lonceng tiap jam
- Tepat `HH:00` → bunyi *beep* sejumlah jam (format 12 jam), lalu suara
  **"Sekarang pukul N"** (file suara bawaan / TTS / mati — bisa dipilih).
- Tepat `HH:30` → satu bunyi *beep*.
- Nada `:00` dan `:30` bisa dipilih sendiri (8 nada) + tombol **Dengar** contoh.
- **Ketuk 2× jam** → popup **Pengaturan** (suara + sinkron NTP + kelola versi aplikasi).

### 🛰️ Sinkron jam NTP otomatis (butuh root)
- Mengambil jam akurat dari internet (SNTP, `time.google.com`) **tiap 30 menit**,
  lalu mengatur jam sistem via `su` bila selisih > 30 detik.
- Mematikan **waktu otomatis** bawaan Android agar waktu NITZ operator yang
  meleset tidak menimpa hasil koreksi (dikembalikan saat fitur dimatikan).
- Status sinkron terakhir + tombol **Sinkronkan sekarang** ada di popup
  Pengaturan (ketuk 2× jam). Dibuat untuk HP yang jamnya sering meleset
  beberapa menit (NITZ operator tidak akurat + tanpa baterai).

### 🎨 Tema (8 pilihan, swipe kiri/kanan pada jam)
| Tema | Layout | Warna |
|---|---|---|
| Siang Klasik | Klasik | Putih, digit hitam |
| Malam Hijau | Penuh (jam raksasa) | Hitam, digit hijau LED |
| Alarm Merah | Klasik | Hitam, digit merah |
| Senja Amber | Klasik | Hitam, digit amber VFD |
| Samudra | Samping (info di kanan) | Navy, digit cyan |
| Kertas Vintage | Klasik | Krem, digit cokelat |
| Smartwatch | Watch face + complications | Hitam, digit putih |
| Malam Klasik | Klasik | Full hitam, digit & tulisan putih |

- Semua fitur tetap sama di semua tema — hanya tampilan yang berubah.
- Pilihan tema tersimpan walau HP restart.

### 🔄 Update & kelola versi (tanpa download manual)
- Tiap dibuka, aplikasi cek GitHub Releases; kalau ada versi baru muncul
  tawaran **Update** → download → install (satu tap di dialog sistem).
- **Ketuk 2× jam → Pengaturan → Kelola versi…**: daftar semua versi yang
  pernah rilis, bisa install versi mana pun.
- **Rollback**: di HP root downgrade berjalan diam-diam; di HP non-root APK
  disalin ke folder Download lalu dipandu uninstall + install ulang
  (pengaturan kembali default — keterbatasan Android, bukan bug).

### 🏠 Mode launcher & jalan pintas
- Terdaftar sebagai **aplikasi Home/Launcher** — tiap HP dinyalakan langsung
  masuk ke jam.
- **Tekan-lama jam** → membuka **daftar semua aplikasi** di Pengaturan
  (jalan pintas membuka aplikasi lain, karena tidak ada laci aplikasi).

---

## 🖥️ Tata letak layar

```
┌──────────────────────────────────────────────────────────────┐
│ [4G] ● ●   ↓ 1.2 MB/s   ↑ 340 KB/s        SIM 1 • Operator   │
│         Data 3.42 GB         [6 bar] -87 dBm   LTE B40  24°C │
│                                                              │
│                                                              │
│                    0 9 : 4 1 : 0 7   AM PM                    │
│                                                              │
│                                                              │
│              Senin   01   Oktober   2026                      │
└──────────────────────────────────────────────────────────────┘
```

- **Kiri atas**: badge jaringan, LED trafik, kecepatan ↓/↑, Data bulanan.
- **Kanan atas**: sinyal SIM1/SIM2 + suhu CPU.
- **Tengah**: jam besar. **Bawah**: hari/tanggal/bulan/tahun.

---

## 🔄 Alur kerja aplikasi

Setiap komponen berjalan sebagai *loop* independen di `MainActivity`:

| Komponen | Pemicu | Yang dilakukan |
|---|---|---|
| `tick` jam | Tiap 1 detik (`Handler`) | Update digit jam, kedipkan titik dua, cek jadwal lonceng |
| `SignalMonitor` | Event perubahan sinyal per SIM | Baca level → petakan ke 6 bar dari dBm, update label/dBm/band/operator |
| `TrafficMonitor` | Tiap 1 detik | Selisih `TrafficStats` → kecepatan ↓/↑, nyalakan LED bila ada trafik |
| Data bulanan | Tiap 60 detik (cache) | `NetworkStatsManager.querySummaryForDevice` dari tanggal 1 |
| Suhu CPU | Tiap 5 detik (thread latar) | Baca `/sys/class/thermal`, ambil maks zona `cpu` |
| Band sel | Tiap perubahan sinyal + tiap 15 detik | Baca `CellInfo` → band utama (`getBands()` API 30+ / EARFCN di bawahnya) |
| Lonceng | Dari `tick`, saat `HH:00` / `HH:30` | `ToneGenerator` + `TextToSpeech` (stream ALARM) |

**Urutan saat aplikasi dibuka:** `setContentView` → sembunyikan system bar
→ minta izin telepon & lokasi → cek data suara TTS → tawarkan *Akses
penggunaan* (sekali saja) → jalankan semua monitor di atas.

---

## 🔐 Izin yang dibutuhkan

| Izin | Untuk apa |
|---|---|
| `READ_PHONE_STATE` | Membaca kekuatan sinyal & info jaringan tiap SIM |
| `ACCESS_FINE_LOCATION` | Syarat Android untuk membaca info sel (band) |
| `PACKAGE_USAGE_STATS` | Membaca pemakaian data bulanan (*Akses penggunaan*, via Pengaturan) |
| `INTERNET` | Cek & download update dari GitHub Releases |
| `ACCESS_NETWORK_STATE` | Cek status jaringan sebelum uji koneksi internet (badge hijau/merah) |
| `REQUEST_INSTALL_PACKAGES` | Install APK hasil download (Android 8+) |

Tanpa izin telepon/lokasi, info sinyal tampil `-` — jam & trafik tetap jalan.

---

## 🛠️ Cara build

Build **tanpa Gradle** memakai skrip `build-apk.sh`
(pipeline: `aapt2` → `kotlinc` → `d8` → `zipalign` → `apksigner`):

```bash
./build-apk.sh ~/workspace/mpdclock-vX.Y.apk
```

- `minSdk 26` (Android 8.0), `targetSdk 35`.
- Naikkan `versionCode`/`versionName` di `app/src/main/AndroidManifest.xml`
  setiap merilis versi baru.
- Signing memakai `release.keystore` yang **tidak ikut repo ini** — buat
  keystore sendiri bila ingin build APK signed:

```bash
keytool -genkeypair -keystore release.keystore -alias appkey \
  -keyalg RSA -keysize 2048 -validity 10000
```

## 📲 Cara install APK di HP

1. Salin file `.apk` ke HP.
2. Buka file-nya → izinkan **"Install aplikasi yang tidak dikenal"** bila diminta.
3. Buka aplikasi → setujui izin yang diminta → aktifkan *Akses penggunaan*
   bila ingin angka Data bulanan akurat.
4. (Opsional) Jadikan aplikasi Home/Launcher default agar tiap HP dinyalakan
   langsung masuk ke jam.

Mulai v2.0 update berikutnya tidak perlu manual: aplikasi menawarkan update
sendiri, atau via ketuk 2× jam → Pengaturan → **Kelola versi…**.

## 🧩 Struktur proyek

```
app/src/main/
├── AndroidManifest.xml                 # minSdk 26, targetSdk 35, kategori HOME
├── java/id/my/sir/mpdclock/
│   ├── MainActivity.kt                 # orkestrasi semua loop & UI
│   ├── SignalMonitor.kt                # sinyal + band per SIM
│   ├── TrafficMonitor.kt               # kecepatan ↓/↑ + LED
│   ├── SegmentDisplayView.kt           # digit seven-segment (custom draw)
│   └── SignalBarsView.kt               # 6 bar sinyal (custom draw)
└── res/layout/activity_main.xml        # layout: bar atas, jam, bar tanggal
```

## ⚠️ Keterbatasan yang diketahui

- **Band sel**: Android tidak mengekspos *carrier aggregation* ke aplikasi
  biasa, jadi yang tampil hanya **band utama** (mis. `B40`, bukan `B40+B1`).
- **Skala 6 bar** adalah pemetaan dari dBm (perkiraan yang masuk akal),
  bukan skala resmi Android yang hanya 0–4.
- **Data bulanan** butuh *Akses penggunaan*; tanpa itu angka fallback
  (`TrafficStats`) akan reset tiap HP restart.
- **Suhu CPU** butuh thermal zone yang bisa dibaca; bila tidak, tampil `--°C`.

---

## Lisensi

Proyek ini dirilis di bawah [MIT License](LICENSE). Font DSEG seven-segment
berlisensi SIL Open Font License (lihat `DSEG-LICENSE.txt`).
