#include "chdboy/chd_write.h"
#include "chdboy/fd_registry.h"
#include "chdboy/io.h"
#include "chdboy/log.h"

#include <jni.h>

#include <atomic>
#include <mutex>
#include <string>

// The JNI surface, kept deliberately small: a session to bind descriptors
// into, one blocking convert call, and two polls for the UI to drive a
// progress dialog off. Everything else lives below this file.

namespace
{
using namespace chdboy;

// A conversion is a single job that runs for minutes, so progress is bytes
// rather than items and there is at most one of them at a time.
std::atomic<bool> g_running{ false };
std::atomic<bool> g_cancel{ false };
std::atomic<uint64_t> g_done{ 0 };
std::atomic<uint64_t> g_total{ 0 };
std::atomic<uint64_t> g_written{ 0 };

std::mutex g_error_mutex;
std::string g_error;

void set_error(const std::string& message)
{
	std::lock_guard<std::mutex> lock(g_error_mutex);
	g_error = message;
}

std::string to_string(JNIEnv* env, jstring value)
{
	if (!value)
	{
		return {};
	}

	const char* chars = env->GetStringUTFChars(value, nullptr);
	std::string result = chars ? chars : "";

	if (chars)
	{
		env->ReleaseStringUTFChars(value, chars);
	}

	return result;
}
}

extern "C" {

JNIEXPORT jint JNICALL
Java_com_izzy2lost_chdboy_core_Native_nativeOpenSession(JNIEnv*, jobject)
{
	return open_session();
}

JNIEXPORT jstring JNICALL
Java_com_izzy2lost_chdboy_core_Native_nativeBindFd(JNIEnv* env, jobject, jint session, jint fd, jstring name)
{
	const std::string path = bind_fd(session, fd, to_string(env, name));
	return env->NewStringUTF(path.c_str());
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_chdboy_core_Native_nativeCloseSession(JNIEnv*, jobject, jint session)
{
	close_session(session);
}

// Blocks for minutes on a full-size image, so Kotlin must call this off the
// main thread. Returns false on failure or cancellation; nativeLastError says
// which, and a cancelled run leaves a partial output for the caller to delete.
JNIEXPORT jboolean JNICALL
Java_com_izzy2lost_chdboy_core_Native_nativeConvert(JNIEnv* env, jobject, jstring source, jstring destination,
	jboolean portable, jint hunkBytes)
{
	const std::string src_path = to_string(env, source);
	const std::string dst_path = to_string(env, destination);

	bool expected = false;

	if (!g_running.compare_exchange_strong(expected, true))
	{
		set_error("A conversion is already running");
		return JNI_FALSE;
	}

	g_cancel = false;
	g_done = 0;
	g_total = 0;
	g_written = 0;
	set_error({});

	create_options options;

	// zstd is the default because it is the only codec that converts a
	// full-size image on a phone in a sensible time. Deflate is offered for
	// images meant to be read by tooling whose CHD support predates zstd.
	options.compressor = portable ? codec::zlib : codec::zstd;

	// 0 leaves the writer's own default in place, and a track sheet ignores it
	// outright -- the CD format fixes the hunk size at eight frames.
	options.hunk_bytes = hunkBytes > 0 ? static_cast<uint32_t>(hunkBytes) : 0;

	chdboy_logi("Converting '%s' -> '%s' (%s, hunk %d)",
		path_leaf(src_path).c_str(), path_leaf(dst_path).c_str(), portable ? "deflate" : "zstd", hunkBytes);

	const create_result result = create_chd(src_path, dst_path, options,
		[](uint64_t done, uint64_t total, uint64_t written)
		{
			g_total = total;
			g_done = done;
			g_written = written;
			return !g_cancel.load();
		});

	g_total = 0;
	g_done = 0;
	g_written = 0;
	g_running = false;

	if (!result.success)
	{
		set_error(result.error.empty() ? "Conversion failed" : result.error);
		chdboy_loge("Conversion failed: %s", result.error.c_str());
		return JNI_FALSE;
	}

	return JNI_TRUE;
}

// "done\ntotal\nwritten", all in bytes, or "" when nothing is converting.
// Percentages and ratios are the caller's to compute: this way the dialog and
// the notification cannot disagree about them.
JNIEXPORT jstring JNICALL
Java_com_izzy2lost_chdboy_core_Native_nativeGetProgress(JNIEnv* env, jobject)
{
	const uint64_t total = g_total.load();

	if (total == 0)
	{
		return env->NewStringUTF("");
	}

	const std::string value = std::to_string(g_done.load()) + "\n" +
		std::to_string(total) + "\n" +
		std::to_string(g_written.load());

	return env->NewStringUTF(value.c_str());
}

// Asks the running conversion to stop at its next batch boundary.
JNIEXPORT void JNICALL
Java_com_izzy2lost_chdboy_core_Native_nativeCancel(JNIEnv*, jobject)
{
	g_cancel = true;
}

JNIEXPORT jstring JNICALL
Java_com_izzy2lost_chdboy_core_Native_nativeLastError(JNIEnv* env, jobject)
{
	std::lock_guard<std::mutex> lock(g_error_mutex);
	return env->NewStringUTF(g_error.c_str());
}
}
