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
                "astro_tv_prefs", Context.MODE_PRIVATE);
        String apiKey = prefs.getString("apiKey", "");
        if (apiKey.isEmpty()) return;

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            startService(context);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            try {
                context.stopService(new Intent(context, OverlayService.class));
            } catch (Exception ignored) {}
            new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> startService(context), 1500);
        } else if ("android.net.conn.CONNECTIVITY_CHANGE".equals(action)) {
            android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean connected = false;
            if (cm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.net.Network net = cm.getActiveNetwork();
                    android.net.NetworkCapabilities cap =
                        net != null ? cm.getNetworkCapabilities(net) : null;
                    connected = cap != null && (
                        cap.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                        cap.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        cap.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET));
                } else {
                    @SuppressWarnings("deprecation")
                    android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                    connected = ni != null && ni.isConnected();
                }
            }
            if (connected) startService(context);
        }
    }

    private void startService(Context context) {
        Intent si = new Intent(context, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(si);
        } else {
            context.startService(si);
        }
    }
}
