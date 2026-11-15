/*
 * OpenSSL-compatible Ed25519 entry points for huks_lite.
 *
 * OpenHarmony's huks_lite calls ED25519_sign()/ED25519_verify() with the signatures OpenSSL's
 * internal curve25519.c exports, and those symbols do not exist in the Android NDK (BoringSSL ships
 * no Ed25519 signing API at all). The vendored ed25519/ directory next to this file provides the
 * algorithm; this shim only renames and reorders the arguments so huks's call sites stay unchanged.
 *
 * Provenance of ed25519/: see ed25519/NOTICE.md.
 */

#include <stddef.h>

#include "ed25519.h"

#if defined(__GNUC__)
#define HICAR_EXPORT __attribute__((visibility("default")))
#else
#define HICAR_EXPORT
#endif

/* OpenSSL order: (out_sig, message, message_len, public_key, private_key). */
HICAR_EXPORT int ED25519_sign(unsigned char *out_sig, const unsigned char *message,
    size_t message_len, const unsigned char public_key[32], const unsigned char private_key[32])
{
    if (out_sig == NULL || message == NULL || public_key == NULL || private_key == NULL) {
        return 0;
    }
    ed25519_sign(out_sig, message, message_len, public_key, private_key);
    return 1;
}

/* OpenSSL order: (message, message_len, signature, public_key). Returns 1 when the signature holds. */
HICAR_EXPORT int ED25519_verify(const unsigned char *message, size_t message_len,
    const unsigned char signature[64], const unsigned char public_key[32])
{
    if (message == NULL || signature == NULL || public_key == NULL) {
        return 0;
    }
    return ed25519_verify(signature, message, message_len, public_key);
}
