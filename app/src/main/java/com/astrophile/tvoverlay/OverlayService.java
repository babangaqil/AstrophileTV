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
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.database.DataSnapshot;

import java.util.Locale;

/**
 * OverlayService — Production-grade 24/7 refactor.
 *
 * Semua concern dipecah ke manager terpisah:
 * - SessionManager    : single source of truth session state
 * - FirebaseManager   : zero duplicate listener
 * - WebViewManager    : zero WebView accumulation, full hardening
 * - TimerManager      : zero duplicate timer, watchdog self-healing
 * - AstroAudioManager : zero thread leak, auto timeout alarm
 *
 * OverlayService hanya wiring + UI orchestration.
 */
public class OverlayService extends Service {

    private static final String TAG        = "AstroOverlay";
    private static final String CHANNEL_ID = "astro_tv_channel";
    private static final String PREFS      = "astro_tv_prefs";

    // Managers
    private SessionManager    sessionManager;
    private FirebaseManager   firebaseManager;
    private WebViewManager    webViewManager;
    private TimerManager      timerManager;
    private AstroAudioManager audioManager;

    // UI views (XML layout — bukan WebView)
    private View widgetView;
    private View expiredView;
    private View toastView;

    // System services
    private WindowManager         windowManager;
    private Handler               mainHandler;
    private PowerManager.WakeLock wakeLock;

    // TV info
    private int    tvNum  = 1;
    private String tvName = "TV 1";

    // State flags
    private boolean isShowingTimeOverlay = false;
    private String  currentBayarStatus   = "belum";

    // License refs
    private com.google.firebase.database.DatabaseReference  licenseRef      = null;
    private com.google.firebase.database.ValueEventListener licenseListener = null;
    private com.google.firebase.database.DatabaseReference  globalUpdateRef      = null;
    private com.google.firebase.database.ValueEventListener globalUpdateListener = null;

    // TTS
    private android.speech.tts.TextToSpeech tts      = null;
    private boolean                          ttsReady = false;

    // Sleep view
    private View sleepView = null;

    // Time overlay (v1.9 style — langsung inline WebView)
    private android.webkit.WebView timeOverlayWv = null;

    // Bayar overlay (v1.9 style — langsung inline WebView)
    private android.webkit.WebView bayarOverlayWv = null;
    private com.google.firebase.database.DatabaseReference  bayarStatusRef      = null;
    private com.google.firebase.database.ValueEventListener bayarStatusListener = null;

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
        initFirebaseAndStart();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand()");

        int savedCode   = getSharedPreferences("astro_tv_svc", MODE_PRIVATE)
                              .getInt("running_version_code", -1);
        int currentCode = getCurrentVersionCode();

        if (savedCode != -1 && currentCode != -1 && savedCode != currentCode) {
            Log.i(TAG, "APK updated " + savedCode + "→" + currentCode + " reinit");
            getSharedPreferences("astro_tv_svc", MODE_PRIVATE).edit()
                .putInt("running_version_code", currentCode).apply();
            initFirebaseAndStart();
            return START_STICKY;
        }
        if (currentCode != -1) {
            getSharedPreferences("astro_tv_svc", MODE_PRIVATE).edit()
                .putInt("running_version_code", currentCode).apply();
        }

