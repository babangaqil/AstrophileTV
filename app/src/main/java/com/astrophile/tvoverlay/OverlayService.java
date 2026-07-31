package com.astrophile.tvoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import org.json.JSONObject;
import java.net.InetAddress;
import java.net.NetworkInterface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.Locale;

/**
 * OverlayService — Versi offline bersih tanpa Firebase.
 * Semua sesi diterima via LAN (LocalHttpServer port 8080).
 */
public class OverlayService extends Service {

    private static final String TAG        = "AstroOverlay";
    private static final String CHANNEL_ID = "astro_tv_channel";
    private static final String PREFS      = "astro_tv_prefs";

    // Managers
    private SessionManager    sessionManager;
    private WebViewManager    webViewManager;
    private TimerManager      timerManager;
    private AstroAudioManager audioManager;
    private LocalHttpServer   localHttpServer;

    // UI views
    private View widgetView;
    private WindowManager.LayoutParams widgetParams;
    private View expiredView;
    private View toastView;

    // System services
    private WindowManager         windowManager;
    private Handler               mainHandler;
    private PowerManager.WakeLock wakeLock;

    // TV info
    private int    tvNum  = 1;
    private String tvName = "TV 1";

    // State
    private boolean isShowingTimeOverlay = false;
    private String  currentBayarStatus   = "belum";

    // TTS
    private android.speech.tts.TextToSpeech tts      = null;
    private boolean                          ttsReady = false;

    // Sleep view
    private View sleepView = null;

    // Time overlay
    private android.webkit.WebView timeOverlayWv = null;

    // Bayar overlay
    private android.webkit.WebView bayarOverlayWv = null;

    // Widget CountDown
    private android.os.CountDownTimer widgetCountDown = null;

    // =========================================================
    // LIFECYCLE
    // =========================================================

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate()");

        mainHandler   = new Handler(Looper.getMainLooper());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AstrophileTV:overlay");
        wakeLock.setReferenceCounted(false);
        if (!wakeLock.isHeld()) wakeLock.acquire();

