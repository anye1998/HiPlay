/*
 * JNI adapter for the HiChain engine used by the HiCar receiver.
 *
 * The engine under third_party/ is OpenHarmony's deviceauth_lite plus huks_lite (both Apache-2.0).
 * This file is ours: it owns the Android side of the contract, and it is deliberately strict where
 * a mistake would leak a key or trust the wrong peer.
 *
 *   - The HUKS store is an app-private directory opened once with O_DIRECTORY|O_NOFOLLOW. Every
 *     file access goes through openat() with O_NOFOLLOW and a three-name allowlist, so a planted
 *     symlink cannot make the engine read or write outside that directory.
 *   - The hardware UDID is SHA-256 of a seed the app passes in, not a device serial: the engine
 *     only needs a stable 32-byte identifier.
 *   - A session key is handed to Java only after the engine reported success, and only with a
 *     length of 16 or 32 bytes. There is no fallback key.
 *   - confirm_receive_request allows BIND and AUTHENTICATE only. Removing auth info, unbinding and
 *     secure clone are refused, so a peer cannot make this car forget or clone the pairing.
 */
#define _GNU_SOURCE 1

#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <jni.h>
#include <mbedtls/sha256.h>

#include "hichain.h"
#include "hks_client.h"
#include "hks_errno.h"
#include "hks_file_api.h"
#include "hks_hardware_api.h"

#define MAX_STORAGE_BYTES (1024u * 1024u)
#define MAX_PACKETS 8u
#define MAX_PACKET_BYTES 65536u
#define MIN_KEY_BYTES 16u
#define MAX_KEY_BYTES 32u
#define MAX_ID_BYTES 64u
#define PIN_DIGITS 6u

/* HiChain's own strings for a HiCar car: the phone computes the service id from both, and a
 * mismatch shows up as an unexplained peer rejection. */
static const char PACKAGE_NAME[] = "com.huawei.hicar";
static const char SERVICE_TYPE[] = "CarDevice";

static int g_root_fd = -1;
static uint8_t g_udid[32];

/* hks_init's own status. Kept rather than only returned, because a key-store failure must not stop
 * the beacon: discovery and key agreement work without it, and the self-test has to be able to say
 * which layer is broken instead of reporting "HiChain failed". */
static int32_t g_hks_status = HKS_ERROR_NOT_SUPPORTED;

static int storage_name_allowed(const char *name)
{
    if (name == NULL) {
        return 0;
    }
    return strcmp(name, "info1.data") == 0 || strcmp(name, "info2.data") == 0 ||
        strcmp(name, "hks_keystore") == 0;
}

static int32_t storage_read(const char *name, uint32_t offset, uint8_t *buf, uint32_t len)
{
    if (!storage_name_allowed(name) || buf == NULL || offset > MAX_STORAGE_BYTES ||
        len > MAX_STORAGE_BYTES - offset) {
        return -1;
    }
    int fd = openat(g_root_fd, name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) {
        /* ENOENT means "this key was never stored", which the engine treats as empty. */
        return errno == ENOENT ? 0 : -1;
    }
    ssize_t read = pread(fd, buf, len, (off_t)offset);
    close(fd);
    return (read < 0) ? -1 : (int32_t)read;
}

static int32_t storage_write(const char *name, uint32_t offset, const uint8_t *buf, uint32_t len)
{
    if (!storage_name_allowed(name) || buf == NULL || offset > MAX_STORAGE_BYTES ||
        len > MAX_STORAGE_BYTES - offset) {
        return -1;
    }
    int fd = openat(g_root_fd, name, O_WRONLY | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) {
        return -1;
    }
    ssize_t written = pwrite(fd, buf, len, (off_t)offset);
    int ok = (written == (ssize_t)len) && (fsync(fd) == 0);
    close(fd);
    return ok ? 0 : -1;
}

static int32_t storage_size(const char *name)
{
    if (!storage_name_allowed(name)) {
        return -1;
    }
    int fd = openat(g_root_fd, name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) {
        return errno == ENOENT ? 0 : -1;
    }
    struct stat status;
    int ok = (fstat(fd, &status) == 0) && S_ISREG(status.st_mode) &&
        (status.st_size <= (off_t)MAX_STORAGE_BYTES);
    close(fd);
    return ok ? (int32_t)status.st_size : -1;
}

