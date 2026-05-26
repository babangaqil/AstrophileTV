package com.astrophile.tvoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

/**
 * FirebaseManager — Zero duplicate listener guarantee.
 *
 * GUARANTEE:
 * - Setiap listener disimpan ke named field
 * - removeListener() SELALU dipanggil sebelum addListener()
 * - destroyAll() membersihkan SEMUA listener sekaligus
 * - Reconnect-safe: re-attach otomatis tanpa akumulasi
 */
public class FirebaseManager {

    private static final String TAG   = "AstroFirebase";
    private static final String PREFS = "astro_tv_prefs";

    // ── Firebase instance ─────────────────────────────────────
    private FirebaseDatabase db;
    private final Context    ctx;

    // ── Refs + Listeners — semua disimpan agar bisa di-remove ─
    private DatabaseReference sessionRef;
    private ValueEventListener sessionListener;

    private DatabaseReference connectedRef;
    private ValueEventListener connectedListener;

    private DatabaseReference storeNameRef;
    private ValueEventListener storeNameListener;

    private DatabaseReference tvControlRef;
    private ValueEventListener tvControlListener;

    private DatabaseReference bayarStatusRef;
    private ValueEventListener bayarStatusListener;

    private DatabaseReference licenseRef;
    private ValueEventListener licenseListener;

    // ── Callback interfaces ───────────────────────────────────
    public interface SessionDataCallback {
        void onData(DataSnapshot snap);
        void onCancelled(String error);
    }

    public interface ConnectionCallback {
        void onConnected();
        void onDisconnected();
    }

    public interface StringCallback {
        void onValue(String value);
    }

    public interface CommandCallback {
        void onCommand(String cmd);
    }

    public interface BayarCallback {
        void onBayarStatus(String status);
    }

    // ── Constructor ───────────────────────────────────────────
    public FirebaseManager(Context ctx) {
        this.ctx = ctx;
    }

