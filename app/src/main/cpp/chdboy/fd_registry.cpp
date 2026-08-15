#include "chdboy/fd_registry.h"

#include "chdboy/log.h"

#include <unistd.h>

#include <map>
#include <mutex>
#include <string>
#include <unordered_map>

namespace chdboy
{
namespace
{
// Every bound path starts with this, which is also how is_bound_path tells a
// synthetic path from a real one. It is deliberately not a directory that can
// exist on Android, so a real file can never collide with a binding.
constexpr const char* bound_prefix = "/chdboy-saf/";

struct registry
{
	std::mutex mutex;
	int next_session = 1;
	std::map<int, std::unordered_map<std::string, int>> sessions;
};

registry& get()
{
	static registry instance;
	return instance;
}

std::string session_dir(int session)
{
	return std::string(bound_prefix) + std::to_string(session) + "/";
}
}

int open_session()
{
	registry& reg = get();
	std::lock_guard<std::mutex> lock(reg.mutex);

	const int session = reg.next_session++;
	reg.sessions[session];
	return session;
}

std::string bind_fd(int session, int fd, const std::string& name)
{
	registry& reg = get();
	std::lock_guard<std::mutex> lock(reg.mutex);

	const auto it = reg.sessions.find(session);

	if (it == reg.sessions.end())
	{
		chdboy_loge("bind_fd: no such session %d", session);
		return {};
	}

	// Only the last component is used. A name that arrived with a path on it
	// -- SAF display names can contain almost anything -- would otherwise
	// produce a binding no sibling lookup could ever reproduce.
	const size_t slash = name.find_last_of('/');
	const std::string leaf = (slash == std::string::npos) ? name : name.substr(slash + 1);

	if (leaf.empty())
	{
		chdboy_loge("bind_fd: empty name");
		return {};
	}

	const std::string path = session_dir(session) + leaf;

	// Rebinding a name closes whatever was there, so a caller that binds the
	// same sidecar twice does not leak the first descriptor.
	auto& bindings = it->second;

	if (const auto existing = bindings.find(leaf); existing != bindings.end())
	{
		::close(existing->second);
		existing->second = fd;
	}
	else
	{
		bindings.emplace(leaf, fd);
	}

	return path;
}

void close_session(int session)
{
	registry& reg = get();
	std::unordered_map<std::string, int> bindings;

	{
		std::lock_guard<std::mutex> lock(reg.mutex);
		const auto it = reg.sessions.find(session);

		if (it == reg.sessions.end())
		{
			return;
		}

		bindings.swap(it->second);
		reg.sessions.erase(it);
	}

	for (const auto& [name, fd] : bindings)
	{
		::close(fd);
	}
}

int lookup_fd(const std::string& path)
{
	if (!is_bound_path(path))
	{
		return -1;
	}

	const size_t prefix_length = std::char_traits<char>::length(bound_prefix);
	const size_t slash = path.find('/', prefix_length);

	if (slash == std::string::npos)
	{
		return -1;
	}

	int session = 0;

	try
	{
		session = std::stoi(path.substr(prefix_length, slash - prefix_length));
	}
	catch (...)
	{
		return -1;
	}

	registry& reg = get();
	std::lock_guard<std::mutex> lock(reg.mutex);

	const auto it = reg.sessions.find(session);

	if (it == reg.sessions.end())
	{
		return -1;
	}

	const auto binding = it->second.find(path.substr(slash + 1));
	return binding == it->second.end() ? -1 : binding->second;
}

bool is_bound_path(const std::string& path)
{
	return path.rfind(bound_prefix, 0) == 0;
}
}
