#include "chdboy/chd_write.h"

#include "chdboy/cd_source.h"
#include "chdboy/io.h"
#include "chdboy/log.h"
#include "chdboy/sha1.h"
#include "chdboy/toc.h"

#include <libchdr/chd.h>

// chd.h guards itself, cdrom.h does not -- so the ecc_* entry points would
// mangle as C++ here and fail to find the C definitions in the archive.
// chd.h above is what makes cdrom.h's own include of it a no-op inside this.
extern "C" {
#include <libchdr/cdrom.h>
}

#include <zlib.h>
#include <zstd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <condition_variable>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>

// Writes the subset of CHD v5 that libchdr reads back: one codec, optional
// CD track metadata, no parent. libchdr is a decoder only -- chd_create is
// commented out in its public header -- and chdman is not an option here
// because it reaches for files through stdio paths, while the app holds no
// storage permission and sees everything as a descriptor bound into
// fd_registry. So the format is produced directly, against libchdr's own
// decoder as the specification.

namespace chdboy
{
namespace
{
constexpr uint32_t chd_v5_header_size = 124;

// One hunk is the unit of both decompression and of the reader's cache, so
// this trades ratio against how much gets decoded to serve a small read. It
// also sets how big the map is: libchdr expands the whole thing into memory on
// open, 12 bytes per hunk, for every open handle.
constexpr uint32_t raw_default_hunk_bytes = 128 * 1024;

// The addressing granularity recorded in the header. Only a parent CHD
// actually resolves through it, but it still has to divide the hunk size.
constexpr uint32_t raw_unit_bytes = 2048;

// libchdr stores units-per-hunk in a byte on the parent path, so a hunk can
// hold at most this many units even though no parent is ever written.
constexpr uint32_t max_units_per_hunk = 255;

// V5 map entry types, mirroring the enum in libchdr_chd.c. Only these three
// are emitted; the RLE and delta pseudo-types are decoder-side conveniences
// that a writer is free not to use.
constexpr uint8_t compression_type_0 = 0;
constexpr uint8_t compression_none = 4;
constexpr uint8_t compression_self = 5;

// Metadata entry header: tag, then a word whose top byte is flags and whose
// low 24 bits are the payload length, then the offset of the next entry.
constexpr uint32_t metadata_header_size = 16;
constexpr uint8_t metadata_flag_checksum = 0x01;

// The 12 bytes every Mode 1 and Mode 2 sector begins with. A frame that starts
// with these and carries ECC the format can regenerate needs neither stored.
constexpr uint8_t cd_sync_header[12] = { 0x00, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x00 };

void put_be16(uint8_t* dst, uint16_t value)
{
	dst[0] = static_cast<uint8_t>(value >> 8);
	dst[1] = static_cast<uint8_t>(value);
}

void put_be24(uint8_t* dst, uint32_t value)
{
	dst[0] = static_cast<uint8_t>(value >> 16);
	dst[1] = static_cast<uint8_t>(value >> 8);
	dst[2] = static_cast<uint8_t>(value);
}

void put_be32(uint8_t* dst, uint32_t value)
{
	dst[0] = static_cast<uint8_t>(value >> 24);
	dst[1] = static_cast<uint8_t>(value >> 16);
	dst[2] = static_cast<uint8_t>(value >> 8);
	dst[3] = static_cast<uint8_t>(value);
}

void put_be48(uint8_t* dst, uint64_t value)
{
	for (int i = 0; i < 6; i++)
	{
		dst[i] = static_cast<uint8_t>(value >> (40 - i * 8));
	}
}

void put_be64(uint8_t* dst, uint64_t value)
{
	for (int i = 0; i < 8; i++)
	{
		dst[i] = static_cast<uint8_t>(value >> (56 - i * 8));
	}
}

uint16_t get_be16(const uint8_t* src)
{
	return static_cast<uint16_t>((static_cast<uint32_t>(src[0]) << 8) | src[1]);
}

uint32_t get_be24(const uint8_t* src)
{
	return (static_cast<uint32_t>(src[0]) << 16) | (static_cast<uint32_t>(src[1]) << 8) | src[2];
}

uint64_t get_be48(const uint8_t* src)
{
	uint64_t value = 0;

	for (int i = 0; i < 6; i++)
	{
		value = (value << 8) | src[i];
	}

	return value;
}

// CRC-16/CCITT-FALSE, the variant libchdr checks hunks and the map with. Its
// own crc16 is not exported through a public header.
uint16_t chd_crc16(const void* data, size_t length)
{
	static const std::array<uint16_t, 256> table = []
	{
		std::array<uint16_t, 256> result{};

		for (uint32_t i = 0; i < 256; i++)
		{
			uint16_t value = static_cast<uint16_t>(i << 8);

			for (int bit = 0; bit < 8; bit++)
			{
				value = static_cast<uint16_t>((value & 0x8000) ? ((value << 1) ^ 0x1021) : (value << 1));
			}

			result[i] = value;
		}

		return result;
	}();

	const uint8_t* src = static_cast<const uint8_t*>(data);
	uint16_t crc = 0xffff;

	for (size_t i = 0; i < length; i++)
	{
		crc = static_cast<uint16_t>(table[(crc >> 8) ^ src[i]] ^ (crc << 8));
	}

	return crc;
}

// Number of bits needed to hold value, matching MAME's bits_for_value.
uint8_t bits_for_value(uint64_t value)
{
	uint8_t bits = 0;

	while (value != 0)
	{
		bits++;
		value >>= 1;
	}

	return bits;
}

// MSB-first bit packer, the mirror image of libchdr's bitstream reader.
class bit_writer
{
	std::vector<uint8_t>& m_out;
	uint64_t m_acc = 0;
	int m_bits = 0;

public:
	explicit bit_writer(std::vector<uint8_t>& out)
		: m_out(out)
	{
	}

