#pragma once

#include <string>

namespace chdboy
{
// Descriptors bound to synthetic paths, so everything below this layer can
// take an ordinary path.
//
// Nothing the app converts is reachable by name: the user picks documents
// through SAF, which hands back a content:// URI that only resolves to a file
// descriptor, and the app holds no storage permission that would let it open
// the real path even if it could name one. Rather than thread descriptors
// through the parsers and the writer, a descriptor is bound to a made-up path
// and file::open_read/open_write recognise it.
//
// Bindings are grouped into a session, and a session is a directory. That is
// what makes a .cue work: the parser resolves `FILE "Game.bin"` against the
// directory of the .cue, so binding the cue as /chdboy/7/Game.cue and its bin
// as /chdboy/7/Game.bin means the ordinary sibling-path logic finds it with no
// special case. The Kotlin side is responsible for binding every sidecar a
// sheet references before starting the conversion.
//
// A bound descriptor is OWNED here: close_session closes it. That matches the
// detachFd() on the Kotlin side, which gives up ownership at the boundary.

// Opens a new, empty session. Returns its id.
int open_session();

// Binds fd into session under `name`, and returns the path it is now
// reachable at. Returns an empty string if the session does not exist, in
// which case fd is left alone for the caller to close.
std::string bind_fd(int session, int fd, const std::string& name);

// Closes every descriptor in the session and forgets it.
void close_session(int session);

// The descriptor bound to `path`, or -1 if it is an ordinary filesystem path.
// The returned descriptor stays owned by the session.
int lookup_fd(const std::string& path);

// Whether `path` lives in the bound namespace at all. Distinguishes "bound,
// but this name was never registered" (an error the caller should report)
// from "an ordinary path" (which should just be opened).
bool is_bound_path(const std::string& path);
}
