# Device Manager Android

aplikasi android native berbasis kotlin dan jetpack compose untuk memantau serta mengelola perangkat yang sedang menjalankan aplikasi. seluruh identitas perangkat dibaca saat runtime; tidak ada nama model, chipset, user id, resolusi, atau daftar aplikasi yang di-hardcode.

## fitur yang diimplementasikan

- **dashboard** — manufacturer, model, device, product, build hardware, android release/api, soc model saat api mengeksposnya, abi, resolusi, density, ram, storage, baterai, cpu usage, logical cores, uptime.
- **display manager** — baca physical size/density dan override aktif dari `wm size`/`wm density`; baseline persisten; safety mode default aktif; preset skala yang menghitung resolusi dan logical density dari baseline dengan faktor sama; estimasi smallest width; mode manual hanya ketika safety mode dimatikan; penerapan terkoordinasi, verifikasi, konfirmasi, rollback 15 detik khusus konfigurasi manual, pemulihan baseline, dan reset default sistem.
- **app manager** — daftar aplikasi yang dapat dilihat PackageManager, nama, package, versi, uid, status system/user, ukuran file apk/split apk; force-stop, enable, disable, uninstall untuk user yang dipilih, dan `install-existing`.
- **multi-user** — membaca output `pm list users`, mengambil user id aktif dari uid proses, memilih profil berdasarkan id yang benar-benar ditemukan, dan menjalankan `pm list packages --user <id>`. metadata profil lain tidak direkayasa: nama/version/uid/ukuran akan ditandai tidak tersedia bila PackageManager proses tidak dapat membacanya.
- **adb manager** — pairing wireless debugging dengan TLS/SPAKE2 dan pairing code 6 digit, koneksi TLS dengan port terpisah, reconnect endpoint terakhir, disconnect, hapus endpoint tersimpan, reset kredensial lokal, riwayat koneksi.
- **interactive notification** — foreground service dengan ongoing notification dan RemoteInput untuk input endpoint pairing, kode pairing, lalu endpoint koneksi. alamat pairing dan alamat koneksi diminta terpisah.
- **system monitor** — penggunaan RAM, storage, CPU dari delta counter `/proc/stat` (dengan fallback pembacaan melalui ADB shell saat akses UID aplikasi ditolak), jumlah logical core, status/error pembacaan CPU, tegangan, dan suhu baterai, uptime, serta sensor thermal yang dapat dibaca.
- **system manager** — baca dan ubah tiga animation scale, baca properti sistem, daftar proses, dan global settings.
- **adb shell terminal** — PTY interaktif melalui Shell v2 dengan fallback ke legacy `shell:` bila inisialisasi Shell v2 gagal, streaming output, input perintah, dan dialog konfirmasi heuristik untuk kelompok perintah berisiko.
- **settings dan activity log** — tema gelap/terang, izin notifikasi, pengaturan optimasi baterai, catatan lokal maksimal 200 event. isi perintah terminal dan kode pairing tidak disimpan dalam log.

## display manager dan safety mode

safety mode aktif secara default dan statusnya disimpan lokal. baseline resolusi diambil dari `Physical size` keluaran `wm size`, bukan ukuran override aktif. baseline logical density mengambil nilai yang sedang efektif saat baseline pertama disimpan (`Override density` bila ada, atau `Physical density` bila tidak), sehingga preferensi density pengguna yang sudah disetel sebelum aplikasi pertama kali dipakai tidak otomatis ditimpa oleh physical density.

preset yang tersedia adalah 100%, 90%, 80%, 75%, 67%, dan 50%. lebar, tinggi, dan logical density dihitung dari baseline dengan faktor yang sama, lalu dibulatkan ke bilangan bulat terdekat. preset yang menghasilkan nilai density di bawah batas valid tidak tersedia. pada safety mode, input manual resolusi/density dikunci; saat safety mode dimatikan dari settings, aplikasi meminta konfirmasi peringatan dan membuka mode manual.

