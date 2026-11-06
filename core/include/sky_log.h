#ifndef SKY_LOG_H
#define SKY_LOG_H

#ifdef SKY_HOST_BUILD

#include <cstdio>
#include <cstdlib>

#define SKY_LOGD(...)                              \
    do {                                           \
        if (std::getenv("SKY_DEBUG")) {            \
            std::fprintf(stderr, __VA_ARGS__);     \
            std::fputc('\n', stderr);              \
        }                                          \
    } while (0)
#define SKY_LOGI(...)                              \
    do {                                           \
        std::fprintf(stderr, __VA_ARGS__);         \
        std::fputc('\n', stderr);                  \
    } while (0)
#define SKY_LOGW(...)                              \
    do {                                           \
        std::fprintf(stderr, __VA_ARGS__);         \
        std::fputc('\n', stderr);                  \
    } while (0)
#define SKY_LOGE(...)                              \
    do {                                           \
        std::fprintf(stderr, __VA_ARGS__);         \
        std::fputc('\n', stderr);                  \
    } while (0)

#else

#include <android/log.h>

#define SKY_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "SkyCore", __VA_ARGS__)
#define SKY_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "SkyCore", __VA_ARGS__)
#define SKY_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "SkyCore", __VA_ARGS__)
#define SKY_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "SkyCore", __VA_ARGS__)

#endif

#endif
