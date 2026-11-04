# catatan update: safety mode dan skala resolusi + density

## status

rancangan fitur --- belum diimplementasikan. detail di bawah merupakan
kesepakatan dan usulan dari sesi pembahasan; bagian yang masih perlu
diputuskan ditandai secara eksplisit.

## tujuan

menambahkan perlindungan saat pengguna mengubah resolusi layar dan
logical density melalui fitur display manager. resolusi dan density
harus diperlakukan sebagai satu konfigurasi skala layar agar ukuran
antarmuka tetap proporsional.

## 1. safety mode

### perilaku default

-   safety mode aktif secara default pada instalasi pertama.
-   pengaturan untuk menonaktifkan safety mode tersedia di halaman
    settings.
-   menonaktifkan safety mode harus melalui peringatan yang menjelaskan
    risiko perubahan resolusi dan density.
-   status safety mode harus disimpan secara persisten.

### saat safety mode aktif

-   perubahan resolusi hanya melalui preset skala yang dihitung dari
    baseline perangkat.
-   jangan tampilkan input teks untuk memasukkan resolusi secara manual.
-   preset tidak boleh menghasilkan resolusi di atas resolusi
    baseline/native yang terdeteksi.
-   perubahan density manual dikunci.
-   pengguna tetap dapat melihat resolusi dan density aktif, nilai
    baseline, serta target yang akan diterapkan.
-   sediakan tindakan reset untuk mengembalikan konfigurasi ke baseline
    yang tersimpan.
-   setiap perubahan harus menampilkan ringkasan nilai sebelum dan
    sesudah, lalu meminta konfirmasi.

### saat safety mode nonaktif

-   izinkan input resolusi manual.
-   izinkan pengaturan density manual.
-   tetap lakukan validasi nilai, tampilkan peringatan, dan minta
    konfirmasi sebelum menerapkan perubahan.
-   mode nonaktif tidak berarti nilai tanpa batas atau perubahan yang
    tidak tervalidasi boleh diterapkan.

## 2. baseline resolusi

aplikasi harus membedakan resolusi native/stable perangkat dari resolusi
override yang sedang aktif.

-   deteksi resolusi native/stable melalui sumber sistem yang sesuai.
-   baca juga resolusi aktif, termasuk kemungkinan adanya override dari
    `wm size`.
-   jangan pernah menganggap resolusi override yang sedang aktif sebagai
    baseline native.
-   simpan baseline agar preset selalu dihitung dari acuan yang sama,
    bukan dari resolusi terakhir yang sudah diperkecil.
-   sediakan mekanisme deteksi ulang atau pengambilan ulang baseline
    jika diperlukan.
-   jika baseline tidak dapat dipastikan, jangan membuat angka asumsi.
    tampilkan kondisi tersebut dan batasi tindakan berisiko.

## 3. preset resolusi berbasis skala

preset dihitung dari resolusi baseline, bukan daftar resolusi tetap yang
dibuat manual.

contoh baseline `720 × 1560`:

    skala      resolusi target
  ------- --------------------
     100%           720 × 1560
      90%           648 × 1404
      80%           576 × 1248
      75%           540 × 1170
      67%   sekitar 482 × 1045
      50%            360 × 780

ketentuan: - gunakan faktor skala yang sama untuk lebar dan tinggi agar
aspect ratio dipertahankan. - hasil dimensi harus dibulatkan secara
konsisten ke bilangan piksel yang valid. - tampilkan persentase skala,
resolusi baseline, dan resolusi target. - preset 100% merepresentasikan
baseline. - preset tidak boleh dihitung ulang dari nilai override aktif
setelah perubahan sebelumnya.

catatan: contoh di atas ilustratif. implementasi perlu menetapkan aturan
pembulatan dan memvalidasi batas minimum/maksimum yang masuk akal untuk
perangkat.

## 4. density ikut diskalakan

nilai yang biasa disebut "dpi" di opsi pengembang Android pada konteks
ini adalah logical display density, bukan DPI fisik panel. nilai ini
memengaruhi skala ukuran UI dalam `dp`.

resolusi dan logical density harus berubah menggunakan faktor skala yang
sama:

`density_target = density_baseline × scale`

contoh baseline: - resolusi: `720 × 1560` - logical density: `500`

    skala   resolusi target   density target
  ------- ----------------- ----------------
     100%        720 × 1560              500
      90%        648 × 1404              450
      80%        576 × 1248              400
      75%        540 × 1170              375
      50%         360 × 780              250

tujuan pendekatan ini adalah menjaga ukuran logis elemen UI relatif
konsisten ketika resolusi berubah. density target harus dihitung dari
density baseline yang tersimpan, bukan dari density hasil perubahan
sebelumnya.

## 5. pembacaan dan penyimpanan density

-   baca logical density aktif perangkat.
-   baca stable density jika tersedia, tetapi jangan menyamakannya
    dengan density aktif.
