#include <jni.h>
#include <string>
#include <memory>
#include <limits>
#include <vector>
#include <cstdint>
#include "skycore.h"
#include <mutex>
#include <android/log.h>

#define LOG_TAG "SkyCore"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* g_jvm = nullptr;

namespace {

thread_local bool t_attachedByUs = false;

struct ThreadDetachGuard {
    ~ThreadDetachGuard() {
        if (!t_attachedByUs || !g_jvm) return;
        JNIEnv* env = nullptr;
        if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
            g_jvm->DetachCurrentThread();
        }
        t_attachedByUs = false;
    }
};

}

extern "C" {

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGD("JNI_OnLoad: Loading libskycore.so");
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
JNI_OnUnload(JavaVM* vm, void* reserved) {
    LOGD("JNI_OnUnload: Unloading libskycore.so");
    if (g_jvm == vm) g_jvm = nullptr;
}

JNIEnv* getJNIEnv() {
    if (!g_jvm) {
        LOGE("JNI Error: JavaVM (g_jvm) is not initialized!");
        return nullptr;
    }

    JNIEnv* env;
    jint res = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("JNI Error: Failed to attach current thread to JVM");
            return nullptr;
        }
        t_attachedByUs = true;
        static thread_local ThreadDetachGuard guard;
        (void)guard;
    }
    return env;
}

namespace {

struct JniCallbackRefs {
    jobject protector = nullptr;
    jobject listener = nullptr;

    ~JniCallbackRefs() {
        if (!g_jvm) return;
        JNIEnv* env = nullptr;
        if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK ||
            !env) {
            return;
        }
        if (protector) env->DeleteGlobalRef(protector);
        if (listener) env->DeleteGlobalRef(listener);
    }
};

}

