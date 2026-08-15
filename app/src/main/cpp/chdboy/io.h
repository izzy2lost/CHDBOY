#pragma once

#include <cstdint>
#include <string>

namespace chdboy
{
// A file, opened either from a real path or from a descriptor bound through
// fd_registry. Reads and writes are always at an explicit offset: the writer
// runs its compression workers off one handle and rewrites the header at the
// end, and a shared file position between those would be a race with no
// symptom other than a corrupt output.
class file
{
public:
	file() = default;
	~file();

	file(file&& other) noexcept;
	file& operator=(file&& other) noexcept;

	file(const file&) = delete;
	file& operator=(const file&) = delete;

	// Opens for reading. Fails if the path is neither bound nor openable.
	static file open_read(const std::string& path);

	// Opens for writing, truncating to empty. A bound descriptor is truncated
	// in place -- the document behind it already exists, since SAF created it
	// before handing the descriptor over, and the caller is entitled to assume
	// it starts from nothing.
	static file open_write(const std::string& path);

	explicit operator bool() const { return m_fd >= 0; }

	// Size in bytes, or 0 if it cannot be determined.
	uint64_t size() const;

	// Exact transfers: a short read or write is reported as failure, since at
	// this layer every one of them has a length the caller already knows.
	bool read_at(uint64_t offset, void* dst, size_t length) const;
	bool write_at(uint64_t offset, const void* src, size_t length) const;

private:
	int m_fd = -1;

	// False for a bound descriptor, whose lifetime belongs to its session.
	bool m_owned = false;
};

// Size of a file without opening it for a transfer. Used by the TOC parsers,
// which derive track lengths from the size of each referenced image.
uint64_t file_size(const std::string& path);

// True if `path` names something that can be opened for reading.
bool file_exists(const std::string& path);

// The directory part of `path`, including the trailing separator, or "" if it
// has none. Works for bound paths too, which is what lets a .cue resolve its
// .bin siblings inside the same session.
std::string path_directory(const std::string& path);

// The last component of `path`.
std::string path_leaf(const std::string& path);

// Lowercased extension including the dot, e.g. ".cue". Empty if there is none.
std::string path_extension(const std::string& path);
}
