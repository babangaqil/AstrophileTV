package com.astrophile.tvoverlay;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * TimerManager — Zero duplicate timer guarantee.
 *
 * GUARANTEE:
 * - Hanya SATU ticker aktif setiap saat
 * - stopTicker() SELALU dipanggil sebelum startTicker()
 * - Heartbeat Runnable disimpan ke named field, selalu di-removeCallbacks
 * - Watchdog mendeteksi ticker yang freeze dan restart otomatis
 */
public class TimerManager {

    private static final String TAG = "AstroTimer";

    private final Handler mainHandler;

    // ── Ticker ────────────────────────────────────────────────
    private java.util.Timer  tickTimer    = null;
    private volatile boolean tickerActive = false;
    private volatile long    lastTickMs   = 0L;
    private static final long TICK_INTERVAL_MS  = 1000L;
    private static final long WATCHDOG_THRESHOLD = 5000L; // freeze jika tidak tick 5 detik

    // ── Heartbeat ─────────────────────────────────────────────
    private Runnable heartbeatRunnable = null;
    private static final long HEARTBEAT_INTERVAL_MS = 30_000L;

    // ── Watchdog ──────────────────────────────────────────────
    private Runnable watchdogRunnable  = null;
    private static final long WATCHDOG_INTERVAL_MS  = 10_000L;

    // ── Callbacks ─────────────────────────────────────────────
    public interface TickCallback      { void onTick(); }
    public interface HeartbeatCallback { void onHeartbeat(); }

    private TickCallback      tickCallback;
    private HeartbeatCallback heartbeatCallback;
    private Runnable          onWatchdogRestart;

    public TimerManager(Handler mainHandler) {
        this.mainHandler = mainHandler;
    }

    // ── Ticker ────────────────────────────────────────────────

    public void startTicker(TickCallback cb) {
        // WAJIB stop dulu — zero duplicate
        stopTicker();

        tickCallback  = cb;
        tickerActive  = true;
        lastTickMs    = System.currentTimeMillis();

        tickTimer = new java.util.Timer("AstroTicker", true);
        tickTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override public void run() {
                if (!tickerActive) return;
                lastTickMs = System.currentTimeMillis();
                mainHandler.post(() -> {
                    try {
                        if (tickCallback != null) tickCallback.onTick();
                    } catch (Exception e) {
                        Log.e(TAG, "onTick exception: " + e.getMessage(), e);
                    }
                });
            }
        }, 0L, TICK_INTERVAL_MS);

        Log.d(TAG, "startTicker OK");
    }

    public void stopTicker() {
        tickerActive = false;
        if (tickTimer != null) {
            try { tickTimer.cancel(); tickTimer.purge(); }
            catch (Exception e) { Log.e(TAG, "stopTicker: " + e.getMessage()); }
            tickTimer = null;
        }
        tickCallback = null;
        Log.d(TAG, "stopTicker OK");
    }

    public boolean isTickerRunning() { return tickerActive && tickTimer != null; }

    // ── Heartbeat ─────────────────────────────────────────────

    public void startHeartbeat(HeartbeatCallback cb) {
        // Remove old runnable first — zero duplicate
        stopHeartbeat();

        heartbeatCallback = cb;
        heartbeatRunnable = new Runnable() {
            @Override public void run() {
                try {
                    if (heartbeatCallback != null) heartbeatCallback.onHeartbeat();
                } catch (Exception e) {
                    Log.e(TAG, "heartbeat exception: " + e.getMessage(), e);
                }
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
            }
        };
        mainHandler.post(heartbeatRunnable);
        Log.d(TAG, "startHeartbeat OK");
    }

    public void stopHeartbeat() {
        if (heartbeatRunnable != null) {
            mainHandler.removeCallbacks(heartbeatRunnable);
            heartbeatRunnable = null;
        }
        heartbeatCallback = null;
        Log.d(TAG, "stopHeartbeat OK");
    }

    // ── Watchdog — self-healing: restart ticker jika freeze ──

    public void startWatchdog(Runnable onRestart) {
        stopWatchdog();
        onWatchdogRestart = onRestart;

        watchdogRunnable = new Runnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                if (tickerActive && tickTimer != null
                        && (now - lastTickMs) > WATCHDOG_THRESHOLD) {
                    Log.e(TAG, "WATCHDOG: ticker frozen " + (now - lastTickMs) + "ms — restarting");
                    if (onWatchdogRestart != null) {
                        mainHandler.post(onWatchdogRestart);
                    }
                }
                mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);
        Log.d(TAG, "startWatchdog OK");
    }

    public void stopWatchdog() {
        if (watchdogRunnable != null) {
            mainHandler.removeCallbacks(watchdogRunnable);
            watchdogRunnable = null;
        }
    }

    // ── Delayed post helper ───────────────────────────────────

    public void postDelayed(Runnable r, long delayMs) {
        mainHandler.postDelayed(r, delayMs);
    }

    public void post(Runnable r) {
        mainHandler.post(r);
    }

    // ── Destroy ALL — wajib dipanggil di onDestroy ────────────

    public void destroyAll() {
        Log.d(TAG, "destroyAll()");
        stopTicker();
        stopHeartbeat();
        stopWatchdog();
    }
}
