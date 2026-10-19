# RetroBit Mirror

Aplikasi native Android (Kotlin) untuk memantulkan layar **Huawei MatePad Mini** ke **Infinix GT 30 Pro**. Ponsel menjadi layar sekaligus kontrol: sentuhan, gamepad, keyboard, dan tombol GT.

Yang berjalan di tablet dikirim ke ponsel. Semua lalu lintas ada di Wi-Fi lokal. Tidak ada akun, tidak ada server internet.

## Peran

| Perangkat | Peran |
| --- | --- |
| MatePad Mini | **Host**. Pasang APK, mulai pantulan, pilih seluruh layar. |
| GT 30 Pro | **Kontrol dan layar**. Pasang APK yang sama, pilih GT 30 Pro, sambungkan dengan PIN. |
| Browser | Cadangan untuk menampilkan dan mengontrol, lewat `http://IP-TABLET:28731`. Browser tidak bisa menjadi host. |

Host harus bisa memasang APK. Kalau MatePad memakai HarmonyOS NEXT dan menolak APK Android, tablet itu tidak bisa menangkap layar atau menerima input. Browser di ponsel tetap hanya penampil.

## Yang bisa dilakukan

- Pantulan layar H.264 latensi rendah (preset Kompetitif 60 fps, Seimbang, Tajam).
- Cadangan MJPEG di browser kalau WebCodecs tidak ada.
- Sentuhan, stylus, dan mouse di area layar.
- Gamepad virtual: stick, ABXY, D-pad, bahu, plus **GT L / GT R**.
- Gamepad Bluetooth dan keyboard yang tersambung ke ponsel, diteruskan ke tablet.
- Profil MOBA, FPS, dan Balap. Tata letak bisa digeser dan disimpan.
- Tombol Back, Home, Recent, volume.
- Penemuan host lewat siaran UDP dan NSD, plus sambungan IP manual.
- PIN sesi. Notifikasi tetap terlihat selama pantulan hidup.

## GT Trigger

GT Trigger **bukan API publik**. Itu sensor kapasitif di rangka GT 30 Pro. XOS menembakkan ketukan ke layar ponsel sendiri, biasanya hanya saat game atau kamera terbuka di ponsel. RetroBit tidak membaca sensornya.

Karena host-nya tablet, trigger fisik tidak masuk ke MatePad. Di ponsel, pakai tombol GT L / GT R pada layar kontrol, lalu kalibrasi titik tembaknya ke posisi di tablet.

Jangan hidupkan gelembung STOP di tablet kalau posisinya menutup aplikasi yang sedang dipantulkan.

## Input: kenapa Shizuku

Android tidak mengizinkan aplikasi biasa menyuntik sentuhan ke aplikasi lain. RetroBit memakai dua jalur, keduanya di **tablet host**:

1. **Shizuku** (disarankan). Proses shell menyuntik `MotionEvent` dengan jari yang bisa ditahan. Ini yang membuat stick analog, tembakan tahan, dan keyboard terasa benar.
2. **Aksesibilitas** (cadangan). Hanya andal untuk ketuk dan geser. Stick analog tidak stabil. Android 13+ kadang menyembunyikan layanan aplikasi sideload: Setelan → Aplikasi → RetroBit Mirror → menu → Izinkan setelan terbatas.

Langkah Shizuku di MatePad:

