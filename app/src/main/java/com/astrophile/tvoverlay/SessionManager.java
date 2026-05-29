package com.astrophile.tvoverlay;

import android.util.Log;

/**
 * SessionManager — Single source of truth untuk semua state sesi.
 * Versi offline: tanpa Firebase, waktu pakai System.currentTimeMillis().
 */
public class SessionManager {

    private static final String TAG = "AstroSession";

    // ── Session State ─────────────────────────────────────────
    private volatile boolean active        = false;
    private volatile boolean expired       = false;
    private volatile String  mode          = "";
    private volatile long    startTime     = 0L;
    private volatile long    duration      = 0L;
    private volatile long    pausedAt      = 0L;
    private volatile String  namaPelanggan = "";
    private volatile String  tvName        = "TV 1";
    private volatile int     tvNum         = 1;
    private volatile String  namaToko      = "ASTROPHILE";

    // ── Notify flags ─────────────────────────────────────────
    private volatile boolean toast5Shown = false;
    private volatile boolean toast1Shown = false;

    // ── Listener ─────────────────────────────────────────────
    public interface SessionListener {
        void onSessionStarted();
        void onSessionExpired();
        void onSessionReset();
    }

    private SessionListener listener;
    public void setListener(SessionListener l) { this.listener = l; }

    // ── Full reset ────────────────────────────────────────────
    public synchronized void resetSession() {
        Log.d(TAG, "resetSession()");
        active        = false;
        expired       = false;
        mode          = "";
        startTime     = 0L;
        duration      = 0L;
        pausedAt      = 0L;
        namaPelanggan = "";
        toast5Shown   = false;
        toast1Shown   = false;
        if (listener != null) listener.onSessionReset();
    }

    // ── Apply data (dari LAN/lokal) ───────────────────────────
    public synchronized void applyFromLocal(boolean fbActive, boolean fbExpired, String fbMode,
            long fbStart, long fbDuration, long fbPausedAt, String fbNama) {

        this.active        = fbActive;
        this.expired       = fbExpired;
        this.mode          = fbMode  != null ? fbMode  : "";
        this.startTime     = fbStart;
        this.duration      = fbDuration;
        this.namaPelanggan = fbNama  != null ? fbNama  : "";
        this.pausedAt      = fbPausedAt;

        Log.d(TAG, "applyFromLocal active=" + active + " mode=" + mode
                + " start=" + startTime + " dur=" + duration);

        if (active && startTime > 0 && listener != null) {
            if (fbExpired) {
                listener.onSessionExpired();
            } else if (!this.expired) {
                listener.onSessionStarted();
            }
        }
    }

    // ── Mark expired ──────────────────────────────────────────
    public synchronized void markExpired() {
        if (!expired) {
            expired = true;
            Log.d(TAG, "markExpired()");
            if (listener != null) listener.onSessionExpired();
        }
    }

    // ── Getters ───────────────────────────────────────────────
    public boolean isActive()         { return active; }
    public boolean isExpired()        { return expired; }
    public String  getMode()          { return mode; }
    public long    getStartTime()     { return startTime; }
    public long    getDuration()      { return duration; }
    public long    getPausedAt()      { return pausedAt; }
    public String  getNamaPelanggan() { return namaPelanggan; }
    public String  getTvName()        { return tvName; }
    public int     getTvNum()         { return tvNum; }
    public String  getNamaToko()      { return namaToko; }
    public boolean isToast5Shown()    { return toast5Shown; }
    public boolean isToast1Shown()    { return toast1Shown; }

    // ── Setters ───────────────────────────────────────────────
    public void setTvNum(int n)           { tvNum = n; }
    public void setTvName(String n)       { tvName = n != null ? n : "TV " + tvNum; }
    public void setNamaToko(String n)     { namaToko = n != null && !n.isEmpty() ? n : "ASTROPHILE"; }
    public void setPausedAt(long t)       { pausedAt = t; }
    public void setToast5Shown(boolean v) { toast5Shown = v; }
    public void setToast1Shown(boolean v) { toast1Shown = v; }

    // ── Computed helpers ──────────────────────────────────────
    public long getRemainingSeconds() {
        if (!"countdown".equals(mode) || startTime == 0) return 0L;
        long now     = (pausedAt > 0) ? pausedAt : System.currentTimeMillis();
        long elapsed = (now - startTime) / 1000;
        return Math.max(0L, duration - elapsed);
    }

    public long getElapsedSeconds() {
        if (startTime == 0) return 0L;
        long now = (pausedAt > 0) ? pausedAt : System.currentTimeMillis();
        return (now - startTime) / 1000;
    }

    public boolean isPaused()     { return pausedAt > 0; }
    public boolean isCountdown()  { return "countdown".equals(mode); }
    public boolean isBilling()    { return "billing".equals(mode); }
}