	void write(uint32_t value, int numbits)
	{
		if (numbits <= 0)
		{
			return;
		}

		const uint64_t mask = (numbits >= 32) ? 0xffffffffull : ((1ull << numbits) - 1);
		m_acc = (m_acc << numbits) | (value & mask);
		m_bits += numbits;

		while (m_bits >= 8)
		{
			m_bits -= 8;
			m_out.push_back(static_cast<uint8_t>(m_acc >> m_bits));
		}
	}

	void flush()
	{
		if (m_bits > 0)
		{
			m_out.push_back(static_cast<uint8_t>(m_acc << (8 - m_bits)));
			m_bits = 0;
		}
	}
};

// The map's compression-type stream is Huffman coded with a 16-symbol,
// 8-maxbits decoder. Giving every symbol a 4-bit code makes the tree complete,
// and libchdr's canonical assignment then hands symbol N the code N -- so
// encoding a type is just writing its own value in 4 bits.
constexpr int map_type_bits = 4;

void write_flat_huffman_tree(bit_writer& bits)
{
	// maxbits of 8 selects 4 bits per length in huffman_import_tree_rle. A
	// length of 1 is that format's escape, and 4 is not, so these are all
	// taken literally.
	for (int i = 0; i < 16; i++)
	{
		bits.write(map_type_bits, 4);
	}
}

struct hunk_job
{
	std::vector<uint8_t> raw;
	std::vector<uint8_t> packed;
	size_t packed_size = 0;
	std::array<uint8_t, 20> sha{};
	uint16_t crc = 0;
};

struct sha_hasher
{
	size_t operator()(const std::array<uint8_t, 20>& key) const
	{
		size_t value = 0;
		std::memcpy(&value, key.data(), sizeof(value));
		return value;
	}
};

// A pool that stays alive for the whole conversion, so each worker keeps one
// codec context instead of rebuilding it for every batch. Work is hunks of the
// current batch, claimed by index; the caller joins the batch by helping and
// then waiting for the workers it started.
class hunk_compressor
{
	const codec m_codec;
	const int m_level;
	const uint32_t m_hunk_bytes;
	const bool m_cd_mode;

	// Derived CD framing, all constant for the run.
	const uint32_t m_frames;
	const uint32_t m_ecc_bytes;
	const uint32_t m_complen_bytes;
	const uint32_t m_cd_header_bytes;

	// Per-worker codec state. zstd keeps a reusable context; zlib is fed a
	// stream that is reset per block, which is how libchdr decodes it (raw
	// deflate, no zlib wrapper).
	struct codec_state
	{
		ZSTD_CCtx* zstd = nullptr;
		z_stream deflater{};
		bool deflater_ready = false;

		// Scratch for the CD path: the hunk with its sectors and its subcode
		// separated, which is the shape both sub-streams are coded in.
		std::vector<uint8_t> cd_buffer;

		~codec_state()
		{
			if (zstd)
			{
				ZSTD_freeCCtx(zstd);
			}

			if (deflater_ready)
			{
				deflateEnd(&deflater);
			}
		}
	};

