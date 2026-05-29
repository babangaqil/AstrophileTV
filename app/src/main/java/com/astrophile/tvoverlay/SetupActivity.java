package com.astrophile.tvoverlay;

import android.Manifest;
import android.content.Intent;
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

    private static final int    REQUEST_OVERLAY = 1001;
    private static final String PREFS           = "astro_tv_prefs";

    private EditText etTvNum, etTvName, etNamaToko;
    private Button   btnConnect;
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

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        etTvNum.setText(String.valueOf(prefs.getInt("tvNum", 1)));
        etTvName.setText(prefs.getString("tvName", ""));
        if (etNamaToko != null)
            etNamaToko.setText(prefs.getString("namaToko", ""));

        // Download Update button
        Button btnDownload = findViewById(R.id.btnDownloadUpdate);
        if (btnDownload != null) {
            btnDownload.setOnClickListener(v -> {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/babangaqil/AstrophileTV/releases/latest/download/AstrophileTV.apk"));
                    startActivity(i);
                } catch (Exception e) {
                    showStatus("Gagal buka browser", "#ff4d6d");
                }
            });
        }

        // Tampilkan status service
        showStatus(isOverlayServiceRunning() ? "● Monitor Aktif" : "○ Monitor Tidak Aktif",
                   isOverlayServiceRunning() ? "#00ff88" : "#ffcc00");

        // Force stop button
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
    }

    private void connectAndStart() {
        String tvNumStr  = etTvNum.getText().toString().trim();
        String tvName    = etTvName.getText().toString().trim();
        String namaToko  = etNamaToko != null ? etNamaToko.getText().toString().trim() : "";

        int tvNum = 1;
        try { tvNum = Integer.parseInt(tvNumStr); } catch (Exception ignored) {}
        if (tvName.isEmpty())   tvName   = "TV " + tvNum;
        if (namaToko.isEmpty()) namaToko = "ASTROPHILE";

        final int    finalTvNum   = tvNum;
        final String finalTvName  = tvName;
        final String finalNamaToko = namaToko;

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt("tvNum", finalTvNum)
            .putString("tvName", finalTvName)
            .putString("namaToko", finalNamaToko)
            .apply();

        if (!hasOverlayPermission()) {
            showStatus("Butuh izin overlay — berikan izin lalu klik Hubungkan", "#ffcc00");
            requestOverlayPermission();
        } else {
            stopService(new Intent(this, OverlayService.class));
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                startOverlayService();
                showStatus("● Monitor Aktif | TV " + finalTvNum + " · " + finalNamaToko, "#00ff88");
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY) {
            if (hasOverlayPermission()) showStatus("Izin OK — klik Hubungkan", "#00ff88");
            else showStatus("Izin ditolak", "#ff4d6d");
        }
    }
}