static int32_t hardware_udid(uint8_t *out, uint32_t len)
{
    if (out == NULL || len < sizeof(g_udid)) {
        return -1;
    }
    memcpy(out, g_udid, sizeof(g_udid));
    return 0;
}

static struct hks_file_callbacks g_files = { storage_read, storage_write, storage_size };

struct session {
    hc_handle handle;
    struct operation_parameter params;
    struct hc_pin pin;
    struct session_identity identity;
    struct hc_call_back callbacks;
    uint8_t *packets[MAX_PACKETS];
    uint32_t lengths[MAX_PACKETS];
    uint32_t count;
    int32_t error;
    int32_t result;
    struct hc_session_key key;
};

static struct session *session_of(const struct session_identity *identity)
{
    return (identity == NULL) ? NULL : (struct session *)identity->context;
}

static void on_transmit(const struct session_identity *identity, const void *data, uint32_t length)
{
    struct session *session = session_of(identity);
    if (session == NULL) {
        return;
    }
    if (data == NULL || length == 0 || length > MAX_PACKET_BYTES || session->count >= MAX_PACKETS ||
        memchr(data, 0, length) != NULL) {
        session->error = 1;
        return;
    }
    uint8_t *copy = malloc((size_t)length + 1);
    if (copy == NULL) {
        session->error = 1;
        return;
    }
    memcpy(copy, data, length);
    copy[length] = 0;
    session->packets[session->count] = copy;
    session->lengths[session->count] = length;
    session->count++;
}

static void on_protocol_params(const struct session_identity *identity, int32_t operation_code,
    struct hc_pin *pin, struct operation_parameter *params)
{
    struct session *session = session_of(identity);
    if (session == NULL || pin == NULL || params == NULL) {
        return;
    }
    *pin = session->pin;
    *params = session->params;
}

static void on_session_key(const struct session_identity *identity, const struct hc_session_key *key)
{
    struct session *session = session_of(identity);
    if (session == NULL) {
        return;
    }
    if (key == NULL || key->length < MIN_KEY_BYTES || key->length > HC_SESSION_KEY_LEN) {
        session->error = 1;
        return;
    }
    session->key = *key;
}

static void on_service_result(const struct session_identity *identity, int32_t result)
{
    struct session *session = session_of(identity);
    if (session != NULL) {
        session->result = result;
    }
}

static int32_t on_confirm(const struct session_identity *identity, int32_t operation_code)
{
    (void)identity;
    /* A car accepts a first-time bind and a later authentication. Everything else - removing auth
     * info, unbind, secure clone - would let the peer change this car's trust state. */
    return (operation_code == BIND || operation_code == AUTHENTICATE) ? 0 : -1;
}

