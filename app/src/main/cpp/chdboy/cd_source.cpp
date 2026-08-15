#include "chdboy/cd_source.h"

#include "chdboy/io.h"
#include "chdboy/log.h"

#include <algorithm>
#include <cstring>
#include <utility>

namespace chdboy
{
namespace
{
class raw_source final : public image_source
{
	file m_file;
	uint64_t m_size = 0;

public:
	raw_source(file&& handle, uint64_t size)
		: m_file(std::move(handle))
		, m_size(size)
	{
	}

	uint64_t logical_bytes() const override { return m_size; }

	uint32_t unit_bytes() const override { return 1; }

	bool read_at(uint64_t offset, uint8_t* dst, size_t length) override
	{
		// The last hunk of an image whose size is not a multiple of the hunk
		// size runs past the end. Serve what is there and zero the rest, which
		// is what the decoder will reproduce.
		const uint64_t available = (offset >= m_size) ? 0 : std::min<uint64_t>(length, m_size - offset);

		if (available && !m_file.read_at(offset, dst, static_cast<size_t>(available)))
		{
			return false;
		}

		if (available < length)
		{
			std::memset(dst + available, 0, length - static_cast<size_t>(available));
		}

		return true;
	}
};

// The disc laid out as MAME lays it out. Mirrors chdman's
// chd_cd_compressor::read_data, including which frames come from the file,
// which are padding, and where the CDDA byte swap happens.
class cd_source final : public image_source
{
	disc_toc m_toc;
	uint64_t m_size = 0;

	// One open file at a time: tracks are visited in order, so a multi-bin
	// disc reopens once per track rather than holding every bin at once.
	file m_file;
	std::string m_open_path;

	bool ensure_open(const std::string& path)
	{
		if (m_open_path == path && m_file)
		{
			return true;
		}

		file handle = file::open_read(path);

		if (!handle)
		{
			chdboy_loge("Could not open track file '%s'", path.c_str());
			return false;
		}

		m_file = std::move(handle);
		m_open_path = path;
		return true;
	}

public:
	explicit cd_source(disc_toc toc)
		: m_toc(std::move(toc))
	{
		for (const track_info& track : m_toc.tracks)
		{
			m_size += static_cast<uint64_t>(track.frames + track.extra_frames) * cd_frame_size;
		}
	}

	uint64_t logical_bytes() const override { return m_size; }

	uint32_t unit_bytes() const override { return cd_frame_size; }

	bool read_at(uint64_t offset, uint8_t* dst, size_t length) override
	{
		if (offset % cd_frame_size || length % cd_frame_size)
		{
			chdboy_loge("Unaligned CD read at %llu for %zu bytes",
				static_cast<unsigned long long>(offset), length);
			return false;
		}

		// Anything not filled from a file stays zero: the tail of a short
		// sector, the subcode area when the source has none, and every pad
		// frame. Doing it once here is why none of the cases below have to.
		std::memset(dst, 0, length);

		uint64_t track_start = 0;
		size_t remaining = length;

		for (const track_info& track : m_toc.tracks)
		{
			if (remaining == 0)
			{
				break;
			}

			const uint64_t track_end = track_start +
				static_cast<uint64_t>(track.frames + track.extra_frames) * cd_frame_size;

			if (offset >= track_start && offset < track_end)
			{
				const uint64_t stride = track.data_size + track.sub_size;
				const uint64_t source_start = track.file_offset;
				const uint64_t source_end = source_start + stride * track.frames;

				// Padding sits at the end of the track and has no bytes behind
				// it in the file, even though it counts towards `frames`.
				const uint64_t pad_start = source_end - stride * track.pad_frames;

				while (remaining && offset < track_end)
				{
					const uint64_t frame_start = source_start + ((offset - track_start) / cd_frame_size) * stride;

					if (frame_start < source_end)
					{
						if (frame_start < pad_start)
						{
							if (!ensure_open(track.file))
							{
								return false;
							}

							if (!m_file.read_at(frame_start, dst, static_cast<size_t>(stride)))
							{
								chdboy_loge("Short read in '%s' at %llu",
									track.file.c_str(), static_cast<unsigned long long>(frame_start));
								return false;
							}
						}

						// CDDA goes in byte-swapped. The swap covers the whole
						// sector area rather than `stride` because that is the
						// span the format defines it over; for an audio track
						// the two are the same 2352 bytes anyway.
						if (track.swap)
						{
							for (uint32_t i = 0; i < cd_max_sector_data; i += 2)
							{
								std::swap(dst[i], dst[i + 1]);
							}
						}
					}

					offset += cd_frame_size;
					dst += cd_frame_size;
					remaining -= cd_frame_size;
				}
			}

			track_start = track_end;
		}

		// The last hunk of a disc whose length is not a multiple of the hunk
		// size runs past the final track. Those bytes are legitimately zero --
		// the memset above already produced them -- so only a shortfall that
		// lands inside the disc is a failure.
		if (remaining && offset < m_size)
		{
			chdboy_loge("Gap in the disc layout at %llu", static_cast<unsigned long long>(offset));
			return false;
		}

		return true;
	}
};
}

std::unique_ptr<image_source> open_raw_source(const std::string& path, std::string& error)
{
	file handle = file::open_read(path);

	if (!handle)
	{
		error = "Could not open '" + path_leaf(path) + "'";
		return nullptr;
	}

	const uint64_t size = handle.size();

	if (size == 0)
	{
		error = "'" + path_leaf(path) + "' is empty";
		return nullptr;
	}

	return std::make_unique<raw_source>(std::move(handle), size);
}

std::unique_ptr<image_source> open_cd_source(const disc_toc& toc, std::string& error)
{
	if (toc.tracks.empty())
	{
		error = "The disc has no tracks";
		return nullptr;
	}

	auto source = std::make_unique<cd_source>(toc);

	if (source->logical_bytes() == 0)
	{
		error = "The disc is empty";
		return nullptr;
	}

	return source;
}
}
