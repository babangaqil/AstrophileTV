# Astrophile TV Monitor — Android Native

Overlay di atas input HDMI untuk Android TV.
Terhubung via Firebase client yang sama dengan app billing Sketchware.

---

## CARA BUILD DI ANDROID STUDIO

### Langkah 1 — Install Android Studio
Download di: https://developer.android.com/studio
Install seperti biasa.

### Langkah 2 — Buka Project
1. Buka Android Studio
2. Klik "Open" (bukan New Project)
3. Pilih folder "AstrophileTV" ini
4. Tunggu Gradle sync selesai (bisa 2-5 menit)

### Langkah 3 — Tambah google-services.json (PENTING!)
Karena pakai Firebase, butuh file google-services.json:
1. Buka Firebase Console (console.firebase.google.com)
2. Pilih project Firebase CLIENT kamu (bukan astrophile-rental)
3. Project Settings → Download google-services.json
4. Copy file itu ke folder: AstrophileTV/app/
5. Setelah itu tambah ini di app/build.gradle (paling bawah):
   apply plugin: 'com.google.gms.google-services'
6. Dan di build.gradle (project level), tambah di plugins:
   id 'com.google.gms.google-services' version '4.4.0' apply false

### Langkah 4 — Build APK
1. Menu Build → Build Bundle(s) / APK(s) → Build APK(s)
2. Tunggu proses build selesai
3. Klik "locate" untuk temukan file APK

### Langkah 5 — Install di Android TV
1. Enable Developer Mode di Android TV:
   Settings → Device Preferences → About → Build (klik 7x)
2. Enable USB Debugging atau Install via USB/ADB
3. Install APK

---

## CARA PAKAI

1. Buka app Astrophile TV di Android TV
2. Isi Firebase config (SAMA dengan yang diisi di app billing HP)
3. Isi nomor TV (TV 1 = 1, TV 2 = 2, dst)
4. Klik HUBUNGKAN
5. Berikan izin overlay saat diminta
6. App jalan di background

Setelah itu:
- TV 1 → install app TV 1 dengan tvNum = 1
- TV 2 → install app TV 2 dengan tvNum = 2
- dst...

---

## FITUR

✅ Timer kecil di pojok kanan bawah (transparan)
✅ Toast notif saat sisa 5 menit & 1 menit
✅ Fullscreen blokir animasi saat waktu habis
✅ Alarm berbunyi saat waktu habis
✅ Auto-start saat Android TV nyala
✅ Overlay di ATAS input HDMI (PS3/PS4/PS5)
✅ Terhubung Firebase realtime

---

## STRUKTUR FILE

app/src/main/java/com/astrophile/tvoverlay/
  SetupActivity.java   → Halaman setup config Firebase
  OverlayService.java  → Service overlay utama
  BootReceiver.java    → Auto-start saat TV nyala

app/src/main/res/layout/
  activity_setup.xml   → Tampilan setup
  overlay_widget.xml   → Timer kecil di pojok
  overlay_toast.xml    → Notif toast
  overlay_expired.xml  → Fullscreen waktu habis

app/src/main/res/drawable/
  *.xml → Background shapes

AndroidManifest.xml → Permission & komponen
build.gradle        → Dependencies Firebase

---

© Astrophile Dev 2026
