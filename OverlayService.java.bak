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

    private Handler mainHandler;
    private Timer tickTimer;
    private ToneGenerator toneGen;

    private FirebaseDatabase firebaseDb;
    private DatabaseReference sessionRef;
    private ValueEventListener sessionListener;

    // State
    private boolean isExpired = false;
    private String namaToko = "";
    private boolean isActive = false;
    private long startTime = 0;
    private long duration = 0;
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

        windowManager.addView(widgetView, widgetParams);
        windowManager.addView(toastView, toastParams);
        windowManager.addView(expiredView, expiredParams);
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
            sessionRef = firebaseDb.getReference("settings/activeSessions/" + tvNum);

            sessionListener = new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    handleFirebaseData(snapshot);
                }
                @Override
                public void onCancelled(DatabaseError error) {}
            };
            sessionRef.addValueEventListener(sessionListener);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleFirebaseData(DataSnapshot snapshot) {
        if (!snapshot.exists()) {
            mainHandler.post(this::hideAll);
            return;
        }

        Boolean active  = snapshot.child("active").getValue(Boolean.class);
        Boolean expired = snapshot.child("expired").getValue(Boolean.class);
        String  modeVal = snapshot.child("mode").getValue(String.class);
        Long    start   = snapshot.child("start").getValue(Long.class);
        Long    dur     = snapshot.child("duration").getValue(Long.class);
        String  nama    = snapshot.child("namaPelanggan").getValue(String.class);

        boolean isAct = active != null && active;
        boolean isExp = expired != null && expired;

        mode          = modeVal != null ? modeVal : "";
        startTime     = start   != null ? start   : 0;
        duration      = dur     != null ? dur     : 0;
        namaPelanggan = nama    != null ? nama    : "";

        mainHandler.post(() -> {
            if (isExp) {
                showExpired();
            } else if (isAct && startTime > 0) {
                isExpired = false;
                isActive  = true;
                hideExpired();
                showWidget();
            } else {
                isActive = false;
                hideAll();
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
                    if (isActive && !isExpired) updateWidget();
                });
            }
        }, 0, 1000);
    }

    // ── WIDGET ─────────────────────────────────────────────────
    private void showWidget() {
        // widget diatur di updateWidget
        widgetView.setVisibility(View.VISIBLE);
        updateWidget();
    }

    private void updateWidget() {
        if (!isActive || startTime == 0) return;

        long now = System.currentTimeMillis();
        long secs;
        boolean isCountdown = "countdown".equals(mode);

        if (isCountdown) {
            long elapsed = (now - startTime) / 1000;
            secs = Math.max(0, duration - elapsed);
        } else {
            secs = (now - startTime) / 1000;
        }

        String timeStr = formatTime(secs);
        TextView tvTime  = widgetView.findViewById(R.id.tvWidgetTime);
        TextView tvLabel = widgetView.findViewById(R.id.tvWidgetLabel);
        View     bgView  = widgetView.findViewById(R.id.widgetBg);

        if (tvTime != null) tvTime.setText(timeStr);

        if (isCountdown) {
            if (secs <= 0) {
                // Expired!
                showExpired();
                return;
            } else if (secs <= 60) {
                widgetView.setVisibility(View.VISIBLE);
                widgetView.setVisibility(View.VISIBLE);
                // Bahaya — merah berkedip
                if (tvTime != null) tvTime.setTextColor(Color.parseColor("#ff1a50"));
                if (tvLabel != null) tvLabel.setText("SEGERA HABIS!");
                if (bgView != null) bgView.setBackgroundResource(R.drawable.widget_bg_danger);
                if (!toast1Shown) {
                    toast1Shown = true;
                }
            } else if (secs <= 300) {
                if (!toast5Shown) { widgetView.setVisibility(View.VISIBLE); mainHandler.postDelayed(() -> widgetView.setVisibility(View.GONE), 10000); }
                if (!toast5Shown) { widgetView.setVisibility(View.VISIBLE); mainHandler.postDelayed(() -> widgetView.setVisibility(View.GONE), 10000); }
                // Warning — kuning
                if (tvTime != null) tvTime.setTextColor(Color.parseColor("#ffcc00"));
                if (tvLabel != null) tvLabel.setText("SISA WAKTU");
                if (bgView != null) bgView.setBackgroundResource(R.drawable.widget_bg_warning);
                if (!toast5Shown) {
                    toast5Shown = true;
                }
            } else {
                // Normal — widget disembunyikan
                widgetView.setVisibility(View.GONE);
                if (tvTime != null) tvTime.setTextColor(Color.parseColor("#00f5ff"));
                if (tvLabel != null) tvLabel.setText("SISA WAKTU");
                if (bgView != null) bgView.setBackgroundResource(R.drawable.widget_bg_normal);
            }
        } else {
            // Billing mode
            if (tvTime != null) tvTime.setTextColor(Color.parseColor("#00f5ff"));
            if (tvLabel != null) tvLabel.setText("WAKTU MAIN");
            if (bgView != null) bgView.setBackgroundResource(R.drawable.widget_bg_normal);
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
    private void showExpired() {
        if (isExpired) return;
        isExpired  = true;
        isActive   = false;
        toast5Shown = false;
        toast1Shown = false;

        widgetView.setVisibility(View.GONE);
        toastView.setVisibility(View.GONE);

        TextView tvNamaTokoView = expiredView.findViewById(R.id.tvNamaToko);
        if (tvNamaTokoView != null) tvNamaTokoView.setText(namaToko.isEmpty() ? "ASTROPHILE" : namaToko.toUpperCase());
        TextView tvName2  = expiredView.findViewById(R.id.tvExpiredName);
        TextView tvTv     = expiredView.findViewById(R.id.tvExpiredTV);

        if (tvName2 != null) tvName2.setText(
            namaPelanggan.isEmpty() ? "" : namaPelanggan.toUpperCase()
        );
        if (tvTv != null) tvTv.setText(tvName);

        expiredView.setVisibility(View.VISIBLE);

        // Play alarm sound
        playAlarm();
    }

    private void hideExpired() {
        isExpired = false;
        expiredView.setVisibility(View.GONE);
        stopAlarm();
    }

    private void hideAll() {
        isActive  = false;
        isExpired = false;
        toast5Shown = false;
        toast1Shown = false;
        widgetView.setVisibility(View.GONE);
        toastView.setVisibility(View.GONE);
        expiredView.setVisibility(View.GONE);
        stopAlarm();
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

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (tickTimer != null) tickTimer.cancel();
        if (sessionRef != null && sessionListener != null) {
            sessionRef.removeEventListener(sessionListener);
        }
        stopAlarm();
        try {
            if (widgetView != null)  windowManager.removeView(widgetView);
            if (toastView != null)   windowManager.removeView(toastView);
            if (expiredView != null) windowManager.removeView(expiredView);
        } catch (Exception e) {}
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
                }
                @Override
                public void onCancelled(com.google.firebase.database.DatabaseError e) {}
            };
            licenseRef.addValueEventListener(licenseListener);
        } catch (Exception e) {}
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
        return START_STICKY;
    }

}