	void init_codec(codec_state& state) const
	{
		if (m_cd_mode)
		{
			state.cd_buffer.resize(m_hunk_bytes);
		}

		if (m_codec == codec::zstd)
		{
			state.zstd = ZSTD_createCCtx();
			return;
		}

		// Negative window bits select raw deflate, which is what libchdr's
		// zlib codec inflates with.
		if (deflateInit2(&state.deflater, m_level, Z_DEFLATED, -MAX_WBITS, 9, Z_DEFAULT_STRATEGY) == Z_OK)
		{
			state.deflater_ready = true;
		}
	}

	// One block through the active codec. Returns 0 if it did not fit or the
	// codec was unavailable, which the callers treat as "store this verbatim".
	size_t compress_block(codec_state& state, const uint8_t* src, size_t src_size,
		uint8_t* dst, size_t dst_capacity) const
	{
		if (dst_capacity == 0)
		{
			return 0;
		}

		if (m_codec == codec::zstd)
		{
			if (!state.zstd)
			{
				return 0;
			}

			const size_t written = ZSTD_compressCCtx(state.zstd, dst, dst_capacity, src, src_size, m_level);
			return ZSTD_isError(written) ? 0 : written;
		}

		if (!state.deflater_ready || deflateReset(&state.deflater) != Z_OK)
		{
			return 0;
		}

		z_stream& stream = state.deflater;
		stream.next_in = const_cast<Bytef*>(static_cast<const Bytef*>(src));
		stream.avail_in = static_cast<uInt>(src_size);
		stream.next_out = dst;
		stream.avail_out = static_cast<uInt>(dst_capacity);

		return deflate(&stream, Z_FINISH) == Z_STREAM_END ? stream.total_out : 0;
	}

	// The CD codec: split each frame into its sector and its subcode, drop the
	// sync header and ECC of any sector whose ECC the decoder can regenerate,
	// then code the two streams separately. Byte-for-byte the layout libchdr's
	// cd_codec_decompress expects.
	void compress_cd(hunk_job& job, codec_state& state) const
	{
		uint8_t* dest = job.packed.data();
		uint8_t* buffer = state.cd_buffer.data();
		uint8_t* const subcode = buffer + static_cast<size_t>(m_frames) * cd_max_sector_data;

		std::memset(dest, 0, m_cd_header_bytes);

		for (uint32_t frame = 0; frame < m_frames; frame++)
		{
			const uint8_t* src = job.raw.data() + static_cast<size_t>(frame) * cd_frame_size;
			uint8_t* sector = buffer + static_cast<size_t>(frame) * cd_max_sector_data;

			std::memcpy(sector, src, cd_max_sector_data);
			std::memcpy(subcode + static_cast<size_t>(frame) * cd_max_subcode_data,
				src + cd_max_sector_data, cd_max_subcode_data);

			// ecc_verify only says the parity already matches what
			// ecc_generate would produce. That is the whole condition: if it
			// holds, the bytes are recomputable and dropping them is lossless.
			if (std::memcmp(sector, cd_sync_header, sizeof(cd_sync_header)) == 0 && ecc_verify(sector))
			{
				dest[frame / 8] |= static_cast<uint8_t>(1u << (frame % 8));
				std::memset(sector, 0, sizeof(cd_sync_header));
				ecc_clear(sector);
			}
		}

		const size_t capacity = job.packed.size();
		const size_t sector_bytes = static_cast<size_t>(m_frames) * cd_max_sector_data;
		const size_t subcode_bytes = static_cast<size_t>(m_frames) * cd_max_subcode_data;

		const size_t base_length = compress_block(state, buffer, sector_bytes,
			dest + m_cd_header_bytes, capacity - m_cd_header_bytes);

		if (base_length == 0)
		{
			return;
		}

		const size_t subcode_length = compress_block(state, subcode, subcode_bytes,
			dest + m_cd_header_bytes + base_length, capacity - m_cd_header_bytes - base_length);

		if (subcode_length == 0)
		{
			return;
		}

		// The decoder derives the subcode stream's length by subtraction, so
		// only the base length is recorded.
		if (m_complen_bytes == 3)
		{
			put_be24(&dest[m_ecc_bytes], static_cast<uint32_t>(base_length));
		}
		else
		{
			put_be16(&dest[m_ecc_bytes], static_cast<uint16_t>(base_length));
		}

		const size_t total = m_cd_header_bytes + base_length + subcode_length;

		if (total < m_hunk_bytes)
		{
			job.packed_size = total;
		}
	}