setiap perubahan menerapkan resolusi dan density sebagai dua langkah yang tetap dicoba secara terpisah, lalu memverifikasi nilai aktif. bila salah satu langkah atau verifikasi gagal, aplikasi mencoba memulihkan kedua nilai sebelumnya. preset, pemulihan baseline, dan reset default sistem adalah tindakan terdefinisi: setelah diverifikasi, perubahan langsung dipertahankan tanpa rollback otomatis. hanya konfigurasi manual yang menyimpan state sebelumnya dan memulai countdown rollback 15 detik setelah target berhasil diterapkan dan diverifikasi; pengguna dapat memilih pertahankan sebelum waktu habis. bila pemulihan darurat setelah kegagalan perintah tidak berhasil, service dapat menyimpan percobaan pemulihan segera untuk diulang setelah ADB tersambung. permintaan putus ADB ketika rollback manual tertunda ditunda sampai pemulihan berhasil; rollback tetap memerlukan perangkat dan sesi ADB yang dapat dijangkau.

perkiraan smallest width dihitung dari sisi terpendek resolusi aktif × 160 / logical density; angka ini estimasi, bukan pembacaan langsung `Configuration.smallestScreenWidthDp`, karena window/insets dapat memengaruhi nilai yang dilihat aplikasi lain. density yang lebih rendah pada resolusi tetap menaikkan estimasi dp dan membuat elemen UI berbasis dp tampak lebih kecil. baseline tidak dikarang bila `wm size`/`wm density` tidak mengembalikan nilai fisik yang dapat diparse. pembacaan ulang status tidak menimpa baseline yang sudah tersimpan. reset baseline memakai physical resolution serta logical density pertama yang disimpan.

## perubahan versi 1.2.0

- preset, pulihkan baseline, dan reset default tidak lagi masuk countdown rollback; perubahan tersebut langsung dipertahankan setelah verifikasi. state rollback lama dari versi sebelumnya juga dibersihkan saat safety mode aktif, sedangkan state manual lama dipertahankan.
- konfigurasi manual tetap memakai rollback 15 detik. countdown dimulai setelah perintah `wm` selesai dan target terverifikasi, bukan sebelum penerapan dimulai.
- status dan preview menampilkan estimasi smallest width (dp) agar efek perubahan logical density terlihat sebelum diterapkan.
- minimum android adalah android 12 / api 31.
- perbaikan terminal sebelumnya tetap disertakan: Shell v2 PTY dicoba lebih dulu, dengan fallback ke legacy `shell:` bila inisialisasi Shell v2 gagal.

## alur pairing

1. buka menu adb dan tekan **mulai pairing**. service dimulai dari interaksi foreground pengguna.
2. buka developer options, aktifkan **wireless debugging**, kemudian pilih **pair device with pairing code**.
3. dari ongoing notification, kirim `ip:port` yang tampil pada dialog pairing.
4. kirim kode pairing enam digit pada input notifikasi berikutnya.
5. sesudah pairing sukses, kirim `ip:port` koneksi yang tampil di halaman wireless debugging utama. port ini berbeda dari port pairing.
6. setelah terkoneksi, notifikasi menyediakan tindakan untuk memutus sesi. aplikasi juga menyimpan endpoint koneksi terakhir dan mencoba reconnect setelah service/proses dipulihkan; port bisa berubah sehingga alamat baru dapat diminta setelah reconnect gagal.

pairing code hanya dipakai dalam operasi pairing dan tidak disimpan. private key rsa adb disimpan sebagai ciphertext aes-gcm, sedangkan kunci aes non-exportable disimpan di android keystore. public key adb disimpan terpisah karena memang bukan rahasia.

## build

kebutuhan build: jdk 17 atau lebih baru, koneksi internet pada build pertama, dan android sdk platform 36 + build tools 36.0.0. `gradlew` dan `gradlew.bat` adalah bootstrap launcher proyek: keduanya mengunduh Gradle 8.14.6 dari distribusi resmi, memverifikasi SHA-256, lalu menjalankannya. launcher ini tidak menyertakan `gradle-wrapper.jar` standar.

linux/mac/termux dengan `curl` atau `wget`, `unzip`, dan `sha256sum`/`shasum`:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

windows:

```bat
gradlew.bat testDebugUnitTest
gradlew.bat assembleDebug
```

output apk debug:

```text
app/build/outputs/apk/debug/app-debug.apk
```

