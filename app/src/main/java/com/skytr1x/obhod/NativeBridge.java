package com.skytr1x.obhod;

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
    }

    public static native long create(String configPath, Protector protector, Listener listener);
    public static native void start(long handle);
    public static native void stop(long handle);
    public static native void destroy(long handle);
    public static native void setTunFd(long handle, int fd);
    public static native void setStrategyProfile(long handle, String profileJson);
    public static native void setNetworkKey(long handle, String networkKey);
    public static native String getStatsJson(long handle);
    public static native String probeNetwork(long handle);
    public static native String probeHost(long handle, String host, int port);
    public static native boolean startTunnel(int tunFd, String configPath);
    public static native void stopTunnel();
    public static native boolean isTunnelRunning();

    private NativeBridge() {}
}
