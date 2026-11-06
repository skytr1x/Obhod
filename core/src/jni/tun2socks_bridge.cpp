#include <jni.h>
#include <android/log.h>
#include <thread>
#include <atomic>
#include <string>
#include <unistd.h>

#define LOG_TAG "Tun2SocksBridge"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifdef HEV_SOCKS5_TUNNEL_AVAILABLE
extern "C" {
#include "hev-socks5-tunnel.h"
}
#endif

static std::atomic<bool> g_tunnelRunning{false};
static std::thread g_tunnelThread;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_skyunblock_NativeBridge_startTunnel(JNIEnv* env, jclass clazz, jint tunFd, jstring configPath) {
    if (g_tunnelRunning.load()) {
        LOGD("startTunnel: already running");
        return JNI_FALSE;
    }

    const char* path = env->GetStringUTFChars(configPath, nullptr);
    if (!path) {
        LOGE("startTunnel: null configPath");
        return JNI_FALSE;
    }
    std::string cfgPath(path);
    env->ReleaseStringUTFChars(configPath, path);

    int fd = (int)tunFd;
    LOGD("startTunnel: tunFd=%d, config=%s", fd, cfgPath.c_str());

#ifdef HEV_SOCKS5_TUNNEL_AVAILABLE
    LOGD("Preparing to start hev_socks5_tunnel_main_from_file...");
    g_tunnelRunning.store(true);
    g_tunnelThread = std::thread([fd, cfgPath]() {
        LOGD("Tunnel thread started, calling main_from_file");
        int ret = hev_socks5_tunnel_main_from_file(cfgPath.c_str(), fd);
        LOGD("Tunnel main returned: %d", ret);
        if (ret < 0) {
            LOGE("Tunnel main failed with error: %d", ret);
        }
        g_tunnelRunning.store(false);
        LOGD("Tunnel thread exited");
    });
    g_tunnelThread.detach();
    LOGD("startTunnel: thread dispatched successfully");
    return JNI_TRUE;
#else

    LOGE("startTunnel: hev-socks5-tunnel not compiled in (stub mode). "
         "Run: git submodule add https://github.com/heiher/hev-socks5-tunnel third_party/hev-socks5-tunnel && "
         "git submodule update --init --recursive");
    g_tunnelRunning.store(true); // pretend running for smoke test
    return JNI_TRUE;
#endif
}

JNIEXPORT void JNICALL
Java_com_skyunblock_NativeBridge_stopTunnel(JNIEnv* env, jclass clazz) {
    LOGD("stopTunnel called");
    if (!g_tunnelRunning.load()) {
        LOGD("stopTunnel: not running");
        return;
    }
#ifdef HEV_SOCKS5_TUNNEL_AVAILABLE
    hev_socks5_tunnel_quit();
#endif
    g_tunnelRunning.store(false);
}

JNIEXPORT jboolean JNICALL
Java_com_skyunblock_NativeBridge_isTunnelRunning(JNIEnv* env, jclass clazz) {
    return g_tunnelRunning.load() ? JNI_TRUE : JNI_FALSE;
}

}