        if (!firebaseManager.isReady()) {
            initFirebaseAndStart();
        } else {
            attachAllFirebaseListeners();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy()");

        timerManager.destroyAll();
        firebaseManager.destroyAll();
        webViewManager.destroyAll();
        audioManager.destroy();

        if (licenseRef != null && licenseListener != null)
            licenseRef.removeEventListener(licenseListener);
        if (globalUpdateRef != null && globalUpdateListener != null)
            globalUpdateRef.removeEventListener(globalUpdateListener);

        if (tts != null) {
            try { tts.stop(); tts.shutdown(); }
            catch (Exception e) { Log.e(TAG, "tts shutdown: " + e.getMessage()); }
            tts = null;
        }

        safeRemoveView(widgetView,  "widgetView");
        safeRemoveView(toastView,   "toastView");
        safeRemoveView(expiredView, "expiredView");
        safeRemoveView(sleepView,   "sleepView");

        try {
            com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("settings/tvStatus/" + tvNum + "/online").setValue(false);
        } catch (Exception e) { Log.e(TAG, "setOffline: " + e.getMessage()); }
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
        sessionManager  = new SessionManager();
        firebaseManager = new FirebaseManager(this);
        webViewManager  = new WebViewManager(this, windowManager);
        timerManager    = new TimerManager(mainHandler);
        audioManager    = new AstroAudioManager();

        sessionManager.setListener(new SessionManager.SessionListener() {
            @Override public void onSessionStarted() {
                mainHandler.post(() -> {
                    webViewManager.destroyAll();
                    isShowingTimeOverlay = false;
                    firebaseManager.clearTvControlCmd(tvNum);
                    // Auto wake saat sesi mulai — kalau layar sedang sleep, langsung gelap dihilangkan
                    if (sleepView != null) hideSleep();
                    // Tulis balik active:true ke Firebase agar kasir tahu TV aktif (seperti v1.9)
                    firebaseManager.setActiveSession(tvNum, true);
                    firebaseManager.setLastSeen(tvNum, System.currentTimeMillis());
                    if (sessionManager.getStartTime() > 0) updateWidget();
                });
            }
            @Override public void onSessionExpired() {
                mainHandler.post(() -> showExpiredOverlay());
            }
            @Override public void onSessionReset() {
                mainHandler.post(() -> hideAll());
            }
        });
    }

    private void initFirebaseAndStart() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        tvNum  = prefs.getInt("tvNum", 1);
        tvName = prefs.getString("tvName", "TV " + tvNum);
        sessionManager.setTvNum(tvNum);
        sessionManager.setTvName(tvName);

        boolean ok = firebaseManager.init();
        if (!ok) {
            Log.e(TAG, "Firebase init failed — retry in 10s");
            mainHandler.postDelayed(this::initFirebaseAndStart, 10_000);
            return;
        }

        // Sync server time offset — agar getRemainingSeconds() pakai jam server, bukan jam lokal TV
        firebaseManager.startServerTimeSync();

        attachAllFirebaseListeners();
        startTicker();
        initTTS();
        checkLicensePeriodic();

