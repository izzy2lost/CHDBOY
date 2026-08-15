#include "chdboy/io.h"

#include "chdboy/fd_registry.h"
#include "chdboy/log.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstring>
#include <utility>

namespace chdboy
{
namespace
{
// pread/pwrite take a size_t but the kernel caps a single transfer well below
// that, and a partial result is not an error. Everything here loops.
bool read_exact(int fd, uint64_t offset, void* dst, size_t length)
{
	uint8_t* out = static_cast<uint8_t*>(dst);

	while (length)
	{
		const ssize_t got = ::pread(fd, out, length, static_cast<off_t>(offset));

		if (got < 0)
		{
			if (errno == EINTR)
			{
				continue;
			}

			return false;
		}

		if (got == 0)
		{
			// End of file with bytes still wanted. The caller asked for a
			// range it had already established was there, so this is a
			// genuine failure rather than a short read to retry.
			return false;
		}

		out += got;
		offset += static_cast<uint64_t>(got);
		length -= static_cast<size_t>(got);
	}

	return true;
}

bool write_exact(int fd, uint64_t offset, const void* src, size_t length)
{
	const uint8_t* in = static_cast<const uint8_t*>(src);

	while (length)
	{
		const ssize_t put = ::pwrite(fd, in, length, static_cast<off_t>(offset));

		if (put <= 0)
		{
			if (put < 0 && errno == EINTR)
			{
				continue;
			}

			return false;
		}

		in += put;
		offset += static_cast<uint64_t>(put);
		length -= static_cast<size_t>(put);
	}

	return true;
}
}

file::~file()
{
	if (m_owned && m_fd >= 0)
	{
		::close(m_fd);
	}
}

file::file(file&& other) noexcept
	: m_fd(other.m_fd)
	, m_owned(other.m_owned)
{
	other.m_fd = -1;
	other.m_owned = false;
}

file& file::operator=(file&& other) noexcept
{
	if (this != &other)
	{
		if (m_owned && m_fd >= 0)
		{
			::close(m_fd);
		}

		m_fd = std::exchange(other.m_fd, -1);
		m_owned = std::exchange(other.m_owned, false);
	}

	return *this;
}

file file::open_read(const std::string& path)
{
	file result;

	if (const int bound = lookup_fd(path); bound >= 0)
	{
		result.m_fd = bound;
		result.m_owned = false;
		return result;
	}

	if (is_bound_path(path))
	{
		// Inside the synthetic namespace but never registered. Almost always a
		// .cue naming a sidecar the Kotlin side did not bind, so say which.
		chdboy_loge("No descriptor bound for '%s'", path.c_str());
		return result;
	}

	const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);

	if (fd < 0)
	{
		return result;
	}

	result.m_fd = fd;
	result.m_owned = true;
	return result;
}

file file::open_write(const std::string& path)
{
	file result;

	if (const int bound = lookup_fd(path); bound >= 0)
	{
		// Nothing here creates the file -- SAF already did, which is where the
		// descriptor came from -- but it may hold a previous attempt, and the
		// writer assumes it starts empty.
		if (::ftruncate(bound, 0) != 0)
		{
			chdboy_loge("Could not truncate '%s': %s", path.c_str(), std::strerror(errno));
			return result;
		}

		result.m_fd = bound;
		result.m_owned = false;
		return result;
	}

	if (is_bound_path(path))
	{
		chdboy_loge("No descriptor bound for '%s'", path.c_str());
		return result;
	}

	const int fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);

	if (fd < 0)
	{
		return result;
	}

	result.m_fd = fd;
	result.m_owned = true;
	return result;
}

uint64_t file::size() const
{
	if (m_fd < 0)
	{
		return 0;
	}

	struct stat info{};

	if (::fstat(m_fd, &info) != 0)
	{
		return 0;
	}

	return static_cast<uint64_t>(info.st_size);
}

bool file::read_at(uint64_t offset, void* dst, size_t length) const
{
	return m_fd >= 0 && (length == 0 || read_exact(m_fd, offset, dst, length));
}

bool file::write_at(uint64_t offset, const void* src, size_t length) const
{
	return m_fd >= 0 && (length == 0 || write_exact(m_fd, offset, src, length));
}

uint64_t file_size(const std::string& path)
{
	if (const int bound = lookup_fd(path); bound >= 0)
	{
		struct stat info{};
		return ::fstat(bound, &info) == 0 ? static_cast<uint64_t>(info.st_size) : 0;
	}

	if (is_bound_path(path))
	{
		return 0;
	}

	struct stat info{};
	return ::stat(path.c_str(), &info) == 0 ? static_cast<uint64_t>(info.st_size) : 0;
}

bool file_exists(const std::string& path)
{
	if (lookup_fd(path) >= 0)
	{
		return true;
	}

	if (is_bound_path(path))
	{
		return false;
	}

	return ::access(path.c_str(), R_OK) == 0;
}

std::string path_directory(const std::string& path)
{
	const size_t slash = path.find_last_of('/');
	return slash == std::string::npos ? std::string() : path.substr(0, slash + 1);
}

std::string path_leaf(const std::string& path)
{
	const size_t slash = path.find_last_of('/');
	return slash == std::string::npos ? path : path.substr(slash + 1);
}

std::string path_extension(const std::string& path)
{
	const std::string leaf = path_leaf(path);
	const size_t dot = leaf.find_last_of('.');

	if (dot == std::string::npos)
	{
		return {};
	}

	std::string extension = leaf.substr(dot);
	std::transform(extension.begin(), extension.end(), extension.begin(),
		[](unsigned char c) { return static_cast<char>(std::tolower(c)); });

	return extension;
}
}
