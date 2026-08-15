#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace chdboy
{
// CD geometry, matching MAME's cdrom.h. These are format constants, not
// tunables: a CHD holding a CD stores 2352 bytes of sector plus 96 bytes of
// subcode per frame and eight frames per hunk, and every reader assumes it.
constexpr uint32_t cd_max_sector_data = 2352;
constexpr uint32_t cd_max_subcode_data = 96;
constexpr uint32_t cd_frame_size = cd_max_sector_data + cd_max_subcode_data; // 2448
constexpr uint32_t cd_frames_per_hunk = 8;
constexpr uint32_t cd_track_padding = 4;
constexpr uint32_t cd_max_tracks = 99;

// Track types, in the numbering the metadata strings are written from.
enum cd_track_type
{
	cd_track_mode1 = 0,
	cd_track_mode1_raw,
	cd_track_mode2,
	cd_track_mode2_form1,
	cd_track_mode2_form2,
	cd_track_mode2_form_mix,
	cd_track_mode2_raw,
	cd_track_audio,
};

enum cd_sub_type
{
	cd_sub_normal = 0,
	cd_sub_raw,
	cd_sub_none,
};

struct track_info
{
	uint32_t track_type = cd_track_mode1;
	uint32_t sub_type = cd_sub_none;

	// Bytes of each per sector in the SOURCE image. Their sum is the source
	// stride; in the CHD both land at the front of a 2448-byte frame and the
	// remainder is zero, which is what makes a MODE1/2048 track legal.
	uint32_t data_size = 0;
	uint32_t sub_size = 0;

	// Total frames the track occupies in the CHD, INCLUDING pad_frames. Only
	// (frames - pad_frames) of them come from the file.
	uint32_t frames = 0;
	uint32_t pad_frames = 0;

	// Rounding up to cd_track_padding. Written as zeroes, not described by the
	// metadata, but they do occupy space in the image.
	uint32_t extra_frames = 0;

	uint32_t pregap = 0;
	uint32_t postgap = 0;
	uint32_t pregap_type = cd_track_mode1;
	uint32_t pregap_sub_type = cd_sub_none;

	// Non-zero when the pregap's sectors are present in the file, which is
	// what decides whether the metadata marks the pregap type "valid".
	uint32_t pregap_data_size = 0;

	// Where the track's sectors start, in the file below.
	std::string file;
	uint64_t file_offset = 0;

	// CDDA is stored in the CHD byte-swapped relative to a BIN. Every audio
	// track ends up with this set; it is not a property of the source's
	// declared endianness.
	bool swap = false;
};

struct disc_toc
{
	std::vector<track_info> tracks;

	// GD-ROM discs describe their tracks with a different metadata tag that
	// carries the pad count, so this changes what gets written, not just how
	// the image is laid out.
	bool gdrom = false;
};

const char* track_type_string(uint32_t track_type);
const char* sub_type_string(uint32_t sub_type);

// Parses a .cue or .gdi into a TOC whose tracks are ready to be laid out.
// `error` receives a human-readable reason on failure.
bool parse_cue(const std::string& path, disc_toc& out, std::string& error);
bool parse_gdi(const std::string& path, disc_toc& out, std::string& error);

// Dispatches on extension. Also fills in extra_frames for every track, which
// both parsers leave at zero.
bool parse_toc(const std::string& path, disc_toc& out, std::string& error);
}
