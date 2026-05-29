package com.astrophile.tvoverlay;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.EditText;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class SetupActivity extends AppCompatActivity {

    private static final int REQUEST_OVERLAY = 1001;
    private static final String PREFS = "astro_tv_prefs";
    private static final String KEY_OFFLINE = "offline_mode";

    private EditText etTvNum, etTvName;
    private Button btnConnect;
    private TextView tvStatus;

    private final ActivityResultLauncher<String> notifPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        showSetupScreen();
    }

    private void showSetupScreen() {
        setContentView(R.layout.activity_setup);
        etTvNum    = findViewById(R.id.etTvNum);
        etTvName   = findViewById(R.id.etTvName);
        btnConnect = findViewById(R.id.btnConnect);
        tvStatus   = findViewById(R.id.tvStatus);

        TextView tvDeviceInfo = findViewById(R.id.tvDeviceInfo);
        String appVersion = "?";
        try {
            appVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}

        if (tvDeviceInfo != null) {
            tvDeviceInfo.setText("v" + appVersion);
            tvDeviceInfo.setVisibility(View.VISIBLE);
        }

        // Tampilkan IP lokal TV untuk mode offline kasir
        TextView tvIpInfo = findViewById(R.id.tvIpInfo);
        if (tvIpInfo != null) {
            String ip = OverlayService.getLocalIpAddress();
            tvIpInfo.setText("📡 IP TV (Offline): " + ip + ":8080");
            tvIpInfo.setVisibility(View.VISIBLE);
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        // ── Toggle Online / Offline ───────────────────────────────────────
        boolean isOffline = prefs.getBoolean(KEY_OFFLINE, false);
        SwitchCompat switchOffline = findViewById(R.id.switchOfflineMode);
        TextView      tvModeLabel  = findViewById(R.id.tvModeLabel);
        if (switchOffline != null) {
            switchOffline.setChecked(isOffline);
            updateModeLabel(tvModeLabel, isOffline);
            switchOffline.setOnCheckedChangeListener((btn, checked) -> {
                prefs.edit().putBoolean(KEY_OFFLINE, checked).apply();
                updateModeLabel(tvModeLabel, checked);
                android.content.Intent intent = new android.content.Intent("com.astrophile.SET_MODE");
                intent.putExtra("offline", checked);
                sendBroadcast(intent);
                android.widget.Toast.makeText(this,
                    checked ? "📡 Mode Offline aktif" : "🌐 Mode Online aktif",
                    android.widget.Toast.LENGTH_SHORT).show();
            });
        }

        etTvNum.setText(String.valueOf(prefs.getInt("tvNum", 1)));
        etTvName.setText(prefs.getString("tvName", ""));

        btnConnect.setOnClickListener(v -> connectAndStart());

        if (!prefs.getString("apiKey", "").isEmpty() && !hasOverlayPermission()) {
            showStatus("Butuh izin overlay — klik Hubungkan", "#ffcc00");
        }

        // Monitor koneksi live
        startConnectionMonitor(prefs);

        // Bind update button
        btnUpdate    = findViewById(R.id.btnUpdate);
        // Bind force stop button
        btnForceStop = findViewById(R.id.btnForceStop);
        if (btnForceStop != null) {
            btnForceStop.setOnClickListener(v -> {
                stopService(new android.content.Intent(this, OverlayService.class));
                new android.os.Handler().postDelayed(() -> {
                    android.content.Intent restart = new android.content.Intent(this, SetupActivity.class);
                    restart.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(restart);
                    finish();
                }, 800);
            });
        }
        tvUpdateInfo = findViewById(R.id.tvUpdateInfo);

        registerUpdateReceiver();
        checkUpdateFromFirebase(prefs);
    }

    private void checkUpdateFromFirebase(SharedPreferences prefs) {
        String apiKey    = prefs.getString("apiKey", "");
        String dbUrl     = prefs.getString("dbUrl", "");
        String projectId = prefs.getString("projectId", "");
        if (apiKey.isEmpty() || dbUrl.isEmpty()) return;

        runOnUiThread(() -> {
            try {
                com.google.firebase.FirebaseApp app;
                try { app = com.google.firebase.FirebaseApp.getInstance("_tv_setup_update"); }
                catch (Exception e) {
                    com.google.firebase.FirebaseOptions opts = new com.google.firebase.FirebaseOptions.Builder()
                        .setApiKey(apiKey)
                        .setDatabaseUrl(dbUrl)
                        .setProjectId(projectId)
                        .setApplicationId("1:000000000000:android:0000000000000000000000")
                        .build();
                    app = com.google.firebase.FirebaseApp.initializeApp(
                        SetupActivity.this, opts, "_tv_setup_update");
                }
                com.google.firebase.database.FirebaseDatabase db =
                    com.google.firebase.database.FirebaseDatabase.getInstance(app);
                db.getReference("settings/globalUpdate").get()
                    .addOnCompleteListener(task -> {
                        if (!task.isSuccessful() || task.getResult() == null
                                || !task.getResult().exists()) return;
                        com.google.firebase.database.DataSnapshot snap = task.getResult();
                        Boolean enabled = snap.child("enabled").getValue(Boolean.class);
                        if (!Boolean.TRUE.equals(enabled)) return;

                        String minVersion = snap.child("minVersion").getValue(String.class);
                        String url        = snap.child("url").getValue(String.class);
                        String message    = snap.child("message").getValue(String.class);
                        if (minVersion == null || minVersion.isEmpty()) return;

                        String currentVersion = "";
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                currentVersion = getPackageManager().getPackageInfo(
                                    getPackageName(),
                                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                                ).versionName;
                            } else {
                                @SuppressWarnings("deprecation")
                                android.content.pm.PackageInfo pi = getPackageManager()
                                    .getPackageInfo(getPackageName(), 0);
                                currentVersion = pi.versionName;
                            }
                        } catch (Exception e) { return; }

                        if (isVersionLower(currentVersion, minVersion)) {
                            final String fUrl = url != null ? url : "";
                            final String fMsg = message != null ? message : "Pembaruan tersedia.";
                            final String fVer = "v" + minVersion;
                            showUpdateButton(fVer, fUrl, fMsg);
                        }
                    });
            } catch (Exception ignored) {}
        });
    }

    private boolean isVersionLower(String current, String minimum) {
        try {
            String[] c = current.split("\\.");
            String[] m = minimum.split("\\.");
            int len = Math.max(c.length, m.length);
            for (int i = 0; i < len; i++) {
                int cv = i < c.length ? Integer.parseInt(c[i].replaceAll("[^0-9]", "")) : 0;
                int mv = i < m.length ? Integer.parseInt(m[i].replaceAll("[^0-9]", "")) : 0;
                if (cv < mv) return true;
                if (cv > mv) return false;
            }
            return false;
        } catch (Exception e) { return false; }
    }

    private void registerUpdateReceiver() {
        if (updateReceiver != null) return;
        updateReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context ctx, android.content.Intent intent) {
                if ("com.astrophile.tvoverlay.UPDATE_CLEAR".equals(intent.getAction())) {
                    runOnUiThread(() -> {
                        if (btnUpdate    != null) btnUpdate.setVisibility(android.view.View.GONE);
                        if (tvUpdateInfo != null) tvUpdateInfo.setVisibility(android.view.View.GONE);
                    });
                    return;
                }
                String version = intent.getStringExtra("version");
                String url     = intent.getStringExtra("url");
                String message = intent.getStringExtra("message");
                showUpdateButton(version, url, message);
            }
        };
        android.content.IntentFilter filter =
            new android.content.IntentFilter("com.astrophile.tvoverlay.UPDATE_AVAILABLE");
        filter.addAction("com.astrophile.tvoverlay.UPDATE_CLEAR");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateReceiver, filter);
        }
    }

    private void showUpdateButton(String version, String url, String message) {
        if (btnUpdate == null || tvUpdateInfo == null) return;
        runOnUiThread(() -> {
            btnUpdate.setVisibility(android.view.View.VISIBLE);
            tvUpdateInfo.setVisibility(android.view.View.VISIBLE);
            String label = (version != null && !version.isEmpty())
                ? message + " (" + version + ")"
                : (message != null ? message : "Update tersedia");
            tvUpdateInfo.setText(label);
            btnUpdate.setFocusable(true);
            btnUpdate.setClickable(true);
            btnUpdate.setOnClickListener(v -> {
                if (url != null && !url.isEmpty()) {
                    try {
                        android.content.Intent i = new android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url));
                        startActivity(i);
                    } catch (Exception ignored) {}
                }
            });
            btnUpdate.post(() -> btnUpdate.requestFocus());
        });
    }

    private android.os.Handler monitorHandler = null;
    private Runnable monitorRunnable = null;
    private android.widget.Button  btnUpdate    = null;
    private android.widget.Button  btnForceStop = null;
    private android.widget.TextView tvUpdateInfo = null;
    private android.content.BroadcastReceiver updateReceiver = null;

    private void startConnectionMonitor(SharedPreferences prefs) {
        if (monitorHandler != null && monitorRunnable != null) {
            monitorHandler.removeCallbacks(monitorRunnable);
        }
        monitorHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        String apiKey    = prefs.getString("apiKey", "");
        String dbUrl     = prefs.getString("dbUrl", "");
        String projectId = prefs.getString("projectId", "");
        int    tvNum     = prefs.getInt("tvNum", 1);

        if (apiKey.isEmpty() || dbUrl.isEmpty()) return;

        com.google.firebase.FirebaseApp tokoApp;
        try { tokoApp = com.google.firebase.FirebaseApp.getInstance("_toko_monitor"); }
        catch (Exception e) {
            com.google.firebase.FirebaseOptions opts = new com.google.firebase.FirebaseOptions.Builder()
                .setApiKey(apiKey).setDatabaseUrl(dbUrl).setProjectId(projectId)
                .setApplicationId("1:000000000000:android:0000000000000000000000")
                .build();
            tokoApp = com.google.firebase.FirebaseApp.initializeApp(this, opts, "_toko_monitor");
        }
        com.google.firebase.database.FirebaseDatabase tokoDB =
            com.google.firebase.database.FirebaseDatabase.getInstance(tokoApp);

        tokoDB.getReference("settings/tvStatus/" + tvNum + "/online")
            .addValueEventListener(new com.google.firebase.database.ValueEventListener() {
                boolean[] wasPreviouslyOnline = {false};
                @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                    if (isFinishing()) return;
                    boolean online = Boolean.TRUE.equals(snap.getValue(Boolean.class));
                    showStatus(
                        online ? "Terhubung! | TV " + tvNum : "Menghubungkan...",
                        online ? "#00ff88" : "#ffcc00"
                    );
                    if (online) {
                        wasPreviouslyOnline[0] = true;
                    } else if (wasPreviouslyOnline[0]) {
                        wasPreviouslyOnline[0] = false;
                        new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(() -> {
                                if (!isFinishing()) startOverlayService();
                            }, 2000);
                    }
                }
                @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {}
            });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (monitorHandler != null && monitorRunnable != null)
            monitorHandler.removeCallbacks(monitorRunnable);
        if (updateReceiver != null) {
            try { unregisterReceiver(updateReceiver); } catch (Exception ignored) {}
            updateReceiver = null;
        }
    }

    private void connectAndStart() {
        String tvNumStr = etTvNum.getText().toString().trim();
        String tvName   = etTvName.getText().toString().trim();

        int tvNum = 1;
        try { tvNum = Integer.parseInt(tvNumStr); } catch (Exception e) { tvNum = 1; }
        if (tvName.isEmpty()) tvName = "TV " + tvNum;

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String apiKey    = prefs.getString("apiKey", "");
        String dbUrl     = prefs.getString("dbUrl", "");
        String projectId = prefs.getString("projectId", "");

        if (apiKey.isEmpty() || dbUrl.isEmpty() || projectId.isEmpty()) {
            showStatus("Config Firebase tidak ditemukan. Isi ApiKey & DbUrl di SharedPreferences.", "#ffcc00");
            return;
        }

        final int finalTvNum = tvNum;
        final String finalTvName = tvName;
        prefs.edit()
            .putInt("tvNum", finalTvNum)
            .putString("tvName", finalTvName)
            .apply();

        if (!hasOverlayPermission()) {
            showStatus("Butuh izin overlay.", "#ffcc00");
            requestOverlayPermission();
        } else {
            stopService(new Intent(this, OverlayService.class));
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                startOverlayService();
                showStatus("Menghubungkan... | TV " + finalTvNum, "#00f5ff");
            }, 800);
        }
    }

    private boolean hasOverlayPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), REQUEST_OVERLAY);
        }
    }

    private void startOverlayService() {
        Intent si = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si);
        else startService(si);
    }

    private void showStatus(String msg, String color) {
        if (tvStatus == null) return;
        boolean running = isOverlayServiceRunning();
        String serviceLabel = running ? "\n● Monitor Aktif" : "\n○ Monitor Tidak Aktif";
        int serviceColor    = running
                ? android.graphics.Color.parseColor("#00ff88")
                : android.graphics.Color.parseColor("#ff4d6d");

        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        android.text.SpannableString s1 = new android.text.SpannableString(msg);
        try { s1.setSpan(new android.text.style.ForegroundColorSpan(
                android.graphics.Color.parseColor(color)), 0, s1.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); }
        catch (Exception ignored) {}
        sb.append(s1);
        android.text.SpannableString s2 = new android.text.SpannableString(serviceLabel);
        s2.setSpan(new android.text.style.ForegroundColorSpan(serviceColor), 0, s2.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(s2);
        tvStatus.setText(sb);
    }

    @SuppressWarnings("deprecation")
    private boolean isOverlayServiceRunning() {
        android.app.ActivityManager am =
                (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am == null) return false;
        for (android.app.ActivityManager.RunningServiceInfo svc : am.getRunningServices(50)) {
            if (OverlayService.class.getName().equals(svc.service.getClassName())) return true;
        }
        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY) {
            if (hasOverlayPermission()) { showStatus("Izin OK — klik Hubungkan untuk mulai", "#00ff88"); }
            else showStatus("Izin ditolak.", "#ff4d6d");
        }
    }

    private void updateModeLabel(android.widget.TextView tv, boolean offline) {
        if (tv == null) return;
        tv.setText(offline ? "📡 Mode Offline (LAN)" : "🌐 Mode Online (Firebase)");
        tv.setTextColor(offline
            ? android.graphics.Color.parseColor("#00ff88")
            : android.graphics.Color.parseColor("#00f5ff"));
    }
}
