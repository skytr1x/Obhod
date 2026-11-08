package com.skytr1x.obhod.bridge;

import android.util.Log;

public final class NativeBridge {
    private static final String TAG = "NativeBridge";
    private static volatile boolean loaded;

    public static void load() {
        if (loaded) return;
        System.loadLibrary("skycore");
        loaded = true;
        Log.i(TAG, "libskycore loaded");
    }

    public static boolean isLoaded() { return loaded; }

    public interface Protector { boolean protect(int fd); }
    public interface Listener {
        void onState(int state, String message);
        void onEvent(String message);
        byte[] resolveDns(byte[] query);
    }

    public static native long create(String configPath, Protector protector, Listener listener);
    public static native void start(long handle);
    public static native void stop(long handle);
    public static native void destroy(long handle);
    public static native void setNetworkKey(long handle, String networkKey);
    public static native void setNetworkStrategyOrder(long handle, String tcpOrder);
    public static native String getStatsJson(long handle);
    public static native boolean startTunnel(int tunFd, String configPath);
    public static native void stopTunnel();

    private NativeBridge() {}
}
