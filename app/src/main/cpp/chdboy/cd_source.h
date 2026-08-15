#pragma once

#include "chdboy/toc.h"

#include <cstdint>
#include <memory>
#include <string>

namespace chdboy
{
// The logical byte stream a CHD stores, whatever shape the source is on disk.
//
// For an .iso that is the file itself. For a .cue or .gdi it is the disc as
// the format lays it out: 2448-byte frames, tracks padded to a multiple of
// four, sectors copied to the front of each frame with the rest zeroed.
class image_source
{
public:
	virtual ~image_source() = default;

	virtual uint64_t logical_bytes() const = 0;

	// Fills [offset, offset + length) of the logical stream. Reads past the
	// end are the caller's problem; the writer never issues one.
	virtual bool read_at(uint64_t offset, uint8_t* dst, size_t length) = 0;

	// Bytes the reader should treat as one unit. A CD image has to keep whole
	// frames in a hunk, so this is the frame size for one and 1 for the other.
	virtual uint32_t unit_bytes() const = 0;
};

// A file read straight through: .iso, .bin, or anything else already in the
// form the disc is stored in.
std::unique_ptr<image_source> open_raw_source(const std::string& path, std::string& error);

// A parsed TOC, presented as the CD frame stream described above. The TOC is
// copied, but the files it names are opened lazily and kept open one at a
// time -- a multi-bin disc otherwise burns a descriptor per track.
std::unique_ptr<image_source> open_cd_source(const disc_toc& toc, std::string& error);
}
