#include "chdboy/toc.h"

#include "chdboy/io.h"
#include "chdboy/log.h"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <cstring>

// CUE and GDI parsing, following MAME's cdrom.cpp parse_cue/parse_gdi.
//
// These are deliberately literal ports rather than a tidier reimplementation.
// The numbers they produce -- where a track starts in its bin, how many frames
// it holds, how much padding sits between tracks -- are what the CHD's track
// metadata will claim, and a reader trusts that claim absolutely. Anything
// that disagrees with MAME by one frame yields a disc that mounts and then
// desynchronises partway through, which is far worse than failing outright.

namespace chdboy
{
namespace
{
// One line of a sheet, handed out token by token. Quoted tokens keep their
// spaces, which is the whole reason this is not just strtok.
class tokenizer
{
	const std::string& m_line;
	size_t m_pos = 0;

public:
	explicit tokenizer(const std::string& line)
		: m_line(line)
	{
	}

	std::string next()
	{
		while (m_pos < m_line.size() && std::isspace(static_cast<unsigned char>(m_line[m_pos])))
		{
			m_pos++;
		}

		if (m_pos >= m_line.size())
		{
			return {};
		}

		std::string token;

		if (m_line[m_pos] == '"')
		{
			m_pos++;

			while (m_pos < m_line.size() && m_line[m_pos] != '"')
			{
				token.push_back(m_line[m_pos++]);
			}

			// Skip the closing quote if it is there. An unterminated quote is
			// tolerated the way MAME tolerates it: the rest of the line
			// becomes the token.
			if (m_pos < m_line.size())
			{
				m_pos++;
			}
		}
		else
		{
			while (m_pos < m_line.size() && !std::isspace(static_cast<unsigned char>(m_line[m_pos])))
			{
				token.push_back(m_line[m_pos++]);
			}
		}

		return token;
	}

