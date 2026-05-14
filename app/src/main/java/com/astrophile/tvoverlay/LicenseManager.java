package com.astrophile.tvoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class LicenseManager {

    private static final String MASTER_API_KEY  = "AIzaSyD8XffAZK8JUOBajCUVyPS-NT9jnwYBats";
    private static final String MASTER_DB_URL   = "https://astrophile-rental-default-rtdb.firebaseio.com";
    private static final String MASTER_PROJECT  = "astrophile-rental";
    private static final String MASTER_APP_ID   = "1:789474619442:android:5f678d3b6ebdc99a1c8c2b";
    private static final String MASTER_APP_NAME = "_tv_license";
    private static final String PREFS           = "astro_tv_prefs";
    private static final String KEY_LICENSE     = "tv_license_key";
    private static final String KEY_STATUS      = "tv_license_status";
    private static final String KEY_STORE_ID    = "tv_store_id";
    private static final String KEY_DEVICE_ID   = "tv_device_id";
    // Firebase config toko — diambil otomatis dari license record
    private static final String KEY_FB_API_KEY  = "apiKey";
    private static final String KEY_FB_DB_URL   = "dbUrl";
    private static final String KEY_FB_PROJ_ID  = "projectId";

    public interface LicenseCallback {
        void onValid(String storeId, String deviceId);
        void onInvalid(String reason);
        void onError(String msg);
    }

    public static String generateDeviceId(Context ctx, int tvNum) {
        String androidId = Settings.Secure.getString(
            ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.isEmpty()) androidId = "UNKNOWN";
        return "TV-" + String.format("%03d", tvNum) + "-" +
            androidId.substring(0, Math.min(6, androidId.length())).toUpperCase();
    }

    public static String getSavedKey(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LICENSE, "");
    }

    public static String getSavedStoreId(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STORE_ID, "");
    }

    public static String getSavedDeviceId(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DEVICE_ID, "");
    }

    public static boolean hasValidLicense(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return "active".equals(p.getString(KEY_STATUS, "")) &&
               !p.getString(KEY_LICENSE, "").isEmpty();
    }

    public static String hashKey(String key) {
        String s = key.replace("-", "").toUpperCase();
        int h = 0;
        for (int i = 0; i < s.length(); i++) h = ((h << 5) - h) + s.charAt(i);
        String hash = Integer.toString(Math.abs(h), 36).toUpperCase();
        while (hash.length() < 8) hash = "0" + hash;
        return hash;
    }

    private static FirebaseApp getMasterApp(Context ctx) {
        try { return FirebaseApp.getInstance(MASTER_APP_NAME); }
        catch (Exception e) {
            return FirebaseApp.initializeApp(ctx, new FirebaseOptions.Builder()
                .setApiKey(MASTER_API_KEY)
                .setDatabaseUrl(MASTER_DB_URL)
                .setProjectId(MASTER_PROJECT)
                .setApplicationId(MASTER_APP_ID)
                .build(), MASTER_APP_NAME);
        }
    }

    public static void verifyAndRegister(Context ctx, String key, int tvNum, String tvName, LicenseCallback cb) {
        if (key == null || key.trim().isEmpty()) { cb.onInvalid("NOT_FOUND"); return; }
        String keyHash  = hashKey(key.replace("-", "").toUpperCase());
        String deviceId = generateDeviceId(ctx, tvNum);
        try {
            DatabaseReference keyRef = FirebaseDatabase.getInstance(getMasterApp(ctx))
                .getReference("tvLicenseKeys/" + keyHash);
            keyRef.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (!snap.exists()) { cb.onInvalid("NOT_FOUND"); return; }
                    Boolean revoked  = snap.child("revoked").getValue(Boolean.class);
                    Long expiredAt   = snap.child("expiredAt").getValue(Long.class);
                    String storeId   = snap.child("storeId").getValue(String.class);
                    if (Boolean.TRUE.equals(revoked)) {
                        saveStatus(ctx, "revoked"); cb.onInvalid("REVOKED"); return;
                    }
                    if (expiredAt != null && System.currentTimeMillis() > expiredAt) {
                        saveStatus(ctx, "expired"); cb.onInvalid("EXPIRED"); return;
                    }
                    // Hapus entry lama jika device ini pernah register dengan nomor TV berbeda
                    // Hardware ID = 6 karakter terakhir dari deviceId (misal: 048ABA)
                    String hwSuffix = deviceId.length() >= 6
                        ? deviceId.substring(deviceId.length() - 6)
                        : deviceId;
                    DataSnapshot allDevices = snap.child("devices");
                    for (DataSnapshot existing : allDevices.getChildren()) {
                        String existingId = existing.getKey();
                        if (existingId != null && !existingId.equals(deviceId)
                            && existingId.endsWith(hwSuffix)) {
                            // Device ID lama dengan hardware yang sama — hapus
                            keyRef.child("devices").child(existingId).removeValue();
                        }
                    }

                    DataSnapshot devSnap = snap.child("devices").child(deviceId);
                    if (devSnap.exists() && Boolean.TRUE.equals(devSnap.child("revoked").getValue(Boolean.class))) {
                        saveStatus(ctx, "device_revoked"); cb.onInvalid("DEVICE_REVOKED"); return;
                    }
                    DatabaseReference devRef = keyRef.child("devices").child(deviceId);
                    devRef.child("tvNum").setValue(tvNum);
                    devRef.child("tvName").setValue(tvName);
                    devRef.child("lastSeen").setValue(System.currentTimeMillis());
                    // Simpan versi APK ke Firebase agar admin bisa lihat
                    try {
                        String appVersion = ctx.getPackageManager()
                            .getPackageInfo(ctx.getPackageName(), 0).versionName;
                        devRef.child("appVersion").setValue(appVersion);
                    } catch (Exception ignored) {}
                    if (!devSnap.exists()) {
                        devRef.child("registeredAt").setValue(System.currentTimeMillis());
                        devRef.child("revoked").setValue(false);
                    }
                    // Ambil Firebase config dari license record → simpan otomatis
                    DataSnapshot fbCfg = snap.child("firebaseConfig");
                    String fbApiKey = fbCfg.child("apiKey").getValue(String.class);
                    String fbDbUrl  = fbCfg.child("databaseURL").getValue(String.class);
                    String fbProjId = fbCfg.child("projectId").getValue(String.class);

                    SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY_LICENSE, key.trim().toUpperCase())
                        .putString(KEY_STATUS, "active")
                        .putString(KEY_STORE_ID, storeId != null ? storeId : keyHash)
                        .putString(KEY_DEVICE_ID, deviceId);

                    // Simpan Firebase config jika tersedia di license
                    if (fbApiKey != null && !fbApiKey.isEmpty()) ed.putString(KEY_FB_API_KEY, fbApiKey);
                    if (fbDbUrl  != null && !fbDbUrl.isEmpty())  ed.putString(KEY_FB_DB_URL,  fbDbUrl);
                    if (fbProjId != null && !fbProjId.isEmpty()) ed.putString(KEY_FB_PROJ_ID, fbProjId);
                    ed.apply();

                    cb.onValid(storeId != null ? storeId : keyHash, deviceId);
                }
                @Override public void onCancelled(DatabaseError e) { cb.onError(e.getMessage()); }
            });
        } catch (Exception e) { cb.onError(e.getMessage()); }
    }

    public static void checkRevoke(Context ctx, LicenseCallback cb) {
        String key      = getSavedKey(ctx);
        String deviceId = getSavedDeviceId(ctx);
        if (key.isEmpty()) return;
        String keyHash  = hashKey(key.replace("-", "").toUpperCase());
        try {
            FirebaseDatabase.getInstance(getMasterApp(ctx))
                .getReference("tvLicenseKeys/" + keyHash)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        if (!snap.exists()) { cb.onInvalid("NOT_FOUND"); return; }
                        Boolean revoked = snap.child("revoked").getValue(Boolean.class);
                        Long expiredAt  = snap.child("expiredAt").getValue(Long.class);
                        if (Boolean.TRUE.equals(revoked)) { saveStatus(ctx,"revoked"); cb.onInvalid("REVOKED"); return; }
                        if (expiredAt != null && System.currentTimeMillis() > expiredAt) { saveStatus(ctx,"expired"); cb.onInvalid("EXPIRED"); return; }
                        if (!deviceId.isEmpty()) {
                            DataSnapshot dev = snap.child("devices").child(deviceId);
                            if (dev.exists() && Boolean.TRUE.equals(dev.child("revoked").getValue(Boolean.class))) {
                                saveStatus(ctx,"device_revoked"); cb.onInvalid("DEVICE_REVOKED"); return;
                            }
                            if (dev.exists()) dev.getRef().child("lastSeen").setValue(System.currentTimeMillis());
                        }
                        cb.onValid(snap.child("storeId").getValue(String.class), deviceId);
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
        } catch (Exception e) {}
    }

    private static void saveStatus(Context ctx, String status) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STATUS, status).apply();
    }

    public static void clearLicense(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_LICENSE).remove(KEY_STATUS)
            .remove(KEY_STORE_ID).remove(KEY_DEVICE_ID).apply();
    }
}