	void compress_one(hunk_job& job, codec_state& state) const
	{
		job.crc = chd_crc16(job.raw.data(), m_hunk_bytes);
		chdboy_sha1_buffer(job.raw.data(), m_hunk_bytes, job.sha.data());

		// A hunk that does not get smaller is stored verbatim, so the worst
		// case never needs more room than the hunk itself. That is also the
		// fallback if the codec could not be set up at all: the conversion
		// stays correct, it just stops saving space.
		job.packed_size = m_hunk_bytes;

		if (m_cd_mode)
		{
			compress_cd(job, state);
			return;
		}

		const size_t written = compress_block(state, job.raw.data(), m_hunk_bytes,
			job.packed.data(), job.packed.size());

		if (written != 0 && written < m_hunk_bytes)
		{
			job.packed_size = written;
		}
	}

	// Batch handoff. The caller publishes a batch under the mutex, bumps the
	// epoch, then joins in; workers claim hunks by index until the batch runs
	// dry and the last one out wakes the caller.
	std::mutex m_mutex;
	std::condition_variable m_start;
	std::condition_variable m_finished;

	uint32_t m_epoch = 0;
	uint32_t m_outstanding = 0;
	bool m_shutdown = false;

	std::atomic<uint32_t> m_next{0};
	uint32_t m_count = 0;
	std::vector<hunk_job>* m_jobs = nullptr;

	// The calling thread's own codec state. It takes a share of every batch
	// rather than idling, so it needs one too, and keeping it here is what
	// stops it being rebuilt thousands of times over a large image.
	codec_state m_own_state;

	std::vector<std::thread> m_workers;

	void run_batch(codec_state& state)
	{
		for (uint32_t i = m_next++; i < m_count; i = m_next++)
		{
			compress_one((*m_jobs)[i], state);
		}
	}

public:
	hunk_compressor(codec compressor, int level, uint32_t hunk_bytes, bool cd_mode, uint32_t worker_count)
		: m_codec(compressor)
		, m_level(level)
		, m_hunk_bytes(hunk_bytes)
		, m_cd_mode(cd_mode)
		, m_frames(cd_mode ? hunk_bytes / cd_frame_size : 0)
		, m_ecc_bytes(cd_mode ? (m_frames + 7) / 8 : 0)
		, m_complen_bytes(hunk_bytes < 65536 ? 2 : 3)
		, m_cd_header_bytes(m_ecc_bytes + m_complen_bytes)
	{
		init_codec(m_own_state);

		m_workers.reserve(worker_count);

		for (uint32_t i = 0; i < worker_count; i++)
		{
			m_workers.emplace_back([this]
			{
				codec_state state;
				init_codec(state);

				for (uint32_t seen = 0;;)
				{
					{
						std::unique_lock<std::mutex> lock(m_mutex);
						m_start.wait(lock, [&] { return m_shutdown || m_epoch != seen; });

						if (m_shutdown)
						{
							return;
						}

						seen = m_epoch;
					}

					run_batch(state);

					{
						std::lock_guard<std::mutex> lock(m_mutex);

						if (--m_outstanding == 0)
						{
							m_finished.notify_one();
						}
					}
				}
			});
		}
	}

	~hunk_compressor()
	{
		{
			std::lock_guard<std::mutex> lock(m_mutex);
			m_shutdown = true;
		}

		m_start.notify_all();

		for (std::thread& worker : m_workers)
		{
			worker.join();
		}
	}

	hunk_compressor(const hunk_compressor&) = delete;
	hunk_compressor& operator=(const hunk_compressor&) = delete;

	// Compresses jobs[0, count) and returns once every one of them is done.
	void run(std::vector<hunk_job>& jobs, uint32_t count)
	{
		{
			std::lock_guard<std::mutex> lock(m_mutex);
			m_jobs = &jobs;
			m_count = count;
			m_next = 0;
			m_outstanding = static_cast<uint32_t>(m_workers.size());
			m_epoch++;
		}

		m_start.notify_all();

		run_batch(m_own_state);

		std::unique_lock<std::mutex> lock(m_mutex);
		m_finished.wait(lock, [&] { return m_outstanding == 0; });
	}

