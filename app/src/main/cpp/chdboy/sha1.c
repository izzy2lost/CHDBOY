// SHA-1, after Steve Reid's public-domain implementation.

#include "chdboy/sha1.h"

#include <string.h>

static uint32_t rol(uint32_t value, int bits)
{
	return (value << bits) | (value >> (32 - bits));
}

static void sha1_transform(uint32_t state[5], const uint8_t block[64])
{
	uint32_t w[80];

	for (int i = 0; i < 16; i++)
	{
		w[i] = ((uint32_t)block[i * 4 + 0] << 24) |
		       ((uint32_t)block[i * 4 + 1] << 16) |
		       ((uint32_t)block[i * 4 + 2] << 8) |
		       ((uint32_t)block[i * 4 + 3]);
	}

	for (int i = 16; i < 80; i++)
	{
		w[i] = rol(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
	}

	uint32_t a = state[0];
	uint32_t b = state[1];
	uint32_t c = state[2];
	uint32_t d = state[3];
	uint32_t e = state[4];

	for (int i = 0; i < 80; i++)
	{
		uint32_t f;
		uint32_t k;

		if (i < 20)
		{
			f = (b & c) | ((~b) & d);
			k = 0x5a827999u;
		}
		else if (i < 40)
		{
			f = b ^ c ^ d;
			k = 0x6ed9eba1u;
		}
		else if (i < 60)
		{
			f = (b & c) | (b & d) | (c & d);
			k = 0x8f1bbcdcu;
		}
		else
		{
			f = b ^ c ^ d;
			k = 0xca62c1d6u;
		}

		const uint32_t temp = rol(a, 5) + f + e + k + w[i];
		e = d;
		d = c;
		c = rol(b, 30);
		b = a;
		a = temp;
	}

	state[0] += a;
	state[1] += b;
	state[2] += c;
	state[3] += d;
	state[4] += e;

	memset(w, 0, sizeof(w));
}

void chdboy_sha1_init(chdboy_sha1* ctx)
{
	ctx->state[0] = 0x67452301u;
	ctx->state[1] = 0xefcdab89u;
	ctx->state[2] = 0x98badcfeu;
	ctx->state[3] = 0x10325476u;
	ctx->state[4] = 0xc3d2e1f0u;
	ctx->count = 0;
}

void chdboy_sha1_update(chdboy_sha1* ctx, const void* data, size_t length)
{
	const uint8_t* src = (const uint8_t*)data;
	size_t have = (size_t)(ctx->count % 64);

	ctx->count += length;

	if (have)
	{
		const size_t fill = 64 - have;

		if (length < fill)
		{
			memcpy(ctx->buffer + have, src, length);
			return;
		}

		memcpy(ctx->buffer + have, src, fill);
		sha1_transform(ctx->state, ctx->buffer);
		src += fill;
		length -= fill;
	}

	while (length >= 64)
	{
		sha1_transform(ctx->state, src);
		src += 64;
		length -= 64;
	}

	if (length)
	{
		memcpy(ctx->buffer, src, length);
	}
}

void chdboy_sha1_final(chdboy_sha1* ctx, uint8_t digest[20])
{
	const uint64_t bits = ctx->count * 8;
	uint8_t length_be[8];

	for (int i = 0; i < 8; i++)
	{
		length_be[i] = (uint8_t)(bits >> (56 - i * 8));
	}

	// The 0x80 terminator, then zeroes up to the length field. Both go through
	// update so the buffering above stays the single place that tracks state.
	static const uint8_t padding[64] = { 0x80 };
	const size_t have = (size_t)(ctx->count % 64);
	const size_t pad = (have < 56) ? (56 - have) : (120 - have);

	chdboy_sha1_update(ctx, padding, pad);
	chdboy_sha1_update(ctx, length_be, 8);

	for (int i = 0; i < 20; i++)
	{
		digest[i] = (uint8_t)(ctx->state[i / 4] >> (24 - (i % 4) * 8));
	}

	memset(ctx, 0, sizeof(*ctx));
}

void chdboy_sha1_buffer(const void* data, size_t length, uint8_t digest[20])
{
	chdboy_sha1 ctx;
	chdboy_sha1_init(&ctx);
	chdboy_sha1_update(&ctx, data, length);
	chdboy_sha1_final(&ctx, digest);
}
