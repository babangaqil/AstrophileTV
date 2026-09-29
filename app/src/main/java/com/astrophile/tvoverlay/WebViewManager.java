package com.astrophile.tvoverlay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * WebViewManager — Zero WebView accumulation guarantee.
 *
 * GUARANTEE:
 * - destroyWebView() SELALU dipanggil sebelum membuat WebView baru
 * - Full hardening sequence: stop → blank → clearHistory → clearCache
 *   → removeAllViews → onPause → destroy
 * - Semua WebView disimpan ke named fields — tidak ada anonymous reference
 * - addView / removeView simetris — cegah WindowLeaked & BadTokenException
 */
public class WebViewManager {

    private static final String TAG = "AstroWebView";

    private final Context       ctx;
    private final WindowManager wm;
    private final Handler       mainHandler;

    // ── Named WebView fields — WAJIB disimpan ────────────────
    private WebView timeOverlayWv  = null;
    private WebView expiredWv      = null;
    private WebView bayarWv        = null;

    // Track apakah masing-masing sedang attached ke WindowManager
    private boolean timeOverlayAttached = false;
    private boolean expiredAttached     = false;
    private boolean bayarAttached       = false;

    // Expired overlay: preload state (WebView disiapkan sebelum waktu habis)
    private boolean  expiredReady    = false;   // halaman selesai dimuat
    private Runnable expiredPending  = null;    // aksi "tampilkan" yang menunggu halaman siap
    private Runnable expiredInjector = null;    // isi data sesi ke halaman