	// What is left of the line, leading whitespace removed. REM sub-commands
	// are matched against this.
	std::string rest() const
	{
		size_t pos = m_pos;

		while (pos < m_line.size() && std::isspace(static_cast<unsigned char>(m_line[pos])))
		{
			pos++;
		}

		return m_line.substr(pos);
	}
};

bool read_text_file(const std::string& path, std::vector<std::string>& lines, std::string& error)
{
	file handle = file::open_read(path);

	if (!handle)
	{
		error = "Could not open '" + path_leaf(path) + "'";
		return false;
	}

	const uint64_t size = handle.size();

	// A sheet is a few kilobytes. Anything of this order is not one, and
	// reading it whole is what lets a bound descriptor be parsed at all.
	if (size > 4u * 1024 * 1024)
	{
		error = "'" + path_leaf(path) + "' is too large to be a track sheet";
		return false;
	}

	std::string text(static_cast<size_t>(size), '\0');

	if (size && !handle.read_at(0, text.data(), text.size()))
	{
		error = "Could not read '" + path_leaf(path) + "'";
		return false;
	}

	std::string current;

	for (const char c : text)
	{
		if (c == '\n')
		{
			lines.push_back(current);
			current.clear();
		}
		else if (c != '\r')
		{
			current.push_back(c);
		}
	}

	if (!current.empty())
	{
		lines.push_back(current);
	}

	return true;
}

// "m:s:f" into frames, at 75 frames per second. A bare number is already a
// frame count, which is how MAME reads it.
int msf_to_frames(const std::string& token)
{
	int m = 0;
	int s = 0;
	int f = 0;

	if (std::sscanf(token.c_str(), "%d:%d:%d", &m, &s, &f) == 1)
	{
		return m;
	}

	s += m * 60;
	f += s * 75;
	return f;
}

std::string upper(std::string value)
{
	std::transform(value.begin(), value.end(), value.begin(),
		[](unsigned char c) { return static_cast<char>(std::toupper(c)); });
	return value;
}

// Track type as spelled in a cue sheet, to (type, bytes per sector in file).
bool type_from_string(const std::string& name, uint32_t& track_type, uint32_t& data_size)
{
	struct entry
	{
		const char* name;
		uint32_t type;
		uint32_t size;
	};

	static constexpr entry table[] = {
		{ "MODE1", cd_track_mode1, 2048 },
		{ "MODE1/2048", cd_track_mode1, 2048 },
		{ "MODE1_RAW", cd_track_mode1_raw, 2352 },
		{ "MODE1/2352", cd_track_mode1_raw, 2352 },
		{ "MODE2", cd_track_mode2, 2336 },
		{ "MODE2/2336", cd_track_mode2, 2336 },
		{ "MODE2_FORM1", cd_track_mode2_form1, 2048 },
		{ "MODE2/2048", cd_track_mode2_form1, 2048 },
		{ "MODE2_FORM2", cd_track_mode2_form2, 2324 },
		{ "MODE2/2324", cd_track_mode2_form2, 2324 },
		{ "MODE2_FORM_MIX", cd_track_mode2_form_mix, 2336 },
		{ "MODE2_RAW", cd_track_mode2_raw, 2352 },
		{ "MODE2/2352", cd_track_mode2_raw, 2352 },
		{ "CDI/2352", cd_track_mode2_raw, 2352 },
		{ "AUDIO", cd_track_audio, 2352 },
	};

	for (const entry& item : table)
	{
		if (name == item.name)
		{
			track_type = item.type;
			data_size = item.size;
			return true;
		}
	}

	return false;
}

void subtype_from_string(const std::string& name, track_info& track)
{
	if (name == "RW")
	{
		track.sub_type = cd_sub_normal;
		track.sub_size = 96;
	}
	else if (name == "RW_RAW")
	{
		track.sub_type = cd_sub_raw;
		track.sub_size = 96;
	}
}

// Per-track state a cue sheet carries that the finished TOC does not.
struct cue_track_state
{
	int index0 = -1;
	int index1 = -1;
};
}

const char* track_type_string(uint32_t track_type)
{
	switch (track_type)
	{
	case cd_track_mode1: return "MODE1";
	case cd_track_mode1_raw: return "MODE1_RAW";
	case cd_track_mode2: return "MODE2";
	case cd_track_mode2_form1: return "MODE2_FORM1";
	case cd_track_mode2_form2: return "MODE2_FORM2";
	case cd_track_mode2_form_mix: return "MODE2_FORM_MIX";
	case cd_track_mode2_raw: return "MODE2_RAW";
	case cd_track_audio: return "AUDIO";
	default: return "UNKNOWN";
	}
}

const char* sub_type_string(uint32_t sub_type)
{
	switch (sub_type)
	{
	case cd_sub_normal: return "RW";
	case cd_sub_raw: return "RW_RAW";
	default: return "NONE";
	}
}

bool parse_cue(const std::string& path, disc_toc& out, std::string& error)
{
	std::vector<std::string> lines;

	if (!read_text_file(path, lines, error))
	{
		return false;
	}

	const std::string directory = path_directory(path);

	out = disc_toc{};

	std::vector<cue_track_state> state;
	std::string last_file;
	int track_index = -1;
	bool multisession = false;

	const auto ensure_track = [&](int index) -> bool
	{
		if (index < 0 || index >= static_cast<int>(cd_max_tracks))
		{
			return false;
		}

		if (static_cast<int>(out.tracks.size()) <= index)
		{
			out.tracks.resize(index + 1);
			state.resize(index + 1);
		}

		return true;
	};

	for (const std::string& line : lines)
	{
		tokenizer tokens(line);
		const std::string command = upper(tokens.next());

		if (command.empty())
		{
			continue;
		}

		if (command == "REM")
		{
			const std::string remainder = upper(tokens.rest());

			// Redump's Dreamcast multi-cue describes two areas of one disc and
			// needs the pregap-stripping and LBA-45000 relocation MAME does
			// for it. Producing a plain CD layout from it would yield a disc
			// that looks fine and boots nothing, so say so instead.
			if (remainder.rfind("HIGH-DENSITY AREA", 0) == 0 || remainder.rfind("SINGLE-DENSITY AREA", 0) == 0)
			{
				error = "This is a Dreamcast multi-area .cue; convert the .gdi instead";
				return false;
			}

			if (remainder.rfind("SESSION", 0) == 0)
			{
				tokens.next();
				const std::string number = tokens.next();

				if (std::strtol(number.c_str(), nullptr, 10) > 1)
				{
					multisession = true;
				}
			}

			continue;
		}

		if (command == "FILE")
		{
			const std::string name = tokens.next();
			const std::string type = upper(tokens.next());

			last_file = directory + name;

			if (!ensure_track(track_index + 1))
			{
				error = "Too many tracks in the cue sheet";
				return false;
			}

			if (type == "BINARY")
			{
				out.tracks[track_index + 1].swap = false;
			}
			else if (type == "MOTOROLA")
			{
				out.tracks[track_index + 1].swap = true;
			}
			else
			{
				error = "Unsupported cue FILE type '" + type + "'; only BINARY and MOTOROLA are handled";
				return false;
			}

			continue;
		}

		if (command == "TRACK")
		{
			const std::string number = tokens.next();
			const std::string type = upper(tokens.next());

			track_index = static_cast<int>(std::strtol(number.c_str(), nullptr, 10)) - 1;

			if (!ensure_track(track_index))
			{
				error = "Cue sheet declares an out-of-range track number";
				return false;
			}

			track_info& track = out.tracks[track_index];

			track.sub_type = cd_sub_none;
			track.sub_size = 0;
			track.pregap_sub_type = cd_sub_none;
			track.pregap = 0;
			track.pad_frames = 0;
			track.data_size = 0;
			track.file_offset = 0;
			track.file = last_file;

			state[track_index] = cue_track_state{};

			if (!type_from_string(type, track.track_type, track.data_size))
			{
				error = "Unknown cue track type '" + type + "'";
				return false;
			}

			subtype_from_string(upper(tokens.next()), track);
			continue;
		}

		if (track_index < 0)
		{
			continue;
		}

		if (command == "INDEX")
		{
			const int index = static_cast<int>(std::strtol(tokens.next().c_str(), nullptr, 10));
			const int frames = msf_to_frames(tokens.next());

			track_info& track = out.tracks[track_index];
			cue_track_state& info = state[track_index];

			if (index == 0)
			{
				info.index0 = frames;
			}
			else if (index == 1)
			{
				info.index1 = frames;

				if (track.pregap == 0 && info.index0 != -1)
				{
					// The pregap's sectors are physically in the file, between
					// index 0 and index 1, so the metadata records its type as
					// present rather than merely declared.
					track.pregap = static_cast<uint32_t>(frames - info.index0);
					track.pregap_type = track.track_type;
					track.pregap_data_size = track.data_size;
				}
				else if (info.index0 == -1)
				{
					// No index 0, so track length is measured from index 1.
					info.index0 = frames;
				}
			}

			continue;
		}

		if (command == "PREGAP")
		{
			out.tracks[track_index].pregap = static_cast<uint32_t>(msf_to_frames(tokens.next()));
			continue;
		}

		if (command == "POSTGAP")
		{
			out.tracks[track_index].postgap = static_cast<uint32_t>(msf_to_frames(tokens.next()));
			continue;
		}

		// FLAGS, CATALOG, ISRC, TITLE, PERFORMER and friends carry nothing the
		// CHD track metadata can express, so they are skipped rather than
		// rejected -- rejecting them would turn most real-world sheets away.
	}

	if (out.tracks.empty())
	{
		error = "The cue sheet declares no tracks";
		return false;
	}

	if (multisession)
	{
		// MAME trims track lengths against REM LEAD-OUT/LEAD-IN times here.
		// Without that the layout is still the ordinary one, which is right
		// for every sheet that does not carry those tags.
		chdboy_logw("Multi-session cue: session lead-in/lead-out timing is not applied");
	}

	// Second pass: track lengths and file offsets, in MAME's three cases.
	for (size_t i = 0; i < out.tracks.size(); i++)
	{
		track_info& track = out.tracks[i];
		cue_track_state& info = state[i];

		if (track.data_size == 0)
		{
			error = "Cue sheet is missing track " + std::to_string(i + 1);
			return false;
		}

		if (info.index1 == -1)
		{
			error = "Track " + std::to_string(i + 1) + " has no INDEX 01 marker";
			return false;
		}

		// True for cue/bin and cue/iso alike: CDDA sits in the CHD in the
		// opposite byte order to the file, whatever the FILE line said.
		if (track.track_type == cd_track_audio)
		{
			track.swap = true;
		}

		const uint32_t stride = track.data_size + track.sub_size;

		if (i + 1 >= out.tracks.size() && i > 0 && track.file == out.tracks[i - 1].file)
		{
			// Last track, sharing its predecessor's file: it runs to the end.
			const uint64_t total = file_size(track.file);

			if (total == 0)
			{
				error = "Could not find '" + path_leaf(track.file) + "'";
				return false;
			}

			const track_info& previous = out.tracks[i - 1];
			track.file_offset = previous.file_offset +
				static_cast<uint64_t>(previous.frames) * (previous.data_size + previous.sub_size);

			if (total <= track.file_offset)
			{
				error = "'" + path_leaf(track.file) + "' is shorter than its cue sheet claims";
				return false;
			}

			track.frames = static_cast<uint32_t>((total - track.file_offset) / stride);
		}
		else if (i + 1 < out.tracks.size() && track.file == out.tracks[i + 1].file)
		{
			// Shares a file with the next track, so the next track's start is
			// this one's end.
			const int length = state[i + 1].index0 - info.index0;

			if (length <= 0)
			{
				error = "Could not size track " + std::to_string(i + 1) + "; check its INDEX markers";
				return false;
			}

			track.frames = static_cast<uint32_t>(length);

			if (i > 0)
			{
				const track_info& previous = out.tracks[i - 1];
				track.file_offset = previous.file_offset +
					static_cast<uint64_t>(previous.frames) * (previous.data_size + previous.sub_size);
			}
		}
		else if (track.frames == 0)
		{
			// A file of its own.
			const uint64_t total = file_size(track.file);

			if (total == 0)
			{
				error = "Could not find '" + path_leaf(track.file) + "'";
				return false;
			}

			track.frames = static_cast<uint32_t>(total / stride);
			track.file_offset = 0;
		}

		if (track.frames == 0)
		{
			error = "Track " + std::to_string(i + 1) + " is empty";
			return false;
		}
	}

	return true;
}

bool parse_gdi(const std::string& path, disc_toc& out, std::string& error)
{
	std::vector<std::string> lines;

	if (!read_text_file(path, lines, error))
	{
		return false;
	}

	const std::string directory = path_directory(path);

	out = disc_toc{};
	out.gdrom = true;

	if (lines.empty())
	{
		error = "The .gdi file is empty";
		return false;
	}

	size_t line_index = 0;

	// First line is the track count.
	const long declared = std::strtol(lines[line_index++].c_str(), nullptr, 10);

	if (declared <= 0 || declared > static_cast<long>(cd_max_tracks))
	{
		error = "The .gdi file does not start with a valid track count";
		return false;
	}

	out.tracks.resize(static_cast<size_t>(declared));

	// physical LBA per track, used only to derive the padding between tracks.
	std::vector<uint32_t> lba(out.tracks.size(), 0);
	std::vector<bool> seen(out.tracks.size(), false);

	for (; line_index < lines.size(); line_index++)
	{
		tokenizer tokens(lines[line_index]);
		const std::string number = tokens.next();

		if (number.empty())
		{
			continue;
		}

		const int index = static_cast<int>(std::strtol(number.c_str(), nullptr, 10)) - 1;

		if (index < 0 || index >= static_cast<int>(out.tracks.size()))
		{
			error = "The .gdi file declares a track outside 1.." + std::to_string(out.tracks.size());
			return false;
		}

		track_info& track = out.tracks[index];

		track.sub_type = cd_sub_none;
		track.sub_size = 0;
		track.pregap_sub_type = cd_sub_none;
		track.file_offset = 0;
		track.swap = false;

		lba[index] = static_cast<uint32_t>(std::strtoul(tokens.next().c_str(), nullptr, 10));

		const long type = std::strtol(tokens.next().c_str(), nullptr, 10);
		const long sector_size = std::strtol(tokens.next().c_str(), nullptr, 10);

		if (type == 4 && sector_size == 2352)
		{
			track.track_type = cd_track_mode1_raw;
			track.data_size = 2352;
		}
		else if (type == 4 && sector_size == 2048)
		{
			track.track_type = cd_track_mode1;
			track.data_size = 2048;
		}
		else if (type == 0)
		{
			track.track_type = cd_track_audio;
			track.data_size = 2352;

			// GD-ROM audio is stored byte-swapped, same as a cue's CDDA.
			track.swap = true;
		}
		else
		{
			error = "Unsupported .gdi track type " + std::to_string(type) +
				" with sector size " + std::to_string(sector_size);
			return false;
		}

		const std::string name = tokens.next();

		if (name.empty())
		{
			error = "Track " + std::to_string(index + 1) + " in the .gdi has no filename";
			return false;
		}

		track.file = directory + name;

		const uint64_t total = file_size(track.file);

		if (total == 0)
		{
			error = "Could not find '" + name + "'";
			return false;
		}

		track.frames = static_cast<uint32_t>(total / static_cast<uint64_t>(sector_size));
		track.pad_frames = 0;
		seen[index] = true;

		// The gap between where the previous track ended and where this one
		// starts is padding, and it belongs to the PREVIOUS track: its frame
		// count grows to cover it and the extra frames are written as zeroes.
		if (index > 0 && seen[index - 1])
		{
			track_info& previous = out.tracks[index - 1];
			const int64_t gap = static_cast<int64_t>(lba[index]) -
				(static_cast<int64_t>(previous.frames) + static_cast<int64_t>(lba[index - 1]));

			if (gap > 0)
			{
				previous.frames += static_cast<uint32_t>(gap);
				previous.pad_frames = static_cast<uint32_t>(gap);
			}
		}
	}

	for (size_t i = 0; i < out.tracks.size(); i++)
	{
		if (!seen[i])
		{
			error = "The .gdi file is missing track " + std::to_string(i + 1);
			return false;
		}
	}

	return true;
}

bool parse_toc(const std::string& path, disc_toc& out, std::string& error)
{
	const std::string extension = path_extension(path);
	bool ok = false;

	if (extension == ".cue")
	{
		ok = parse_cue(path, out, error);
	}
	else if (extension == ".gdi")
	{
		ok = parse_gdi(path, out, error);
	}
	else
	{
		error = "'" + extension + "' is not a track sheet";
		return false;
	}

	if (!ok)
	{
		return false;
	}

	// Every track starts on a multiple of cd_track_padding frames. MAME leaves
	// these out of the metadata -- they are not part of the track -- but they
	// do occupy frames in the image, so both the layout and the reader's idea
	// of where the next track begins depend on them.
	for (track_info& track : out.tracks)
	{
		const uint32_t padded = (track.frames + cd_track_padding - 1) / cd_track_padding;
		track.extra_frames = padded * cd_track_padding - track.frames;
	}

	return true;
}
}
