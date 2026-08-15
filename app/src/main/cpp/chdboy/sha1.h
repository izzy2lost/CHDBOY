#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// SHA-1, needed because a CHD v5 header carries two of them: one over the raw
// logical image and one over the image plus its metadata. Both are checked by
// chdman verify, so they are not optional decoration.
typedef struct
{
	uint32_t state[5];
	uint64_t count;
	uint8_t buffer[64];
} chdboy_sha1;

void chdboy_sha1_init(chdboy_sha1* ctx);
void chdboy_sha1_update(chdboy_sha1* ctx, const void* data, size_t length);
void chdboy_sha1_final(chdboy_sha1* ctx, uint8_t digest[20]);

// One-shot form, for the per-metadata-entry hashes.
void chdboy_sha1_buffer(const void* data, size_t length, uint8_t digest[20]);

#ifdef __cplusplus
}
#endif