        startForegroundNotification();
        initManagers();
        initOverlayViews();
        initFromPrefs();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand()");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy()");

        timerManager.destroyAll();
        stopWidgetCountDown();
        if (localHttpServer != null) localHttpServer.stop();
        webViewManager.destroyAll();
        audioManager.destroy();

        if (tts != null) {
            try { tts.stop(); tts.shutdown(); }
            catch (Exception e) { Log.e(TAG, "tts shutdown: " + e.getMessage()); }
            tts = null;
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }

        safeRemoveView(widgetView,  "widgetView");
        safeRemoveView(toastView,   "toastView");
        safeRemoveView(expiredView, "expiredView");
        safeRemoveView(sleepView,   "sleepView");
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        scheduleRestart();
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }

    // =========================================================
    // INIT
    // =========================================================

    private void initManagers() {
        sessionManager = new SessionManager();
        webViewManager = new WebViewManager(this, windowManager);
        timerManager   = new TimerManager(mainHandler);
        audioManager   = new AstroAudioManager();

        sessionManager.setListener(new SessionManager.SessionListener() {
            @Override public void onSessionStarted(boolean isNewSession, boolean durationChanged) {
                mainHandler.post(() -> {
                    if (sessionManager.isExpired()) return;

                    if (isNewSession) {
                        // ── Hard reset state hanya untuk sesi baru ────────
                        stopWidgetCountDown();
                        webViewManager.destroyAll();
                        audioManager.stopAlarm();
                        isShowingTimeOverlay = false;
                        currentBayarStatus   = "belum";
                        hideBayarOverlay();

                        // Sembunyikan semua view lama
                        if (widgetView  != null) widgetView.setVisibility(View.GONE);
                        if (toastView   != null) toastView.setVisibility(View.GONE);
                        if (expiredView != null) expiredView.setVisibility(View.GONE);

                        // Reset flags hanya di sesi baru
                        sessionManager.setToast5Shown(false);
                        sessionManager.setToast1Shown(false);

                        // Wake TV kalau lagi sleep
                        if (sleepView != null) hideSleep();
                    } else if (durationChanged) {
                        // ── Tambah waktu / bonus waktu dari kasir ─────────
                        // PENTING: kalau sebelumnya sesi ini WAKTU HABIS,
                        // overlay fullscreen + alarm HARUS ditutup di sini juga —
                        // sebelumnya cuma widget kecil yang di-restart, overlay
                        // fullscreen & alarm tetap nyala nutupin layar TV.
                        webViewManager.destroyExpiredOverlay();
                        audioManager.stopAlarm();
                        if (expiredView != null) expiredView.setVisibility(View.GONE);

                        // Restart countdown agar sisa waktu dihitung ulang
                        stopWidgetCountDown();
                        Log.d(TAG, "durationChanged — restart widgetCountDown");
                        // Kalau setelah tambah waktu sisa > 5 menit lagi,
                        // reset flag agar overlay 5 menit bisa muncul kembali
                        if (sessionManager.getRemainingSeconds() > 300) {
                            sessionManager.setToast5Shown(false);
                            isShowingTimeOverlay = false;
                        }
                        // Reset toast 1 menit juga kalau sisa > 1 menit
                        if (sessionManager.getRemainingSeconds() > 60) {
                            sessionManager.setToast1Shown(false);
                        }
                    }

                    // Update widget (sesi baru, duration berubah, maupun sync biasa)
                    if (sessionManager.getStartTime() > 0) updateWidget();
                });
            }
            @Override public void onSessionExpired() {
                mainHandler.post(() -> showExpiredOverlay());
            }
            @Override public void onSessionReset() {
                mainHandler.post(() -> {
                    hideAll();
                    hideBayarOverlay();
                    currentBayarStatus = "belum";
                    // Sesi bersih → TV masuk sleep
                    mainHandler.postDelayed(() -> showSleep(), 500);
                });
            }
        });

        // HTTP server LAN — satu-satunya sumber perintah kasir
        try {
            localHttpServer = new LocalHttpServer(payload -> {
                sendBroadcast(new Intent("com.astrophile.tvoverlay.KASIR_HIT"));
                mainHandler.post(() -> handleLocalCommand(payload));
            });
            localHttpServer.start();
            Log.i(TAG, "LocalHttpServer started on port " + LocalHttpServer.PORT);
        } catch (Exception e) {
            Log.e(TAG, "Gagal start HTTP server: " + e.getMessage());
        }
    }

    private void initFromPrefs() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        tvNum  = prefs.getInt("tvNum", 1);
        tvName = prefs.getString("tvName", "TV " + tvNum);
        String namaToko = prefs.getString("namaToko", "ASTROPHILE");
        sessionManager.setTvNum(tvNum);
        sessionManager.setTvName(tvName);
        sessionManager.setNamaToko(namaToko);

        startTicker();
        initTTS();
        Log.i(TAG, "OverlayService ready — TV " + tvNum + " | LAN only");
    }

    // =========================================================
    // TICKER
    // =========================================================

    private void startTicker() {
        timerManager.startTicker(() -> {
            if (!sessionManager.isActive() || sessionManager.isExpired()) return;
            updateWidget();
        });

        timerManager.startWatchdog(() -> {
            Log.e(TAG, "WATCHDOG: ticker frozen — restarting");
            startTicker();
        });
    }

    // =========================================================
    // WIDGET
    // =========================================================

    private void startWidgetCountDown(long sisaMs) {
        stopWidgetCountDown();
        if (sisaMs <= 0) return;

        widgetCountDown = new android.os.CountDownTimer(sisaMs, 1000) {
            @Override public void onTick(long ms) {
                renderWidget(ms / 1000);
            }
            @Override public void onFinish() {
                renderWidget(0);
                widgetView.setVisibility(View.GONE);
                sessionManager.markExpired();
            }
        }.start();
        Log.d(TAG, "startWidgetCountDown sisaMs=" + sisaMs);
    }

    private void stopWidgetCountDown() {
        if (widgetCountDown != null) {
            widgetCountDown.cancel();
            widgetCountDown = null;
        }
    }

    private void renderWidget(long secs) {
        if (widgetView == null) return;
        TextView tvTime  = widgetView.findViewById(R.id.tvWidgetTime);
        TextView tvLabel = widgetView.findViewById(R.id.tvWidgetLabel);
        View     bgView  = widgetView.findViewById(R.id.widgetBg);

        if (tvTime != null) tvTime.setText(formatTime(secs));

        if (secs <= 60) {
            widgetView.setVisibility(View.VISIBLE);
            if (tvTime  != null) tvTime.setTextColor(Color.parseColor("#ff1a50"));
            if (tvLabel != null) tvLabel.setText("SEGERA HABIS!");
            if (bgView  != null) bgView.setBackgroundResource(R.drawable.widget_bg_danger);
        } else {
            widgetView.setVisibility(View.GONE);
        }

        try {
            if (widgetParams != null) windowManager.updateViewLayout(widgetView, widgetParams);
        } catch (Exception ignored) {}
    }

    private void updateWidget() {
        if (!sessionManager.isActive() || sessionManager.getStartTime() == 0) return;
        if (sessionManager.isBilling()) {
            widgetView.setVisibility(View.GONE);
            stopWidgetCountDown();
            return;
        }

        long secs = sessionManager.getRemainingSeconds();

        if (sessionManager.isPaused()) {
            stopWidgetCountDown();
            renderWidget(secs);
            return;
        }

        // ── Auto-trigger 5 menit: deteksi dari timer TV sendiri ──
        if (secs <= 300 && secs >= 299 && !sessionManager.isToast5Shown()) {
            sessionManager.setToast5Shown(true);
            showTimeOverlay(true); // tampil overlay + TTS otomatis dari timer
        }

        // ── Auto-trigger 1 menit ──────────────────────────────
        if (secs <= 60 && secs >= 59 && !sessionManager.isToast1Shown()) {
            sessionManager.setToast1Shown(true);
            speakWarning("Perhatian! Waktu bermain tinggal satu menit. Segera hubungi operator.");
        }

        // Selalu restart countdown agar sisa waktu akurat setelah tambah/bonus waktu
        startWidgetCountDown(secs * 1000L);
    }

    // =========================================================
    // EXPIRED OVERLAY
    // =========================================================

    private void showExpiredOverlay() {
        Log.d(TAG, "showExpiredOverlay()");
        widgetView.setVisibility(View.GONE);
        toastView.setVisibility(View.GONE);
        expiredView.setVisibility(View.GONE);

        webViewManager.getOrCreateExpiredOverlay(
            "file:///android_asset/expired.html",
            () -> injectExpiredData(webViewManager.getExpiredOverlay())
        );

        if (!webViewManager.isExpiredAttached()) {
            webViewManager.attachExpiredOverlay(makeFullscreenParams(PixelFormat.OPAQUE));
        } else {
            android.webkit.WebView ev = webViewManager.getExpiredOverlay();
            if (ev != null) ev.setVisibility(View.VISIBLE);
        }
    }

    private void injectExpiredData(android.webkit.WebView view) {
        if (view == null) return;
        String sn = sessionManager.getNamaToko().replace("'", "\\'");
        String cn = sessionManager.getNamaPelanggan().isEmpty()
                    ? "-" : sessionManager.getNamaPelanggan().toUpperCase().replace("'", "\\'");
        String tn = "TV " + sessionManager.getTvNum();
        String md = sessionManager.getMode() != null ? sessionManager.getMode().toUpperCase() : "PS";

        view.evaluateJavascript(
            "try{" +
            "document.querySelectorAll('.store-name').forEach(e=>e.textContent='" + sn + "');" +
            "document.querySelectorAll('.name').forEach(e=>e.textContent='"       + cn + "');" +
            "document.querySelectorAll('.tvrow').forEach(e=>e.textContent='"      + tn + " \\u00b7 " + md + "');" +
            "}catch(e){}", null
        );
    }

    // =========================================================
    // TV CONTROL COMMANDS (dari LAN)
    // =========================================================

    private void handleLocalCommand(JSONObject p) {
        // Catat timestamp kasir terakhir hit — dibaca langsung oleh SetupActivity
        SetupActivity.lastKasirHitMs = System.currentTimeMillis();

        try {
            String tvCmd = p.optString("_cmd", "");
            if (!tvCmd.isEmpty()) {
                Log.d(TAG, "handleLocalCommand _cmd=" + tvCmd);
                switch (tvCmd) {
                    case "ping":      /* hanya trigger KASIR_HIT broadcast, tidak ada aksi lain */ break;
                    case "sleep":     showSleep();    break;
                    case "wake":      hideSleep();    break;
                    case "showtime":  sessionManager.setToast5Shown(true); showTimeOverlay(); break;
                    case "showbayar":
                        String bs = p.optString("bayarStatusOverlay", "");
                        if (bs.isEmpty()) bs = p.optString("bayarStatus", currentBayarStatus);
                        showBayarOverlay(bs.isEmpty() ? "belum" : bs);
                        break;
                    case "hidebayar": hideBayarOverlay(); break;
                    default: Log.w(TAG, "unknown _cmd=" + tvCmd);
                }
                return;
            }

            // Payload sesi
            boolean active   = p.optBoolean("active", false);
            boolean expired  = p.optBoolean("expired", false);
            String  mode     = p.optString("mode", "countdown");
            long    start    = p.optLong("start", 0);
            long    duration = p.optLong("duration", 0);
            long    pausedAt = p.optLong("pausedAt", 0);
            String  namaP    = p.optString("namaPelanggan", "");

            sessionManager.applyFromLocal(active, expired, mode, start, duration, pausedAt, namaP);
            Log.d(TAG, "handleLocalCommand: active=" + active + " mode=" + mode + " expired=" + expired);
        } catch (Exception e) {
            Log.e(TAG, "handleLocalCommand error: " + e.getMessage());
        }
    }

    // =========================================================
    // HIDE ALL
    // =========================================================

    private void hideAll() {
        Log.d(TAG, "hideAll()");
        stopWidgetCountDown();
        if (widgetView  != null) widgetView.setVisibility(View.GONE);
        if (toastView   != null) toastView.setVisibility(View.GONE);
        if (expiredView != null) expiredView.setVisibility(View.GONE);
        webViewManager.destroyAll();
        isShowingTimeOverlay = false;
        audioManager.stopAlarm();
    }

    // =========================================================
    // BAYAR OVERLAY
    // =========================================================

    private void showBayarOverlay(final String bayarStatusInit) {
        currentBayarStatus = bayarStatusInit != null ? bayarStatusInit : "belum";
        mainHandler.post(() -> {
            try {
                if (bayarOverlayWv != null) {
                    try { windowManager.removeView(bayarOverlayWv); } catch (Exception ignored) {}
                    bayarOverlayWv = null;
                }
                WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    getOverlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                );
                p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                p.x = 0; p.y = 4;
                android.webkit.WebView wv = new android.webkit.WebView(getApplicationContext());
                wv.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                wv.getSettings().setJavaScriptEnabled(true);
                wv.loadUrl("file:///android_asset/bayaroverlay.html?bayarStatus=" + currentBayarStatus);
                bayarOverlayWv = wv;
                try { windowManager.addView(bayarOverlayWv, p); } catch (Exception ignored) {}
            } catch (Exception e) {
                Log.e(TAG, "showBayarOverlay error: " + e.getMessage());
            }
        });
    }

    private void hideBayarOverlay() {
        mainHandler.post(() -> {
            try {
                if (bayarOverlayWv != null) {
                    windowManager.removeView(bayarOverlayWv);
                    bayarOverlayWv = null;
                }
            } catch (Exception ignored) {}
        });
    }

    // =========================================================
    // TIME OVERLAY
    // =========================================================

    private void showTimeOverlay() {
        showTimeOverlay(false); // default: tanpa TTS (manual dari kasir)
    }

    private void showTimeOverlay(boolean withTts) {
        // Kalau overlay sedang tampil, force dismiss dulu lalu tampil ulang
        // (jangan return langsung — kasir bisa butuh refresh overlay)
        if (isShowingTimeOverlay) {
            mainHandler.post(() -> {
                try {
                    if (timeOverlayWv != null) {
                        windowManager.removeView(timeOverlayWv);
                        timeOverlayWv.destroy();
                        timeOverlayWv = null;
                    }
                } catch (Exception ignored) {}
                isShowingTimeOverlay = false;
            });
            // Delay sedikit lalu tampil ulang
            mainHandler.postDelayed(() -> showTimeOverlay(withTts), 100);
            return;
        }
        isShowingTimeOverlay = true;
        if (withTts) speakWarning("Perhatian! Waktu bermain tinggal lima menit.");
        final String  modeVal   = sessionManager.getMode() != null ? sessionManager.getMode() : "countdown";
        final boolean isPaused  = sessionManager.isPaused();
        final long    totalSec  = sessionManager.getDuration();
        final long    sisaMs;
        if ("billing".equals(modeVal)) {
            sisaMs = sessionManager.getElapsedSeconds() * 1000L;
        } else {
            sisaMs = Math.max(0, sessionManager.getRemainingSeconds() * 1000L);
        }
        mainHandler.post(() -> {
            try {
                if (timeOverlayWv != null) {
                    try { windowManager.removeView(timeOverlayWv); } catch (Exception ignored) {}
                    try { timeOverlayWv.destroy(); } catch (Exception ignored) {}
                    timeOverlayWv = null;
                }
                // Thread.sleep(50) dihapus — blok UI thread, sudah digantikan removeView di atas
                WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    getOverlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                );
                p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                p.y = 0;
                android.webkit.WebView wv = new android.webkit.WebView(getApplicationContext());
                wv.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                wv.getSettings().setJavaScriptEnabled(true);
                wv.getSettings().setDomStorageEnabled(true);
                String url = "file:///android_asset/timeoverlay.html"
                    + "?mode="     + android.net.Uri.encode(modeVal)
                    + "&tvNum="    + tvNum
                    + "&totalSec=" + Math.max(0, totalSec)
                    + "&sisaSec="  + (sisaMs / 1000.0)
                    + "&paused="   + (isPaused ? "1" : "0");
                wv.loadUrl(url);
                timeOverlayWv = wv;
                try { windowManager.addView(timeOverlayWv, p); } catch (Exception ignored) {}
                mainHandler.postDelayed(() -> {
                    try {
                        if (timeOverlayWv != null) {
                            windowManager.removeView(timeOverlayWv);
                            timeOverlayWv.destroy();
                            timeOverlayWv = null;
                        }
                    } catch (Exception ignored) {}
                    isShowingTimeOverlay = false;
                }, 7000);
            } catch (Exception e) {
                Log.e(TAG, "showTimeOverlay error: " + e.getMessage());
                isShowingTimeOverlay = false;
            }
        });
    }

    // =========================================================
    // SLEEP VIEW
    // =========================================================

    private void showSleep() {
        if (sleepView != null) return;
        try {
            View black = new View(this);
            black.setBackgroundColor(Color.BLACK);
            sleepView = black;
            windowManager.addView(sleepView, makeFullscreenParams(PixelFormat.OPAQUE));
        } catch (Exception e) { Log.e(TAG, "showSleep: " + e.getMessage(), e); }
    }

    private void hideSleep() {
        safeRemoveView(sleepView, "sleepView");
        sleepView = null;
    }

    // =========================================================
    // OVERLAY VIEWS INIT
    // =========================================================

    private void initOverlayViews() {
        LayoutInflater inf = LayoutInflater.from(this);
        widgetView  = inf.inflate(R.layout.overlay_widget,  null);
        toastView   = inf.inflate(R.layout.overlay_toast,   null);
        expiredView = inf.inflate(R.layout.overlay_expired, null);

        widgetView.setVisibility(View.GONE);
        toastView.setVisibility(View.GONE);
        expiredView.setVisibility(View.GONE);

        int ot = getOverlayType();

        WindowManager.LayoutParams wp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            ot, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        wp.gravity = Gravity.BOTTOM | Gravity.END; wp.x = 24; wp.y = 24;

        WindowManager.LayoutParams tp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            ot, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        tp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; tp.y = 32;

        widgetParams = wp;
        safeAddView(widgetView,  wp, "widgetView");
        safeAddView(toastView,   tp, "toastView");
        safeAddView(expiredView, makeFullscreenParams(PixelFormat.TRANSLUCENT), "expiredView");
    }

    // =========================================================
    // TTS
    // =========================================================

    private void initTTS() {
        try {
            tts = new android.speech.tts.TextToSpeech(this, status -> {
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    int r = tts.setLanguage(new java.util.Locale("id", "ID"));
                    if (r == android.speech.tts.TextToSpeech.LANG_MISSING_DATA ||
                        r == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED)
                        tts.setLanguage(java.util.Locale.getDefault());
                    tts.setSpeechRate(0.9f);
                    ttsReady = true;
                }
            });
        } catch (Exception e) { Log.e(TAG, "initTTS: " + e.getMessage(), e); }
    }

    private void speakWarning(String text) {
        if (!ttsReady || tts == null) return;
        try { tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null,
                  "w" + System.currentTimeMillis());
        } catch (Exception e) { Log.e(TAG, "speak: " + e.getMessage()); }
    }

    // =========================================================
    // RESTART HANDLING
    // =========================================================

    private void scheduleRestart() {
        try {
            Intent si = new Intent(this, OverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si);
            else startService(si);
        } catch (Exception e) { Log.e(TAG, "immediate restart: " + e.getMessage()); }

        int flags = android.app.PendingIntent.FLAG_ONE_SHOT |
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
        android.app.PendingIntent pi = android.app.PendingIntent.getService(
            this, 1, new Intent(this, OverlayService.class), flags);
        android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + 2000, pi);
            else
                am.set(android.app.AlarmManager.ELAPSED_REALTIME,
                    android.os.SystemClock.elapsedRealtime() + 2000, pi);
        }
    }

    // =========================================================
    // FOREGROUND NOTIFICATION
    // =========================================================

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Astrophile TV Monitor", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Astrophile TV Monitor")
            .setContentText("Monitoring aktif...")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
        startForeground(1, n);
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private int getOverlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
    }

    private WindowManager.LayoutParams makeFullscreenParams(int fmt) {
        return new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
            WindowManager.LayoutParams.FLAG_FULLSCREEN, fmt);
    }

    private void safeAddView(View v, WindowManager.LayoutParams p, String name) {
        if (v == null) return;
        try { windowManager.addView(v, p); }
        catch (Exception e) { Log.e(TAG, "addView [" + name + "]: " + e.getMessage(), e); }
    }

    private void safeRemoveView(View v, String name) {
        if (v == null) return;
        try { windowManager.removeView(v); }
        catch (Exception e) { Log.e(TAG, "removeView [" + name + "]: " + e.getMessage(), e); }
    }

    private String formatTime(long secs) {
        long h = secs/3600, m = (secs%3600)/60, s = secs%60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                     : String.format(Locale.US, "%02d:%02d", m, s);
    }

    public static String getLocalIpAddress() {
        try {
            java.util.Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                NetworkInterface ni = nets.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                java.util.Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address)
                        return addr.getHostAddress();
                }
            }
        } catch (Exception e) { Log.e("OverlayService", "getLocalIpAddress: " + e); }
        return "0.0.0.0";
    }
}
