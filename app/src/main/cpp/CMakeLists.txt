cmake_minimum_required(VERSION 3.22)

project(chdboy C CXX)

# The converter is a from-scratch CHD v5 writer (chdboy/chd_write.cpp), not a
# wrapper around MAME's chdman. libchdr is here as the *decoder* half: it owns
# the ECC routines the CD codec needs, and it is the same reader DuckStation
# and Flycast use, so anything this writes is checked against the code that
# will actually read it.

set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Gradle drives this file, so the build type comes from the Gradle variant.
# Pin the optimisation level on our own targets regardless: a debug variant of
# the app must not turn a 40 GB conversion into an overnight job.
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# ---------------------------------------------------------------------------
# zstd
#
# libchdr vendors zstd too, but what it builds is zstddeclib -- the decoder
# only, which is all a reader needs and none of what a writer does. Building
# the full library under the target name `zstd` means libchdr's own
# `if(NOT TARGET zstd)` finds this one and skips its copy, so exactly one zstd
# ends up on the link line instead of two that differ in what they export.
# ---------------------------------------------------------------------------
file(GLOB ZSTD_SOURCES
    "zstd/lib/common/*.c"
    "zstd/lib/compress/*.c"
    "zstd/lib/decompress/*.c"
)

add_library(zstd STATIC ${ZSTD_SOURCES})
target_include_directories(zstd PUBLIC "zstd/lib")
# ZSTD_DISABLE_ASM: the decoder ships a hand-written x86-64 Huffman loop that
# has no AArch64 equivalent, and the .S is not in the glob above anyway.
target_compile_definitions(zstd PRIVATE ZSTD_DISABLE_ASM XXH_NAMESPACE=ZSTD_)
target_compile_options(zstd PRIVATE -O3 -fvisibility=hidden)

# ---------------------------------------------------------------------------
# libchdr
# ---------------------------------------------------------------------------
# Static only: BUILD_SHARED_LIBS is read by libchdr's own CMakeLists, and a
# libchdr.so would be a second copy of the decoder to package for nothing.
set(BUILD_SHARED_LIBS OFF)

# zlib lives in the NDK sysroot, so libchdr should link that rather than build
# its bundled miniz -- the writer's Deflate path uses the same one.
set(WITH_SYSTEM_ZLIB ON CACHE BOOL "" FORCE)

# Both default to ON upstream; set them explicitly because the CD codec below
# depends on the ECC routines existing (RAW_DATA_SECTOR) and on the decoder
# splitting subcode out of each frame the way the encoder puts it in.
set(CHDR_WANT_RAW_DATA_SECTOR ON CACHE BOOL "" FORCE)
set(CHDR_WANT_SUBCODE ON CACHE BOOL "" FORCE)

# Upstream defaults this ON; its test and fuzzer targets have no place in an
# APK build.
set(CHDR_WANT_TESTS OFF CACHE BOOL "" FORCE)

add_subdirectory(libchdr EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------------------
# chdboy
# ---------------------------------------------------------------------------
add_library(chdboy SHARED
    chdboy/sha1.c
    chdboy/fd_registry.cpp
    chdboy/io.cpp
    chdboy/toc.cpp
    chdboy/cd_source.cpp
    chdboy/chd_write.cpp
    chdboy/jni.cpp
)

target_include_directories(chdboy PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/libchdr/include"
)

target_compile_features(chdboy PRIVATE cxx_std_17 c_std_11)

# -O3 unconditionally, for the reason in the build-type note above. This is
# also where the old build went wrong: its FLAC target listed -O3 early and
# -O0 last on the same line, and the last one wins, so the codec that does the
# most work in a CD conversion was the one built unoptimised.
target_compile_options(chdboy PRIVATE
    -O3
    -fno-strict-aliasing
    -ffunction-sections
    -fdata-sections
    -Wall
    -Wextra
    -Wno-unused-parameter
)

# WANT_*: libchdr applies these to its own targets PRIVATE-ly, but they also
# gate the ecc_* declarations in its public cdrom.h. Without them here the
# header compiles to nothing useful and the CD codec fails to link.
target_compile_definitions(chdboy PRIVATE
    WANT_RAW_DATA_SECTOR=1
    WANT_SUBCODE=1
    _FILE_OFFSET_BITS=64
)

target_link_options(chdboy PRIVATE
    -Wl,--gc-sections
    # Nothing outside the JNI entry points is part of the ABI, and the static
    # zstd/libchdr archives would otherwise export a few thousand symbols.
    -Wl,--exclude-libs,ALL
)

target_link_libraries(chdboy PRIVATE
    chdr-static
    zstd
    z
    log
)
