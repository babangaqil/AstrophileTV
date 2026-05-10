package com.astrophile.tvoverlay;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
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

    private EditText etApiKey, etDbUrl, etProjectId, etTvNum, etTvName;
    private Button btnConnect;
    private TextView tvStatus;

    private final ActivityResultLauncher<String> notifPermLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        etApiKey    = findViewById(R.id.etApiKey);
        etDbUrl     = findViewById(R.id.etDbUrl);
        etProjectId = findViewById(R.id.etProjectId);
        etTvNum     = findViewById(R.id.etTvNum);
        etTvName    = findViewById(R.id.etTvName);
        btnConnect  = findViewById(R.id.btnConnect);
        tvStatus    = findViewById(R.id.tvStatus);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        etApiKey.setText(prefs.getString("apiKey", ""));
        etDbUrl.setText(prefs.getString("dbUrl", ""));
        etProjectId.setText(prefs.getString("projectId", ""));
        etTvNum.setText(String.valueOf(prefs.getInt("tvNum", 1)));
        etTvName.setText(prefs.getString("tvName", ""));

        btnConnect.setOnClickListener(v -> connectAndStart());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!prefs.getString("apiKey", "").isEmpty() && hasOverlayPermission()) {
            startOverlayService();
            showStatus("Service berjalan di background", "#00ff88");
        }
    }

    private void connectAndStart() {
        String apiKey    = etApiKey.getText().toString().trim();
        String dbUrl     = etDbUrl.getText().toString().trim();
        String projectId = etProjectId.getText().toString().trim();
        String tvNumStr  = etTvNum.getText().toString().trim();
        String tvName    = etTvName.getText().toString().trim();

        if (apiKey.isEmpty() || dbUrl.isEmpty() || projectId.isEmpty()) {
            showStatus("Semua field wajib diisi!", "#ffcc00");
            return;
        }
        if (!dbUrl.contains("firebaseio.com") && !dbUrl.contains("firebasedatabase.app")) {
            showStatus("Database URL tidak valid!", "#ffcc00");
            return;
        }

        int tvNum = 1;
        try { tvNum = Integer.parseInt(tvNumStr); } catch (Exception e) { tvNum = 1; }
        if (tvName.isEmpty()) tvName = "TV " + tvNum;

        SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        editor.putString("apiKey", apiKey);
        editor.putString("dbUrl", dbUrl);
        editor.putString("projectId", projectId);
        editor.putInt("tvNum", tvNum);
        editor.putString("tvName", tvName);
        editor.apply();

        if (!hasOverlayPermission()) {
            showStatus("Butuh izin overlay. Aktifkan lalu kembali.", "#ffcc00");
            requestOverlayPermission();
        } else {
            startOverlayService();
            showStatus("Terhubung! Service berjalan.", "#00ff88");
        }
    }

    private boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
            );
            startActivityForResult(intent, REQUEST_OVERLAY);
        }
    }

    private void startOverlayService() {
        Intent serviceIntent = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void showStatus(String msg, String color) {
        if (tvStatus == null) return;
        tvStatus.setText(msg);
        try {
            tvStatus.setTextColor(android.graphics.Color.parseColor(color));
        } catch (Exception e) {
            tvStatus.setTextColor(android.graphics.Color.WHITE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY) {
            if (hasOverlayPermission()) {
                startOverlayService();
                showStatus("Izin diberikan! Service berjalan.", "#00ff88");
            } else {
                showStatus("Izin ditolak. Tidak bisa overlay.", "#ff4d6d");
            }
        }
    }
}
