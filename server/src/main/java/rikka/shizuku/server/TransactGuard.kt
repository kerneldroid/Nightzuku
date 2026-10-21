package rikka.shizuku.server

import android.os.IBinder
import kerneldroid.nightzuku.api.NightProtocol
import rikka.shizuku.ShizukuApiConstants

/**
 * Security policy guard for Binder transaction forwarding (`transactRemote`), global access pause,
 * and per-UID capability masks.
 *
 * Prevents privilege escalation via self-referential binder forwarding (where a client passes the
 * server's own binder into `transactRemote` to execute manager-only calls under `clearCallingIdentity()`)
 * and enforces per-UID capability masks and global access pause state.
 */
object TransactGuard {

    @JvmStatic
    fun verifyTargetBinder(
        targetBinder: IBinder?,
        legacyEndpoint: IBinder,
        nightEndpoint: IBinder
    ) {
        if (targetBinder == null) {
            throw IllegalArgumentException("Target binder cannot be null")
        }
        if (targetBinder === legacyEndpoint || targetBinder === nightEndpoint) {
            throw SecurityException("Self-referential binder forwarding to server endpoint is forbidden")
        }
        val descriptor = try {
            targetBinder.interfaceDescriptor
        } catch (_: Throwable) {
            null
        }
        if (descriptor == ShizukuApiConstants.BINDER_DESCRIPTOR ||
            descriptor == NightProtocol.DESCRIPTOR
        ) {
            throw SecurityException("Forwarding transactions to privileged server interface ($descriptor) is forbidden")
        }
    }

    @JvmStatic
    fun effectiveCapabilities(
        entry: ShizukuConfig.PackageEntry?,
        accessPaused: Boolean = false
    ): Int {
        if (accessPaused || entry == null || !entry.isAllowed()) return 0
        val explicitCaps = entry.flags and NightProtocol.MASK_CAPABILITIES
        return if (explicitCaps == 0) NightProtocol.DEFAULT_CAPABILITIES else explicitCaps
    }

    @JvmStatic
    fun enforceCapability(
        configManager: ShizukuConfigManager,
        callingUid: Int,
        isManager: Boolean,
        requiredCapability: Int,
        operationName: String
    ) {
        if (isManager) return
        if (configManager.isAccessPaused()) {
            throw SecurityException("Permission Denial: client access is globally paused ($operationName, uid=$callingUid)")
        }
        val entry = configManager.find(callingUid)
        val caps = effectiveCapabilities(entry, accessPaused = false)
        if ((caps and requiredCapability) == 0) {
            throw SecurityException(
                "Permission Denial: $operationName requires capability 0x${Integer.toHexString(requiredCapability)} for uid=$callingUid"
            )
        }
    }
}