-   jika pengguna telah mengubah density melalui opsi pengembang sebelum
    aplikasi pertama kali mengambil baseline, gunakan nilai aktual yang
    terdeteksi sebagai baseline pengguna; jangan otomatis menimpanya
    dengan stable density.
-   simpan baseline resolusi dan density secara persisten.
-   simpan informasi yang diperlukan untuk memulihkan nilai awal secara
    akurat.
-   jangan menyimpan pairing code ADB.
-   jika nilai baseline tidak berhasil dibaca atau tidak konsisten,
    hentikan penerapan otomatis dan minta pengguna melakukan deteksi
    ulang.

## 6. penerapan dan pemulihan

-   terapkan resolusi dan density sebagai satu operasi konfigurasi yang
    terkoordinasi.
-   validasi kedua nilai sebelum menjalankan perintah ADB.
-   tampilkan konfirmasi berisi nilai saat ini dan target.
-   setelah perubahan, mulai countdown pemulihan (usulan awal: 15
    detik).
-   pengguna harus memilih "pertahankan perubahan" sebelum countdown
    berakhir.
-   jika tidak dikonfirmasi, aplikasi mencoba mengembalikan konfigurasi
    sebelumnya.
-   jelaskan bahwa rollback tidak dapat dijamin jika proses aplikasi,
    perangkat, atau sesi ADB terputus sebelum pemulihan berjalan.
-   sediakan tindakan reset ke baseline tersimpan.
-   pertimbangkan opsi terpisah untuk reset ke default bawaan sistem,
    karena default sistem belum tentu sama dengan baseline pengguna yang
    tersimpan.

## 7. informasi yang perlu ditampilkan di UI

pada halaman display manager, tampilkan: - resolusi aktif. - resolusi
baseline. - logical density aktif. - logical density baseline. - preset
skala yang tersedia. - resolusi dan density target sebelum diterapkan. -
status safety mode. - status countdown dan tindakan untuk mempertahankan
atau membatalkan perubahan.

hindari menyebut logical density sebagai DPI fisik. gunakan label UI
seperti "density (dpi)" disertai penjelasan singkat bahwa ini adalah
skala UI Android.

## 8. validasi dan batasan

-   jangan mengarang baseline jika pembacaan sistem gagal.
-   jangan mengasumsikan semua perangkat memiliki resolusi, density,
    atau dukungan override yang sama.
-   validasi nilai target dan tangani kegagalan perintah ADB.
-   perubahan display dapat membuat UI tidak nyaman digunakan atau sulit
    diakses; karena itu konfirmasi dan mekanisme pemulihan wajib.
-   kemampuan rollback bergantung pada aplikasi masih berjalan dan
    koneksi ADB masih tersedia.
-   uji pada beberapa konfigurasi Android dan ukuran layar; jangan
    mengunci implementasi ke perangkat atau vendor tertentu.

## 9. keputusan yang masih perlu dikonfirmasi

1.  **density pada safety mode:** rancangan saat ini mengunci perubahan
    density sepenuhnya, termasuk preset. pengguna hanya dapat melihat
    nilai dan melakukan reset ke baseline. perlu konfirmasi final.
2.  **menonaktifkan safety mode:** rancangan saat ini meminta peringatan
    dan konfirmasi tambahan, bukan toggle biasa. perlu konfirmasi final.
3.  **reset:** tentukan apakah UI menyediakan dua tindakan terpisah:
    "pulihkan baseline tersimpan" dan "reset ke default sistem".
4.  **preset:** daftar faktor skala final dan aturan pembulatan dimensi
    perlu ditetapkan saat implementasi.

## 10. kriteria penerimaan

-   safety mode aktif secara default pada instalasi baru.
-   tidak ada input resolusi manual saat safety mode aktif.
-   preset selalu dihitung dari baseline yang tersimpan.
-   aspect ratio dipertahankan semaksimal mungkin.
-   density target dihitung dari density baseline dengan faktor skala
    yang sama.
-   mengembalikan skala ke 100% memulihkan resolusi dan density
    baseline.
-   baseline tidak tertimpa oleh nilai override hasil perubahan
    aplikasi.
-   perubahan menampilkan konfirmasi dan menyediakan countdown
    pemulihan.
-   kegagalan deteksi atau penerapan ditangani tanpa mengklaim perubahan
    berhasil.
-   seluruh fitur tetap tunduk pada batasan izin dan kemampuan
    ADB/Android pada perangkat target.


---

## catatan implementasi update 1.1.0 (2026-11-03)

status rancangan: **sudah diimplementasikan di source code**. catatan di atas tetap dipertahankan sebagai riwayat requirement; keputusan final untuk implementasi ini:

1. safety mode aktif secara default dan disimpan di `SharedPreferences`.
2. safety mode mengunci input resolusi dan density manual. density tetap mengikuti faktor skala pada preset agar ukuran logis UI relatif konsisten; dengan demikian yang dikunci adalah perubahan density manual, bukan penyesuaian density otomatis yang merupakan bagian dari preset.
3. menonaktifkan safety mode dari settings memerlukan dialog peringatan dan konfirmasi tambahan.
4. dua opsi reset disediakan: pulihkan baseline tersimpan, dan reset ke default sistem.
5. preset final: 100%, 90%, 80%, 75%, 67%, 50%. pembulatan dimensi dan density menggunakan nearest integer (`value + 0.5`, karena semua nilai positif). preset yang melanggar batas valid tidak diterapkan.
6. baseline resolusi diambil dari `Physical size` pada output `wm size`; baseline density mengambil logical density aktif saat baseline pertama disimpan (override bila ada, physical bila tidak), sesuai preferensi density pengguna yang mungkin sudah diubah sebelum aplikasi dipasang/dijalankan.
7. penerapan dilakukan sebagai pasangan `wm size` + `wm density`; status dibaca ulang dan diverifikasi. bila langkah penerapan atau verifikasi gagal, aplikasi mencoba mengembalikan state override sebelumnya.
8. countdown 15 detik dijalankan oleh `AdbSessionService`, state rollback disimpan secara persisten, dan rollback yang belum selesai dicoba lagi setelah sesi ADB tersambung kembali. tetap ada batasan: pemulihan tidak dijamin bila perangkat mati, service dihentikan permanen, atau wireless debugging tidak dapat disambungkan kembali.
9. parser membutuhkan `Physical size` dan `Physical density`. bila salah satunya tidak ditemukan atau nilainya tidak valid, baseline/preset tidak dibuat dari asumsi.

file terkait: `display/DisplayStatus.kt`, `display/DisplayRepository.kt`, `adb/AdbSessionService.kt`, dan `MainActivity.kt`. test unit ditambahkan untuk parser status display dan kalkulasi skala.

## catatan tambahan update 1.1.0: cpu monitor dan ikon

- pembacaan cpu menggunakan delta counter baris agregat `cpu` dari `/proc/stat`, dengan `idle` dan `iowait` dihitung sebagai waktu idle. field guest tidak dijumlahkan ulang karena sudah tercakup dalam user/nice.
- bila akses `/proc/stat` dari proses aplikasi gagal, system monitor mencoba membaca file yang sama melalui ADB shell jika sesi sudah tersambung. kegagalan menampilkan alasan yang tersedia; nilai tidak tersedia tidak lagi disamarkan menjadi `n/a`.
- sampel pertama hanya membangun baseline counter dan berstatus `sampling`; persentase baru dihitung setelah ada sampel kedua dengan delta valid.
- UI memakai Font Awesome Free Solid melalui `fa-solid-900.ttf` yang sudah tersedia di `app/src/main/assets`. glyph dimuat langsung melalui `Typeface`/Compose tanpa dependency icon pack tambahan. TTF regular dan brands tetap disimpan sebagai aset proyek. lisensi dan atribusi ada pada `FONT-AWESOME-NOTICE.txt`.
- state rollback display disimpan sinkron sebelum perintah `wm` dijalankan. jika pengguna meminta pemutusan ADB saat rollback masih tertunda, service mencoba memulihkan konfigurasi terlebih dahulu dan menunda pemutusan bila pemulihan gagal.


## keputusan lanjutan versi 1.2.0 (2026-11-04)

1. preset skala tidak memakai rollback otomatis. preset dihitung dari baseline dan merupakan target terdefinisi; setelah kedua perintah `wm` berhasil serta status diverifikasi, konfigurasi langsung dipertahankan.
2. tindakan pulihkan baseline dan reset default sistem juga tidak memakai rollback otomatis karena targetnya sudah didefinisikan aplikasi.
3. rollback 15 detik hanya berlaku untuk konfigurasi manual ketika safety mode nonaktif. countdown dimulai setelah penerapan dan verifikasi berhasil, bukan sebelum perintah `wm` berjalan.
4. bila penerapan parsial atau verifikasi gagal, aplikasi tetap mencoba memulihkan state sebelumnya. bila pemulihan darurat itu gagal, state rollback dengan deadline langsung jatuh tempo disimpan agar service dapat mencoba ulang setelah ADB tersambung.
5. UI menampilkan estimasi smallest width dengan rumus `min(widthPx, heightPx) × 160 / logicalDensityDpi`. ini estimasi matematis; nilai `smallestScreenWidthDp` aktual yang diterima aplikasi lain dapat dipengaruhi konfigurasi window/insets.
6. minimum Android ditetapkan ke Android 12 / API 31 (`minSdk = 31`).

7. migrasi kebijakan rollback versi 1.2.0 membersihkan state rollback lama jika safety mode aktif (karena versi lama hanya mengizinkan preset/pemulihan terdefinisi dalam mode itu). state lama pada mode manual dipertahankan karena mungkin melindungi konfigurasi custom. migrasi dijalankan dari activity dan foreground service sebelum pemulihan terjadwal.
