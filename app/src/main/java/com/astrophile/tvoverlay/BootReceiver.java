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
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // Auto start setelah boot atau setelah update APK
            SharedPreferences prefs = context.getSharedPreferences(
                "astro_tv_prefs", Context.MODE_PRIVATE
            );
            String apiKey = prefs.getString("apiKey", "");
            if (!apiKey.isEmpty()) {
                Intent serviceIntent = new Intent(context, OverlayService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
        }
    }
}
