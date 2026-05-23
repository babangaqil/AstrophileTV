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
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class SetupActivity extends AppCompatActivity {

    private static final int REQUEST_OVERLAY = 1001;
    private static final String PREFS = "astro_tv_prefs";

    private EditText etLicenseKey;
    private Button btnActivate;
    private TextView tvLicenseStatus;
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
        if (LicenseManager.hasValidLicense(this)) {
            showSetupScreen();
            LicenseManager.checkRevoke(this, new LicenseManager.LicenseCallback() {
                @Override public void onValid(String s, String d) {}
                @Override public void onInvalid(String reason) {
                    runOnUiThread(() -> { LicenseManager.clearLicense(SetupActivity.this); showLicenseScreen(reason); });
                }
                @Override public void onError(String m) {}
            });
        } else {
            showLicenseScreen(null);
        }
    }

    private void showLicenseScreen(String reason) {
        setContentView(R.layout.activity_license);
        etLicenseKey    = findViewById(R.id.etLicenseKey);
        btnActivate     = findViewById(R.id.btnActivate);
        tvLicenseStatus = findViewById(R.id.tvLicenseStatus);
        if (reason != null) {
            switch (reason) {
                case "REVOKED":        setLicenseStatus("License toko dicabut. Hubungi developer.", "#ff4d6d"); break;
                case "EXPIRED":        setLicenseStatus("License expired. Hubungi developer.", "#ffcc00"); break;
                case "DEVICE_REVOKED": setLicenseStatus("Unit TV ini dinonaktifkan.", "#ff4d6d"); break;
            }
        }
        etLicenseKey.addTextChangedListener(new TextWatcher() {
            boolean fmt = false;
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (fmt) return; fmt = true;
                String raw = s.toString().replace("-", "").toUpperCase();
                if (raw.length() > 17) raw = raw.substring(0, 17);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < raw.length(); i++) {
                    if (i == 5 || i == 9 || i == 13) sb.append('-');
                    sb.append(raw.charAt(i));
                }
                etLicenseKey.setText(sb.toString()); etLicenseKey.setSelection(sb.length());
                fmt = false;
            }
        });
        btnActivate.setOnClickListener(v -> activateLicense());
    }

    private void activateLicense() {
        String key = etLicenseKey.getText().toString().trim();
        if (key.length() < 5) { setLicenseStatus("Masukkan license key yang valid", "#ffcc00"); return; }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int tvNum = prefs.getInt("tvNum", 1);
        String tvName = prefs.getString("tvName", "TV " + tvNum);
        btnActivate.setEnabled(false);
        setLicenseStatus("Memverifikasi key...", "#00f5ff");
        LicenseManager.verifyAndRegister(this, key, tvNum, tvName, new LicenseManager.LicenseCallback() {
            @Override public void onValid(String storeId, String deviceId) {
                runOnUiThread(() -> { btnActivate.setEnabled(true); showSetupScreen(); });
            }
            @Override public void onInvalid(String reason) {
                runOnUiThread(() -> {
                    btnActivate.setEnabled(true);
                    switch (reason) {
                        case "NOT_FOUND":      setLicenseStatus("Key tidak ditemukan.", "#ff4d6d"); break;
                        case "REVOKED":        setLicenseStatus("License toko dicabut.", "#ff4d6d"); break;
                        case "EXPIRED":        setLicenseStatus("License expired.", "#ffcc00"); break;
                        case "DEVICE_REVOKED": setLicenseStatus("Unit TV ini dinonaktifkan.", "#ff4d6d"); break;
                        default:               setLicenseStatus("Key tidak valid.", "#ff4d6d");
                    }
                });
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> { btnActivate.setEnabled(true); setLicenseStatus("Gagal koneksi. Cek internet.", "#ffcc00"); });
            }
        });
    }

    private void setLicenseStatus(String msg, String color) {
        if (tvLicenseStatus == null) return;
        tvLicenseStatus.setText(msg);
        tvLicenseStatus.setVisibility(View.VISIBLE);
        try { tvLicenseStatus.setTextColor(android.graphics.Color.parseColor(color)); }
        catch (Exception e) { tvLicenseStatus.setTextColor(android.graphics.Color.WHITE); }
    }

    private void showSetupScreen() {
        setContentView(R.layout.activity_setup);
        etTvNum    = findViewById(R.id.etTvNum);
        etTvName   = findViewById(R.id.etTvName);
        btnConnect = findViewById(R.id.btnConnect);
        tvStatus   = findViewById(R.id.tvStatus);

        TextView tvDeviceInfo = findViewById(R.id.tvDeviceInfo);
        String deviceId = LicenseManager.getSavedDeviceId(this);
        String storeId  = LicenseManager.getSavedStoreId(this);

        String appVersion = "?";
        try {
            appVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}

        if (tvDeviceInfo != null && !deviceId.isEmpty()) {
            tvDeviceInfo.setText(storeId + " | " + deviceId + " | v" + appVersion);
            tvDeviceInfo.setVisibility(View.VISIBLE);
        } else if (tvDeviceInfo != null) {
            tvDeviceInfo.setText("v" + appVersion);
            tvDeviceInfo.setVisibility(View.VISIBLE);
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        etTvNum.setText(String.valueOf(prefs.getInt("tvNum", 1)));
        etTvName.setText(prefs.getString("tvName", ""));

        btnConnect.setOnClickListener(v -> connectAndStart());

        if (!prefs.getString("apiKey", "").isEmpty() && hasOverlayPermission()) {
            startOverlayService();
            showStatus("Terhubung! | " + deviceId, "#00ff88");
        }

        // Monitor koneksi live — polling tvStatus/online setiap 5 detik
        startConnectionMonitor(prefs);

        // Bind update button
        btnUpdate    = findViewById(R.id.btnUpdate);
        tvUpdateInfo = findViewById(R.id.tvUpdateInfo);

        // Register receiver update dari OverlayService
        registerUpdateReceiver();

        // Cek update langsung saat SetupActivity dibuka — tidak nunggu broadcast
        checkUpdateFromFirebase(prefs);
    }

    private void checkUpdateFromFirebase(SharedPreferences prefs) {
        String apiKey    = prefs.getString("apiKey", "");
        String dbUrl     = prefs.getString("dbUrl", "");
        String projectId = prefs.getString("projectId", "");
        if (apiKey.isEmpty() || dbUrl.isEmpty()) return;

        // Firebase init & query harus di main thread
        runOnUiThread(() -> {
            try {
                com.google.firebase.FirebaseApp app;
                try { app = com.google.firebase.FirebaseApp.getInstance("_tv_license"); }
                catch (Exception e) {
                    com.google.firebase.FirebaseOptions opts = new com.google.firebase.FirebaseOptions.Builder()
                        .setApiKey("AIzaSyD8XffAZK8JUOBajCUVyPS-NT9jnwYBats")
                        .setDatabaseUrl("https://astrophile-rental-default-rtdb.firebaseio.com")
                        .setProjectId("astrophile-rental")
                        .setApplicationId("1:789474619442:android:5f678d3b6ebdc99a1c8c2b")
                        .build();
                    app = com.google.firebase.FirebaseApp.initializeApp(
                        SetupActivity.this, opts, "_tv_license");
                }
                com.google.firebase.database.FirebaseDatabase masterDb =
                    com.google.firebase.database.FirebaseDatabase.getInstance(app);
                masterDb.getReference("settings/globalUpdate").get()
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
            // Minta focus agar D-pad remote TV langsung bisa pilih button ini
            btnUpdate.post(() -> btnUpdate.requestFocus());
        });
    }

    private android.os.Handler monitorHandler = null;
    private Runnable monitorRunnable = null;
    private android.widget.Button  btnUpdate    = null;
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
        String deviceId  = LicenseManager.getSavedDeviceId(this);
        int    tvNum     = prefs.getInt("tvNum", 1);

        if (apiKey.isEmpty() || dbUrl.isEmpty()) return;

        // ── Gunakan Firebase TOKO (dbUrl toko) bukan Master ──────────────
        // tvStatus/online ditulis oleh OverlayService ke Firebase toko
        com.google.firebase.FirebaseApp tokoApp;
        try { tokoApp = com.google.firebase.FirebaseApp.getInstance("_toko_monitor"); }
        catch (Exception e) {
            com.google.firebase.FirebaseOptions opts = new com.google.firebase.FirebaseOptions.Builder()
                .setApiKey(apiKey).setDatabaseUrl(dbUrl).setProjectId(projectId)
                .setApplicationId("1:789474619442:android:5f678d3b6ebdc99a1c8c2b")
                .build();
            tokoApp = com.google.firebase.FirebaseApp.initializeApp(this, opts, "_toko_monitor");
        }
        com.google.firebase.database.FirebaseDatabase tokoDB =
            com.google.firebase.database.FirebaseDatabase.getInstance(tokoApp);

        // ── Listener realtime — lebih efisien dari polling .get() ─────────
        // Tidak perlu restart service dari sini — service manage dirinya sendiri
        tokoDB.getReference("settings/tvStatus/" + tvNum + "/online")
            .addValueEventListener(new com.google.firebase.database.ValueEventListener() {
                boolean[] wasPreviouslyOnline = {false};
                @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                    if (isFinishing()) return;
                    boolean online = Boolean.TRUE.equals(snap.getValue(Boolean.class));
                    showStatus(
                        online ? "Terhubung! | " + deviceId : "Menghubungkan...",
                        online ? "#00ff88" : "#ffcc00"
                    );
                    if (online) {
                        wasPreviouslyOnline[0] = true;
                    } else if (wasPreviouslyOnline[0]) {
                        // Sempat online lalu offline (service restart setelah update)
                        // Auto-reconnect tanpa perlu operator klik manual
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
    protected void onResume() {
        super.onResume();
        // Cek setiap kali app kembali ke foreground (termasuk setelah izin overlay diberikan)
        // Kalau sudah ada API key + permission → langsung connect tanpa perlu klik
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getString("apiKey", "").isEmpty() && hasOverlayPermission()) {
            // Service belum jalan → auto-start
            startOverlayService();
        }
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
            showStatus("Config Firebase tidak ditemukan. Aktivasi ulang license.", "#ffcc00");
            return;
        }

        final int finalTvNum = tvNum;
        final String finalTvName = tvName;
        prefs.edit()
            .putInt("tvNum", finalTvNum)
            .putString("tvName", finalTvName)
            .apply();

        String key = LicenseManager.getSavedKey(this);
        if (!key.isEmpty()) {
            LicenseManager.verifyAndRegister(this, key, finalTvNum, finalTvName, new LicenseManager.LicenseCallback() {
                @Override public void onValid(String s, String d) {}
                @Override public void onInvalid(String r) {}
                @Override public void onError(String m) {}
            });
        }

        if (!hasOverlayPermission()) {
            showStatus("Butuh izin overlay.", "#ffcc00");
            requestOverlayPermission();
        } else {
            // Stop service lama dulu agar tvNum baru dipakai
            stopService(new Intent(this, OverlayService.class));
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                startOverlayService();
                showStatus("Terhubung! | " + LicenseManager.getSavedDeviceId(SetupActivity.this), "#00ff88");
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
        tvStatus.setText(msg);
        try { tvStatus.setTextColor(android.graphics.Color.parseColor(color)); }
        catch (Exception e) { tvStatus.setTextColor(android.graphics.Color.WHITE); }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY) {
            if (hasOverlayPermission()) { startOverlayService(); showStatus("Izin diberikan!", "#00ff88"); }
            else showStatus("Izin ditolak.", "#ff4d6d");
        }
    }
}