github actions pada `.github/workflows/android-build.yml` menjalankan unit test dan `assembleDebug` pada push, pull request, atau workflow dispatch. apk debug diunggah sebagai artifact workflow selama 14 hari. tidak ada signing key release di repository; konfigurasi signing release harus ditambahkan melalui github actions secrets sebelum distribusi release.

## batasan android

aplikasi menggunakan identitas `shell` yang diberikan setelah wireless debugging dipairing. ini **bukan root**. operasi yang memerlukan root, signature permission, device owner, atau system app tetap ditolak android. aplikasi tidak dapat mengaktifkan wireless debugging sendiri atau menyetujui pairing dialog secara otomatis.

foreground service `connectedDevice`, ongoing notification, `stopWithTask=false`, dan `START_STICKY` membantu menjaga sesi selama sistem mengizinkan, tetapi bukan jaminan service tidak dihentikan. bila sesi sebelumnya memang dimulai pengguna, receiver mencoba memulihkannya setelah `BOOT_COMPLETED` dan `MY_PACKAGE_REPLACED`; jika sistem/vendor menolak start tersebut, buka aplikasi dan mulai reconnect secara manual. optimasi baterai dan pengaturan autostart vendor dapat memerlukan tindakan manual. tidak ada api android publik yang seragam untuk membuka seluruh halaman autostart vendor, jadi aplikasi tidak melakukan pemanggilan activity vendor yang spekulatif.

pada profil android selain profil aktif, aplikasi mengambil daftar package melalui adb shell tetapi metadata package manager bisa tidak tersedia bagi proses aplikasi. beberapa versi android/vendor juga menyembunyikan informasi suhu cpu, frekuensi chipset, kapasitas baterai terestimasi, dan statistik lainnya. UI menyatakan data tidak tersedia alih-alih menebaknya.

filter konfirmasi terminal adalah heuristik untuk mengingatkan pengguna, bukan sandbox atau firewall command. terminal sengaja memberi akses shell adb penuh yang tersedia setelah pairing. jangan menjalankan perintah yang tidak lu pahami.

penggunaan `QUERY_ALL_PACKAGES` diperlukan untuk fungsi inventaris aplikasi. distribusi melalui google play tetap perlu ditinjau berdasarkan kebijakan package visibility/play policy yang berlaku.

## aset ikon

UI menggunakan Font Awesome 6 Free Solid dari `app/src/main/assets/fa-solid-900.ttf`. file regular dan brands tetap disertakan sebagai aset font yang tersedia. font ikon dimuat langsung dari assets dan tidak menambah dependency icon pack. aset font mengikuti SIL Open Font License 1.1; lihat `FONT-AWESOME-NOTICE.txt`.

## arsitektur

```text
app/src/main/java/com/devicemanager/
├── MainActivity.kt
├── adb/              # pairing/connection runtime dan foreground service
├── apps/             # inventory aplikasi dan parser multi-user
├── data/             # activity log lokal
├── device/           # metadata perangkat dari android api
├── display/          # kontrol wm size dan density
├── monitor/          # ram, cpu, baterai, storage, thermal
├── notifications/    # channel notifikasi
├── security/         # adb key provider dengan android keystore
├── system/           # animation scale, properties, process, global settings
└── terminal/         # klasifikasi risiko command terminal
```

## adb engine

proyek menggunakan [`adb-kt`](https://github.com/rhythmcache/adb-kt) `com.github.rhythmcache:adb-kt:1.0.13`, implementasi pure kotlin untuk protokol adb termasuk wireless debugging pairing tls/spake2, tls transport, authentication, shell v2, dan pty. aplikasi tidak menjalankan binary `adb`, termux, shizuku, atau root. library upstream dirilis dengan lisensi apache-2.0.

## verifikasi

unit test tersedia untuk parsing endpoint adb, parsing daftar user/package, parsing status display dan pemisahan physical/override, perhitungan skala, parsing counter CPU, dan klasifikasi command berisiko. file project ini sudah diperiksa secara statis dan arsipnya diverifikasi; **build android penuh belum dapat dijalankan di lingkungan pembuat karena android sdk dan gradle tidak tersedia serta jaringan build dibatasi**. gunakan workflow github actions atau lingkungan android sdk untuk hasil kompilasi final dan pengujian pada perangkat nyata.