	// Room a packed hunk can need. The CD path codes two streams, so it pays
	// two lots of frame overhead rather than one.
	size_t packed_capacity() const
	{
		const auto bound = [&](size_t size)
		{
			return m_codec == codec::zstd
				? ZSTD_compressBound(size)
				: static_cast<size_t>(compressBound(static_cast<uLong>(size)));
		};

		if (!m_cd_mode)
		{
			return bound(m_hunk_bytes) + 64;
		}

		return m_cd_header_bytes
			+ bound(static_cast<size_t>(m_frames) * cd_max_sector_data)
			+ bound(static_cast<size_t>(m_frames) * cd_max_subcode_data)
			+ 64;
	}
};

int default_level(codec compressor)
{
	// zstd at 9 is roughly where a phone still converts a disc image faster
	// than it can read one; Deflate has nothing above 9.
	return compressor == codec::zstd ? 9 : 9;
}

int clamp_level(codec compressor, int level)
{
	if (level <= 0)
	{
		return default_level(compressor);
	}

	return compressor == codec::zstd ? std::min(level, ZSTD_maxCLevel()) : std::min(level, 9);
}

struct metadata_entry
{
	uint32_t tag = 0;
	uint8_t flags = metadata_flag_checksum;
	std::string data;
};

std::string format_string(const char* format, ...) __attribute__((format(printf, 1, 2)));

std::string format_string(const char* format, ...)
{
	va_list args;
	va_start(args, format);

	va_list measure;
	va_copy(measure, args);
	const int length = std::vsnprintf(nullptr, 0, format, measure);
	va_end(measure);

	std::string result;

	if (length > 0)
	{
		result.resize(static_cast<size_t>(length));
		std::vsnprintf(result.data(), static_cast<size_t>(length) + 1, format, args);
	}

	va_end(args);
	return result;
}

// One entry per track, in the shape MAME's cdrom_file::write_metadata emits.
// A reader parses these with sscanf against the same format string, so the
// spacing and the field order are load-bearing.
std::vector<metadata_entry> build_cd_metadata(const disc_toc& toc)
{
	std::vector<metadata_entry> entries;
	entries.reserve(toc.tracks.size());

	for (size_t i = 0; i < toc.tracks.size(); i++)
	{
		const track_info& track = toc.tracks[i];
		metadata_entry entry;

		if (toc.gdrom)
		{
			entry.tag = CHD_MAKE_TAG('C', 'H', 'G', 'D');
			entry.data = format_string("TRACK:%d TYPE:%s SUBTYPE:%s FRAMES:%d PAD:%d PREGAP:%d PGTYPE:%s PGSUB:%s POSTGAP:%d",
				static_cast<int>(i + 1), track_type_string(track.track_type), sub_type_string(track.sub_type),
				track.frames, track.pad_frames, track.pregap, track_type_string(track.pregap_type),
				sub_type_string(track.pregap_sub_type), track.postgap);
		}
		else
		{
			// A 'V' in front of the pregap type marks its sectors as actually
			// present in the image rather than merely declared.
			const std::string pregap_type = (track.pregap_data_size > 0 ? "V" : "") +
				std::string(track_type_string(track.pregap_type));

			entry.tag = CHD_MAKE_TAG('C', 'H', 'T', '2');
			entry.data = format_string("TRACK:%d TYPE:%s SUBTYPE:%s FRAMES:%d PREGAP:%d PGTYPE:%s PGSUB:%s POSTGAP:%d",
				static_cast<int>(i + 1), track_type_string(track.track_type), sub_type_string(track.sub_type),
				track.frames, track.pregap, pregap_type.c_str(),
				sub_type_string(track.pregap_sub_type), track.postgap);
		}

		// MAME stores the terminating NUL as part of the payload, and a reader
		// that trusts the recorded length would otherwise run off the end.
		entry.data.push_back('\0');
		entries.push_back(std::move(entry));
	}

	return entries;
}

// The overall SHA-1 covers the raw image plus every checksummed metadata
// entry, hashed individually, tagged, and sorted so the result does not depend
// on the order they happen to sit in the file.
void compute_overall_sha1(const std::array<uint8_t, 20>& raw_sha1,
	const std::vector<metadata_entry>& metadata, uint8_t out[20])
{
	struct hash_entry
	{
		uint8_t tag[4];
		uint8_t sha1[20];
	};

	std::vector<hash_entry> hashes;
	hashes.reserve(metadata.size());

	for (const metadata_entry& entry : metadata)
	{
		if ((entry.flags & metadata_flag_checksum) == 0)
		{
			continue;
		}

		hash_entry hash{};
		put_be32(hash.tag, entry.tag);
		chdboy_sha1_buffer(entry.data.data(), entry.data.size(), hash.sha1);
		hashes.push_back(hash);
	}

	std::sort(hashes.begin(), hashes.end(), [](const hash_entry& a, const hash_entry& b)
	{
		return std::memcmp(&a, &b, sizeof(hash_entry)) < 0;
	});

	chdboy_sha1 ctx;
	chdboy_sha1_init(&ctx);
	chdboy_sha1_update(&ctx, raw_sha1.data(), raw_sha1.size());

	if (!hashes.empty())
	{
		chdboy_sha1_update(&ctx, hashes.data(), hashes.size() * sizeof(hash_entry));
	}

	chdboy_sha1_final(&ctx, out);
}
}

create_result create_chd(const std::string& src_path, const std::string& dst_path,
	const create_options& options, const progress_fn& progress)
{
	create_result result{};

	const std::string extension = path_extension(src_path);
	const bool cd_mode = (extension == ".cue" || extension == ".gdi");

	std::unique_ptr<image_source> source;
	std::vector<metadata_entry> metadata;

	if (cd_mode)
	{
		disc_toc toc;

		if (!parse_toc(src_path, toc, result.error))
		{
			return result;
		}

		source = open_cd_source(toc, result.error);

		if (!source)
		{
			return result;
		}

		metadata = build_cd_metadata(toc);
		chdboy_logi("Parsed %zu track(s) from '%s'", toc.tracks.size(), path_leaf(src_path).c_str());
	}
	else
	{
		source = open_raw_source(src_path, result.error);

		if (!source)
		{
			return result;
		}

		if (extension == ".iso")
		{
			// The tag chdman's createdvd writes. Its payload is a lone NUL --
			// it carries no fields, it just says what kind of image this is.
			metadata_entry entry;
			entry.tag = CHD_MAKE_TAG('D', 'V', 'D', ' ');
			entry.data = std::string(1, '\0');
			metadata.push_back(std::move(entry));
		}
	}

	// A CD image's hunk size is fixed by the format: the reader assumes eight
	// whole frames per hunk. Only a raw image gets a choice.
	const uint32_t unit_bytes = cd_mode ? cd_frame_size : raw_unit_bytes;
	const uint32_t hunk_bytes = cd_mode
		? cd_frames_per_hunk * cd_frame_size
		: (options.hunk_bytes ? options.hunk_bytes : raw_default_hunk_bytes);

	if (hunk_bytes == 0 || hunk_bytes % unit_bytes || hunk_bytes / unit_bytes > max_units_per_hunk)
	{
		result.error = format_string("Hunk size %u must be a multiple of %u and at most %u",
			hunk_bytes, unit_bytes, unit_bytes * max_units_per_hunk);
		return result;
	}

	const uint64_t logical_bytes = source->logical_bytes();

	if (logical_bytes == 0)
	{
		result.error = "The source image is empty";
		return result;
	}

	const uint64_t hunk_count_64 = (logical_bytes + hunk_bytes - 1) / hunk_bytes;

	if (hunk_count_64 > 0xffffffffull)
	{
		result.error = format_string("The source needs %llu hunks, more than the format allows",
			static_cast<unsigned long long>(hunk_count_64));
		return result;
	}

	const uint32_t hunk_count = static_cast<uint32_t>(hunk_count_64);
	const codec compressor = options.compressor;
	const int level = clamp_level(compressor, options.level);

	result.source_bytes = logical_bytes;

	file dst = file::open_write(dst_path);

	if (!dst)
	{
		result.error = "Could not create '" + path_leaf(dst_path) + "'";
		return result;
	}

	uint64_t write_offset = 0;

	const auto write_all = [&](const void* data, size_t size)
	{
		if (size == 0)
		{
			return true;
		}

		if (!dst.write_at(write_offset, data, size))
		{
			return false;
		}

		write_offset += size;
		return true;
	};

	// Reserve the header. It is rewritten at the end, once the hashes and the
	// map offset are known.
	std::array<uint8_t, chd_v5_header_size> header{};

	if (!write_all(header.data(), header.size()))
	{
		result.error = "Could not write to '" + path_leaf(dst_path) + "'";
		return result;
	}

	const uint32_t hardware_threads = std::max<uint32_t>(std::thread::hardware_concurrency(), 1);
	const uint32_t requested = options.worker_threads ? options.worker_threads : hardware_threads;
	const uint32_t worker_count = std::max<uint32_t>(requested, 1) - 1;
	const uint32_t batch_hunks = std::clamp<uint32_t>(hardware_threads * 8, 8, 256);

	hunk_compressor compressor_pool(compressor, level, hunk_bytes, cd_mode, worker_count);

	std::vector<hunk_job> jobs(batch_hunks);
	const size_t packed_capacity = compressor_pool.packed_capacity();

	for (hunk_job& job : jobs)
	{
		job.raw.resize(hunk_bytes);
		job.packed.resize(packed_capacity);
	}

	// The decoder rebuilds exactly this: 12 bytes per hunk, holding the entry
	// type, compressed length, file offset and hunk CRC. The map that gets
	// written is a bit-packed encoding of it, and its CRC covers this form.
	std::vector<uint8_t> raw_map(static_cast<size_t>(hunk_count) * 12);

	std::unordered_map<std::array<uint8_t, 20>, uint32_t, sha_hasher> seen_hunks;
	seen_hunks.reserve(std::min<uint32_t>(hunk_count, 1u << 20));

	chdboy_sha1 raw_sha1_ctx;
	chdboy_sha1_init(&raw_sha1_ctx);

	// Hunk payloads start immediately after the header and are laid out in
	// hunk order with no gaps: the decoder tracks a running offset rather than
	// storing one per entry, so anything else would desynchronise it.
	uint64_t data_offset = chd_v5_header_size;
	uint32_t max_length = 0;
	uint32_t max_self = 0;
	uint64_t done_bytes = 0;
	uint64_t duplicate_hunks = 0;
	uint64_t stored_hunks = 0;

	for (uint32_t base = 0; base < hunk_count; base += batch_hunks)
	{
		const uint32_t count = std::min<uint32_t>(batch_hunks, hunk_count - base);

		for (uint32_t i = 0; i < count; i++)
		{
			hunk_job& job = jobs[i];
			const uint64_t offset = static_cast<uint64_t>(base + i) * hunk_bytes;
			const uint64_t valid = std::min<uint64_t>(hunk_bytes, logical_bytes - offset);

			// The source zero-fills anything past the end of the image, which
			// both the CRC and the codec have to agree with the decoder about.
			if (!source->read_at(offset, job.raw.data(), hunk_bytes))
			{
				result.error = format_string("Could not read the source at offset %llu",
					static_cast<unsigned long long>(offset));
				return result;
			}

			chdboy_sha1_update(&raw_sha1_ctx, job.raw.data(), static_cast<size_t>(valid));
		}

		compressor_pool.run(jobs, count);

		for (uint32_t i = 0; i < count; i++)
		{
			const hunk_job& job = jobs[i];
			const uint32_t hunknum = base + i;
			uint8_t* entry = raw_map.data() + static_cast<size_t>(hunknum) * 12;

			if (const auto [it, inserted] = seen_hunks.try_emplace(job.sha, hunknum); !inserted)
			{
				// Identical to an earlier hunk, so it costs a map entry and
				// nothing else. Disc images are mostly padding, which makes
				// this by far the largest single saving.
				entry[0] = compression_self;
				put_be24(&entry[1], 0);
				put_be48(&entry[4], it->second);
				put_be16(&entry[10], 0);
				max_self = std::max(max_self, it->second);
				duplicate_hunks++;
			}
			else if (job.packed_size < hunk_bytes)
			{
				entry[0] = compression_type_0;
				put_be24(&entry[1], static_cast<uint32_t>(job.packed_size));
				put_be48(&entry[4], data_offset);
				put_be16(&entry[10], job.crc);

				if (!write_all(job.packed.data(), job.packed_size))
				{
					result.error = "Could not write to '" + path_leaf(dst_path) + "'";
					return result;
				}

				max_length = std::max<uint32_t>(max_length, static_cast<uint32_t>(job.packed_size));
				data_offset += job.packed_size;
			}
			else
			{
				// The decoder derives the length of an uncompressed hunk from
				// the hunk size, so it has to occupy exactly that much.
				entry[0] = compression_none;
				put_be24(&entry[1], hunk_bytes);
				put_be48(&entry[4], data_offset);
				put_be16(&entry[10], job.crc);

				if (!write_all(job.raw.data(), hunk_bytes))
				{
					result.error = "Could not write to '" + path_leaf(dst_path) + "'";
					return result;
				}

				data_offset += hunk_bytes;
				stored_hunks++;
			}
		}

		done_bytes = std::min<uint64_t>(static_cast<uint64_t>(base + count) * hunk_bytes, logical_bytes);

		if (progress && !progress(done_bytes, logical_bytes, write_offset))
		{
			result.cancelled = true;
			result.error = "Cancelled";
			return result;
		}
	}

	// Encode the map. The type stream comes first for every hunk, then the
	// per-entry payloads, because that is the order the decoder reads them in.
	const uint8_t length_bits = bits_for_value(max_length);
	const uint8_t self_bits = bits_for_value(max_self);

	std::vector<uint8_t> packed_map;
	packed_map.reserve(static_cast<size_t>(hunk_count) * 6 + 16);

	bit_writer bits(packed_map);
	write_flat_huffman_tree(bits);

	for (uint32_t i = 0; i < hunk_count; i++)
	{
		bits.write(raw_map[static_cast<size_t>(i) * 12], map_type_bits);
	}

	for (uint32_t i = 0; i < hunk_count; i++)
	{
		const uint8_t* entry = raw_map.data() + static_cast<size_t>(i) * 12;

		switch (entry[0])
		{
		case compression_type_0:
			bits.write(get_be24(&entry[1]), length_bits);
			bits.write(get_be16(&entry[10]), 16);
			break;

		case compression_none:
			bits.write(get_be16(&entry[10]), 16);
			break;

		case compression_self:
			bits.write(static_cast<uint32_t>(get_be48(&entry[4])), self_bits);
			break;

		default:
			result.error = "Internal error: unreachable map entry type";
			return result;
		}
	}

	bits.flush();

	// The decoder's bit reader refills a word at a time and flags an overflow
	// once it has run past the buffer, which it checks before decoding each
	// type. Pad so a legitimate read of the last entries cannot trip it.
	packed_map.insert(packed_map.end(), 8, uint8_t{ 0 });

	std::array<uint8_t, 16> map_header{};
	put_be32(&map_header[0], static_cast<uint32_t>(packed_map.size()));
	put_be48(&map_header[4], chd_v5_header_size);
	put_be16(&map_header[10], chd_crc16(raw_map.data(), raw_map.size()));
	map_header[12] = length_bits;
	map_header[13] = self_bits;
	map_header[14] = 0; // parentbits

	const uint64_t map_offset = data_offset;

	if (!write_all(map_header.data(), map_header.size()) || !write_all(packed_map.data(), packed_map.size()))
	{
		result.error = "Could not write to '" + path_leaf(dst_path) + "'";
		return result;
	}

	// Metadata entries form a singly linked list, each one pointing at where
	// the next begins, so their offsets have to be known before any is written.
	uint64_t meta_offset = 0;

	if (!metadata.empty())
	{
		meta_offset = write_offset;
		uint64_t entry_offset = meta_offset;

		for (size_t i = 0; i < metadata.size(); i++)
		{
			const metadata_entry& entry = metadata[i];
			const uint64_t next = entry_offset + metadata_header_size + entry.data.size();

			std::array<uint8_t, metadata_header_size> entry_header{};
			put_be32(&entry_header[0], entry.tag);
			entry_header[4] = entry.flags;
			put_be24(&entry_header[5], static_cast<uint32_t>(entry.data.size()));
			put_be64(&entry_header[8], (i + 1 < metadata.size()) ? next : 0);

			if (!write_all(entry_header.data(), entry_header.size()) ||
				!write_all(entry.data.data(), entry.data.size()))
			{
				result.error = "Could not write to '" + path_leaf(dst_path) + "'";
				return result;
			}

			entry_offset = next;
		}
	}

	std::array<uint8_t, 20> raw_sha1{};
	chdboy_sha1_final(&raw_sha1_ctx, raw_sha1.data());

	std::array<uint8_t, 20> overall_sha1{};
	compute_overall_sha1(raw_sha1, metadata, overall_sha1.data());

	const uint32_t codec_tag = cd_mode
		? (compressor == codec::zstd ? CHD_CODEC_CD_ZSTD : CHD_CODEC_CD_ZLIB)
		: (compressor == codec::zstd ? CHD_CODEC_ZSTD : CHD_CODEC_ZLIB);

	std::memcpy(&header[0], "MComprHD", 8);
	put_be32(&header[8], chd_v5_header_size);
	put_be32(&header[12], 5);
	put_be32(&header[16], codec_tag);
	put_be32(&header[20], CHD_CODEC_NONE);
	put_be32(&header[24], CHD_CODEC_NONE);
	put_be32(&header[28], CHD_CODEC_NONE);
	put_be64(&header[32], logical_bytes);
	put_be64(&header[40], map_offset);
	put_be64(&header[48], meta_offset);
	put_be32(&header[56], hunk_bytes);
	put_be32(&header[60], unit_bytes);
	std::memcpy(&header[64], raw_sha1.data(), raw_sha1.size());
	std::memcpy(&header[84], overall_sha1.data(), overall_sha1.size());

	if (!dst.write_at(0, header.data(), header.size()))
	{
		result.error = "Could not finalize '" + path_leaf(dst_path) + "'";
		return result;
	}

	result.success = true;
	result.output_bytes = write_offset;

	chdboy_logi("Converted '%s' to '%s': %llu -> %llu bytes (%.1f%%), %u hunks, %llu duplicate, %llu stored",
		path_leaf(src_path).c_str(), path_leaf(dst_path).c_str(),
		static_cast<unsigned long long>(logical_bytes), static_cast<unsigned long long>(result.output_bytes),
		logical_bytes ? (result.output_bytes * 100.0 / logical_bytes) : 0.0,
		hunk_count, static_cast<unsigned long long>(duplicate_hunks), static_cast<unsigned long long>(stored_hunks));

	return result;
}
}