/* Copies a Java string into a fixed buffer, returning the length or 0 when it does not fit. */
static uint32_t copy_string(JNIEnv *env, jstring value, uint8_t *out, uint32_t max)
{
    if (value == NULL || out == NULL) {
        return 0;
    }
    const char *chars = (*env)->GetStringUTFChars(env, value, NULL);
    if (chars == NULL) {
        return 0;
    }
    size_t length = strlen(chars);
    uint32_t result = 0;
    if (length > 0 && length <= max) {
        memcpy(out, chars, length);
        result = (uint32_t)length;
    }
    (*env)->ReleaseStringUTFChars(env, value, chars);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_hicarauth_HiChainNative_init(JNIEnv *env, jclass clazz, jstring directory,
    jstring seed)
{
    (void)clazz;
    if (g_root_fd >= 0) {
        return 0;
    }
    uint8_t seed_bytes[256];
    uint32_t seed_length = copy_string(env, seed, seed_bytes, sizeof(seed_bytes));
    if (seed_length == 0) {
        return -1;
    }
    /* A seed of any length is accepted; only its digest leaves this function. */
    if (mbedtls_sha256_ret(seed_bytes, seed_length, g_udid, 0) != 0) {
        memset(seed_bytes, 0, sizeof(seed_bytes));
        return -1;
    }
    memset(seed_bytes, 0, sizeof(seed_bytes));

    const char *path = (*env)->GetStringUTFChars(env, directory, NULL);
    if (path == NULL) {
        return -1;
    }
    int fd = open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    (*env)->ReleaseStringUTFChars(env, directory, path);
    if (fd < 0) {
        return -2;
    }
    struct stat status;
    /* The store must be a directory only this app can reach; a world-readable one is refused
     * rather than silently used. */
    if (fstat(fd, &status) != 0 || (status.st_mode & 0077) != 0) {
        close(fd);
        return -3;
    }
    g_root_fd = fd;
    int32_t result = hks_register_file_callbacks(&g_files);
    if (result == 0) {
        result = hks_register_get_hardware_udid_callback(hardware_udid);
    }
    if (result != 0) {
        close(g_root_fd);
        g_root_fd = -1;
        return result;
    }
    /* Loads the key store and initialises the root key component. A failure here breaks the
     * exchange phase but not discovery or key agreement, so it is recorded instead of returned:
     * refusing to start would turn a key-store problem into "nothing works at all". */
    g_hks_status = hks_init();
    return 0;
}

/*
 * Exercises the exact huks calls HiChain's bind exchange makes, in order, on one throwaway alias.
 *
 * The car is the only place these run for real, and driving to it to learn that signing is broken
 * costs a test cycle; this runs on any device that can install the APK. Every line is a separate
 * call with its own status, so a failure names the step instead of the subsystem.
 */
JNIEXPORT jstring JNICALL
Java_com_shilapi_xcertplay_hicarauth_HiChainNative_selfTest(JNIEnv *env, jclass clazz)
{
    (void)clazz;
    static const char SELF_ALIAS[] = "hicar.selftest";
    static const char PEER_ALIAS[] = "hicar.selftest.peer";
    static const uint8_t SELF_ID[] = "self-test";
    static const uint8_t PEER_ID[] = "peer-test";
    static const char MESSAGE[] = "hicar huks self test";

    /* Fourteen lines plus a verdict; sized so a longer status string can never truncate the report. */
    char report[2048];
    uint32_t used = 0;
    uint32_t failures = 0;

    /* One line per call: "PASS <what> (<status>)". A FAIL line never hides the status either. */
    #define ST_LINE(ok, ...) do { \
        char line[192]; \
        (void)snprintf(line, sizeof(line), __VA_ARGS__); \
        int written = snprintf(report + used, sizeof(report) - used, "%s %s\n", (ok) ? "PASS" : "FAIL", line); \
        if (written > 0) { \
            used += (uint32_t)written; \
            if (used >= sizeof(report)) used = sizeof(report) - 1; \
        } \
        if (!(ok)) failures++; \
    } while (0)

    if (g_root_fd < 0) {
        return (*env)->NewStringUTF(env, "FAIL engine not initialised (no store directory)\n");
    }

    ST_LINE(g_hks_status == HKS_SUCCESS, "hks_init: status=%d", g_hks_status);

    struct hks_blob self_alias = { HKS_BLOB_TYPE_ALIAS, (uint8_t *)SELF_ALIAS, sizeof(SELF_ALIAS) - 1 };
    struct hks_blob peer_alias = { HKS_BLOB_TYPE_ALIAS, (uint8_t *)PEER_ALIAS, sizeof(PEER_ALIAS) - 1 };

    /* A previous run may have left the alias behind; the test must not depend on a clean store. */
    (void)hks_delete_key(&self_alias);
    (void)hks_delete_key(&peer_alias);

    struct hks_key_param pair_param;
    memset(&pair_param, 0, sizeof(pair_param));
    pair_param.key_type = HKS_KEY_TYPE_EDDSA_KEYPAIR_ED25519;
    pair_param.key_usage = HKS_KEY_USAGE_SIGN | HKS_KEY_USAGE_VERIFY;
    pair_param.key_mode = HKS_ALG_GCM;
    pair_param.key_len = HKS_PUBLIC_BYTES_ED25519;
    pair_param.key_auth_id.type = HKS_BLOB_TYPE_AUTH_ID;
    pair_param.key_auth_id.data = (uint8_t *)SELF_ID;
    pair_param.key_auth_id.size = sizeof(SELF_ID) - 1;

    int32_t status = hks_generate_key(&self_alias, &pair_param);
    ST_LINE(status == HKS_SUCCESS, "hks_generate_key (Ed25519 pair): status=%d", status);

    uint8_t public_key[HKS_PUBLIC_BYTES_ED25519];
    struct hks_blob public_blob = { HKS_BLOB_TYPE_KEY, public_key, sizeof(public_key) };
    status = hks_export_public_key(&self_alias, &public_blob);
    ST_LINE(status == HKS_SUCCESS && public_blob.size == HKS_PUBLIC_BYTES_ED25519,
        "hks_export_public_key: status=%d, bytes=%u", status, public_blob.size);

    uint8_t digest[32];
    (void)mbedtls_sha256_ret((const uint8_t *)MESSAGE, sizeof(MESSAGE) - 1, digest, 0);
    struct hks_blob hash = { HKS_BLOB_TYPE_KEY, digest, sizeof(digest) };

    struct hks_key_param sign_param;
    memset(&sign_param, 0, sizeof(sign_param));
    sign_param.key_type = HKS_KEY_TYPE_EDDSA_KEYPAIR_ED25519;
    sign_param.key_usage = HKS_KEY_USAGE_SIGN;
    sign_param.key_mode = HKS_ALG_GCM;

    uint8_t signature[HKS_SIGNATURE_MIN_SIZE];
    struct hks_blob signature_blob = { HKS_BLOB_TYPE_KEY, signature, sizeof(signature) };
    status = hks_asymmetric_sign(&self_alias, &sign_param, &hash, &signature_blob);
    ST_LINE(status == HKS_SUCCESS && signature_blob.size == HKS_SIGNATURE_MIN_SIZE,
        "hks_asymmetric_sign: status=%d, bytes=%u", status, signature_blob.size);

    struct hks_key_param verify_param;
    memset(&verify_param, 0, sizeof(verify_param));
    verify_param.key_type = HKS_KEY_TYPE_EDDSA_PUBLIC_KEY_ED25519;
    verify_param.key_usage = HKS_KEY_USAGE_VERIFY;
    verify_param.key_mode = HKS_ALG_GCM;

    /* The exchange phase verifies with the peer's raw public key, not with an alias: this exact
     * call is the one that used to answer HKS_ERROR_NOT_SUPPORTED (error 29 downstream). */
    status = hks_asymmetric_verify(&public_blob, &verify_param, &hash, &signature_blob);
    ST_LINE(status == HKS_SUCCESS, "hks_asymmetric_verify (fresh signature): status=%d", status);

    /* A verifier that accepts anything would also pass the line above, so break one bit. */
    signature[0] ^= 0x01;
    status = hks_asymmetric_verify(&public_blob, &verify_param, &hash, &signature_blob);
    signature[0] ^= 0x01;
    ST_LINE(status != HKS_SUCCESS, "hks_asymmetric_verify (tampered signature rejected): status=%d", status);

    struct hks_key_param import_param;
    memset(&import_param, 0, sizeof(import_param));
    import_param.key_type = HKS_KEY_TYPE_EDDSA_PUBLIC_KEY_ED25519;
    import_param.key_usage = HKS_KEY_USAGE_ENCRYPT | HKS_KEY_USAGE_DECRYPT;
    import_param.key_mode = HKS_ALG_GCM;
    import_param.key_len = HKS_PUBLIC_BYTES_ED25519;
    import_param.key_auth_id.type = HKS_BLOB_TYPE_AUTH_ID;
    import_param.key_auth_id.data = (uint8_t *)PEER_ID;
    import_param.key_auth_id.size = sizeof(PEER_ID) - 1;

    status = hks_import_public_key(&peer_alias, &import_param, &public_blob);
    ST_LINE(status == HKS_SUCCESS, "hks_import_public_key (peer): status=%d", status);

    status = hks_is_key_exist(&peer_alias);
    ST_LINE(status == HKS_SUCCESS, "hks_is_key_exist (peer, after import): status=%d", status);

    /*
     * Reload the store from disk and read the same key back.
     *
     * Everything above can pass while the keystore only ever held the key in memory: a write that
     * silently fails, or one that is encrypted under a root key that changes when the store is
     * reopened, would still sign and verify perfectly until the next session. That failure would
     * then surface on the car as a phone rejecting a key it had already accepted, so it is checked
     * here in one run rather than discovered across two.
     */
    status = hks_init();
    ST_LINE(status == HKS_SUCCESS, "hks_init (reload after writes): status=%d", status);

    status = hks_is_key_exist(&self_alias);
    ST_LINE(status == HKS_SUCCESS, "hks_is_key_exist (self, after reload): status=%d", status);

    uint8_t reread_key[HKS_PUBLIC_BYTES_ED25519];
    struct hks_blob reread_blob = { HKS_BLOB_TYPE_KEY, reread_key, sizeof(reread_key) };
    status = hks_export_public_key(&self_alias, &reread_blob);
    int same_key = (status == HKS_SUCCESS) && (reread_blob.size == sizeof(public_key)) &&
        (memcmp(reread_key, public_key, sizeof(public_key)) == 0);
    ST_LINE(same_key, "public key identical after reload: status=%d, equal=%s", status,
        same_key ? "yes" : "no");

    status = hks_delete_key(&peer_alias);
    ST_LINE(status == HKS_SUCCESS, "hks_delete_key (peer): status=%d", status);
    status = hks_is_key_exist(&peer_alias);
    ST_LINE(status != HKS_SUCCESS, "hks_is_key_exist (peer, after delete): status=%d", status);

    status = hks_delete_key(&self_alias);
    ST_LINE(status == HKS_SUCCESS, "hks_delete_key (self): status=%d", status);

    #undef ST_LINE

    char verdict[64];
    (void)snprintf(verdict, sizeof(verdict), "verdict: %s (%u failure(s))\n",
        (failures == 0) ? "PASS" : "FAIL", failures);
    if (used + strlen(verdict) < sizeof(report)) {
        memcpy(report + used, verdict, strlen(verdict) + 1);
    }
    return (*env)->NewStringUTF(env, report);
}

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_hicarauth_HiChainNative_create(JNIEnv *env, jclass clazz, jstring self,
    jstring peer, jstring pin, jint role)
{
    (void)clazz;
    if (g_root_fd < 0 || (role != HC_CENTRE && role != HC_ACCESSORY)) {
        return 0;
    }
    struct session *session = calloc(1, sizeof(*session));
    if (session == NULL) {
        return 0;
    }
    session->result = -1;

    session->params.self_auth_id.length = copy_string(env, self, session->params.self_auth_id.auth_id,
        MAX_ID_BYTES);
    session->params.peer_auth_id.length = copy_string(env, peer, session->params.peer_auth_id.auth_id,
        MAX_ID_BYTES);
    session->pin.length = copy_string(env, pin, session->pin.pin, PIN_DIGITS);
    session->params.key_length = MAX_KEY_BYTES;

    /* The PIN is the six digits the car shows; anything else cannot match the phone's input. */
    int pin_valid = (session->pin.length == PIN_DIGITS);
    for (uint32_t index = 0; pin_valid && index < PIN_DIGITS; index++) {
        pin_valid = (session->pin.pin[index] >= '0' && session->pin.pin[index] <= '9');
    }
    if (session->params.self_auth_id.length == 0 || session->params.peer_auth_id.length == 0 ||
        !pin_valid) {
        memset(session, 0, sizeof(*session));
        free(session);
        return 0;
    }

    session->identity.package_name.length = (uint32_t)strlen(PACKAGE_NAME);
    memcpy(session->identity.package_name.name, PACKAGE_NAME, session->identity.package_name.length);
    session->identity.service_type.length = (uint32_t)strlen(SERVICE_TYPE);
    memcpy(session->identity.service_type.type, SERVICE_TYPE, session->identity.service_type.length);
    session->identity.context = session;
    session->callbacks.transmit = on_transmit;
    session->callbacks.get_protocol_params = on_protocol_params;
    session->callbacks.set_session_key = on_session_key;
    session->callbacks.set_service_result = on_service_result;
    session->callbacks.confirm_receive_request = on_confirm;

    session->handle = get_instance(&session->identity, (enum hc_type)role, &session->callbacks);
    if (session->handle == NULL) {
        memset(session, 0, sizeof(*session));
        free(session);
        return 0;
    }
    return (jlong)(intptr_t)session;
}

