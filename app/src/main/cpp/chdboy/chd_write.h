#pragma once

#include <cstdint>
#include <functional>
#include <string>

namespace chdboy
{
// Codec every hunk is compressed with. Both are read back by libchdr, which is
// what DuckStation and Flycast embed, so either choice stays loadable; the
// difference is how well older tooling copes.
enum class codec
{
	// Best ratio-per-second by a wide margin, which is what makes converting a
	// disc image on a phone practical. chdman only learned to read it in
	// 0.264, so a CHD written with it is not portable to older tooling.
	zstd,

	// Deflate. Slower and larger, but understood by every chdman ever shipped.
	zlib,
};

struct create_options
{
	codec compressor = codec::zstd;

	// 0 selects the codec's own default. Otherwise 1-22 for zstd, 1-9 for zlib.
	int level = 0;

	// 0 selects the default for the source: 128 KiB for a raw image, eight CD
	// frames for a disc. A CD image cannot use anything else -- the format
	// fixes it -- so this is only consulted for raw sources.
	uint32_t hunk_bytes = 0;

	// 0 derives it from the core count. One worker is the calling thread,
	// which takes a share of every batch rather than idling.
	uint32_t worker_threads = 0;
};

struct create_result
{
	bool success = false;

	// Distinguishes a cancelled conversion, whose output is a truncated file
	// the caller is expected to delete, from a genuine failure.
	bool cancelled = false;

	uint64_t source_bytes = 0;
	uint64_t output_bytes = 0;
	std::string error;
};

// Called with (bytes converted, bytes total, bytes written so far) once per
// batch of hunks. The third is what lets a caller show a live compression
// ratio without stat()ing an output it may only hold a descriptor for.
// Returning false cancels. Runs on the thread that called create_chd.
using progress_fn = std::function<bool(uint64_t, uint64_t, uint64_t)>;

// Writes dst_path as a CHD v5 holding src_path.
//
// src_path may be a raw disc image (.iso and friends) or a track sheet (.cue,
// .gdi). A track sheet produces a CD-format CHD: 2448-byte frames, per-track
// metadata, and the CD codec that splits subcode out and drops recomputable
// ECC. Anything else is stored as a flat image.
//
// Both paths go through chdboy::file, so descriptors bound through fd_registry
// work on either side.
//
// This is a whole-image operation over tens of gigabytes: run it off the UI
// thread and expect minutes, not seconds.
create_result create_chd(const std::string& src_path, const std::string& dst_path,
	const create_options& options = {}, const progress_fn& progress = {});
}