JNIEXPORT jlong JNICALL
Java_com_skytr1x_obhod_bridge_NativeBridge_create(JNIEnv* env, jclass clazz, jstring configPath, jobject protector, jobject listener) {
    LOGD("JNI: create() called");
    if (!configPath) {
        LOGE("JNI: configPath is null");
        return 0;
    }

    const char* path = env->GetStringUTFChars(configPath, nullptr);
    std::string cppPath(path);
    env->ReleaseStringUTFChars(configPath, path);

    if (!protector || !listener) {
        LOGE("JNI: protector or listener is null");
        return 0;
    }

    auto refs = std::make_shared<JniCallbackRefs>();
    refs->protector = env->NewGlobalRef(protector);
    refs->listener = env->NewGlobalRef(listener);
    if (!refs->protector || !refs->listener) {
        LOGE("JNI: failed to create global callback references");
        return 0;
    }

    auto protectorCb = [refs](int pid) -> bool {
        JNIEnv* env = getJNIEnv();
        if (!env || !refs->protector) return false;

        jclass protectorClass = env->GetObjectClass(refs->protector);
        if (!protectorClass) return false;

        jmethodID protectMethod = env->GetMethodID(protectorClass, "protect", "(I)Z");
        if (!protectMethod) {
            LOGE("JNI: protect() method not found");
            env->DeleteLocalRef(protectorClass);
            return false;
        }
        jboolean result = env->CallBooleanMethod(refs->protector, protectMethod, pid);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            result = JNI_FALSE;
        }
        env->DeleteLocalRef(protectorClass);
        return result == JNI_TRUE;
    };

    auto listenerCb = [refs](int state, const std::string& message) {
        JNIEnv* env = getJNIEnv();
        if (!env || !refs->listener) return;

        jclass listenerClass = env->GetObjectClass(refs->listener);
        if (!listenerClass) return;

        jmethodID onStateMethod = env->GetMethodID(listenerClass, "onState", "(ILjava/lang/String;)V");
        if (!onStateMethod) {
            LOGE("JNI: onState() method not found");
            env->DeleteLocalRef(listenerClass);
            return;
        }
        jstring jmsg = env->NewStringUTF(message.c_str());
        if (jmsg) {
            env->CallVoidMethod(refs->listener, onStateMethod, state, jmsg);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
            env->DeleteLocalRef(jmsg);
        }
        env->DeleteLocalRef(listenerClass);
    };

    auto eventCb = [refs](const std::string& message) {
        JNIEnv* env = getJNIEnv();
        if (!env || !refs->listener) return;

        jclass listenerClass = env->GetObjectClass(refs->listener);
        if (!listenerClass) return;

        jmethodID onEventMethod = env->GetMethodID(
            listenerClass, "onEvent", "(Ljava/lang/String;)V");
        if (!onEventMethod) {
            env->DeleteLocalRef(listenerClass);
            return;
        }
        jstring jmsg = env->NewStringUTF(message.c_str());
        if (jmsg) {
            env->CallVoidMethod(refs->listener, onEventMethod, jmsg);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
            env->DeleteLocalRef(jmsg);
        }
        env->DeleteLocalRef(listenerClass);
    };

    auto dnsResolverCb = [refs](const std::vector<std::uint8_t>& query)
        -> std::vector<std::uint8_t> {
        JNIEnv* env = getJNIEnv();
        if (!env || !refs->listener ||
            query.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
            return {};
        }
        jclass listenerClass = env->GetObjectClass(refs->listener);
        if (!listenerClass) return {};
        jmethodID resolveMethod =
            env->GetMethodID(listenerClass, "resolveDns", "([B)[B");
        if (!resolveMethod) {
            env->DeleteLocalRef(listenerClass);
            return {};
        }
        jbyteArray request = env->NewByteArray(static_cast<jsize>(query.size()));
        if (!request) {
            env->DeleteLocalRef(listenerClass);
            return {};
        }
        env->SetByteArrayRegion(
            request, 0, static_cast<jsize>(query.size()),
            reinterpret_cast<const jbyte*>(query.data()));
        auto response = static_cast<jbyteArray>(
            env->CallObjectMethod(refs->listener, resolveMethod, request));
        std::vector<std::uint8_t> result;
        if (!env->ExceptionCheck() && response) {
            const jsize length = env->GetArrayLength(response);
            if (length > 0 && length <= 65535) {
                result.resize(static_cast<std::size_t>(length));
                env->GetByteArrayRegion(
                    response, 0, length, reinterpret_cast<jbyte*>(result.data()));
            }
        }
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            result.clear();
        }
        if (response) env->DeleteLocalRef(response);
        env->DeleteLocalRef(request);
        env->DeleteLocalRef(listenerClass);
        return result;
    };

    LOGD("JNI: Creating SkyCore instance...");
    try {
        auto core = new skyunblock::SkyCore(
            cppPath, protectorCb, listenerCb, eventCb, dnsResolverCb);
        LOGD("JNI: SkyCore instance created at %p", core);
        return reinterpret_cast<jlong>(core);
    } catch (const std::exception& e) {
        LOGE("JNI: Exception during SkyCore creation: %s", e.what());
        return 0;
    } catch (...) {
        LOGE("JNI: Unknown crash during SkyCore creation");
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_skytr1x_obhod_bridge_NativeBridge_start(JNIEnv* env, jclass clazz, jlong handle) {
    LOGD("JNI: start() called with handle %p", (void*)handle);
    auto core = reinterpret_cast<skyunblock::SkyCore*>(handle);
    if (core) {
        core->start();
    } else {
        LOGE("JNI: start() failed - null handle");
    }
}

JNIEXPORT void JNICALL
Java_com_skytr1x_obhod_bridge_NativeBridge_stop(JNIEnv* env, jclass clazz, jlong handle) {
    LOGD("JNI: stop() called with handle %p", (void*)handle);
    auto core = reinterpret_cast<skyunblock::SkyCore*>(handle);
    if (core) {
        core->stop();
    }
}

JNIEXPORT void JNICALL
Java_com_skytr1x_obhod_bridge_NativeBridge_destroy(JNIEnv* env, jclass clazz, jlong handle) {
    LOGD("JNI: destroy() called with handle %p", (void*)handle);
    auto core = reinterpret_cast<skyunblock::SkyCore*>(handle);
    if (core) {
        delete core;
    }
}

JNIEXPORT void JNICALL
Java_com_skytr1x_obhod_bridge_NativeBridge_setNetworkKey(JNIEnv* env, jclass clazz, jlong handle,
                                               jstring networkKey) {
    auto core = reinterpret_cast<skyunblock::SkyCore*>(handle);
    if (!core) return;

    std::string key;
    if (networkKey) {
        const char* text = env->GetStringUTFChars(networkKey, nullptr);
        if (text) {
            key.assign(text);
            env->ReleaseStringUTFChars(networkKey, text);
        }
    }
    core->setNetworkKey(key);
}

JNIEXPORT void JNICALL
Java_com_skytr1x_obhod_bridge_NativeBridge_setNetworkStrategyOrder(
    JNIEnv* env, jclass clazz, jlong handle, jstring tcpOrder) {
    auto core = reinterpret_cast<skyunblock::SkyCore*>(handle);
    if (!core) return;

    std::string order;
    if (tcpOrder) {
        const char* text = env->GetStringUTFChars(tcpOrder, nullptr);
        if (text) {
            order.assign(text);
            env->ReleaseStringUTFChars(tcpOrder, text);
        }
    }
    core->setNetworkStrategyOrder(order);
}

JNIEXPORT jstring JNICALL
Java_com_skytr1x_obhod_bridge_NativeBridge_getStatsJson(JNIEnv* env, jclass clazz, jlong handle) {
    auto core = reinterpret_cast<skyunblock::SkyCore*>(handle);
    if (!core) return nullptr;

    std::string stats = core->getStatsJson();
    return env->NewStringUTF(stats.c_str());
}

}