/*
 * Runs one engine step. A null `data` starts the PAKE exchange; a packet continues it.
 *
 * Result layout: element 0 is a four-int header in native byte order
 * {engine code, service result, session key length, error flag}, followed by the packets the
 * engine wants transmitted, in order.
 */
JNIEXPORT jobjectArray JNICALL
Java_com_shilapi_xcertplay_hicarauth_HiChainNative_step(JNIEnv *env, jclass clazz, jlong handle,
    jbyteArray data, jint key_length)
{
    (void)clazz;
    struct session *session = (struct session *)(intptr_t)handle;
    if (session == NULL || key_length < (jint)MIN_KEY_BYTES || key_length > (jint)MAX_KEY_BYTES) {
        return NULL;
    }
    session->params.key_length = (uint32_t)key_length;

    int32_t code;
    if (data == NULL) {
        code = start_pake(session->handle, &session->params);
    } else {
        jsize length = (*env)->GetArrayLength(env, data);
        if (length <= 0 || length > (jsize)MAX_PACKET_BYTES) {
            return NULL;
        }
        uint8_t *packet = malloc((size_t)length + 1);
        if (packet == NULL) {
            return NULL;
        }
        (*env)->GetByteArrayRegion(env, data, 0, length, (jbyte *)packet);
        packet[length] = 0;
        struct uint8_buff buffer = { packet, (uint32_t)length, (uint32_t)length };
        code = receive_data(session->handle, &buffer);
        memset(packet, 0, (size_t)length);
        free(packet);
    }

    jclass byte_array = (*env)->FindClass(env, "[B");
    if (byte_array == NULL) {
        return NULL;
    }
    jobjectArray out = (*env)->NewObjectArray(env, (jsize)session->count + 1, byte_array, NULL);
    if (out == NULL) {
        return NULL;
    }
    int32_t header[4] = { code, session->result, (int32_t)session->key.length, session->error };
    jbyteArray meta = (*env)->NewByteArray(env, (jsize)sizeof(header));
    if (meta == NULL) {
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, meta, 0, (jsize)sizeof(header), (const jbyte *)header);
    (*env)->SetObjectArrayElement(env, out, 0, meta);
    (*env)->DeleteLocalRef(env, meta);

    for (uint32_t index = 0; index < session->count; index++) {
        jbyteArray packet = (*env)->NewByteArray(env, (jsize)session->lengths[index]);
        if (packet == NULL) {
            break;
        }
        (*env)->SetByteArrayRegion(env, packet, 0, (jsize)session->lengths[index],
            (const jbyte *)session->packets[index]);
        (*env)->SetObjectArrayElement(env, out, (jsize)index + 1, packet);
        (*env)->DeleteLocalRef(env, packet);
        memset(session->packets[index], 0, session->lengths[index]);
        free(session->packets[index]);
        session->packets[index] = NULL;
    }
    session->count = 0;
    return out;
}

