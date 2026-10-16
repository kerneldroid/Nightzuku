package kerneldroid.nightzuku.api

import android.os.IBinder

/**
 * Wire protocol constants and capability definitions for the native Nightzuku API.
 *
 * Uses flat Parcel serialization instead of Bundle maps on hot paths to minimize
 * Binder IPC overhead and eliminate dependency on legacy Shizuku stub classes.
 */
object NightProtocol {

    const val DESCRIPTOR = "kerneldroid.nightzuku.INightService"
    const val CALLBACK_DESCRIPTOR = "kerneldroid.nightzuku.INightCallback"
    const val PROTOCOL_VERSION = 1

    const val PROVIDER_SUFFIX = ".nightzuku"
    const val LEGACY_PROVIDER_SUFFIX = ".shizuku"

    const val METHOD_SEND_BINDER = "sendNightBinder"
    const val METHOD_GET_BINDER = "getNightBinder"
    const val METHOD_SEND_USER_SERVICE = "sendNightUserService"

    const val EXTRA_BINDER = "kerneldroid.nightzuku.extra.BINDER"
    const val EXTRA_LEGACY_BINDER = "kerneldroid.nightzuku.extra.LEGACY_BINDER"
    const val EXTRA_USER_SERVICE_TOKEN = "kerneldroid.nightzuku.extra.USER_SERVICE_TOKEN"

    // Fine-grained capability bitmasks stored in upper bits of ConfigManager flags
    const val CAP_SHELL_EXEC = 1 shl 8
    const val CAP_BINDER_PROXY = 1 shl 9
    const val CAP_USER_SERVICE = 1 shl 10
    const val CAP_SYSTEM_PROPS = 1 shl 11
    const val MASK_CAPABILITIES = CAP_SHELL_EXEC or CAP_BINDER_PROXY or CAP_USER_SERVICE or CAP_SYSTEM_PROPS
    const val DEFAULT_CAPABILITIES = MASK_CAPABILITIES

    // Grant states returned during flat attach handshake
    const val STATE_UNDETERMINED = 0
    const val STATE_GRANTED = 1
    const val STATE_DENIED_PERMANENT = 2

    // INightService transaction codes
    const val TRANSACTION_ATTACH = IBinder.FIRST_CALL_TRANSACTION + 1
    const val TRANSACTION_TRANSACT_REMOTE = IBinder.FIRST_CALL_TRANSACTION + 2
    const val TRANSACTION_EXEC_FAST = IBinder.FIRST_CALL_TRANSACTION + 3
    const val TRANSACTION_CHECK_PERMISSION = IBinder.FIRST_CALL_TRANSACTION + 4
    const val TRANSACTION_REQUEST_PERMISSION = IBinder.FIRST_CALL_TRANSACTION + 5
    const val TRANSACTION_GET_SYSTEM_PROPERTY = IBinder.FIRST_CALL_TRANSACTION + 6
    const val TRANSACTION_SET_SYSTEM_PROPERTY = IBinder.FIRST_CALL_TRANSACTION + 7
    const val TRANSACTION_CLAIM_USER_SERVICE = IBinder.FIRST_CALL_TRANSACTION + 8
    const val TRANSACTION_GET_UID_CAPABILITIES = IBinder.FIRST_CALL_TRANSACTION + 9
    const val TRANSACTION_SET_UID_CAPABILITIES = IBinder.FIRST_CALL_TRANSACTION + 10

    // INightCallback transaction codes
    const val CALLBACK_ON_PERMISSION_CHANGED = IBinder.FIRST_CALL_TRANSACTION + 1
    const val CALLBACK_ON_REQUEST_PERMISSION_RESULT = IBinder.FIRST_CALL_TRANSACTION + 2

    // Bounds for fast one-shot command execution
    const val EXEC_FAST_MAX_OUTPUT_BYTES = 256 * 1024
    const val EXEC_FAST_DEFAULT_TIMEOUT_MS = 30_000L
}
