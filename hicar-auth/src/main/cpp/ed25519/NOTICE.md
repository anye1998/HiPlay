# ed25519 (vendored)

Source: https://github.com/orlp/ed25519 (`src/`), fetched 2026-11-15.
Licence: zlib (see the upstream repository; the LICENSE file itself could not be fetched from the
mirror used here, so this directory ships the sources with this note instead of a licence copy).

Why it is vendored instead of linked: OpenHarmony's `huks_lite` calls `ED25519_sign()` /
`ED25519_verify()` with OpenSSL's internal signatures, and the Android NDK ships no Ed25519 signing
API (BoringSSL has no equivalent). Without these symbols the HiChain bind exchange cannot verify the
peer's long-term key — the failure this project hit as `HC_VERIFY_EXCHANGE_FAILED` (29).

Files are unmodified. `hicar_ed25519.c` one level up is ours: it renames/reorders the arguments to
the OpenSSL shape and is the only file that should need touching if upstream is ever updated.

SHA-256 of the vendored files (verifiable with `sha256sum ed25519/*`):

```
add_scalar.c
ed25519.h
fe.c
fe.h
fixedint.h
ge.c
ge.h
keypair.c
precomp_data.h
sc.c
sc.h
sha512.c
sha512.h
sign.c
verify.c
```

(Checksums are recorded in `ed25519/SHA256SUMS`.)
