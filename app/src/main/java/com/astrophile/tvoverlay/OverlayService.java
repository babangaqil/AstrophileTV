package com.astrophile.tvoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

public class OverlayService extends Service {

    private static final String CHANNEL_ID = "astro_tv_channel";
    private static final String PREFS = "astro_tv_prefs";

    private WindowManager windowManager;
    private View widgetView;     // Timer kecil di pojok
    private View expiredView;    // Fullscreen waktu habis
    private View toastView;      // Toast notif
    private View updateView;     // Fullscreen force update

    private Handler mainHandler;
    private Timer tickTimer;
    private ToneGenerator toneGen;

    // WakeLock — cegah Firebase di-throttle saat background
    private PowerManager.WakeLock wakeLock;

    private FirebaseDatabase firebaseDb;
    private DatabaseReference sessionRef;
    private ValueEventListener sessionListener;

    // State
    private boolean isExpired = false;
    private String namaToko = "";
    private boolean isActive = false;
    private long startTime = 0;
    private long duration = 0;
    private long pausedAt  = 0;  // timestamp saat di-pause (0 = tidak pause)
    private String  currentBayarStatus = "belum"; // dipakai showBayarOverlay
    private String mode = "";
    private String namaPelanggan = "";
    private String tvName = "TV 1";
    private int tvNum = 1;

    private boolean toast5Shown = false;
    private boolean toast1Shown = false;
    private boolean alarmPlaying = false;

    @Override
    public void onCreate() {
        super.onCreate();
        checkLicensePeriodic();
        mainHandler = new Handler(Looper.getMainLooper());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForegroundNotification();

        // Acquire WakeLock agar Firebase listener tetap aktif di background
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AstrophileTV:overlay");
        wakeLock.setReferenceCounted(false);
        if (!wakeLock.isHeld()) wakeLock.acquire();

        initOverlayViews();
        initFirebase();
        startTicker();
    }

