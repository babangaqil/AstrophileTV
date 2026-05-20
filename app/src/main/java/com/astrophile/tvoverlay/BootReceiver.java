package com.astrophile.tvoverlay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        SharedPreferences prefs = context.getSharedPreferences(
                "astro_tv_prefs", Context.MODE_PRIVATE
            );
        String apiKey = prefs.getString("apiKey", "");
        if (apiKey.isEmpty()) return;

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // Auto start setelah boot atau setelah update APK
            startService(context);
        } else if ("android.net.conn.CONNECTIVITY_CHANGE".equals(action)) {
            // Jaringan balik online — restart service jika belum jalan
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
            if (ni != null && ni.isConnected()) {
                startService(context);
            }
        }
    }

    private void startService(Context context) {
        Intent serviceIntent = new Intent(context, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}
