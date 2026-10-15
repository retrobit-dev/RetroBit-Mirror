# RetroBit Mirror

Aplikasi native Android (Kotlin) untuk memantulkan layar **Infinix GT 30 Pro** ke **Huawei MatePad Mini**, plus gamepad, keyboard, sentuhan, dan GT Trigger.

Game tetap berjalan di ponsel. Tablet hanya menjadi layar besar dan alat kontrol. Semua lalu lintas ada di Wi-Fi lokal. Tidak ada akun, tidak ada server internet.

## Dua jalur, karena MatePad Mini

MatePad Mini yang beredar memakai **HarmonyOS 5.1**. Sistem itu tidak memasang APK Android biasa. Karena itu aplikasi ini punya dua penerima:

| Perangkat | Cara |
| --- | --- |
| Infinix GT 30 Pro | Pasang APK ini. Peran **Host**. |
| MatePad Mini (HarmonyOS 5) | Browser ke `http://IP-PONSEL:28731`. Halaman penerima ikut di dalam APK. |
| MatePad / tablet yang masih bisa pasang APK | Pasang APK yang sama, pilih peran **Layar**. Jalur ini H.264 native, latensi lebih rendah. |

## Yang bisa dilakukan

- Pantulan layar H.264 latensi rendah (preset Kompetitif 60 fps, Seimbang, Tajam).
- Cadangan MJPEG di browser kalau WebCodecs tidak ada.
- Sentuhan, stylus, dan mouse di area layar.
- Gamepad virtual: stick, ABXY, D-pad, bahu, plus **GT L / GT R**.
- Gamepad Bluetooth dan keyboard yang tersambung ke tablet, diteruskan ke ponsel.
- Profil MOBA, FPS, dan Balap. Tata letak bisa digeser dan disimpan.
- Tombol Back, Home, Recent, volume.
- Penemuan host lewat siaran UDP dan NSD, plus sambungan IP manual.
- PIN sesi. Notifikasi tetap terlihat selama pantulan hidup.

## GT Trigger

GT Trigger **bukan API publik**. Itu sensor kapasitif di rangka GT 30 Pro yang dipetakan XOS menjadi ketukan di dalam game. RetroBit tidak membaca sensornya, dan sengaja tidak menutup layar game dengan overlay.

Cara pakai yang benar:

1. Game dibuka di GT 30 Pro, bukan di tablet.
2. Di Game Space / setelan GT Trigger, petakan trigger kiri dan kanan ke skill.
3. Di MatePad, masuk mode Edit lalu **Kalibrasi GT L / GT R**. Ketuk posisi skill yang sama di layar yang dipantulkan.
4. Pegang ponsel sambil melihat tablet kalau ingin trigger fisik. Atau taruh ponsel di stand dan main dari tombol GT di tablet.

Di luar game, XOS biasanya hanya mengaktifkan trigger di atas tombol volume, dan sering butuh ketukan ganda. Di dalam game, keduanya aktif.

Jangan hidupkan gelembung STOP kalau posisinya menutup tombol game. Overlay menelan ketukan GT Trigger.

## Input: kenapa Shizuku

Android tidak mengizinkan aplikasi biasa menyuntik sentuhan ke game. RetroBit memakai dua jalur, keduanya hanya ke ponsel milikmu:

1. **Shizuku** (disarankan). Proses shell menyuntik `MotionEvent` dengan jari yang bisa ditahan. Ini yang membuat stick analog, tembakan tahan, dan keyboard terasa benar. Latensi biasanya di bawah 5 ms di dalam ponsel.
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

- `RetroBit-Mirror-release` — yang dipasang di ponsel. File di dalamnya `RetroBit-Mirror-1.0.0.apk`.
- `RetroBit-Mirror-debug` — hanya untuk pengembangan.

Unduh dari tab Actions pada run yang hijau, atau dari [Releases](https://github.com/retrobit-dev/RetroBit-Mirror/releases) kalau run-nya menerbitkan rilis `v1.0.0`.

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

Pasang di GT 30 Pro. Izinkan pemasangan dari sumber tak dikenal. Kalau tabletmu masih Android / HarmonyOS 4, pasang APK yang sama di sana.

## Main

1. Samakan Wi-Fi. Kalau router memisahkan perangkat (AP isolation), jadikan hotspot GT 30 Pro dan sambungkan MatePad ke hotspot itu.
2. Di ponsel: **GT 30 Pro → Mulai pantulan**. Pada dialog sistem, pilih **seluruh layar**.
3. Catat PIN dan URL, misalnya `http://192.168.1.23:28731`.
4. Di MatePad, buka URL itu di browser, masukkan PIN, lalu layar penuh.
5. Buka game di ponsel. Jangan tutup notifikasi RetroBit.
6. Di tablet, pilih profil (MOBA / FPS / Balap), geser tombol ke posisi skill, kalibrasi GT L dan GT R.
7. Matikan optimalisasi baterai untuk RetroBit. Di Game Space, jangan blokir jaringannya.

Suara sengaja tidak dikirim ke tablet secara bawaan, supaya tidak gema dengan speaker ponsel. Aktifkan "Suara di tablet" hanya kalau ponsel dibisukan.

## Profil kontrol

Koordinat tombol adalah posisi ketukan di layar ponsel, 0–1. Posisi visual di tablet bisa dipisah: geser tombol di mode Edit, lalu kalibrasi titik tembak dengan mengetuk layar game. GT L / GT R memang dipisah sejak awal — jempol di tepi tablet, tembakan jatuh di skill.

Profil bawaan hanya titik awal. Setiap game menaruh tombol di tempat berbeda, jadi kalibrasi sekali lalu tersimpan di perangkat.

Gamepad atau keyboard Bluetooth disambungkan ke **tablet**, bukan ke ponsel. Aplikasi yang meneruskan.

## Port

| Port | Fungsi |
| --- | --- |
| UDP 28729 | Siaran penemuan host |
| TCP 28730 | Klien native: H.264 + input |
| TCP 28731 | Browser MatePad: halaman, WebSocket, MJPEG cadangan |

## Jika tersendat

- Layar hitam: mulai ulang pantulan dan pilih seluruh layar, bukan satu aplikasi.
- Tablet tidak menemukan host: ketik IP manual. UDP sering diblokir router.
- Ketukan tidak masuk game: Shizuku belum siap, atau aksesibilitas belum diizinkan.
- Stick jalan tapi skill tidak: kalibrasi ulang titik tembak, bukan hanya posisi visual tombol.
- Stream putus saat game dibuka: whitelist baterai, kunci aplikasi di recent, dan jangan aktifkan mode hemat XOS.
- GT Trigger tidak terasa: pastikan sedang di dalam game, kedua trigger diaktifkan di XOS, dan tidak ada overlay RetroBit di atas tombol.
- Browser MatePad hanya menampilkan gambar diam: itu cadangan JPEG. Tetap bisa main; untuk latensi lebih rendah pakai klien APK jika tablet mengizinkan.

## Privasi

Tidak ada analitik. Video dan input tidak meninggalkan LAN. PIN bukan enkripsi — jangan pakai di Wi-Fi publik. Notifikasi pantulan tidak bisa disembunyikan.

Infinix, GT Trigger, XOS, Huawei, dan MatePad adalah merek pemiliknya. RetroBit Mirror tidak berafiliasi dengan mereka.
