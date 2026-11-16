package com.shilapi.xcertplay.hicar

/**
 * The engine's numeric vocabulary, named once.
 *
 * Every failure this chain reports arrives as an integer: `hc_result` from a step, `hc_error` inside
 * an inform message, and a `message_code` on each packet. Printed raw they look like counts and
 * phases; written down once here, a log line can say `errorCode=5 HC_UNKNOW_MESSAGE` and the reader
 * does not need `hichain.h` open beside it.
 *
 * Source: `third_party/deviceauth/interfaces/innerkits/deviceauth_lite/hichain.h` (the `hc_error`
 * enum, in declaration order) and `frameworks/deviceauth_lite/source/base/base.h` (`message_code`).
 * The enum is positional, so a code is only meaningful for the tree under `hicar-auth`.
 */
internal object HiChainCodes {
    /** `enum message_code`. Requests are low, responses echo the request with the top bit set. */
    val MESSAGE_NAMES: Map<Int, String> = mapOf(
        0x0001 to "PAKE_REQUEST",
        0x0002 to "PAKE_CLIENT_CONFIRM",
        0x0003 to "EXCHANGE_REQUEST",
        0x0011 to "AUTH_START_REQUEST",
        0x0012 to "AUTH_ACK_REQUEST",
        0x0023 to "ADD_AUTHINFO_REQUEST",
        0x0033 to "REMOVE_AUTHINFO_REQUEST",
        0x0041 to "SEC_CLONE_START_REQUEST",
        0x0042 to "SEC_CLONE_ACK_REQUEST",
        0x8001 to "PAKE_RESPONSE",
        0x8002 to "PAKE_SERVER_CONFIRM_RESPONSE",
        0x8003 to "EXCHANGE_RESPONSE",
        0x8011 to "AUTH_START_RESPONSE",
        0x8012 to "AUTH_ACK_RESPONSE",
        0x8023 to "ADD_AUTHINFO_RESPONSE",
        0x8033 to "REMOVE_AUTHINFO_RESPONSE",
        0x8041 to "SEC_CLONE_START_RESPONSE",
        0x8042 to "SEC_CLONE_ACK_RESPONSE",
        0x8080 to "INFORM_MESSAGE",
    )

    /** `enum hc_error`, in the order the header declares it. */
    val ERROR_NAMES: Map<Int, String> = listOf(
        "HC_OK",
        "HC_INPUT_ERROR",
        "HC_INNER_ERROR",
        "HC_STATE_ERROR",
        "HC_SERVICE_CONFIRM_ERROR",
        "HC_UNKNOW_MESSAGE",
        "HC_NO_MESSAGE_TO_SEND",
        "HC_REPEATED_REFERENCE",
        "HC_NEED_DEPEND",
        "HC_BUILD_OBJECT_FAILED",
        "HC_BUILD_SEND_DATA_FAILED",
        "HC_MALLOC_FAILED",
        "HC_VERSION_UNSUPPORT",
        "HC_LARGE_PRIME_NUMBER_LEN_UNSUPPORT",
        "HC_CAL_BIGNUM_EXP_FAILED",
        "HC_INTERNAL_ERROR",
        "HC_IMPORT_AUTH_DATA_FAILED",
        "HC_VERIFY_PROOF_FAILED",
        "HC_GENERATE_PROOF_FAILED",
        "HC_GENERATE_SESSION_KEY_FAILED",
        "HC_ST_KEY_PAIR_FAILED",
        "HC_COMPUTE_STS_SHARED_SECRET_FAILED",
        "HC_COMPUTE_HKDF_FAILED",
        "HC_PAKE_SESSION_KEY_ERROR",
        "HC_PAKE_OBJECT_ERROR",
        "HC_STS_OBJECT_ERROR",
        "HC_DECRYPT_FAILED",
        "HC_ENCRYPT_FAILED",
        "HC_SIGN_TOO_SHORT",
        "HC_VERIFY_EXCHANGE_FAILED",
        "HC_SIGN_EXCHANGE_FAILED",
        "HC_SAVE_LTPK_FAILED",
        "HC_DELETE_LTPK_FAILED",
        "HC_GET_LTPK_FAILED",
        "HC_GEN_ALIAS_FAILED",
        "HC_GEN_SERVICE_ID_FAILED",
        "HC_GEN_RANDOM_FAILED",
        "HC_UNSUPPORT",
        "HC_MESSAGE_INCONSISTENCY",
        "HC_OPERATION_CODE_ERROR",
        "HC_MEMCPY_ERROR",
        "PROTOCOL_STATE_ERROR",
        "PROTOCOL_SET_STATE_ERROR",
        "PROTOCOL_TIMEOUT_ERROR",
        "PROTOCOL_SAHRED_SECRET_FAIL",
        "PROTOCOL_HKDF_FAIL",
        "PROTOCOL_SHA_FAIL",
        "PROTOCOL_RAND_FAIL",
        "PROTOCOL_ENCRYPT_FAIL",
        "PROTOCOL_DECRYPT_FAIL",
        "PROTOCOL_VERIFY_DATA_FAIL",
        "PROTOCOL_KEY_ALG_FAIL",
        "PROTOCOL_RESULT_ERROR",
        "KEK_NOT_EXIST",
        "DEK_NOT_EXIST",
        "TEMP_KEY_GEN_FAILED",
    ).withIndex().associate { (index, name) -> index to name }

    /** `hc_result` from `hichain.h`; the phase a step reports, not an error. */
    val RESULT_NAMES: Map<Int, String> = listOf(
        "END_SUCCESS",
        "END_FAILED",
        "KEY_AGREEMENT_PROCESSING",
        "KEY_AGREEMENT_END",
        "OPERATION_PROCESSING",
        "OPERATION_END",
    ).withIndex().associate { (index, name) -> index to name }

    /** `0x0011 (AUTH_START_REQUEST)`, or just the hex for a number outside the table. */
    fun messageName(code: Int): String {
        if (code < 0) return ""
        val name = MESSAGE_NAMES[code]
        return if (name == null) " (0x%04x)".format(code) else " (0x%04x $name)".format(code)
    }

    /** The name of an `hc_error`, or `null` for a number this tree does not declare. */
    fun errorName(code: Int): String? = ERROR_NAMES[code]
}