    // ── Init Firebase instance ────────────────────────────────
    public boolean init() {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String apiKey   = prefs.getString("apiKey",    "");
        String dbUrl    = prefs.getString("dbUrl",     "");
        String projId   = prefs.getString("projectId", "");
        String appId    = prefs.getString("appId",     "");

        if (apiKey.isEmpty() || dbUrl.isEmpty()) {
            Log.e(TAG, "init() FAILED — apiKey or dbUrl empty");
            return false;
        }

        try {
            FirebaseApp app;
            try {
                app = FirebaseApp.getInstance("_tv_overlay");
            } catch (Exception e) {
                String finalAppId = appId.isEmpty()
                    ? "1:000000000000:android:0000000000000000000000"
                    : appId;
                app = FirebaseApp.initializeApp(ctx,
                    new FirebaseOptions.Builder()
                        .setApiKey(apiKey)
                        .setDatabaseUrl(dbUrl)
                        .setProjectId(projId)
                        .setApplicationId(finalAppId)
                        .build(),
                    "_tv_overlay");
            }
            db = FirebaseDatabase.getInstance(app);
            db.setPersistenceEnabled(false); // Disable persistence — cegah stale cache 24 jam
            Log.d(TAG, "init() OK — db=" + dbUrl);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "init() exception: " + e.getMessage(), e);
            return false;
        }
    }

    public boolean isReady() { return db != null; }

    // ── Session listener ──────────────────────────────────────
    public void listenSession(int tvNum, SessionDataCallback cb) {
        if (db == null) { Log.e(TAG, "listenSession — db null"); return; }

        // WAJIB: remove listener lama sebelum attach baru
        removeSessionListener();

        sessionRef = db.getReference("settings/activeSessions/" + tvNum);
        sessionListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                Log.d(TAG, "sessionListener.onDataChange tv=" + tvNum);
                cb.onData(snap);
            }
            @Override public void onCancelled(DatabaseError e) {
                Log.e(TAG, "sessionListener.onCancelled: " + e.getMessage());
                cb.onCancelled(e.getMessage());
            }
        };
        sessionRef.addValueEventListener(sessionListener);
        Log.d(TAG, "listenSession attached tv=" + tvNum);
    }

    public void removeSessionListener() {
        if (sessionRef != null && sessionListener != null) {
            sessionRef.removeEventListener(sessionListener);
            Log.d(TAG, "removeSessionListener OK");
        }
        sessionListener = null;
    }

    // ── Connection state listener ─────────────────────────────
    public void listenConnection(ConnectionCallback cb) {
        if (db == null) return;

        // Remove old first
        removeConnectionListener();

        connectedRef = db.getReference(".info/connected");
        connectedListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                Boolean connected = snap.getValue(Boolean.class);
                if (Boolean.TRUE.equals(connected)) {
                    Log.d(TAG, "Firebase CONNECTED");
                    cb.onConnected();
                } else {
                    Log.d(TAG, "Firebase DISCONNECTED");
                    cb.onDisconnected();
                }
            }
            @Override public void onCancelled(DatabaseError e) {
                Log.e(TAG, "connectedListener cancelled: " + e.getMessage());
            }
        };
        connectedRef.addValueEventListener(connectedListener);
    }

    public void removeConnectionListener() {
        if (connectedRef != null && connectedListener != null) {
            connectedRef.removeEventListener(connectedListener);
        }
        connectedListener = null;
    }

    // ── Store name listener ───────────────────────────────────
    public void listenStoreName(StringCallback cb) {
        if (db == null) return;

        removeStoreNameListener();

        storeNameRef = db.getReference("settings/namaToko");
        storeNameListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                String name = snap.getValue(String.class);
                if (name != null && !name.isEmpty()) cb.onValue(name);
            }
            @Override public void onCancelled(DatabaseError e) {
                Log.e(TAG, "storeNameListener cancelled: " + e.getMessage());
            }
        };
        storeNameRef.addValueEventListener(storeNameListener);
    }

    public void removeStoreNameListener() {
        if (storeNameRef != null && storeNameListener != null) {
            storeNameRef.removeEventListener(storeNameListener);
        }
        storeNameListener = null;
    }

    // ── TV control command listener ───────────────────────────
    public void listenTvControl(int tvNum, CommandCallback cb) {
        if (db == null) return;

        removeTvControlListener();

        tvControlRef = db.getReference("settings/tvControl/" + tvNum);
        tvControlListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                String cmd = snap.child("cmd").getValue(String.class);
                if (cmd != null && !cmd.isEmpty()) cb.onCommand(cmd);
            }
            @Override public void onCancelled(DatabaseError e) {
                Log.e(TAG, "tvControlListener cancelled: " + e.getMessage());
            }
        };
        tvControlRef.addValueEventListener(tvControlListener);
    }

    public void removeTvControlListener() {
        if (tvControlRef != null && tvControlListener != null) {
            tvControlRef.removeEventListener(tvControlListener);
        }
        tvControlListener = null;
    }

    // ── Bayar status listener ─────────────────────────────────
    public void listenBayarStatus(int tvNum, BayarCallback cb) {
        if (db == null) return;

        removeBayarStatusListener();

        bayarStatusRef = db.getReference("settings/activeSessions/" + tvNum + "/bayarStatus");
        bayarStatusListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                String status = snap.getValue(String.class);
                cb.onBayarStatus(status != null ? status : "");
            }
            @Override public void onCancelled(DatabaseError e) {
                Log.e(TAG, "bayarStatusListener cancelled: " + e.getMessage());
            }
        };
        bayarStatusRef.addValueEventListener(bayarStatusListener);
    }

    public void removeBayarStatusListener() {
        if (bayarStatusRef != null && bayarStatusListener != null) {
            bayarStatusRef.removeEventListener(bayarStatusListener);
        }
        bayarStatusListener = null;
    }

    // ── License revoke listener ───────────────────────────────
    public void listenLicenseRevoke(String keyHash, String deviceId, Runnable onRevoked) {
        if (db == null) return;

        removeLicenseListener();

        licenseRef = db.getReference("tvLicenseKeys/" + keyHash);
        licenseListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                Boolean revoked = snap.child("revoked").getValue(Boolean.class);
                Long expiredAt  = snap.child("expiredAt").getValue(Long.class);
                if (Boolean.TRUE.equals(revoked)) { onRevoked.run(); return; }
                if (expiredAt != null && System.currentTimeMillis() > expiredAt) { onRevoked.run(); return; }
                if (!deviceId.isEmpty()) {
                    DataSnapshot dev = snap.child("devices").child(deviceId);
                    if (dev.exists() && Boolean.TRUE.equals(dev.child("revoked").getValue(Boolean.class))) {
                        onRevoked.run();
                    }
                }
            }
            @Override public void onCancelled(DatabaseError e) {
                Log.e(TAG, "licenseListener cancelled: " + e.getMessage());
            }
        };
        licenseRef.addValueEventListener(licenseListener);
    }

    public void removeLicenseListener() {
        if (licenseRef != null && licenseListener != null) {
            licenseRef.removeEventListener(licenseListener);
        }
        licenseListener = null;
    }

    // ── Write helpers ─────────────────────────────────────────
    public void setLastSeen(int tvNum, long timestamp) {
        if (db == null) return;
        db.getReference("settings/tvStatus/" + tvNum + "/lastSeen")
            .setValue(timestamp, (err, ref) -> {
                if (err != null) Log.e(TAG, "setLastSeen error: " + err.getMessage());
            });
    }

    public void setTvOnline(int tvNum, boolean online) {
        if (db == null) return;
        db.getReference("settings/tvStatus/" + tvNum + "/online")
            .setValue(online, (err, ref) -> {
                if (err != null) Log.e(TAG, "setTvOnline error: " + err.getMessage());
            });
    }

    public void setActiveSession(int tvNum, boolean active) {
        if (db == null) return;
        db.getReference("settings/activeSessions/" + tvNum + "/active")
            .setValue(active, (err, ref) -> {
                if (err != null) Log.e(TAG, "setActiveSession error: " + err.getMessage());
            });
    }

    public void clearTvControlCmd(int tvNum) {
        if (db == null) return;
        db.getReference("settings/tvControl/" + tvNum + "/cmd")
            .setValue("none", (err, ref) -> {
                if (err != null) Log.e(TAG, "clearTvControlCmd error: " + err.getMessage());
            });
    }

    // ── Destroy ALL listeners — wajib dipanggil di onDestroy ─
    public void destroyAll() {
        Log.d(TAG, "destroyAll() — removing all Firebase listeners");
        removeSessionListener();
        removeConnectionListener();
        removeStoreNameListener();
        removeTvControlListener();
        removeBayarStatusListener();
        removeLicenseListener();
        db = null;
    }
}