1. Pasang Shizuku dari [shizuku.rikka.app](https://shizuku.rikka.app/).
2. Setelan pengembang → Debugging nirkabel → hidupkan.
3. Di Shizuku, mulai lewat debugging nirkabel dan masukkan kode pairing.
4. Kembali ke RetroBit → Izinkan.
5. Status harus **Shizuku siap** sebelum main ranked.

Tanpa Shizuku, layar tetap bisa dipantulkan. Kontrol analog-nya yang belum layak untuk game.

## Build di GitHub

Setiap push menjalankan GitHub Actions dan menghasilkan dua APK:

- `RetroBit-Mirror-release` — yang dipasang di tablet dan ponsel. File di dalamnya `RetroBit-Mirror-1.1.0.apk`.
- `RetroBit-Mirror-debug` — hanya untuk pengembangan.

Unduh dari tab Actions pada run yang hijau, atau dari [Releases](https://github.com/retrobit-dev/RetroBit-Mirror/releases) kalau run-nya menerbitkan rilis `v1.1.0`.

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

Pasang APK yang sama di MatePad dan GT 30 Pro. Izinkan pemasangan dari sumber tak dikenal. Tablet harus benar-benar bisa memasang APK; kalau tidak, ia tidak bisa menjadi host.

## Main

1. Samakan Wi-Fi. Kalau router memisahkan perangkat, jadikan hotspot salah satu perangkat dan sambungkan yang lain ke hotspot itu.
2. Di tablet: **MatePad Mini → Mulai pantulan**. Pada dialog sistem, pilih **seluruh layar**.
3. Catat PIN dan URL, misalnya `http://192.168.1.23:28731`.
4. Di GT 30 Pro, buka RetroBit, pilih **GT 30 Pro**, lalu sambungkan dengan IP dan PIN. Browser ke URL yang sama juga bisa, tetapi APK lebih rendah latensi.
5. Buka aplikasi yang ingin ditampilkan di tablet. Jangan tutup notifikasi RetroBit di tablet.
6. Di ponsel, pilih profil, geser tombol, lalu kalibrasi GT L dan GT R ke posisi di layar tablet.
7. Matikan optimalisasi baterai untuk RetroBit di kedua perangkat.

Suara sengaja tidak dikirim ke ponsel secara bawaan, supaya tidak gema dengan speaker tablet. Aktifkan "Suara di ponsel" hanya kalau tablet dibisukan.

## Profil kontrol

Koordinat tombol adalah posisi ketukan di layar tablet, 0–1. Posisi visual di ponsel bisa dipisah: geser tombol di mode Edit, lalu kalibrasi titik tembak dengan mengetuk layar yang dipantulkan. GT L / GT R memang dipisah sejak awal — jempol di tepi ponsel, tembakan jatuh di titik yang dikalibrasi di tablet.

Profil bawaan hanya titik awal. Kalibrasi sekali, lalu tersimpan di ponsel.

Gamepad atau keyboard Bluetooth disambungkan ke **ponsel**, bukan ke tablet. Aplikasi yang meneruskan.

## Port

| Port | Fungsi |
| --- | --- |
| UDP 28729 | Siaran penemuan host |
| TCP 28730 | Klien native: H.264 + input |
| TCP 28731 | Browser kontrol: halaman, WebSocket, MJPEG cadangan |

## Jika tersendat

- Layar hitam: mulai ulang pantulan dan pilih seluruh layar, bukan satu aplikasi.
- Tablet tidak menemukan host: ketik IP manual. UDP sering diblokir router.
- Ketukan tidak masuk tablet: Shizuku belum siap di MatePad, atau aksesibilitas belum diizinkan.
- Stick jalan tapi titik tembak meleset: kalibrasi ulang titik tembak, bukan hanya posisi visual tombol.
- Stream putus saat aplikasi dibuka di tablet: whitelist baterai dan kunci RetroBit di recent.
- GT Trigger fisik tidak menembak tablet: itu batas XOS. Pakai tombol GT di layar ponsel.
- Browser hanya menampilkan gambar diam: itu cadangan JPEG. Untuk latensi lebih rendah pakai peran kontrol di APK ponsel.

## Privasi

Tidak ada analitik. Video dan input tidak meninggalkan LAN. PIN bukan enkripsi — jangan pakai di Wi-Fi publik. Notifikasi pantulan tidak bisa disembunyikan.

Infinix, GT Trigger, XOS, Huawei, dan MatePad adalah merek pemiliknya. RetroBit Mirror tidak berafiliasi dengan mereka.
