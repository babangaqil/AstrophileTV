package com.astrophile.tvoverlay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            startService(context);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            try { context.stopService(new Intent(context, OverlayService.class)); }
            catch (Exception ignored) {}
            new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> startService(context), 1500);
        }
    }

    private void startService(Context context) {
        Intent si = new Intent(context, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(si);
        else
            context.startService(si);
    }
}