JNIEXPORT jbyteArray JNICALL
Java_com_shilapi_xcertplay_hicarauth_HiChainNative_sessionKey(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)clazz;
    struct session *session = (struct session *)(intptr_t)handle;
    /*
     * The gate is "the exchange produced a usable key", not "result == 0": for a bind the engine
     * reports the protocol phase (KEY_AGREEMENT_END) in `result`, so demanding 0 discarded every
     * completed first-time pair. END_FAILED (1) is the only value that means the exchange failed.
     */
    if (session == NULL || session->error != 0 || session->result == 1 ||
        (session->key.length != MIN_KEY_BYTES && session->key.length != MAX_KEY_BYTES)) {
        return NULL;
    }
    jbyteArray key = (*env)->NewByteArray(env, (jsize)session->key.length);
    if (key == NULL) {
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, key, 0, (jsize)session->key.length,
        (const jbyte *)session->key.session_key);
    return key;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_hicarauth_HiChainNative_close(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env;
    (void)clazz;
    struct session *session = (struct session *)(intptr_t)handle;
    if (session == NULL) {
        return;
    }
    if (session->handle != NULL) {
        destroy(&session->handle);
    }
    for (uint32_t index = 0; index < session->count; index++) {
        if (session->packets[index] != NULL) {
            memset(session->packets[index], 0, session->lengths[index]);
            free(session->packets[index]);
        }
    }
    memset(session, 0, sizeof(*session));
    free(session);
}
