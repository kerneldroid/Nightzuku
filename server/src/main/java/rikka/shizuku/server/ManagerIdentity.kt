package rikka.shizuku.server

import rikka.shizuku.server.util.Android17Compat

/**
 * Identity of the installed manager package.
 *
 * The default build uses `kerneldroid.nightzuku`, while stealth builds get a randomized
 * application id. The fixed discovery authority keeps server, starter and stubs independent of
 * the package name.
 */
object ManagerIdentity {

    const val DISCOVERY_AUTHORITY = "kerneldroid.nightzuku.manager"
    const val AUTHORITY_BASE = "kerneldroid.nightzuku"

    @Volatile
    var packageName: String = ServerConstants.MANAGER_APPLICATION_ID
        private set

    val requestPermissionAction: String
        get() = "$packageName${ServerConstants.REQUEST_PERMISSION_ACTION_SUFFIX}"

    /**
     * Resolves the manager through the fixed provider authority. Falls back to the default id when
     * the authority is not visible (e.g. early startup or a legacy install).
     */
    @JvmStatic
    fun resolve(): String {
        val resolved = Android17Compat.resolveContentProvider(DISCOVERY_AUTHORITY, 0)
        if (!resolved.isNullOrBlank()) {
            packageName = resolved
        }
        return packageName
    }
}
