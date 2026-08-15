#pragma once

#define CHDBOY_LOG_TAG "chdboy-native"

#ifdef __ANDROID__

#include <android/log.h>

#define chdboy_logd(...) __android_log_print(ANDROID_LOG_DEBUG, CHDBOY_LOG_TAG, __VA_ARGS__)
#define chdboy_logi(...) __android_log_print(ANDROID_LOG_INFO, CHDBOY_LOG_TAG, __VA_ARGS__)
#define chdboy_logw(...) __android_log_print(ANDROID_LOG_WARN, CHDBOY_LOG_TAG, __VA_ARGS__)
#define chdboy_loge(...) __android_log_print(ANDROID_LOG_ERROR, CHDBOY_LOG_TAG, __VA_ARGS__)

#else

// Host builds exist so the writer can be exercised against libchdr on a
// desktop, where a failure is a stack trace away rather than a logcat dump.
#include <cstdio>

#define chdboy_log_at(level, ...)                          \
	do                                                     \
	{                                                      \
		std::fprintf(stderr, "[%s/%s] ", CHDBOY_LOG_TAG, level); \
		std::fprintf(stderr, __VA_ARGS__);                 \
		std::fputc('\n', stderr);                          \
	} while (0)

#define chdboy_logd(...) chdboy_log_at("D", __VA_ARGS__)
#define chdboy_logi(...) chdboy_log_at("I", __VA_ARGS__)
#define chdboy_logw(...) chdboy_log_at("W", __VA_ARGS__)
#define chdboy_loge(...) chdboy_log_at("E", __VA_ARGS__)

#endif