        firebaseManager.setTvOnline(tvNum, true);
        try {
            com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("settings/tvStatus/" + tvNum + "/online")
                .onDisconnect().setValue(false);
        } catch (Exception e) { Log.e(TAG, "onDisconnect: " + e.getMessage()); }
    }

    private void attachAllFirebaseListeners() {
        firebaseManager.listenSession(tvNum, new FirebaseManager.SessionDataCallback() {
            @Override public void onData(DataSnapshot snap) { handleFirebaseData(snap); }
            @Override public void onCancelled(String error) {
                Log.e(TAG, "sessionListener cancelled: " + error + " — retry 2s");
                mainHandler.postDelayed(() -> {
                    try { com.google.firebase.database.FirebaseDatabase.getInstance().goOnline(); }
                    catch (Exception e) { Log.e(TAG, "goOnline: " + e.getMessage()); }
                    // Re-attach + keepSynced seperti v1.9 agar langsung fetch dari server
                    mainHandler.postDelayed(() -> firebaseManager.listenSession(tvNum, this), 500);
                }, 2000);
            }
        });

        firebaseManager.listenConnection(new FirebaseManager.ConnectionCallback() {
            @Override public void onConnected() {
                Log.d(TAG, "Firebase CONNECTED");
                // Hanya update status online — listener sudah persistent, tidak perlu re-attach
                firebaseManager.setTvOnline(tvNum, true);
                firebaseManager.setLastSeen(tvNum, System.currentTimeMillis());
            }
            @Override public void onDisconnected() {
                Log.w(TAG, "Firebase DISCONNECTED — goOnline in 5s");
                mainHandler.postDelayed(() -> {
                    try { com.google.firebase.database.FirebaseDatabase.getInstance().goOnline(); }
                    catch (Exception e) { Log.e(TAG, "goOnline: " + e.getMessage()); }
                }, 5000);
            }
        });

        firebaseManager.listenStoreName(name -> {
            sessionManager.setNamaToko(name);
            mainHandler.post(() -> {
                if (webViewManager.isExpiredAttached())
                    injectExpiredData(webViewManager.getExpiredOverlay());
            });
        });

        firebaseManager.listenTvControl(tvNum, (cmd, snap) -> {
            Log.d(TAG, "tvControl cmd=" + cmd);
            mainHandler.post(() -> handleTvCommand(cmd, snap));
        });

        timerManager.startHeartbeat(() ->
            firebaseManager.setLastSeen(tvNum, System.currentTimeMillis()));
    }

    // =========================================================
    // FIREBASE DATA HANDLER
    // =========================================================

    private void handleFirebaseData(DataSnapshot snap) {
        if (!snap.exists()) {
            mainHandler.post(() -> sessionManager.resetSession());
            return;
        }

        Boolean fbActive     = snap.child("active").getValue(Boolean.class);
        Boolean fbProcessing = snap.child("processing").getValue(Boolean.class);
        String  fbMode       = snap.child("mode").getValue(String.class);
        Boolean fbExpired    = snap.child("expired").getValue(Boolean.class);
        Long    fbStart      = snap.child("start").getValue(Long.class);
        Long    fbDur        = snap.child("duration").getValue(Long.class);
        String  fbNama       = snap.child("namaPelanggan").getValue(String.class);
        Long    fbPausedAt   = snap.child("pausedAt").getValue(Long.class);

        boolean isAct  = Boolean.TRUE.equals(fbActive);
        boolean isProc = Boolean.TRUE.equals(fbProcessing) || "processing".equals(fbMode);

        if (isProc)                    { mainHandler.post(this::hideAll); return; }
        if (!isAct)                    { mainHandler.post(() -> sessionManager.resetSession()); return; }
        if ("reserved".equals(fbMode)) { mainHandler.post(this::hideAll); return; }

        sessionManager.applyFromFirebase(
            isAct,
            Boolean.TRUE.equals(fbExpired),
            fbMode,
            fbStart    != null ? fbStart    : 0L,
            fbDur      != null ? fbDur      : 0L,
            fbNama,
            fbPausedAt != null ? fbPausedAt : 0L
        );
    }

    // =========================================================
    // TICKER
    // =========================================================

    private void startTicker() {
        timerManager.startTicker(() -> {
            if (!sessionManager.isActive() || sessionManager.isExpired()) return;
            // Selalu update offset sebelum hitung sisa waktu — pastikan jam sinkron
            sessionManager.setServerTimeOffset(
                firebaseManager.getServerNow() - System.currentTimeMillis());
            mainHandler.post(() -> { if (!sessionManager.isExpired()) updateWidget(); });
            if (sessionManager.getStartTime() == 0) {
                Log.w(TAG, "active but startTime=0 — force re-fetch");
                firebaseManager.listenSession(tvNum, new FirebaseManager.SessionDataCallback() {
                    @Override public void onData(DataSnapshot s)    { handleFirebaseData(s); }
                    @Override public void onCancelled(String err)   { Log.e(TAG, "refetch: " + err); }
                });
            }
        });

        timerManager.startWatchdog(() -> {
            Log.e(TAG, "WATCHDOG: ticker frozen — restarting");
            startTicker();
        });
    }

    // =========================================================
    // WIDGET
    // =========================================================

    private void updateWidget() {
        if (!sessionManager.isActive() || sessionManager.getStartTime() == 0) return;
        if (sessionManager.isBilling()) { widgetView.setVisibility(View.GONE); return; }

        long secs = sessionManager.getRemainingSeconds();

        if (sessionManager.isPaused()) {
            TextView tvTime  = widgetView.findViewById(R.id.tvWidgetTime);
            TextView tvLabel = widgetView.findViewById(R.id.tvWidgetLabel);
            if (tvTime  != null) tvTime.setText(formatTime(secs));
            if (tvLabel != null) tvLabel.setText("⏸ DIJEDA");
            widgetView.setVisibility(secs <= 300 ? View.VISIBLE : View.GONE);
            return;
        }

        TextView tvTime  = widgetView.findViewById(R.id.tvWidgetTime);
        TextView tvLabel = widgetView.findViewById(R.id.tvWidgetLabel);
        View     bgView  = widgetView.findViewById(R.id.widgetBg);
        if (tvTime != null) tvTime.setText(formatTime(secs));

        if (secs <= 0) {
            widgetView.setVisibility(View.GONE);
            sessionManager.markExpired();
            try {
                // Tulis expired:true + active:true agar kasir tahu sesi habis (v1.9 behaviour)
                com.google.firebase.database.FirebaseDatabase db =
                    com.google.firebase.database.FirebaseDatabase.getInstance();
                db.getReference("settings/activeSessions/" + tvNum + "/expired").setValue(true);
                db.getReference("settings/activeSessions/" + tvNum + "/active").setValue(true);
            } catch (Exception e) { Log.e(TAG, "setExpired: " + e.getMessage()); }
            return;
        }

        if (secs <= 60) {
            // Selalu VISIBLE + update warna tiap tick — widget mungkin GONE dari postDelayed 5-menit
            widgetView.setVisibility(View.VISIBLE);
            if (tvTime  != null) tvTime.setTextColor(Color.parseColor("#ff1a50"));
            if (tvLabel != null) tvLabel.setText("SEGERA HABIS!");
            if (bgView  != null) bgView.setBackgroundResource(R.drawable.widget_bg_danger);
            if (!sessionManager.isToast1Shown()) {
                sessionManager.setToast1Shown(true);
                speakWarning("Perhatian! Waktu bermain tinggal satu menit. Segera hubungi operator.");
            }
        } else if (secs <= 300) {
            // Teks waktu sudah di-set di atas tiap tick — hanya urus visibility & one-time toast
            if (!sessionManager.isToast5Shown()) {
                sessionManager.setToast5Shown(true);
                widgetView.setVisibility(View.VISIBLE);
                if (tvTime  != null) tvTime.setTextColor(Color.parseColor("#ffcc00"));
                if (tvLabel != null) tvLabel.setText("SISA WAKTU");
                if (bgView  != null) bgView.setBackgroundResource(R.drawable.widget_bg_warning);
                speakWarning("Perhatian! Waktu bermain tinggal lima menit.");
                mainHandler.postDelayed(() -> {
                    // Sembunyikan hanya jika masih di range 5 menit — jangan sembunyikan saat sudah ≤ 1 menit
                    if (sessionManager.getRemainingSeconds() > 60)
                        widgetView.setVisibility(View.GONE);
                }, 10_000);
            }
        } else {
            widgetView.setVisibility(View.GONE);
        }
        // Force WindowManager redraw — postInvalidate tidak cukup untuk overlay dari Service
        try { windowManager.updateViewLayout(widgetView, widgetView.getLayoutParams()); }
        catch (Exception ignored) {}
    }

    // =========================================================
    // EXPIRED OVERLAY
    // =========================================================

    private void showExpiredOverlay() {
        Log.d(TAG, "showExpiredOverlay()");
        widgetView.setVisibility(View.GONE);
        toastView.setVisibility(View.GONE);
        expiredView.setVisibility(View.GONE);

        audioManager.startAlarm();

        // Attach dulu ke WindowManager sebelum load, supaya overlay muncul tepat waktu
        webViewManager.getOrCreateExpiredOverlay(
            "file:///android_asset/expired.html",
            () -> injectExpiredData(webViewManager.getExpiredOverlay())
        );

        // Attach ke window — kalau sudah attached (reuse sesi lama) skip addView
        if (!webViewManager.isExpiredAttached()) {
            webViewManager.attachExpiredOverlay(makeFullscreenParams(PixelFormat.OPAQUE));
        } else {
            // Sudah attached dari sesi sebelumnya — pastikan visible
            android.webkit.WebView ev = webViewManager.getExpiredOverlay();
            if (ev != null) ev.setVisibility(android.view.View.VISIBLE);
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
    // TV CONTROL COMMANDS
    // =========================================================

    private void handleTvCommand(String cmd, com.google.firebase.database.DataSnapshot snap) {
        if (cmd == null || cmd.isEmpty() || "none".equals(cmd)) return;
        switch (cmd) {
            case "sleep":
                showSleep();
                firebaseManager.clearTvControlCmd(tvNum);
                break;
            case "wake":
                hideSleep();
                firebaseManager.clearTvControlCmd(tvNum);
                break;
            case "showtime":
                showTimeOverlay();
                firebaseManager.clearTvControlCmd(tvNum);
                break;
            case "showbayar":
                // Baca bayarStatusOverlay dari snap (agregat) — sama seperti v1.9
                String bs = snap != null ? snap.child("bayarStatusOverlay").getValue(String.class) : null;
                if (bs == null && snap != null) bs = snap.child("bayarStatus").getValue(String.class);
                showBayarOverlay(bs != null ? bs : currentBayarStatus);
                firebaseManager.clearTvControlCmd(tvNum);
                break;
            case "hidebayar":
                hideBayarOverlay();
                firebaseManager.clearTvControlCmd(tvNum);
                break;
            default:
                Log.w(TAG, "Unknown cmd: " + cmd);
        }
    }

    // =========================================================
    // HIDE ALL — full session cleanup
    // =========================================================

    private void hideAll() {
        Log.d(TAG, "hideAll()");
        if (widgetView  != null) widgetView.setVisibility(View.GONE);
        if (toastView   != null) toastView.setVisibility(View.GONE);
        if (expiredView != null) expiredView.setVisibility(View.GONE);

        webViewManager.destroyAll();
        isShowingTimeOverlay = false;
        firebaseManager.removeBayarStatusListener();
        audioManager.stopAlarm();
        // sleepView TIDAK di-remove di sini — biarkan layar tetap gelap
        // sampai operator klik Wake manual
    }

    // =========================================================
    // BAYAR OVERLAY (v1.9 — langsung inline WebView)
    // =========================================================

    private void showBayarOverlay(final String bayarStatusInit) {
        currentBayarStatus = bayarStatusInit != null ? bayarStatusInit : "belum";
        mainHandler.post(new Runnable() {
            @Override public void run() {
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
            }
        });
        // Listen perubahan bayarStatus dari Firebase realtime
        com.google.firebase.database.FirebaseDatabase fbDb = firebaseManager.getDb();
        if (fbDb != null) {
            if (bayarStatusRef != null && bayarStatusListener != null) {
                bayarStatusRef.removeEventListener(bayarStatusListener);
            }
            bayarStatusRef = fbDb.getReference("settings/activeSessions/" + tvNum + "/bayarStatusOverlay");
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

    // =========================================================
    // TIME OVERLAY (v1.9 — langsung inline WebView)
    // =========================================================

    private void showTimeOverlay() {
        if (isShowingTimeOverlay) return;
        isShowingTimeOverlay = true;
        final String modeVal   = sessionManager.getMode() != null ? sessionManager.getMode() : "countdown";
        final boolean isPaused = sessionManager.isPaused();
        final long startTime   = sessionManager.getStartTime();
        final long duration    = sessionManager.getDuration();
        final long effNow      = isPaused ? sessionManager.getPausedAt() : System.currentTimeMillis();
        final long totalSec, sisaSec;
        if ("billing".equals(modeVal)) {
            totalSec = 0; sisaSec = (effNow - startTime) / 1000;
        } else {
            totalSec = duration; sisaSec = Math.max(0, duration - (effNow - startTime) / 1000);
        }
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    if (timeOverlayWv != null) {
                        try { windowManager.removeView(timeOverlayWv); } catch (Exception ignored) {}
                        try { timeOverlayWv.destroy(); } catch (Exception ignored) {}
                        timeOverlayWv = null;
                    }
                    try { Thread.sleep(50); } catch (Exception ignored) {}
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
                    long safeStart = startTime > 0 ? startTime : System.currentTimeMillis();
                    String url = "file:///android_asset/timeoverlay.html"
                        + "?mode="        + android.net.Uri.encode(modeVal)
                        + "&tvNum="       + tvNum
                        + "&totalSec="    + Math.max(0, totalSec)
                        + "&sisaSec="     + Math.max(0, sisaSec)
                        + "&fbStartTime=" + safeStart
                        + "&loadMs="      + System.currentTimeMillis()
                        + "&paused="      + (isPaused ? "1" : "0");
                    wv.loadUrl(url);
                    timeOverlayWv = wv;
                    try { windowManager.addView(timeOverlayWv, p); } catch (Exception ignored) {}
                    mainHandler.postDelayed(new Runnable() {
                        @Override public void run() {
                            try {
                                if (timeOverlayWv != null) {
                                    windowManager.removeView(timeOverlayWv);
                                    timeOverlayWv.destroy();
                                    timeOverlayWv = null;
                                }
                            } catch (Exception ignored) {}
                            isShowingTimeOverlay = false;
                        }
                    }, 5500);
                } catch (Exception e) {
                    Log.e(TAG, "showTimeOverlay error: " + e.getMessage());
                    isShowingTimeOverlay = false;
                }
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
            Log.d(TAG, "showSleep OK");
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT);
        wp.gravity = Gravity.BOTTOM | Gravity.END; wp.x = 24; wp.y = 24;

        // LAYER_TYPE_NONE — biarkan sistem handle rendering, HARDWARE justru cache layer & freeze teks
        widgetView.setLayerType(View.LAYER_TYPE_NONE, null);

        WindowManager.LayoutParams tp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            ot, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        tp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; tp.y = 32;

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
    // LICENSE
    // =========================================================

    private void checkLicensePeriodic() {
        String key      = LicenseManager.getSavedKey(this);
        String deviceId = LicenseManager.getSavedDeviceId(this);
        if (key.isEmpty()) return;

        String keyHash = LicenseManager.hashKey(key.replace("-", "").toUpperCase());
        try {
            com.google.firebase.FirebaseApp master = getMasterApp();
            if (licenseRef != null && licenseListener != null)
                licenseRef.removeEventListener(licenseListener);

            licenseRef = com.google.firebase.database.FirebaseDatabase
                .getInstance(master).getReference("tvLicenseKeys/" + keyHash);

            licenseListener = new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                    if (!snap.exists()) { blockOverlay(); return; }
                    Boolean rev  = snap.child("revoked").getValue(Boolean.class);
                    Long    expAt = snap.child("expiredAt").getValue(Long.class);
                    if (Boolean.TRUE.equals(rev))                                        { blockOverlay(); return; }
                    if (expAt != null && System.currentTimeMillis() > expAt)             { blockOverlay(); return; }
                    if (!deviceId.isEmpty()) {
                        com.google.firebase.database.DataSnapshot dev =
                            snap.child("devices").child(deviceId);
                        if (dev.exists() && Boolean.TRUE.equals(dev.child("revoked").getValue(Boolean.class)))
                            { blockOverlay(); return; }
                    }
                    com.google.firebase.database.DataSnapshot fu = snap.child("forceUpdate");
                    if (Boolean.TRUE.equals(fu.child("enabled").getValue(Boolean.class))) {
                        broadcastUpdate(
                            fu.child("version").getValue(String.class),
                            fu.child("url").getValue(String.class),
                            fu.child("message").getValue(String.class));
                        return;
                    }
                    checkGlobalUpdate();
                }
                @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {
                    Log.e(TAG, "licenseListener: " + e.getMessage());
                }
            };
            licenseRef.addValueEventListener(licenseListener);
        } catch (Exception e) { Log.e(TAG, "checkLicensePeriodic: " + e.getMessage(), e); }
    }

    private void checkGlobalUpdate() {
        try {
            if (globalUpdateRef != null && globalUpdateListener != null)
                globalUpdateRef.removeEventListener(globalUpdateListener);

            globalUpdateRef = com.google.firebase.database.FirebaseDatabase
                .getInstance(getMasterApp()).getReference("settings/globalUpdate");

            globalUpdateListener = new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                    if (!snap.exists() || !Boolean.TRUE.equals(snap.child("enabled").getValue(Boolean.class))) {
                        sendBroadcast(new Intent("com.astrophile.tvoverlay.UPDATE_CLEAR")); return;
                    }
                    String minVer = snap.child("minVersion").getValue(String.class);
                    String url    = snap.child("url").getValue(String.class);
                    String msg    = snap.child("message").getValue(String.class);
                    if (minVer == null || minVer.isEmpty()) return;
                    try {
                        String cur = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                        if (isVersionLower(cur, minVer)) broadcastUpdate("v" + minVer, url, msg);
                    } catch (Exception e) { Log.e(TAG, "version check: " + e.getMessage()); }
                }
                @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {
                    Log.e(TAG, "globalUpdateListener: " + e.getMessage());
                }
            };
            globalUpdateRef.addValueEventListener(globalUpdateListener);
        } catch (Exception e) { Log.e(TAG, "checkGlobalUpdate: " + e.getMessage(), e); }
    }

    private void blockOverlay() {
        LicenseManager.clearLicense(this);
        stopSelf();
        Intent i = new Intent(this, SetupActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
    }

    private void broadcastUpdate(String version, String url, String message) {
        Intent i = new Intent("com.astrophile.tvoverlay.UPDATE_AVAILABLE");
        i.putExtra("version", version != null ? version : "");
        i.putExtra("url",     url     != null ? url     : "");
        i.putExtra("message", message != null ? message : "");
        sendBroadcast(i);
    }

    private com.google.firebase.FirebaseApp getMasterApp() {
        try { return com.google.firebase.FirebaseApp.getInstance("_tv_license"); }
        catch (Exception e) {
            return com.google.firebase.FirebaseApp.initializeApp(this,
                new com.google.firebase.FirebaseOptions.Builder()
                    .setApiKey("AIzaSyD8XffAZK8JUOBajCUVyPS-NT9jnwYBats")
                    .setDatabaseUrl("https://astrophile-rental-default-rtdb.firebaseio.com")
                    .setProjectId("astrophile-rental")
                    .setApplicationId("1:789474619442:android:5f678d3b6ebdc99a1c8c2b")
                    .build(), "_tv_license");
        }
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

    private int getCurrentVersionCode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                return (int) getPackageManager().getPackageInfo(getPackageName(),
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)).getLongVersionCode();
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) { return -1; }
    }

    private boolean isVersionLower(String cur, String min) {
        try {
            String[] c = cur.split("[.\\-]"), m = min.split("[.\\-]");
            for (int i = 0; i < Math.max(c.length, m.length); i++) {
                int cv = i < c.length ? Integer.parseInt(c[i].replaceAll("[^0-9]","0")) : 0;
                int mv = i < m.length ? Integer.parseInt(m[i].replaceAll("[^0-9]","0")) : 0;
                if (cv < mv) return true; if (cv > mv) return false;
            }
        } catch (Exception e) { Log.e(TAG, "isVersionLower: " + e.getMessage()); }
        return false;
    }

    private String formatTime(long secs) {
        long h = secs/3600, m = (secs%3600)/60, s = secs%60;
        return h > 0 ? String.format(Locale.US,"%d:%02d:%02d",h,m,s)
                     : String.format(Locale.US,"%02d:%02d",m,s);
    }
}
