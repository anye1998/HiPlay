# HiChain engine for the HiCar receiver.
#
# The C sources under third_party are pinned upstream releases fetched by
# `python3 scripts/fetch-hichain-sources.py` and are not committed:
#
#   deviceauth  openharmony/security_deviceauth            (deviceauth_lite, Apache-2.0)
#   huks        openharmony/security_huks                 (huks_lite software keystore, Apache-2.0)
#   securec     openharmony/third_party_bounds_checking_function (Apache-2.0)
#   mbedtls     Mbed-TLS/mbedtls v2.28.9                  (Apache-2.0)
#
# Only two of those need explaining: deviceauth_lite itself pulls in no crypto, and huks_lite
# uses the mbedtls 2.x internal APIs (md_internal.h, pk_internal.h), which is why 2.28 is pinned
# instead of 3.x.
cmake_minimum_required(VERSION 3.22)
project(hicarauth C)

set(THIRD "${CMAKE_CURRENT_SOURCE_DIR}/third_party")
set(AUTH "${THIRD}/deviceauth/frameworks/deviceauth_lite/source")
set(HUKS "${THIRD}/huks/frameworks/huks_lite/source/hw_keystore_sdk")
set(MBEDTLS "${THIRD}/mbedtls")

foreach(required "${AUTH}" "${HUKS}" "${MBEDTLS}/library" "${THIRD}/securec/src")
    if(NOT EXISTS "${required}")
        message(FATAL_ERROR
            "Missing ${required}. Run: python3 scripts/fetch-hichain-sources.py")
    endif()
endforeach()

file(GLOB_RECURSE AUTH_SRC CONFIGURE_DEPENDS "${AUTH}/*.c")
file(GLOB_RECURSE HUKS_SRC CONFIGURE_DEPENDS "${HUKS}/*.c")
file(GLOB SECUREC_SRC CONFIGURE_DEPENDS "${THIRD}/securec/src/*.c")
# mbedcrypto's sources are compiled here instead of through mbedtls' own CMake project: that
# project wants Python for generated files and adds tooling targets this APK does not need.
file(GLOB MBEDTLS_SRC CONFIGURE_DEPENDS "${MBEDTLS}/library/*.c")
set(CJSON_SRC "${THIRD}/cjson/cJSON.c")
# huks_lite only needs libmbedcrypto; the TLS half of the library would add a few hundred KB per
# ABI for code that can never run here.
list(FILTER MBEDTLS_SRC EXCLUDE REGEX "/ssl_[^/]*\\.c$")

# Ed25519: huks_lite calls OpenSSL's ED25519_sign/ED25519_verify, which the Android NDK does not
# provide at all. The sources are vendored unmodified under ed25519/ with checksums in SHA256SUMS.
file(GLOB ED25519_SRC "${CMAKE_CURRENT_SOURCE_DIR}/ed25519/*.c")

add_library(hicarauth SHARED
    hichain_jni.c
    hicar_ed25519.c
    ${ED25519_SRC}
    ${AUTH_SRC}
    ${HUKS_SRC}
    ${SECUREC_SRC}
    ${MBEDTLS_SRC}
    ${CJSON_SRC})

target_include_directories(hicarauth PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/ed25519"
    # Supplies the three ED25519_* declarations hks_service.c takes from OpenSSL's ec_local.h.
    "${CMAKE_CURRENT_SOURCE_DIR}/openssl_shim"
    "${THIRD}/deviceauth/interfaces/innerkits/deviceauth_lite"
    "${THIRD}/huks/interfaces/innerkits/huks_lite"
    "${THIRD}/securec/include"
    "${MBEDTLS}/include"
    "${AUTH}"
    "${AUTH}/base"
    "${AUTH}/auth_info"
    "${AUTH}/huks_adapter"
    "${AUTH}/json"
    "${AUTH}/key_agreement"
    "${AUTH}/log"
    "${AUTH}/schedule"
    "${AUTH}/struct"
    "${HUKS}"
    "${HUKS}/.."
    "${HUKS}/common"
    "${HUKS}/soft_service"
    "${THIRD}/cjson")

# _CUT_LOG_ is deliberately NOT defined. It compiles every LOG* call in the engine to nothing, and
# that is what made a refusal look like a bare number: the engine writes its reason down, then the
# macro deletes it, and all the app receives is an inform message carrying one int32. The engine's
# logger has no Android backend of its own, so `hichain_jni.c` registers one with `registe_log` and
# those lines appear under the `HiChain` logcat tag. `_HC_DEBUG_` stays undefined: defining it would
# empty log.c and leave `registe_log` without a definition.
#
# _CUT_AUTHENTICATE_ is deliberately NOT defined. It is upstream's switch for the scaled-down HUKS
# build, and it cuts far more than the x25519 helpers: the key store, hks_asymmetric_sign/verify,
# the long-term key pair and the alias list all disappear with it. That is why HiChain's bind
# exchange used to fail with HC_VERIFY_EXCHANGE_FAILED (29) — see docs/HICAR_MIGRATION.md 7.27.
# The only thing it was ever protecting us from is `#include <ec_local.h>` at the top of
# hks_service.c, which exists solely to see three ED25519_* declarations; openssl_shim/ provides
# them, and huks_lite references no other OpenSSL symbol.
target_compile_definitions(hicarauth PRIVATE
    _GNU_SOURCE
    _POSIX_C_SOURCE=200809L)

target_compile_options(hicarauth PRIVATE
    -fvisibility=hidden
    -fstack-protector-strong
    -Wno-unused-parameter
    -Wno-sign-compare
    -Wno-implicit-function-declaration)

# The JNI entry points are the only exported symbols; the engine stays internal.
target_compile_options(hicarauth PRIVATE -fvisibility-inlines-hidden)
set_target_properties(hicarauth PROPERTIES
    C_VISIBILITY_PRESET hidden
    BUILD_RPATH "$ORIGIN"
    INSTALL_RPATH "$ORIGIN")

find_library(ANDROID_LOG log)
# `dl` is for the vendor-SDK probe: it dlopens the head unit's own HiCar libraries, which are not
# available at link time and must never become a link-time dependency.
target_link_libraries(hicarauth PRIVATE ${ANDROID_LOG} dl)
target_link_options(hicarauth PRIVATE -Wl,--no-undefined -Wl,-z,max-page-size=16384)

# A separate, deliberately tiny library: reading an interface address is not authentication, and a
# failure to load the HiChain engine must not take the address lookup down with it (or the reverse).
add_library(netiface SHARED netiface_jni.c)
target_compile_options(netiface PRIVATE -fvisibility=hidden -fstack-protector-strong)
target_link_options(netiface PRIVATE -Wl,--no-undefined -Wl,-z,max-page-size=16384)
