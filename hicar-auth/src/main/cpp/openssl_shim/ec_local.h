/*
 * The three declarations huks_lite expects from OpenSSL's internal ec_local.h.
 *
 * hks_service.c opens with `#include <ec_local.h>` purely to see ED25519_sign,
 * ED25519_verify and ED25519_public_from_private: the algorithm itself was migrated to mbedtls
 * upstream, and no other OpenSSL symbol is referenced anywhere in huks_lite (checked with a grep
 * for EC_KEY / EC_POINT / BN_ / EVP_). The Android NDK ships no such header, and BoringSSL has no
 * Ed25519 signing API at all, so the include used to force _CUT_AUTHENTICATE_ — which removed the
 * key store, the sign and verify paths and the long-term key pair with it, and with them HiChain's
 * bind exchange (see docs/HICAR_MIGRATION.md 7.27).
 *
 * This header supplies only the declarations, with the argument order of the OpenSSL functions the
 * call sites were written against. The implementations live in ../../hicar_ed25519.c, which wraps
 * the vendored ed25519/ sources; nothing here re-implements or weakens anything.
 *
 * Guarded against OpenSSL's own header: if a real ec_local.h ever appears first in the include
 * path, this file steps aside instead of clashing with it.
 */
#ifndef HICAR_OPENSSL_EC_LOCAL_SHIM_H
#define HICAR_OPENSSL_EC_LOCAL_SHIM_H

#if defined(OPENSSL_EC_LOCAL_H) || defined(HEADER_EC_LOCAL_H)
#error "a real ec_local.h is already in the include path; drop this shim from the build"
#endif

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Writes a 64-byte signature. Returns 1 on success, 0 on failure. */
int ED25519_sign(uint8_t *out_sig, const uint8_t *message, size_t message_len,
    const uint8_t public_key[32], const uint8_t private_key[32]);

/* Returns 1 when the signature holds for the message, 0 otherwise. */
int ED25519_verify(const uint8_t *message, size_t message_len,
    const uint8_t signature[64], const uint8_t public_key[32]);

/* Derives the public key from the 32-byte private seed. */
void ED25519_public_from_private(uint8_t out_public_key[32], const uint8_t private_key[32]);

#ifdef __cplusplus
}
#endif

#endif /* HICAR_OPENSSL_EC_LOCAL_SHIM_H */
