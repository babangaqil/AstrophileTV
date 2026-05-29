package com.astrophile.tvoverlay;

/**
 * FirebaseManager — stub kosong.
 * Semua sesi dikelola via LAN (LocalHttpServer).
 * File ini dipertahankan agar tidak ada perubahan di build.gradle / import lain.
 */
public class FirebaseManager {

    public FirebaseManager(android.content.Context ctx) {}

    public boolean init()    { return true; }
    public boolean isReady() { return true; }

    public void destroyAll() {}

    // Stub listener methods — tidak melakukan apa-apa
    public interface SessionDataCallback {
        void onData(Object snap);
        void onCancelled(String error);
    }
    public interface ConnectionCallback {
        void onConnected();
        void onDisconnected();
    }
    public interface StringCallback  { void onValue(String value); }
    public interface CommandCallback { void onCommand(String cmd); }
    public interface BayarCallback   { void onBayarStatus(String status); }
    public interface SnapCommandCallback {
        void onCommand(String cmd, Object snap);
    }

    public void listenSession(int tvNum, SessionDataCallback cb)        {}
    public void removeSessionListener()                                  {}
    public void listenConnection(ConnectionCallback cb)                  {}
    public void removeConnectionListener()                               {}
    public void listenStoreName(StringCallback cb)                       {}
    public void removeStoreNameListener()                                {}
    public void listenTvControl(int tvNum, SnapCommandCallback cb)      {}
    public void removeTvControlListener()                                {}
    public void listenBayarStatus(int tvNum, BayarCallback cb)          {}
    public void removeBayarStatusListener()                              {}
    public void startServerTimeSync()                                    {}
    public void stopServerTimeSync()                                     {}
    public long getServerNow()  { return System.currentTimeMillis(); }
    public void setLastSeen(int tvNum, long ts)                          {}
    public void setTvOnline(int tvNum, boolean online)                   {}
    public void setActiveSession(int tvNum, boolean active)              {}
    public void clearTvControlCmd(int tvNum)                             {}
    public Object getDb() { return null; }
}
