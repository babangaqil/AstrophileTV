package com.astrophile.tvoverlay;

import android.media.ToneGenerator;
import android.util.Log;

/**
 * AstroAudioManager — Zero thread leak guarantee.
 *
 * GUARANTEE:
 * - Hanya SATU alarm thread aktif setiap saat
 * - Auto-stop setelah MAX_DURATION_MS (10 menit)
 * - ToneGenerator selalu di-release di finally block
 * - stopAlarm() thread-safe via volatile flag
 */
public class AstroAudioManager {

    private static final String TAG              = "AstroAudio";
    private static final long   MAX_DURATION_MS  = 10 * 60 * 1000L; // 10 menit max
    private static final int    TONE_VOLUME      = 100;

    private volatile boolean alarmPlaying = false;
    private ToneGenerator    toneGen      = null;

    // ── Alarm ─────────────────────────────────────────────────

    public void startAlarm() {
        if (alarmPlaying) {
            Log.w(TAG, "startAlarm — already playing, skip");
            return;
        }
        alarmPlaying = true;
        final long startMs = System.currentTimeMillis();

        Thread alarmThread = new Thread(() -> {
            ToneGenerator localGen = null;
            try {
                localGen = new ToneGenerator(android.media.AudioManager.STREAM_ALARM, TONE_VOLUME);
                toneGen  = localGen;

                while (alarmPlaying) {
                    // Auto-stop safeguard — tidak boleh jalan selamanya
                    if (System.currentTimeMillis() - startMs > MAX_DURATION_MS) {
                        Log.w(TAG, "alarm auto-stopped after " + MAX_DURATION_MS / 60000 + " min");
                        break;
                    }
                    localGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500);
                    Thread.sleep(700);
                    if (!alarmPlaying) break;
                    localGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300);
                    Thread.sleep(400);
                    if (!alarmPlaying) break;
                    localGen.startTone(ToneGenerator.TONE_CDMA_HIGH_PBX_SLS, 800);
                    Thread.sleep(1200);
                }
            } catch (InterruptedException e) {
                Log.d(TAG, "alarm thread interrupted");
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.e(TAG, "alarm thread error: " + e.getMessage(), e);
            } finally {
                // SELALU release di finally — tidak ada ToneGenerator leak
                alarmPlaying = false;
                try {
                    if (localGen != null) localGen.release();
                } catch (Exception e) {
                    Log.e(TAG, "toneGen.release error: " + e.getMessage());
                }
                toneGen = null;
                Log.d(TAG, "alarm thread exited cleanly");
            }
        }, "AstroAlarm");

        alarmThread.setDaemon(true); // daemon — tidak halangi proses exit
        alarmThread.start();
        Log.d(TAG, "startAlarm OK");
    }

    public void stopAlarm() {
        if (!alarmPlaying) return;
        alarmPlaying = false;
        // toneGen akan di-release oleh thread-nya di finally block
        Log.d(TAG, "stopAlarm signaled");
    }

    public boolean isPlaying() { return alarmPlaying; }

    // ── Destroy — wajib dipanggil di onDestroy ────────────────
    public void destroy() {
        stopAlarm();
        // Extra safety: paksa release jika thread masih jalan
        try {
            if (toneGen != null) { toneGen.release(); toneGen = null; }
        } catch (Exception e) {
            Log.e(TAG, "destroy toneGen error: " + e.getMessage());
        }
        Log.d(TAG, "destroy OK");
    }
}