    // ── FOREGROUND NOTIFICATION ────────────────────────────────
    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Astrophile TV Monitor",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Astrophile TV Monitor")
            .setContentText("Monitoring aktif...")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
        startForeground(1, notification);
    }

    // ── OVERLAY VIEWS ──────────────────────────────────────────
    private void initOverlayViews() {
        LayoutInflater inflater = LayoutInflater.from(this);

        // Widget kecil di pojok kanan bawah
        widgetView = inflater.inflate(R.layout.overlay_widget, null);
        widgetView.setVisibility(View.GONE);

        // Toast notif di atas
        toastView = inflater.inflate(R.layout.overlay_toast, null);
        toastView.setVisibility(View.GONE);

        // Fullscreen expired
        expiredView = inflater.inflate(R.layout.overlay_expired, null);
        expiredView.setVisibility(View.GONE);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

        // Widget params - pojok kanan bawah
        WindowManager.LayoutParams widgetParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        widgetParams.gravity = Gravity.BOTTOM | Gravity.END;
        widgetParams.x = 24;
        widgetParams.y = 24;

        // Toast params - tengah atas
        WindowManager.LayoutParams toastParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        toastParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        toastParams.y = 32;

        // Expired params - fullscreen
        WindowManager.LayoutParams expiredParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        );

        try { windowManager.addView(widgetView,  widgetParams);  } catch (Exception ignored) {}
        try { windowManager.addView(toastView,   toastParams);   } catch (Exception ignored) {}
        try { windowManager.addView(expiredView, expiredParams); } catch (Exception ignored) {}
    }

    // ── FIREBASE ───────────────────────────────────────────────
    private void initFirebase() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String apiKey    = prefs.getString("apiKey", "");
        String dbUrl     = prefs.getString("dbUrl", "");
        String projectId = prefs.getString("projectId", "");
        tvNum  = prefs.getInt("tvNum", 1);
        tvName = prefs.getString("tvName", "TV " + tvNum);

        if (apiKey.isEmpty() || dbUrl.isEmpty()) return;

        try {
            FirebaseApp app;
            try {
                app = FirebaseApp.getInstance("astro_tv");
            } catch (Exception e) {
                FirebaseOptions options = new FirebaseOptions.Builder()
                    .setApiKey(apiKey)
                    .setDatabaseUrl(dbUrl)
                    .setProjectId(projectId)
                    .setApplicationId("1:789474619442:android:5f678d3b6ebdc99a1c8c2b")
                    .build();
                app = FirebaseApp.initializeApp(this, options, "astro_tv");
            }

            firebaseDb = FirebaseDatabase.getInstance(app);
            // setPersistenceEnabled SENGAJA tidak diaktifkan —
            // cache disk menyebabkan onDataChange dipanggil dengan data lama saat reconnect
            // Sinkronisasi realtime lebih penting dari offline cache untuk use-case ini

            sessionRef = firebaseDb.getReference("settings/activeSessions/" + tvNum);

            sessionListener = new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    handleFirebaseData(snapshot);
                }
                @Override
                public void onCancelled(DatabaseError error) {
                    // Reconnect otomatis setelah error — goOnline + re-attach listener
                    mainHandler.postDelayed(() -> {
                        try { firebaseDb.goOnline(); } catch (Exception ignored) {}
                        mainHandler.postDelayed(() -> {
                            if (sessionRef != null && sessionListener != null) {
                                sessionRef.removeEventListener(sessionListener);
                                sessionRef.addValueEventListener(sessionListener);
                                sessionRef.keepSynced(true);
                            }
                        }, 500);
                    }, 2000);
                }
            };
            sessionRef.addValueEventListener(sessionListener);
            sessionRef.keepSynced(true);
            listenTvControl();
            listenStoreName();
            initTTS();

            // Tandai TV online di Firebase
            DatabaseReference tvStatusRef = firebaseDb.getReference("settings/tvStatus/" + tvNum);
            tvStatusRef.setValue(new java.util.HashMap<String, Object>() {{
                put("online", true);
                put("lastSeen", System.currentTimeMillis());
            }});

            // Auto set offline saat TV mati/disconnect dari internet
            tvStatusRef.child("online").onDisconnect().setValue(false);

            // ── Monitor koneksi Firebase (.info/connected) ──────────
            // Saat reconnect setelah putus, re-attach semua listener agar sinkron kembali
            DatabaseReference connectedRef = firebaseDb.getReference(".info/connected");
            connectedRef.addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    boolean connected = Boolean.TRUE.equals(snap.getValue(Boolean.class));
                    if (connected) {
                        // Re-attach session listener
                        if (sessionRef != null && sessionListener != null) {
                            sessionRef.removeEventListener(sessionListener);
                            sessionRef.addValueEventListener(sessionListener);
                            sessionRef.keepSynced(true);
                            // Force fetch dari server — bypass cache lokal
                            sessionRef.get().addOnCompleteListener(task -> {
                                if (task.isSuccessful() && task.getResult() != null) {
                                    handleFirebaseData(task.getResult());
                                }
                            });
                        }
                        // Re-attach tvControl listener (listenTvControl sudah ada removeEventListener internal)
                        mainHandler.postDelayed(() -> {
                            listenTvControl();
                            listenStoreName();
                        }, 500);
                        // Update status online
                        try { tvStatusRef.child("online").setValue(true); } catch (Exception ignored) {}
                        try { tvStatusRef.child("lastSeen").setValue(System.currentTimeMillis()); } catch (Exception ignored) {}
                    } else {
                        // Offline — jadwalkan reconnect manual setelah 5 detik
                        mainHandler.postDelayed(() -> {
                            try { firebaseDb.goOnline(); } catch (Exception ignored) {}
                        }, 5000);
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });

            // Heartbeat: update lastSeen setiap 30 detik
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    try { tvStatusRef.child("lastSeen").setValue(System.currentTimeMillis()); }
                    catch (Exception ignored) {}
                    mainHandler.postDelayed(this, 30000);
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleFirebaseData(DataSnapshot snapshot) {
        if (!snapshot.exists()) {
            mainHandler.post(this::hideAll);
            return;
        }

        Boolean active      = snapshot.child("active").getValue(Boolean.class);
        Boolean expired     = snapshot.child("expired").getValue(Boolean.class);
        Boolean processing  = snapshot.child("processing").getValue(Boolean.class);
        Boolean expiredStop = snapshot.child("expiredStop").getValue(Boolean.class);
        String  modeVal     = snapshot.child("mode").getValue(String.class);
        Long    start       = snapshot.child("start").getValue(Long.class);
        Long    dur         = snapshot.child("duration").getValue(Long.class);
        String  nama        = snapshot.child("namaPelanggan").getValue(String.class);
        Long    pausedAtVal = snapshot.child("pausedAt").getValue(Long.class);

        boolean isAct      = active      != null && active;
        boolean isExp      = expired     != null && expired;
        boolean isProc     = processing  != null && processing;
        boolean isExpStop  = expiredStop != null && expiredStop;
        pausedAt           = pausedAtVal != null ? pausedAtVal : 0;

        // "processing" = kasir sedang di popup bayar setelah Stop diklik.
        // Sembunyikan semua overlay dan tunggu — jangan showWidget lagi.
        if (isProc || "processing".equals(modeVal)) {
            mainHandler.post(this::hideAll);
            return;
        }

        mode          = modeVal != null ? modeVal : "";
        startTime     = start   != null ? start   : 0;
        duration      = dur     != null ? dur     : 0;
        namaPelanggan = nama    != null ? nama    : "";

        mainHandler.post(() -> {
            if (!isAct) {
                // Operator klik Stop + Simpan Transaksi — reset semua overlay untuk sesi baru
                isActive = false;
                hideAll();
            } else if ("reserved".equals(mode)) {
                // Mode reserved = booking dijadwalkan — sembunyikan semua overlay, tampilkan idle
                hideAll();
            } else if (isExp) {
                showExpired();
            } else {
                isExpired = false;
                isActive  = true;
                // Destroy expiredWebView lama agar next pelanggan dapat instance baru
                try {
                    if (expiredWebView != null) {
                        windowManager.removeView(expiredWebView);
                        expiredWebView.destroy();
                        expiredWebView = null;
                    }
                } catch (Exception ignored) {}
                // Hapus sleepView (layar hitam) jika masih ada dari sesi sebelumnya
                // Ini yang bikin layar tetap hitam saat sesi baru mulai setelah sleep
                try {
                    if (sleepView != null) {
                        windowManager.removeView(sleepView);
                        sleepView = null;
                    }
                } catch (Exception ignored) {}
                hideExpired();
                if (startTime > 0) {
                    showWidget();
                    updateWidget();
                }
            }
        });
    }

    // ── TICKER ─────────────────────────────────────────────────
    private void startTicker() {
        tickTimer = new Timer();
        tickTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                mainHandler.post(() -> {
                    if (isActive && !isExpired) {
                        updateWidget(); // updateWidget sudah handle pausedAt internal
                    }
                    // Watchdog: kalau isActive tapi startTime kosong > 3 detik
                    // artinya data Firebase belum masuk — paksa re-fetch
                    if (isActive && startTime == 0) {
                        if (sessionRef != null) {
                            sessionRef.get().addOnCompleteListener(task -> {
                                if (task.isSuccessful() && task.getResult() != null) {
                                    handleFirebaseData(task.getResult());
                                }
                            });
                        }
                    }
                });
            }
        }, 0, 1000);
    }

    // ── WIDGET ─────────────────────────────────────────────────
    private void showWidget() {
        // Widget hanya tampil saat notif 5 menit / 1 menit — tidak langsung visible
        updateWidget();
    }

    private void updateWidget() {
        if (!isActive || startTime == 0) return;

        // Saat paused: gunakan waktu saat pause sebagai acuan (bukan sekarang)
        long effectiveNow = (pausedAt > 0) ? pausedAt : System.currentTimeMillis();
        long secs;
        boolean isCountdown = "countdown".equals(mode);

        if (isCountdown) {
            long elapsed = (effectiveNow - startTime) / 1000;
            secs = Math.max(0, duration - elapsed);
        } else {
            // Billing mode — tidak tampilkan widget sama sekali
            widgetView.setVisibility(View.GONE);
            return;
        }

        // Saat paused — tampilkan sisa waktu tapi tidak update expired trigger
        if (pausedAt > 0) {
            String pausedStr = formatTime(secs);
            TextView tvTimePaused = widgetView.findViewById(R.id.tvWidgetTime);
            TextView tvLblPaused  = widgetView.findViewById(R.id.tvWidgetLabel);
            if (tvTimePaused != null) tvTimePaused.setText(pausedStr);
            if (tvLblPaused  != null) tvLblPaused.setText("⏸ DIJEDA");
            if (secs <= 60)       widgetView.setVisibility(View.VISIBLE);
            else if (secs <= 300) widgetView.setVisibility(View.VISIBLE);
            else                  widgetView.setVisibility(View.GONE);
            return; // jangan proses expired/toast saat paused
        }

        String timeStr = formatTime(secs);
        TextView tvTime  = widgetView.findViewById(R.id.tvWidgetTime);
        TextView tvLabel = widgetView.findViewById(R.id.tvWidgetLabel);
        View     bgView  = widgetView.findViewById(R.id.widgetBg);
        if (tvTime != null) tvTime.setText(timeStr);

        if (secs <= 0) {
            // Waktu habis → tampil fullscreen expired overlay saja
            // Sleep/reset terjadi saat operator klik Stop + Simpan Transaksi
            widgetView.setVisibility(View.GONE);
            if (!isExpired) {
                // Write balik ke Firebase agar kasir langsung tahu
                if (sessionRef != null) {
                    sessionRef.child("expired").setValue(true);
                    sessionRef.child("active").setValue(true);
                }
                showExpired();
            }
            return;
        } else if (secs <= 60) {
            // ≤ 1 menit — tampil terus dengan warna merah
            widgetView.setVisibility(View.VISIBLE);
            if (tvTime != null) tvTime.setTextColor(Color.parseColor("#ff1a50"));
            if (tvLabel != null) tvLabel.setText("SEGERA HABIS!");
            if (bgView != null) bgView.setBackgroundResource(R.drawable.widget_bg_danger);
            if (!toast1Shown) {
                toast1Shown = true;
                speakWarning("Perhatian! Waktu bermain tinggal satu menit. Segera hubungi operator.");
            }
        } else if (secs <= 300) {
            // ≤ 5 menit — tampil sebentar (10 detik) lalu hilang
            if (!toast5Shown) {
                toast5Shown = true;
                widgetView.setVisibility(View.VISIBLE);
                if (tvTime != null) tvTime.setTextColor(Color.parseColor("#ffcc00"));
                if (tvLabel != null) tvLabel.setText("SISA WAKTU");
                if (bgView != null) bgView.setBackgroundResource(R.drawable.widget_bg_warning);
                speakWarning("Perhatian! Waktu bermain tinggal lima menit.");
                mainHandler.postDelayed(() -> {
                    // Setelah 10 detik sembunyikan (kecuali sudah masuk zona 1 menit)
                    long remaining = Math.max(0, duration - ((System.currentTimeMillis() - startTime) / 1000));
                    if (remaining > 60) widgetView.setVisibility(View.GONE);
                }, 10000);
            }
        } else {
            // > 5 menit — sembunyikan widget
            widgetView.setVisibility(View.GONE);
        }
    }

    // ── TOAST ──────────────────────────────────────────────────
    private void showToast(String icon, String text, String color) {
        TextView tvIcon = toastView.findViewById(R.id.tvToastIcon);
        TextView tvText = toastView.findViewById(R.id.tvToastText);
        if (tvIcon != null) tvIcon.setText(icon);
        if (tvText != null) {
            tvText.setText(text);
            try { tvText.setTextColor(Color.parseColor(color)); } catch (Exception e) {}
        }
        toastView.setVisibility(View.VISIBLE);
        mainHandler.postDelayed(() -> toastView.setVisibility(View.GONE), 3500);
    }

    // ── EXPIRED ────────────────────────────────────────────────
    private android.webkit.WebView expiredWebView = null;

    // ── JS Bridge untuk expired.html ──────────────────────────
    public class ExpiredBridge {
        @android.webkit.JavascriptInterface
        public void onSleepCountdownFinished() {
            // Tidak dipakai di alur baru — reset terjadi saat operator Simpan Transaksi
            // Dipanggil sebagai safety fallback dari JS bridge
            mainHandler.post(() -> hideAll());
        }
    }

    // Dipanggil setelah kasir simpan transaksi — trigger sleep countdown di expired WebView
    private void startSleepCountdown() {
        // Tidak dipakai di alur baru — stub untuk kompatibilitas
    }

    private void showExpired() {
        if (isExpired) return;
        isExpired   = true;
        isActive    = false;
        toast5Shown = false;
        toast1Shown = false;

        widgetView.setVisibility(View.GONE);
        toastView.setVisibility(View.GONE);
        expiredView.setVisibility(View.GONE); // sembunyikan XML lama

        // Buat WebView overlay untuk expired
        if (expiredWebView != null) {
            // Reset _sleepStarted agar countdown bisa jalan ulang
            expiredWebView.evaluateJavascript("window._sleepStarted = false;", null);
            expiredWebView.setVisibility(android.view.View.VISIBLE);
            injectExpiredData(expiredWebView);
            return;
        }

        try {
            android.webkit.WebView wv = new android.webkit.WebView(this);
            wv.getSettings().setJavaScriptEnabled(true);
            wv.getSettings().setDomStorageEnabled(true);
            wv.getSettings().setBuiltInZoomControls(false);
            wv.getSettings().setDisplayZoomControls(false);
            wv.setBackgroundColor(android.graphics.Color.BLACK);

            final String fStoreName    = (namaToko.isEmpty() ? "ASTROPHILE" : namaToko).replace("'", "\\'");
            final String fCustomerName = namaPelanggan.isEmpty() ? "" : namaPelanggan.toUpperCase().replace("'", "\\'");
            final String fTvName       = tvName.replace("'", "\\'");
            final String fMode         = mode != null ? mode.toUpperCase() : "PS";

            wv.setWebViewClient(new android.webkit.WebViewClient() {
                @Override
                public void onPageFinished(android.webkit.WebView view, String url) {
                    injectExpiredData(view);
                    // Tidak auto-start sleep — tunggu operator Stop + Simpan Transaksi
                }
            });
            wv.addJavascriptInterface(new ExpiredBridge(), "Android");
            wv.loadUrl("file:///android_asset/expired.html");

            int overlayType = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.OPAQUE
            );
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;

            expiredWebView = wv;
            try { windowManager.addView(expiredWebView, lp); } catch (Exception ignored) {}
        } catch (Exception e) {}

        playAlarm();
    }

    private void injectExpiredData(android.webkit.WebView view) {
        String storeName    = (namaToko.isEmpty() ? "ASTROPHILE" : namaToko).replace("'", "\\'");
        String customerName = namaPelanggan.isEmpty() ? "-" : namaPelanggan.toUpperCase().replace("'", "\\'");
        String tvNum_str    = "TV " + tvNum;
        String modeStr      = mode != null ? mode.toUpperCase() : "PS";

        view.evaluateJavascript(
            "try{" +
            "  var sn=document.querySelectorAll('.store-name');" +
            "  sn.forEach(function(el){el.textContent='" + storeName + "'});" +
            "  var cn=document.querySelectorAll('.name');" +
            "  cn.forEach(function(el){el.textContent='" + customerName + "'});" +
            "  var tv=document.querySelectorAll('.tvrow');" +
            "  tv.forEach(function(el){el.textContent='" + tvNum_str + " \\u00b7 " + modeStr + "'});" +
            "}catch(e){}", null
        );
    }

    private void hideExpired() {
        isExpired = false;
        expiredView.setVisibility(View.GONE);
        if (expiredWebView != null) expiredWebView.setVisibility(android.view.View.GONE);
        stopAlarm();
    }

    private void hideAll() {
        isActive    = false;
        isExpired   = false;
        toast5Shown = false;
        toast1Shown = false;
        pausedAt    = 0; // Reset pausedAt saat hideAll

        // Widget, toast, expired XML — null-safe
        if (widgetView  != null) widgetView.setVisibility(View.GONE);
        if (toastView   != null) toastView.setVisibility(View.GONE);
        if (expiredView != null) expiredView.setVisibility(View.GONE);

        // Expired WebView — destroy sepenuhnya agar JS countdown berhenti
        // Mencegah countdown lama terpanggil di tengah sesi baru
        try {
            if (expiredWebView != null) {
                windowManager.removeView(expiredWebView);
                expiredWebView.destroy();
                expiredWebView = null;
            }
        } catch (Exception ignored) {}

        // Bayar overlay — lepas listener + remove view
        if (bayarStatusRef != null && bayarStatusListener != null) {
            bayarStatusRef.removeEventListener(bayarStatusListener);
            bayarStatusListener = null;
            bayarStatusRef = null;
        }
        try {
            if (bayarOverlayWv != null) {
                windowManager.removeView(bayarOverlayWv);
                bayarOverlayWv = null;
            }
        } catch (Exception ignored) {}

        // Sleep view (layar hitam)
        try {
            if (sleepView != null) {
                windowManager.removeView(sleepView);
                sleepView = null;
            }
        } catch (Exception ignored) {}

        // Time overlay (showtime 5 detik)
        try {
            if (timeOverlayWv != null) {
                windowManager.removeView(timeOverlayWv);
                timeOverlayWv = null;
            }
        } catch (Exception ignored) {}

        stopAlarm();
    }

    // ── TEXT TO SPEECH ─────────────────────────────────────────
    private android.speech.tts.TextToSpeech tts = null;
    private boolean ttsReady = false;

    private void initTTS() {
        try {
            tts = new android.speech.tts.TextToSpeech(this, status -> {
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    // Gunakan bahasa Indonesia
                    int result = tts.setLanguage(new java.util.Locale("id", "ID"));
                    if (result == android.speech.tts.TextToSpeech.LANG_MISSING_DATA ||
                        result == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
                        // Fallback ke bahasa default
                        tts.setLanguage(java.util.Locale.getDefault());
                    }
                    tts.setSpeechRate(0.9f);
                    ttsReady = true;
                }
            });
        } catch (Exception e) {}
    }

    private void speakWarning(String text) {
        if (!ttsReady || tts == null) return;
        try {
            tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "warning_" + System.currentTimeMillis());
        } catch (Exception e) {}
    }

    // ── OTA AUTO UPDATE ───────────────────────────────────────
    private static final String APK_URL =
        "https://github.com/babangaqil/AstrophileTV/releases/latest/download/AstrophileTV.apk";

    private void checkAndDownloadUpdate(String latestVersion) {
        try {
            String currentVersion = getPackageManager()
                .getPackageInfo(getPackageName(), 0).versionName;

            if (isNewerVersion(latestVersion, currentVersion)) {
                mainHandler.post(() -> showUpdateNotification(latestVersion));
                downloadAndInstallApk();
            }
        } catch (Exception e) {}
    }

    private boolean isNewerVersion(String latest, String current) {
        try {
            String[] l = latest.replace("v","").split("\\.");
            String[] c = current.replace("v","").split("\\.");
            for (int i = 0; i < Math.max(l.length, c.length); i++) {
                int lv = i < l.length ? Integer.parseInt(l[i]) : 0;
                int cv = i < c.length ? Integer.parseInt(c[i]) : 0;
                if (lv > cv) return true;
                if (lv < cv) return false;
            }
        } catch (Exception e) {}
        return false;
    }

    private void showUpdateNotification(String version) {
        // Sudah digantikan oleh broadcast ke SetupActivity
        // Tidak ada overlay yang ditampilkan dari sini
    }

    
    private void downloadAndInstallApk() {
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL(APK_URL);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.connect();

                java.io.File apkFile = new java.io.File(getExternalFilesDir(null), "AstrophileTV_update.apk");
                java.io.InputStream input = conn.getInputStream();
                java.io.FileOutputStream output = new java.io.FileOutputStream(apkFile);

                byte[] buffer = new byte[4096];
                int len;
                while ((len = input.read(buffer)) != -1) output.write(buffer, 0, len);

                output.close();
                input.close();
                conn.disconnect();

                mainHandler.post(() -> installApk(apkFile));
            } catch (Exception e) {}
        }).start();
    }

    private void installApk(java.io.File apkFile) {
        try {
            android.net.Uri apkUri;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                apkUri = androidx.core.content.FileProvider.getUriForFile(
                    this, "com.astrophile.tvoverlay.fileprovider", apkFile);
            } else {
                apkUri = android.net.Uri.fromFile(apkFile);
            }

            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK |
                           android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {}
    }

    // ── ALARM SOUND ────────────────────────────────────────────
    private void playAlarm() {
        if (alarmPlaying) return;
        alarmPlaying = true;
        new Thread(() -> {
            try {
                toneGen = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
                while (alarmPlaying) {
                    toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500);
                    Thread.sleep(700);
                    toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300);
                    Thread.sleep(400);
                    toneGen.startTone(ToneGenerator.TONE_CDMA_HIGH_PBX_SLS, 800);
                    Thread.sleep(1200);
                }
            } catch (Exception e) {
                alarmPlaying = false;
            }
        }).start();
    }

    private void stopAlarm() {
        alarmPlaying = false;
        if (toneGen != null) {
            toneGen.release();
            toneGen = null;
        }
    }

    // ── UTILS ──────────────────────────────────────────────────
    private String formatTime(long secs) {
        long h = secs / 3600;
        long m = (secs % 3600) / 60;
        long s = secs % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── RESTART SAAT APP DI-SWIPE KELUAR ──────────────────────
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // Service tidak boleh berhenti saat di-swipe — langsung restart
        // Coba start ulang segera sebagai backup pertama
        try {
            Intent immediateRestart = new Intent(this, OverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(immediateRestart);
            } else {
                startService(immediateRestart);
            }
        } catch (Exception ignored) {}
        // Jadwalkan restart via AlarmManager sebagai backup kedua
        int piFlags = android.app.PendingIntent.FLAG_ONE_SHOT |
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? android.app.PendingIntent.FLAG_IMMUTABLE
                : android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.PendingIntent restartIntent = android.app.PendingIntent.getService(
            this, 1,
            new Intent(this, OverlayService.class),
            piFlags
        );
        android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) {
            // setExactAndAllowWhileIdle: restart tepat 2 detik, tidak delay seperti set() di Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + 2000,
                    restartIntent);
            } else {
                am.set(android.app.AlarmManager.ELAPSED_REALTIME,
                    android.os.SystemClock.elapsedRealtime() + 2000,
                    restartIntent);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Jangan self-restart dari onDestroy — menyebabkan crash IllegalStateException di Android 12+
        // Restart sudah ditangani oleh onTaskRemoved + AlarmManager + START_STICKY
        if (tickTimer != null) tickTimer.cancel();
        if (sessionRef != null && sessionListener != null) {
            sessionRef.removeEventListener(sessionListener);
        }
        stopAlarm();
        // WakeLock tidak di-release agar Firebase tetap aktif saat restart otomatis
        // Sistem Android akan release otomatis jika service benar-benar dihentikan paksa
        // Tandai TV offline
        if (firebaseDb != null) {
            try {
                firebaseDb.getReference("settings/tvStatus/" + tvNum + "/online").setValue(false);
            } catch (Exception ignored) {}
        }
        // Individual try-catch tiap view — satu crash tidak stop cleanup lainnya
        try { if (widgetView    != null) windowManager.removeView(widgetView);    } catch (Exception ignored) {}
        try { if (toastView     != null) windowManager.removeView(toastView);     } catch (Exception ignored) {}
        try { if (expiredView   != null) windowManager.removeView(expiredView);   } catch (Exception ignored) {}
        try { if (expiredWebView!= null) { expiredWebView.destroy(); windowManager.removeView(expiredWebView); } } catch (Exception ignored) {}
        try { if (timeOverlayWv != null) windowManager.removeView(timeOverlayWv); } catch (Exception ignored) {}
        try { if (sleepView     != null) windowManager.removeView(sleepView);     } catch (Exception ignored) {}
        try { if (bayarOverlayWv!= null) windowManager.removeView(bayarOverlayWv);} catch (Exception ignored) {}
        try { if (bayarStatusRef != null && bayarStatusListener != null)
                bayarStatusRef.removeEventListener(bayarStatusListener);          } catch (Exception ignored) {}
        if (tts != null) { try { tts.stop(); tts.shutdown(); } catch(Exception ignored){} tts = null; }
        if (globalUpdateRef != null && globalUpdateListener != null)
            globalUpdateRef.removeEventListener(globalUpdateListener);
        if (tvControlRef != null && tvControlListener != null)
            tvControlRef.removeEventListener(tvControlListener);
    }

    private com.google.firebase.database.ValueEventListener licenseListener = null;
    private com.google.firebase.database.DatabaseReference licenseRef = null;

    private void checkLicensePeriodic() {
        String key = LicenseManager.getSavedKey(this);
        String deviceId = LicenseManager.getSavedDeviceId(this);
        if (key.isEmpty()) return;
        String keyHash = LicenseManager.hashKey(key.replace("-", "").toUpperCase());
        try {
            com.google.firebase.FirebaseApp masterApp;
            try { masterApp = com.google.firebase.FirebaseApp.getInstance("_tv_license"); }
            catch (Exception e) {
                com.google.firebase.FirebaseOptions opts = new com.google.firebase.FirebaseOptions.Builder()
                    .setApiKey("AIzaSyD8XffAZK8JUOBajCUVyPS-NT9jnwYBats")
                    .setDatabaseUrl("https://astrophile-rental-default-rtdb.firebaseio.com")
                    .setProjectId("astrophile-rental")
                    .setApplicationId("1:789474619442:android:5f678d3b6ebdc99a1c8c2b")
                    .build();
                masterApp = com.google.firebase.FirebaseApp.initializeApp(this, opts, "_tv_license");
            }
            licenseRef = com.google.firebase.database.FirebaseDatabase.getInstance(masterApp)
                .getReference("tvLicenseKeys/" + keyHash);
            licenseListener = new com.google.firebase.database.ValueEventListener() {
                @Override
                public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                    if (!snap.exists()) { blockOverlay(); return; }
                    Boolean revoked = snap.child("revoked").getValue(Boolean.class);
                    Long expiredAt = snap.child("expiredAt").getValue(Long.class);
                    if (Boolean.TRUE.equals(revoked)) { blockOverlay(); return; }
                    if (expiredAt != null && System.currentTimeMillis() > expiredAt) { blockOverlay(); return; }
                    if (!deviceId.isEmpty()) {
                        com.google.firebase.database.DataSnapshot dev = snap.child("devices").child(deviceId);
                        if (dev.exists() && Boolean.TRUE.equals(dev.child("revoked").getValue(Boolean.class))) {
                            blockOverlay(); return;
                        }
                    }
                    // ── Cek force update per toko ──
                    com.google.firebase.database.DataSnapshot fu = snap.child("forceUpdate");
                    Boolean fuEnabled = fu.child("enabled").getValue(Boolean.class);
                    if (Boolean.TRUE.equals(fuEnabled)) {
                        String fuVersion = fu.child("version").getValue(String.class);
                        String fuUrl     = fu.child("url").getValue(String.class);
                        String fuMsg     = fu.child("message").getValue(String.class);
                        mainHandler.post(() -> showForceUpdate(
                            fuVersion != null ? fuVersion : "Terbaru",
                            fuUrl     != null ? fuUrl     : "",
                            fuMsg     != null ? fuMsg     : "Pembaruan tersedia. Silakan update aplikasi."
                        ));
                        return; // sudah ditangani, skip global check
                    }
                    // ── Cek global update berdasarkan versi ──
                    checkGlobalUpdate();
                }
                @Override
                public void onCancelled(com.google.firebase.database.DatabaseError e) {}
            };
            licenseRef.addValueEventListener(licenseListener);
        } catch (Exception e) {}
    }

    private com.google.firebase.database.ValueEventListener globalUpdateListener = null;
    private com.google.firebase.database.DatabaseReference  globalUpdateRef      = null;

    private String storeName = "";

    private void listenStoreName() {
        try {
            firebaseDb.getReference("settings/namaToko")
                .addValueEventListener(new com.google.firebase.database.ValueEventListener() {
                    @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                        String name = snap.getValue(String.class);
                        if (name == null || name.isEmpty()) return;
                        storeName = name;
                        namaToko  = name; // sync ke field yang dipakai overlay expired

                        mainHandler.post(() -> {
                            // Update WebView expired jika sedang tampil
                            if (expiredWebView != null && expiredWebView.getVisibility() == android.view.View.VISIBLE) {
                                injectExpiredData(expiredWebView);
                            }
                            // Update overlay expired XML jika sedang tampil (fallback)
                            if (expiredView != null && expiredView.getVisibility() == android.view.View.VISIBLE) {
                                android.widget.TextView tv = expiredView.findViewById(R.id.tvNamaToko);
                                if (tv != null) tv.setText(name.toUpperCase());
                            }
                        });
                    }
                    @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {}
                });
        } catch (Exception e) {}
    }

    private com.google.firebase.database.ValueEventListener tvControlListener = null;
    private com.google.firebase.database.DatabaseReference  tvControlRef      = null;

    private void listenTvControl() {
        try {
            if (tvControlRef != null && tvControlListener != null)
                tvControlRef.removeEventListener(tvControlListener);

            tvControlRef = firebaseDb.getReference("settings/tvControl/" + tvNum);
            tvControlListener = new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                    if (!snap.exists()) return;
                    String cmd = snap.child("cmd").getValue(String.class);
                    if (cmd == null || cmd.equals("none")) return;
                    mainHandler.post(() -> {
                        switch (cmd) {
                            case "sleep":
                                showSleep();
                                break;
                            case "wake":
                                hideSleep();
                                break;
                            case "showtime":
                                showTimeOverlay();
                                try { snap.getRef().child("cmd").setValue("none"); } catch (Exception ignored) {}
                                break;
                            case "showbayar":
                                // Baca status AGREGAT (main + items + tambahan waktu)
                                // dari path bayarStatusOverlay agar singkron dengan badge kasir
                                String bs = snap.child("bayarStatusOverlay").getValue(String.class);
                                if (bs == null) bs = snap.child("bayarStatus").getValue(String.class); // fallback ke main jika belum ada
                                showBayarOverlay(bs != null ? bs : "belum");
                                try { snap.getRef().child("cmd").setValue("none"); } catch (Exception ignored) {}
                                break;
                            case "hidebayar":
                                hideBayarOverlay();
                                try { snap.getRef().child("cmd").setValue("none"); } catch (Exception ignored) {}
                                break;
                        }
                    });
                }
                @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {}
            };
            tvControlRef.addValueEventListener(tvControlListener);
        } catch (Exception e) {}
    }

    private View sleepView = null;

    // ── BAYAR OVERLAY (toggle on/off) ─────────────────────────────
    private com.google.firebase.database.ValueEventListener bayarStatusListener = null;
    private com.google.firebase.database.DatabaseReference  bayarStatusRef      = null;

    private android.webkit.WebView bayarOverlayWv = null;

    private void showBayarOverlay(final String bayarStatusInit) {
        currentBayarStatus = bayarStatusInit != null ? bayarStatusInit : "belum";
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    if (bayarOverlayWv != null) {
                        try { windowManager.removeView(bayarOverlayWv); } catch (Exception ignored) {}
                        bayarOverlayWv = null;
                    }
                    int overlayType = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                        ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : android.view.WindowManager.LayoutParams.TYPE_PHONE;
                    android.view.WindowManager.LayoutParams params = new android.view.WindowManager.LayoutParams(
                        android.view.WindowManager.LayoutParams.MATCH_PARENT,
                        android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                        overlayType,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        android.graphics.PixelFormat.TRANSLUCENT
                    );
                    // Full width agar bisa center, posisi tepat di bawah widget timer
                    params.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
                    params.x = 0;
                    params.y = 4;

                    android.webkit.WebView wv = new android.webkit.WebView(getApplicationContext());
                    wv.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                    wv.getSettings().setJavaScriptEnabled(true);
                    wv.loadUrl("file:///android_asset/bayaroverlay.html?bayarStatus=" + currentBayarStatus);
                    bayarOverlayWv = wv;
                    try { windowManager.addView(bayarOverlayWv, params); } catch (Exception ignored) {}
                } catch (Exception e) {
                    android.util.Log.e("Astrophile", "showBayarOverlay error: " + e.getMessage());
                }
            }
        });

        // Listen perubahan bayarStatus dari Firebase secara realtime
        if (firebaseDb != null) {
            // Jangan removeView di sini — bayarOverlayWv baru saja dibuat di mainHandler.post
            // removeView di sini menyebabkan overlay hilang sebelum tampil (race condition)
            if (bayarStatusRef != null && bayarStatusListener != null) {
                bayarStatusRef.removeEventListener(bayarStatusListener);
            }
            // Listen path bayarStatusOverlay (status AGREGAT) — bukan bayarStatus (main sewa PS).
            // Agar overlay TV singkron dengan badge kasir saat ada item/tambahan waktu belum bayar.
            bayarStatusRef = firebaseDb.getReference("settings/activeSessions/" + tvNum + "/bayarStatusOverlay");
            bayarStatusListener = new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                    final String status = snap.exists() && snap.getValue() != null
                        ? snap.getValue(String.class) : "belum";
                    currentBayarStatus = status;
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            if (bayarOverlayWv != null) {
                                bayarOverlayWv.evaluateJavascript("updateStatus('" + status + "')", null);
                            }
                        }
                    });
                }
                @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {}
            };
            bayarStatusRef.addValueEventListener(bayarStatusListener);
        }
    }

    private void hideBayarOverlay() {
        if (bayarStatusRef != null && bayarStatusListener != null) {
            bayarStatusRef.removeEventListener(bayarStatusListener);
            bayarStatusListener = null;
            bayarStatusRef = null;
        }
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    if (bayarOverlayWv != null) {
                        windowManager.removeView(bayarOverlayWv);
                        bayarOverlayWv = null;
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    // ── SHOW TIME OVERLAY (5 detik) ──────────────────────────────
    private android.webkit.WebView timeOverlayWv = null;

    private void showTimeOverlay() {
        if (!isActive) return;
        final String modeVal = (mode != null) ? mode : "countdown";
        final int tvNumVal = tvNum;
        final boolean isPaused = pausedAt > 0;
        final long effectiveNow = (pausedAt > 0) ? pausedAt : System.currentTimeMillis();

        // Hitung nilai yang dikirim ke timeoverlay.html
        final long totalSec;
        final long sisaSec;
        if ("billing".equals(modeVal)) {
            // Billing: tidak ada durasi tetap — kirim elapsed sebagai sisaSec
            // timeoverlay.html akan hitung (nowMs - fbStartTime)/1000 untuk durasi berjalan
            totalSec = 0;
            sisaSec  = (effectiveNow - startTime) / 1000; // elapsed untuk billing
        } else {
            // Countdown: hitung sisa waktu
            long elapsed = (effectiveNow - startTime) / 1000;
            totalSec = duration;
            sisaSec  = Math.max(0, duration - elapsed);
        }

        mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    // Hapus overlay sebelumnya jika masih ada
                    if (timeOverlayWv != null) {
                        try { windowManager.removeView(timeOverlayWv); } catch (Exception ignored) {}
                        timeOverlayWv = null;
                    }

                    int overlayType = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                        ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : android.view.WindowManager.LayoutParams.TYPE_PHONE;

                    android.view.WindowManager.LayoutParams params = new android.view.WindowManager.LayoutParams(
                        android.view.WindowManager.LayoutParams.MATCH_PARENT,
                        android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                        overlayType,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        android.graphics.PixelFormat.TRANSLUCENT
                    );
                    params.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
                    params.y = 0;

                    android.webkit.WebView wv = new android.webkit.WebView(getApplicationContext());
                    wv.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                    wv.getSettings().setJavaScriptEnabled(true);
                    wv.getSettings().setDomStorageEnabled(true);

                    // Inject data via URL parameter
                    // Kirim startTime Firebase (ms) agar timer sync dengan sesi asli
                    String url = "file:///android_asset/timeoverlay.html"
                        + "?mode=" + android.net.Uri.encode(modeVal)
                        + "&tvNum=" + tvNumVal
                        + "&totalSec=" + totalSec
                        + "&sisaSec=" + sisaSec
                        + "&fbStartTime=" + startTime
                        + "&loadMs=" + System.currentTimeMillis()
                        + "&paused=" + (isPaused ? "1" : "0");
                    wv.loadUrl(url);
                    timeOverlayWv = wv;
                    try { windowManager.addView(timeOverlayWv, params); } catch (Exception ignored) {}

                    // Hapus setelah 5.5 detik (0.5 detik extra untuk animasi fadeout)
                    mainHandler.postDelayed(new Runnable() {
                        @Override public void run() {
                            try {
                                if (timeOverlayWv != null) {
                                    windowManager.removeView(timeOverlayWv);
                                    timeOverlayWv = null;
                                }
                            } catch (Exception ignored) {}
                        }
                    }, 5500);

                } catch (Exception e) {
                    android.util.Log.e("Astrophile", "showTimeOverlay error: " + e.getMessage());
                }
            }
        });
    }

    private void showSleep() {
        if (sleepView != null) return;
        try {
            android.view.View black = new android.view.View(OverlayService.this);
            black.setBackgroundColor(android.graphics.Color.BLACK);

            int overlayType = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.OPAQUE
            );
            sleepView = black;
            try { windowManager.addView(sleepView, lp); } catch (Exception ignored) {}
        } catch (Exception e) {}
    }

    private void hideSleep() {
        try {
            if (sleepView != null) { windowManager.removeView(sleepView); sleepView = null; }
        } catch (Exception e) { sleepView = null; }
    }

    private void checkGlobalUpdate() {
        try {
            // Real-time listener agar langsung update saat admin ubah
            if (globalUpdateRef != null && globalUpdateListener != null) {
                globalUpdateRef.removeEventListener(globalUpdateListener);
            }
            globalUpdateRef = com.google.firebase.database.FirebaseDatabase
                .getInstance(getMasterApp())
                .getReference("settings/globalUpdate");

            globalUpdateListener = new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                    if (!snap.exists()) return;
                    Boolean enabled = snap.child("enabled").getValue(Boolean.class);
                    if (!Boolean.TRUE.equals(enabled)) {
                        // Update dimatikan → kirim broadcast sembunyikan button di SetupActivity
                        sendBroadcast(new android.content.Intent("com.astrophile.tvoverlay.UPDATE_CLEAR"));
                        return;
                    }
                    String minVersion = snap.child("minVersion").getValue(String.class);
                    String url        = snap.child("url").getValue(String.class);
                    String message    = snap.child("message").getValue(String.class);
                    if (minVersion == null || minVersion.isEmpty()) return;

                    String currentVersion = "";
                    try {
                        currentVersion = getPackageManager()
                            .getPackageInfo(getPackageName(), 0).versionName;
                    } catch (Exception e) { return; }

                    if (isVersionLower(currentVersion, minVersion)) {
                        final String fUrl = url != null ? url : APK_URL;
                        final String fMsg = message != null ? message : "Pembaruan tersedia. Sedang download...";
                        final String fVer = "v" + minVersion;
                        // Auto download OTA
                        checkAndDownloadUpdate(minVersion);
                        mainHandler.post(() -> showForceUpdate(fVer, fUrl, fMsg));
                    }
                }
                @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {}
            };
            globalUpdateRef.addValueEventListener(globalUpdateListener);
        } catch (Exception e) {}
    }

    // Bandingkan versi: "1.0" < "1.2" → true
    private boolean isVersionLower(String current, String minimum) {
        try {
            String[] c = current.split("[.\\-]");
            String[] m = minimum.split("[.\\-]");
            int len = Math.max(c.length, m.length);
            for (int i = 0; i < len; i++) {
                int cv = i < c.length ? Integer.parseInt(c[i].replaceAll("[^0-9]", "0")) : 0;
                int mv = i < m.length ? Integer.parseInt(m[i].replaceAll("[^0-9]", "0")) : 0;
                if (cv < mv) return true;
                if (cv > mv) return false;
            }
        } catch (Exception e) {}
        return false;
    }

    private FirebaseApp getMasterApp() {
        try { return FirebaseApp.getInstance("_tv_license"); }
        catch (Exception e) {
            return FirebaseApp.initializeApp(this, new com.google.firebase.FirebaseOptions.Builder()
                .setApiKey("AIzaSyD8XffAZK8JUOBajCUVyPS-NT9jnwYBats")
                .setDatabaseUrl("https://astrophile-rental-default-rtdb.firebaseio.com")
                .setProjectId("astrophile-rental")
                .setApplicationId("1:789474619442:android:5f678d3b6ebdc99a1c8c2b")
                .build(), "_tv_license");
        }
    }

    private void showForceUpdate(String version, String url, String message) {
        // Jangan tampilkan overlay — kirim broadcast ke SetupActivity agar muncul button update di sana
        android.content.Intent intent = new android.content.Intent("com.astrophile.tvoverlay.UPDATE_AVAILABLE");
        intent.putExtra("version", version);
        intent.putExtra("url",     url != null ? url : "");
        intent.putExtra("message", message != null ? message : "");
        sendBroadcast(intent);
    }

    
    private void blockOverlay() {
        LicenseManager.clearLicense(this);
        stopSelf();
        android.content.Intent i = new android.content.Intent(this, SetupActivity.class);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // ── Cek versi — pastikan bukan service lama yang masih jalan setelah update ──
        int runningVersionCode = getSharedPreferences("astro_tv_svc", MODE_PRIVATE)
            .getInt("running_version_code", -1);
        int currentVersionCode = -1;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                currentVersionCode = (int) getPackageManager().getPackageInfo(
                    getPackageName(),
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                ).getLongVersionCode();
            } else {
                @SuppressWarnings("deprecation")
                android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
                currentVersionCode = pi.versionCode;
            }
        } catch (Exception ignored) {}

        if (runningVersionCode != -1 && currentVersionCode != -1
                && runningVersionCode != currentVersionCode) {
            // Versi berbeda setelah APK update — update SharedPrefs dan lanjut init normal
            // Tidak perlu stopSelf()+restart — BootReceiver sudah handle stop+start saat MY_PACKAGE_REPLACED
            // Manual restart di sini bisa menyebabkan double instance dan overlay tidak muncul
            android.util.Log.i("AstroTV", "Version updated " + runningVersionCode
                + " → " + currentVersionCode + ", reinitializing...");
            getSharedPreferences("astro_tv_svc", MODE_PRIVATE).edit()
                .putInt("running_version_code", currentVersionCode).apply();
            // Reinit semua: overlay views + Firebase listeners
            try { initOverlayViews(); } catch (Exception ignored) {}
            initFirebase();
            return START_STICKY;
        }

        // Simpan versionCode yang sedang jalan
        if (currentVersionCode != -1) {
            getSharedPreferences("astro_tv_svc", MODE_PRIVATE).edit()
                .putInt("running_version_code", currentVersionCode).apply();
        }

        // Reconnect Firebase penuh saat service di-restart oleh sistem (START_STICKY)
        if (sessionRef == null || sessionListener == null || firebaseDb == null) {
            initFirebase();
        } else {
            sessionRef.removeEventListener(sessionListener);
            sessionRef.addValueEventListener(sessionListener);
            sessionRef.keepSynced(true);
            listenTvControl();
            listenStoreName();
            try { firebaseDb.goOnline(); } catch (Exception ignored) {}
        }
        return START_STICKY;
    }

}
