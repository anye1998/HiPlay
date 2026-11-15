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

# _CUT_LOG_: the upstream logging writes through a hiview logger that does not exist on Android.
# Without it the engine still builds but every protocol step prints to a missing backend.
# _CUT_AUTHENTICATE_ is upstream's own switch for the scaled-down HUKS build: it removes the
# x25519 helpers, which are the only part of huks_lite that reaches into OpenSSL's internals
# (ec_local.h). HiChain itself only needs the ECDH P-256, HMAC and digest paths, so this keeps
# OpenSSL out of the APK. If a future upstream version uses those helpers, the link step fails
# loudly instead of silently degrading.
target_compile_definitions(hicarauth PRIVATE
    _CUT_LOG_
    _CUT_AUTHENTICATE_
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
target_link_libraries(hicarauth PRIVATE ${ANDROID_LOG})
target_link_options(hicarauth PRIVATE -Wl,--no-undefined -Wl,-z,max-page-size=16384)

# A separate, deliberately tiny library: reading an interface address is not authentication, and a
# failure to load the HiChain engine must not take the address lookup down with it (or the reverse).
add_library(netiface SHARED netiface_jni.c)
target_compile_options(netiface PRIVATE -fvisibility=hidden -fstack-protector-strong)
target_link_options(netiface PRIVATE -Wl,--no-undefined -Wl,-z,max-page-size=16384)
