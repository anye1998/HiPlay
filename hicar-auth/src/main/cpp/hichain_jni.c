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
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <jni.h>
#include <mbedtls/sha256.h>

#include "hichain.h"
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
    }
    return result;
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
