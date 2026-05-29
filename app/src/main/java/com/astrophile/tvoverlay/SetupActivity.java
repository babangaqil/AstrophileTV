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

    // Setup screen views
    private EditText etTvNum, etTvName, etNamaToko;
    private Button   btnConnect;
    private TextView tvStatus;

    // License screen views
    private View     layoutLicense;
    private View     layoutSetup;
    private EditText etLicenseKey;
    private Button   btnActivate;
    private TextView tvLicenseStatus;
    private TextView tvHwId;

    private final ActivityResultLauncher<String> notifPermLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED)
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
        setContentView(R.layout.activity_setup);

        // Setup views
        etTvNum    = findViewById(R.id.etTvNum);
        etTvName   = findViewById(R.id.etTvName);
        etNamaToko = findViewById(R.id.etNamaToko);
        btnConnect = findViewById(R.id.btnConnect);
        tvStatus   = findViewById(R.id.tvStatus);

        // License views
        layoutLicense   = findViewById(R.id.layoutLicense);
        layoutSetup     = findViewById(R.id.layoutSetup);
        etLicenseKey    = findViewById(R.id.etLicenseKey);
        btnActivate     = findViewById(R.id.btnActivate);
        tvLicenseStatus = findViewById(R.id.tvLicenseStatus);
        tvHwId          = findViewById(R.id.tvHwId);

        // IP LAN
        TextView tvIpInfo = findViewById(R.id.tvIpInfo);
        if (tvIpInfo != null) {
            tvIpInfo.setText("📡 IP TV (LAN): " + OverlayService.getLocalIpAddress() + ":8080");
            tvIpInfo.setVisibility(View.VISIBLE);
        }

        // Force stop button
        Button btnForceStop = findViewById(R.id.btnForceStop);
        if (btnForceStop != null) {
            btnForceStop.setOnClickListener(v -> {
                stopService(new Intent(this, OverlayService.class));
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Intent restart = new Intent(this, SetupActivity.class);
                    restart.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(restart); finish();
                }, 800);
            });
        }

        // Download update button
        Button btnDownload = findViewById(R.id.btnDownloadUpdate);
        if (btnDownload != null) {
            btnDownload.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://github.com/babangaqil/AstrophileTV/releases/latest/download/AstrophileTV.apk")));
                } catch (Exception e) { showStatus("Gagal buka browser", "#ff4d6d"); }
            });
        }

        // Cek license
        if (LicenseManager.hasValidLicense(this)) {
            // Re-verify lokal saat startup — pastikan tidak expired
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            int tvNum = prefs.getInt("tvNum", 1);
            String savedKey = LicenseManager.getSavedKey(this);
            LicenseManager.verify(this, savedKey, tvNum, new LicenseManager.LicenseCallback() {
                @Override public void onValid(String tvNumStr, String expireDate) {
                    runOnUiThread(() -> showSetupScreen());
                }
                @Override public void onInvalid(String reason) {
                    runOnUiThread(() -> {
                        LicenseManager.clearLicense(SetupActivity.this);
                        showLicenseScreen(reason);
                    });
                }
            });
        } else {
            showLicenseScreen(null);
        }
    }

    // ═══════════════════════════════════════════════════════
    // LICENSE SCREEN
    // ═══════════════════════════════════════════════════════

    private void showLicenseScreen(String reason) {
        if (layoutLicense != null) layoutLicense.setVisibility(View.VISIBLE);
        if (layoutSetup   != null) layoutSetup.setVisibility(View.GONE);

        // Tampilkan Hardware ID
        String hwId = LicenseManager.getOrCreateHardwareId(this);
        if (tvHwId != null) {
            tvHwId.setText("Hardware ID: " + hwId);
            tvHwId.setVisibility(View.VISIBLE);
        }

        // Pesan alasan
        if (reason != null && tvLicenseStatus != null) {
            switch (reason) {
                case "EXPIRED":      setLicenseStatus("⚠️ License expired. Hubungi developer.", "#ffcc00"); break;
                case "WRONG_DEVICE": setLicenseStatus("❌ Key bukan untuk device ini.", "#ff4d6d"); break;
                case "WRONG_TV_NUM": setLicenseStatus("❌ Key bukan untuk nomor TV ini.", "#ff4d6d"); break;
                default:             setLicenseStatus("❌ License tidak valid.", "#ff4d6d"); break;
            }
        }

        if (btnActivate != null) {
            btnActivate.setOnClickListener(v -> activateLicense());
        }
    }

    private void activateLicense() {
        if (etLicenseKey == null) return;
        String key = etLicenseKey.getText().toString().trim();
        if (key.length() < 5) { setLicenseStatus("Masukkan license key yang valid", "#ffcc00"); return; }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int tvNum = prefs.getInt("tvNum", 1);

        // Coba baca tvNum dari field input jika ada
        if (etTvNum != null && !etTvNum.getText().toString().trim().isEmpty()) {
            try { tvNum = Integer.parseInt(etTvNum.getText().toString().trim()); } catch (Exception ignored) {}
        }

        final int finalTvNum = tvNum;
        if (btnActivate != null) btnActivate.setEnabled(false);
        setLicenseStatus("Memverifikasi...", "#00f5ff");

        LicenseManager.verify(this, key, finalTvNum, new LicenseManager.LicenseCallback() {
            @Override public void onValid(String tvNumStr, String expireDate) {
                runOnUiThread(() -> {
                    if (btnActivate != null) btnActivate.setEnabled(true);
                    setLicenseStatus("✅ License aktif s/d " + expireDate, "#00ff88");
                    // Simpan tvNum dari key ke prefs
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putInt("tvNum", finalTvNum).apply();
                    new Handler(Looper.getMainLooper()).postDelayed(
                        () -> showSetupScreen(), 1000);
                });
            }
            @Override public void onInvalid(String reason) {
                runOnUiThread(() -> {
                    if (btnActivate != null) btnActivate.setEnabled(true);
                    switch (reason) {
                        case "WRONG_DEVICE":   setLicenseStatus("❌ Key bukan untuk device ini.\nHW ID: " + LicenseManager.getOrCreateHardwareId(SetupActivity.this), "#ff4d6d"); break;
                        case "WRONG_TV_NUM":   setLicenseStatus("❌ Key bukan untuk TV " + finalTvNum + ".", "#ff4d6d"); break;
                        case "EXPIRED":        setLicenseStatus("⚠️ License expired.", "#ffcc00"); break;
                        case "INVALID_FORMAT": setLicenseStatus("❌ Format key salah.", "#ff4d6d"); break;
                        default:               setLicenseStatus("❌ Key tidak valid.", "#ff4d6d"); break;
                    }
                });
            }
        });
    }

    private void setLicenseStatus(String msg, String color) {
        if (tvLicenseStatus == null) return;
        try { tvLicenseStatus.setTextColor(android.graphics.Color.parseColor(color)); }
        catch (Exception ignored) {}
        tvLicenseStatus.setText(msg);
        tvLicenseStatus.setVisibility(View.VISIBLE);
    }

    // ═══════════════════════════════════════════════════════
    // SETUP SCREEN
    // ═══════════════════════════════════════════════════════

    private void showSetupScreen() {
        if (layoutLicense != null) layoutLicense.setVisibility(View.GONE);
        if (layoutSetup   != null) layoutSetup.setVisibility(View.VISIBLE);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (etTvNum    != null) etTvNum.setText(String.valueOf(prefs.getInt("tvNum", 1)));
        if (etTvName   != null) etTvName.setText(prefs.getString("tvName", ""));
        if (etNamaToko != null) etNamaToko.setText(prefs.getString("namaToko", ""));

        // Device info
        TextView tvDeviceInfo = findViewById(R.id.tvDeviceInfo);
        if (tvDeviceInfo != null) {
            String hwId   = LicenseManager.getOrCreateHardwareId(this);
            String expire = LicenseManager.getSavedExpire(this);
            String ver    = "?";
            try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception ignored) {}
            String info = "HW: " + hwId;
            if (!expire.isEmpty()) info += " | Exp: " + expire;
            info += " | v" + ver;
            tvDeviceInfo.setText(info);
            tvDeviceInfo.setVisibility(View.VISIBLE);
        }

        showStatus(isOverlayServiceRunning() ? "● Monitor Aktif" : "○ Monitor Tidak Aktif",
                   isOverlayServiceRunning() ? "#00ff88" : "#ffcc00");

        if (btnConnect != null) btnConnect.setOnClickListener(v -> connectAndStart());
    }

    // ═══════════════════════════════════════════════════════
    // CONNECT & START
    // ═══════════════════════════════════════════════════════

    private void connectAndStart() {
        String tvNumStr  = etTvNum    != null ? etTvNum.getText().toString().trim()    : "1";
        String tvName    = etTvName   != null ? etTvName.getText().toString().trim()   : "";
        String namaToko  = etNamaToko != null ? etNamaToko.getText().toString().trim() : "";

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

    // ═══════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════

    private boolean hasOverlayPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())), REQUEST_OVERLAY);
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
        for (android.app.ActivityManager.RunningServiceInfo s : am.getRunningServices(50))
            if (OverlayService.class.getName().equals(s.service.getClassName())) return true;
        return false;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQUEST_OVERLAY) {
            if (hasOverlayPermission()) showStatus("Izin OK — klik Hubungkan", "#00ff88");
            else showStatus("Izin ditolak", "#ff4d6d");
        }
    }
}
