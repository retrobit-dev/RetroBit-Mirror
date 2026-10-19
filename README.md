# RetroBit Mirror

Aplikasi native Android (Kotlin) untuk memantulkan layar **Infinix GT 30 Pro** ke **Huawei MatePad Mini**. Game dan semua kontrol ada di ponsel. Tablet hanya menampilkan. Bisa layar saja, atau layar bersama gamepad, tanpa menerima ketukan.

Semua lalu lintas lokal: Wi-Fi yang sama, atau hotspot ponsel kalau router memisahkan perangkat. Tidak ada akun, tidak ada server internet.

## Peran

| Perangkat | Peran |
| --- | --- |
| GT 30 Pro | **Host dan kontrol**. Game berjalan di sini. Kiri dan kanan layar untuk gamepad. |
| MatePad Mini | **Layar saja**. Menampilkan game dari ponsel. Tidak mengirim input. |
| Browser | Cadangan layar, lewat `http://IP-PONSEL:28731`. Juga tanpa gamepad. |

Di GT 30 Pro ada dua tata letak:

- **Belah**: potret memakai 1/4 kiri dan 1/4 kanan untuk kontrol, tengah untuk game. Landscape memakai panel yang lebih sempit supaya area game lebih lebar. MatePad menerima game, bukan tombol.
- **Layar tambahan**: ponsel hanya menampilkan kontrol. MatePad menampilkan aplikasi yang dipilih pada dialog tangkap layar. Pilih satu aplikasi, bukan seluruh layar.
- **Sembunyikan layar ponsel**: saat MatePad tersambung, tengah layar GT 30 Pro bisa ditutup. Gambar tetap dikirim. Aktifkan aksesibilitas RetroBit di ponsel, supaya penutup tidak ikut tertangkap.
- **Tampilan MatePad**: Layar saja mengirim game tanpa panel kontrol. Layar + gamepad mengirim game dan panel kontrol. Ketukan tetap di ponsel.

## Yang bisa dilakukan

- Pantulan layar H.264 latensi rendah (preset Kompetitif 60 fps, Seimbang, Tajam).
- Cadangan JPEG di browser kalau WebCodecs tidak ada. Browser MatePad tidak perlu H.264.
- Sentuhan, stylus, dan mouse di area layar.
- Gamepad virtual: stick, ABXY, D-pad, bahu, plus **GT L / GT R**.
- Gamepad Bluetooth dan keyboard disambungkan ke ponsel, lalu disuntikkan ke game di ponsel. Tablet tidak menerima input.
- Profil MOBA, FPS, dan Balap. Tata letak bisa digeser dan disimpan.
- Tombol Back, Home, Recent, volume.
- Penemuan host lewat siaran UDP, pindai jaringan, dan sambungan IP manual. Kalau router memisahkan perangkat, pakai hotspot ponsel dan kode QR.
- Layout gamepad di GT 30 Pro bisa digeser, disimpan per profil, atau disembunyikan.
- PIN sesi. Notifikasi tetap terlihat selama pantulan hidup.

## GT Trigger

GT Trigger **bukan API publik**. Itu sensor kapasitif di rangka GT 30 Pro. XOS menembakkan ketukan ke layar ponsel, biasanya hanya saat game terbuka di sana. RetroBit tidak membaca sensornya.

Mode belah membiarkan tengah layar terbuka, jadi trigger fisik tetap bisa mengenai game. Overlay kiri dan kanan menelan ketukan yang jatuh di atasnya. Tombol GT di pinggir ponsel mengirim ketukan ke titik yang dikalibrasi. MatePad tidak punya tombol itu.

## Input: kenapa Shizuku

Android tidak mengizinkan aplikasi biasa menyuntik sentuhan ke aplikasi lain. RetroBit memakai dua jalur, keduanya di **GT 30 Pro**:

1. **Shizuku** (disarankan). Proses shell menyuntik `MotionEvent` dengan jari yang bisa ditahan. Ini yang membuat stick analog, tembakan tahan, dan keyboard terasa benar.
2. **Aksesibilitas** (cadangan). Hanya andal untuk ketuk dan geser. Stick analog tidak stabil. Android 13+ kadang menyembunyikan layanan aplikasi sideload: Setelan → Aplikasi → RetroBit Mirror → menu → Izinkan setelan terbatas.

Langkah Shizuku di GT 30 Pro:

1. Pasang Shizuku dari [shizuku.rikka.app](https://shizuku.rikka.app/).
2. Setelan pengembang → Debugging nirkabel → hidupkan.
3. Di Shizuku, mulai lewat debugging nirkabel dan masukkan kode pairing.
4. Kembali ke RetroBit → Izinkan.
5. Status harus **Shizuku siap** sebelum main ranked.

Tanpa Shizuku, layar tetap bisa dipantulkan. Kontrol analog-nya yang belum layak untuk game.

## Build di GitHub

Setiap push menjalankan GitHub Actions dan menghasilkan dua APK:

- `RetroBit-Mirror-release` — pasang di ponsel dan tablet. File di dalamnya `RetroBit-Mirror-1.5.0.apk`.
- `RetroBit-Mirror-debug` — hanya untuk pengembangan.

Unduh dari tab Actions pada run yang hijau, atau dari [Releases](https://github.com/retrobit-dev/RetroBit-Mirror/releases) kalau run-nya menerbitkan rilis `v1.5.0`.

APK rilis ditandatangani dengan kunci tetap di `signing/`. Build berikutnya bisa dipasang di atas yang lama tanpa hapus data. Kalau sebelumnya terpasang APK debug, Android menolak karena tanda tangannya berbeda: hapus dulu, lalu pasang rilis.

Kunci itu ada di repo supaya Actions bisa menandatangani tanpa secret. Repo ini publik, jadi kunci hanya untuk sideload. Jangan unggah APK ini ke Play Store, dan jangan anggap tanda tangannya sebagai jaminan bahwa APK berasal dari pengembang.

## Build di komputer

Butuh Android Studio dengan JDK 17.

1. Buka folder ini.
2. Biarkan Android Studio mengunduh Gradle 8.9 dan Android SDK 35.
3. `Build > Generate Signed App Bundle or APK`, atau:

```bash
./gradlew :app:assembleRelease
```

APK ada di `app/build/outputs/apk/release/`. Kunci sideload yang sama dengan GitHub dibaca dari `signing/release.properties`.

Pasang APK yang sama di GT 30 Pro dan, kalau bisa, di MatePad. Izinkan pemasangan dari sumber tak dikenal. Izinkan juga RetroBit tampil di atas aplikasi lain, supaya gamepad pinggir bisa muncul di atas game.

## Main

1. Samakan Wi-Fi. Kalau router memisahkan perangkat, jadikan hotspot ponsel dan sambungkan MatePad ke hotspot itu.
2. Di ponsel: **GT 30 Pro → pilih Belah atau Layar tambahan → Mulai pantulan**.
3. Mode belah: pilih **seluruh layar**. Mode layar tambahan: pilih **aplikasi game**, bukan seluruh layar.
4. Catat PIN dan URL, misalnya `http://192.168.1.23:28731`.
5. Di MatePad, pilih peran layar dan sambungkan, atau buka URL itu di browser. Jangan cari gamepad di tablet.
6. Buka game di ponsel. Kontrol kiri dan kanan menyuntikkan input ke game. Tengah, pada mode belah, tetap terlihat.
7. Matikan optimalisasi baterai untuk RetroBit di ponsel.

Suara sengaja tidak dikirim ke tablet secara bawaan, supaya tidak gema dengan speaker ponsel. Aktifkan "Suara di tablet" hanya kalau ponsel dibisukan.

## Profil kontrol

Koordinat tombol adalah posisi ketukan di layar ponsel, 0–1. Tombol visual ada di pinggir GT 30 Pro. GT L / GT R mengirim tembakan ke titik skill, bukan ke tempat jempol.

Profil bawaan hanya titik awal. Gamepad atau keyboard Bluetooth disambungkan ke **ponsel**. Tablet tidak meneruskan input.

## Port

| Port | Fungsi |
| --- | --- |
| UDP 28729 | Siaran penemuan host |
| TCP 28730 | Klien native: H.264 + input |
| TCP 28731 | Browser layar: halaman, WebSocket, MJPEG cadangan |

## Jika tersendat

- Layar hitam: mulai ulang pantulan dan pilih seluruh layar, bukan satu aplikasi.
- Tablet tidak menemukan host: ketik IP manual. UDP sering diblokir router.
- Ketukan kontrol tidak masuk game: Shizuku belum siap di GT 30 Pro, atau aksesibilitas belum diizinkan.
- Gamepad tidak muncul di pinggir: izinkan RetroBit tampil di atas aplikasi lain.
- MatePad ikut menampilkan gamepad: mode belah harus memilih seluruh layar. Mode layar tambahan harus memilih satu aplikasi.
- GT Trigger tidak terasa: pastikan sedang di dalam game dan titik skill tidak tertutup overlay pinggir.
- Browser hanya menampilkan gambar diam: itu cadangan JPEG. Gambarnya tetap layar, bukan kontrol.

## Privasi

Tidak ada analitik. Video dan input tidak meninggalkan LAN. PIN bukan enkripsi — jangan pakai di Wi-Fi publik. Notifikasi pantulan tidak bisa disembunyikan.

Infinix, GT Trigger, XOS, Huawei, dan MatePad adalah merek pemiliknya. RetroBit Mirror tidak berafiliasi dengan mereka.
