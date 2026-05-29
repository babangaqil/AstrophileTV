package com.astrophile.tvoverlay;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class SetupActivity extends AppCompatActivity {

    private static final int    REQUEST_OVERLAY  = 1001;
    private static final String PREFS            = "astro_tv_prefs";
    private static final String UPDATE_URL       =
        "https://github.com/babangaqil/AstrophileTV/releases/latest/download/AstrophileTV.apk";
    public  static final String ACTION_KASIR_HIT = "com.astrophile.tvoverlay.KASIR_HIT";

    private EditText etTvNum, etTvName, etNamaToko;
    private Button   btnConnect;
    private TextView tvStatus;

    // Polling status service
    private final Handler  statusHandler  = new Handler(Looper.getMainLooper());
    private final Runnable statusRunnable = this::refreshStatus;

    // Receiver: kasir hit /command → broadcast dari OverlayService
    private BroadcastReceiver kasirReceiver;
    private long lastKasirHit = 0;

    private final ActivityResultLauncher<String> notifPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});

    // ── LIFECYCLE ─────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Minta notif permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        setContentView(R.layout.activity_setup);

        etTvNum    = findViewById(R.id.etTvNum);
        etTvName   = findViewById(R.id.etTvName);
        etNamaToko = findViewById(R.id.etNamaToko);
        btnConnect = findViewById(R.id.btnConnect);
        tvStatus   = findViewById(R.id.tvStatus);

        // Versi app
        TextView tvDeviceInfo = findViewById(R.id.tvDeviceInfo);
        if (tvDeviceInfo != null) {
            String ver = "?";
            try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception ignored) {}
            tvDeviceInfo.setText("v" + ver);
            tvDeviceInfo.setVisibility(View.VISIBLE);
        }

        // IP LAN
        TextView tvIpInfo = findViewById(R.id.tvIpInfo);
        if (tvIpInfo != null) {
            String ip = OverlayService.getLocalIpAddress();
            tvIpInfo.setText("📡 IP TV (LAN): " + ip + ":8080");
            tvIpInfo.setVisibility(View.VISIBLE);
        }

        // Load saved values
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        etTvNum.setText(String.valueOf(prefs.getInt("tvNum", 1)));
        etTvName.setText(prefs.getString("tvName", ""));
        etNamaToko.setText(prefs.getString("namaToko", ""));

        // Tombol connect
        btnConnect.setOnClickListener(v -> connectAndStart());

        // Download update
        Button btnDownload = findViewById(R.id.btnDownloadUpdate);
        if (btnDownload != null) {
            btnDownload.setOnClickListener(v -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(UPDATE_URL))); }
                catch (Exception e) { showStatus("Gagal buka browser", "#ff4d6d"); }
            });
        }

        // Force stop
        Button btnForceStop = findViewById(R.id.btnForceStop);
        if (btnForceStop != null) {
            btnForceStop.setOnClickListener(v -> {
                stopService(new Intent(this, OverlayService.class));
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Intent restart = new Intent(this, SetupActivity.class);
                    restart.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(restart);
                    finish();
                }, 800);
            });
        }

        // Auto-minta izin overlay jika belum ada
        if (!hasOverlayPermission()) {
            showStatus("⚠ Butuh izin tampil di atas layar — memberikan izin...", "#ffcc00");
            new Handler(Looper.getMainLooper()).postDelayed(this::requestOverlayPermission, 800);
        }

        registerKasirReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        statusHandler.postDelayed(statusRunnable, 2000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        statusHandler.removeCallbacks(statusRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        statusHandler.removeCallbacks(statusRunnable);
        if (kasirReceiver != null) {
            try { unregisterReceiver(kasirReceiver); } catch (Exception ignored) {}
        }
    }

    // ── STATUS POLLING ────────────────────────────────────────

    private void refreshStatus() {
        boolean running = isOverlayServiceRunning();
        boolean kasirTerhubung = (System.currentTimeMillis() - lastKasirHit) < 10_000; // 10 detik

        if (!running) {
            showStatus("○ Monitor Tidak Aktif", "#ff4d6d");
        } else if (kasirTerhubung) {
            showStatus("● Monitor Aktif  ·  ✓ Terhubung ke Kasir", "#00ff88");
        } else {
            showStatus("● Monitor Aktif  ·  Menunggu Kasir...", "#ffcc00");
        }

        // Jadwalkan polling berikutnya
        statusHandler.removeCallbacks(statusRunnable);
        statusHandler.postDelayed(statusRunnable, 2000);
    }

    // ── KASIR BROADCAST RECEIVER ──────────────────────────────

    private void registerKasirReceiver() {
        kasirReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                lastKasirHit = System.currentTimeMillis();
                refreshStatus();
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_KASIR_HIT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(kasirReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(kasirReceiver, filter);
        }
    }

    // ── CONNECT ───────────────────────────────────────────────

    private void connectAndStart() {
        // Cek izin overlay dulu
        if (!hasOverlayPermission()) {
            showStatus("⚠ Butuh izin overlay — memberikan izin...", "#ffcc00");
            requestOverlayPermission();
            return;
        }

        String tvNumStr = etTvNum.getText().toString().trim();
        String tvName   = etTvName.getText().toString().trim();
        String namaToko = etNamaToko.getText().toString().trim();

        int tvNum = 1;
        try { tvNum = Integer.parseInt(tvNumStr); } catch (Exception ignored) {}
        if (tvName.isEmpty())   tvName   = "TV " + tvNum;
        if (namaToko.isEmpty()) namaToko = "ASTROPHILE";

        final int    finalTvNum    = tvNum;
        final String finalTvName   = tvName;
        final String finalNamaToko = namaToko;

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt("tvNum", finalTvNum)
            .putString("tvName", finalTvName)
            .putString("namaToko", finalNamaToko)
            .apply();

        showStatus("⏳ Memulai monitor...", "#00f5ff");
        stopService(new Intent(this, OverlayService.class));
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startOverlayService();
            refreshStatus();
        }, 1000);
    }

    // ── OVERLAY PERMISSION ────────────────────────────────────

    private boolean hasOverlayPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_OVERLAY);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY) {
            if (hasOverlayPermission()) {
                showStatus("✓ Izin diberikan — klik Hubungkan", "#00ff88");
            } else {
                showStatus("✗ Izin ditolak — tidak bisa menampilkan overlay", "#ff4d6d");
            }
        }
    }

    // ── HELPERS ───────────────────────────────────────────────

    private void startOverlayService() {
        Intent si = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si);
        else startService(si);
    }

    private void showStatus(String msg, String color) {
        if (tvStatus == null) return;
        try { tvStatus.setTextColor(android.graphics.Color.parseColor(color)); }
        catch (Exception ignored) {}
        tvStatus.setText(msg);
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
}