    public WebViewManager(Context ctx, WindowManager wm) {
        this.ctx         = ctx;
        this.wm          = wm;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    // ─────────────────────────────────────────────────────────
    // TIME OVERLAY WebView
    // ─────────────────────────────────────────────────────────

    public WebView getOrCreateTimeOverlay(String htmlPath, Runnable onReady) {
        // Destroy existing first — zero accumulation
        destroyTimeOverlay();

        timeOverlayWv = buildHardenedWebView();
        timeOverlayWv.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (onReady != null) mainHandler.post(onReady);
            }
        });
        timeOverlayWv.loadUrl(htmlPath);
        Log.d(TAG, "createTimeOverlay OK");
        return timeOverlayWv;
    }

    public void attachTimeOverlay(WindowManager.LayoutParams params) {
        if (timeOverlayWv == null) { Log.e(TAG, "attachTimeOverlay — wv null"); return; }
        if (timeOverlayAttached) {
            Log.w(TAG, "attachTimeOverlay — already attached, skip");
            return;
        }
        try {
            wm.addView(timeOverlayWv, params);
            timeOverlayAttached = true;
            Log.d(TAG, "attachTimeOverlay OK");
        } catch (Exception e) {
            Log.e(TAG, "attachTimeOverlay failed: " + e.getMessage(), e);
        }
    }

    public void detachTimeOverlay() {
        if (timeOverlayWv != null && timeOverlayAttached) {
            try { wm.removeView(timeOverlayWv); }
            catch (Exception e) { Log.e(TAG, "detachTimeOverlay: " + e.getMessage()); }
            timeOverlayAttached = false;
            Log.d(TAG, "detachTimeOverlay OK");
        }
    }

    public void destroyTimeOverlay() {
        detachTimeOverlay();
        fullyDestroyWebView(timeOverlayWv, "timeOverlay");
        timeOverlayWv = null;
    }

    public WebView getTimeOverlay() { return timeOverlayWv; }
    public boolean isTimeOverlayAttached() { return timeOverlayAttached; }

    // ─────────────────────────────────────────────────────────
    // EXPIRED WebView
    // ─────────────────────────────────────────────────────────

    public WebView getOrCreateExpiredOverlay(String htmlPath, Runnable onReady) {
        if (expiredWv != null && expiredAttached) {
            // Reuse existing — reset JS state + inject ulang data sesi baru
            Log.d(TAG, "reuseExpiredOverlay — reinject data");
            expiredWv.evaluateJavascript("if(window._sleepStarted !== undefined) window._sleepStarted = false;", null);
            // Inject data sesi baru via callback
            if (onReady != null) mainHandler.post(onReady);
            return expiredWv;
        }

        destroyExpiredOverlay();

        expiredWv = buildHardenedWebView();
        expiredWv.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (onReady != null) mainHandler.post(onReady);
            }
        });
        expiredWv.loadUrl(htmlPath);
        Log.d(TAG, "createExpiredOverlay OK");
        return expiredWv;
    }

    public void attachExpiredOverlay(WindowManager.LayoutParams params) {
        if (expiredWv == null) { Log.e(TAG, "attachExpiredOverlay — wv null"); return; }
        if (expiredAttached) { Log.w(TAG, "attachExpiredOverlay — already attached"); return; }
        try {
            wm.addView(expiredWv, params);
            expiredAttached = true;
            Log.d(TAG, "attachExpiredOverlay OK");
        } catch (Exception e) {
            Log.e(TAG, "attachExpiredOverlay failed: " + e.getMessage(), e);
        }
    }

    public void detachExpiredOverlay() {
        if (expiredWv != null && expiredAttached) {
            try { wm.removeView(expiredWv); }
            catch (Exception e) { Log.e(TAG, "detachExpiredOverlay: " + e.getMessage()); }
            expiredAttached = false;
        }
    }

    public void destroyExpiredOverlay() {
        detachExpiredOverlay();
        fullyDestroyWebView(expiredWv, "expiredOverlay");
        expiredWv       = null;
        expiredReady    = false;
        expiredPending  = null;
        expiredInjector = null;
    }

    public WebView getExpiredOverlay() { return expiredWv; }
    public boolean isExpiredAttached() { return expiredAttached; }

    /**
     * Siapkan WebView expired lebih awal (belum ditampilkan / belum di-attach).
     * Dipanggil beberapa saat sebelum waktu habis, supaya saat waktu habis
     * overlay tinggal ditampilkan tanpa membuat WebView + load HTML dulu
     * (penyebab lag di TV box spek rendah). Aman dipanggil berulang.
     */
    public void preloadExpiredOverlay(String htmlPath) {
        if (expiredWv != null) return;
        expiredReady   = false;
        expiredPending = null;

        expiredWv = buildExpiredWebView();
        expiredWv.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                expiredReady = true;
                Runnable pending = expiredPending;
                expiredPending = null;
                if (pending != null) {
                    mainHandler.post(pending);
                } else if (expiredAttached && expiredInjector != null) {
                    mainHandler.post(expiredInjector);
                }
            }
        });
        expiredWv.loadUrl(htmlPath);
        Log.d(TAG, "preloadExpiredOverlay OK");
    }

    /**
     * Tampilkan overlay expired. Kalau sudah dipreload -> langsung tampil.
     * Data sesi diisi SEBELUM di-attach, jadi tidak ada kedipan teks default.
     * Kalau halaman belum siap, ditunggu (fallback 1.5 detik).
     */
    public void showExpiredOverlay(String htmlPath,
                                   WindowManager.LayoutParams params,
                                   Runnable injector) {
        preloadExpiredOverlay(htmlPath); // no-op kalau sudah ada
        expiredInjector = injector;

        if (expiredAttached) {
            expiredWv.setVisibility(android.view.View.VISIBLE);
            if (injector != null) injector.run();
            return;
        }

        final Runnable attach = () -> {
            if (expiredWv == null || expiredAttached) return;
            if (expiredInjector != null) expiredInjector.run();
            attachExpiredOverlay(params);
        };

        if (expiredReady) {
            attach.run();
        } else {
            expiredPending = attach;
            mainHandler.postDelayed(() -> {
                if (expiredPending == attach) {
                    expiredPending = null;
                    attach.run();
                }
            }, 1500);
        }
    }

    // ─────────────────────────────────────────────────────────
    // BAYAR WebView
    // ─────────────────────────────────────────────────────────

    public WebView getOrCreateBayarOverlay(String htmlPath, Runnable onReady) {
        destroyBayarOverlay();

        bayarWv = buildHardenedWebView();
        bayarWv.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (onReady != null) mainHandler.post(onReady);
            }
        });
        bayarWv.loadUrl(htmlPath);
        Log.d(TAG, "createBayarOverlay OK");
        return bayarWv;
    }

    public void attachBayarOverlay(WindowManager.LayoutParams params) {
        if (bayarWv == null) { Log.e(TAG, "attachBayarOverlay — wv null"); return; }
        if (bayarAttached) { Log.w(TAG, "attachBayarOverlay — already attached"); return; }
        try {
            wm.addView(bayarWv, params);
            bayarAttached = true;
            Log.d(TAG, "attachBayarOverlay OK");
        } catch (Exception e) {
            Log.e(TAG, "attachBayarOverlay failed: " + e.getMessage(), e);
        }
    }

    public void detachBayarOverlay() {
        if (bayarWv != null && bayarAttached) {
            try { wm.removeView(bayarWv); }
            catch (Exception e) { Log.e(TAG, "detachBayarOverlay: " + e.getMessage()); }
            bayarAttached = false;
        }
    }

    public void destroyBayarOverlay() {
        detachBayarOverlay();
        fullyDestroyWebView(bayarWv, "bayarOverlay");
        bayarWv = null;
    }

    public WebView getBayarOverlay() { return bayarWv; }

    // ─────────────────────────────────────────────────────────
    // Destroy ALL — wajib dipanggil saat session reset & onDestroy
    // ─────────────────────────────────────────────────────────

    public void destroyAll() {
        Log.d(TAG, "destroyAll() — destroying all WebViews");
        destroyTimeOverlay();
        destroyExpiredOverlay();
        destroyBayarOverlay();
    }

    // ─────────────────────────────────────────────────────────
    // Core: full hardening destroy sequence (per prompt spesifikasi)
    // ─────────────────────────────────────────────────────────

    private void fullyDestroyWebView(WebView wv, String name) {
        if (wv == null) return;
        try {
            wv.stopLoading();
            wv.loadUrl("about:blank");
            wv.clearHistory();
            wv.clearCache(true);
            wv.removeAllViews();
            wv.onPause();
            wv.destroy();
            Log.d(TAG, "fullyDestroyWebView [" + name + "] OK");
        } catch (Exception e) {
            Log.e(TAG, "fullyDestroyWebView [" + name + "] error: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────
    // Build hardened WebView
    // ─────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private WebView buildHardenedWebView() {
        WebView wv = new WebView(ctx);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE); // No cache — cegah stale data 24 jam
        s.setMediaPlaybackRequiresUserGesture(false);
        // Hardware acceleration
        wv.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
        // Cegah layar putih saat loading — background transparan dulu
        wv.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        return wv;
    }

    /**
     * WebView khusus overlay expired: 100% lokal.
     * - blockNetworkLoads: tidak ada request internet sama sekali
     * - background hitam solid (bukan transparan) -> tidak ada blending
     *   dengan layar di belakangnya, lebih ringan di GPU TV box
     */
    private WebView buildExpiredWebView() {
        WebView wv = buildHardenedWebView();
        WebSettings s = wv.getSettings();
        s.setBlockNetworkLoads(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        wv.setBackgroundColor(android.graphics.Color.BLACK);
        return wv;
    }

    // ─────────────────────────────────────────────────────────
    // JS injection helpers
    // ─────────────────────────────────────────────────────────

    public void evalOnTimeOverlay(String js) {
        if (timeOverlayWv == null) return;
        mainHandler.post(() -> timeOverlayWv.evaluateJavascript(js, null));
    }

    public void evalOnExpiredOverlay(String js) {
        if (expiredWv == null) return;
        mainHandler.post(() -> expiredWv.evaluateJavascript(js, null));
    }

    public void evalOnBayarOverlay(String js) {
        if (bayarWv == null) return;
        mainHandler.post(() -> bayarWv.evaluateJavascript(js, null));
    }

    public void setTimeOverlayVisible(boolean visible) {
        if (timeOverlayWv == null) return;
        mainHandler.post(() -> timeOverlayWv.setVisibility(
            visible ? android.view.View.VISIBLE : android.view.View.GONE));
    }
}
