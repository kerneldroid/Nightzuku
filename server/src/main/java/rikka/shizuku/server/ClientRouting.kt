package rikka.shizuku.server

import android.content.pm.PackageInfo
import kerneldroid.nightzuku.api.NightProtocol

/**
 * Routes client packages to either the native Nightzuku wire or the legacy Shizuku wire
 * without requiring any stub or companion APK.
 */
object ClientRouting {

    enum class Wire {
        NIGHTZUKU,
        SHIZUKU
    }

    @JvmStatic
    fun requests(permissions: Array<String>?, permission: String): Boolean {
        return permissions != null && permission in permissions
    }

    /**
     * Determines which wire(s) a package should receive binders on.
     * Packages declaring a manager permission (`*.permission.MANAGER`) are never treated as ordinary clients.
     */
    @JvmStatic
    fun route(client: PackageInfo?): Wire? {
        val requestedPermissions = client?.requestedPermissions ?: return null
        val declaredPermissions = client.permissions
        if (declaredPermissions != null) {
            for (perm in declaredPermissions) {
                val name = perm.name
                if (name != null && name.endsWith(".permission.MANAGER") && client.packageName != ServerConstants.MANAGER_APPLICATION_ID) {
                    return null
                }
            }
        }
        if (requests(requestedPermissions, ServerConstants.PERMISSION)) {
            return Wire.NIGHTZUKU
        }
        if (requests(requestedPermissions, ServerConstants.LEGACY_PERMISSION)) {
            return Wire.SHIZUKU
        }
        return null
    }

    @JvmStatic
    fun supportsLegacyWire(client: PackageInfo?): Boolean {
        val requestedPermissions = client?.requestedPermissions ?: return false
        return requests(requestedPermissions, ServerConstants.LEGACY_PERMISSION)
    }

    @JvmStatic
    fun providerSuffix(wire: Wire): String {
        return when (wire) {
            Wire.NIGHTZUKU -> NightProtocol.PROVIDER_SUFFIX
            Wire.SHIZUKU -> NightProtocol.LEGACY_PROVIDER_SUFFIX
        }
    }
}
