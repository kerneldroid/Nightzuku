package rikka.shizuku.server

import android.content.pm.UserInfo
import android.os.Build

/**
 * Classifies Android user profiles and enforces user/profile isolation and Binder delivery rules.
 *
 * Prevents `shizuku_server` from probing, temp-whitelisting, or dispatching Binders to isolated
 * containers such as Samsung Knox / Secure Folder, Samsung Dual Messenger, Android 15+ Private
 * Space, and OEM Clone / Dual App profiles by default.
 */
object UserAccessPolicy {

    const val BINDER_DELIVERY_ALL_DECLARED = 0
    const val BINDER_DELIVERY_AUTHORIZED_ONLY = 1

    /** Samsung Knox workspace flag bit in `UserInfo.flags` (`SEM_FLAG_KNOX_WORKSPACE`). */
    private const val FLAG_KNOX_WORKSPACE = 0x00001000

    private const val USER_TYPE_PROFILE_MANAGED = "android.os.usertype.profile.MANAGED"
    private const val USER_TYPE_PROFILE_PRIVATE = "android.os.usertype.profile.PRIVATE"
    private const val USER_TYPE_PROFILE_CLONE = "android.os.usertype.profile.CLONE"

    private val ISOLATED_KEYWORDS = listOf(
        "knox",
        "secure folder",
        "secure_folder",
        "securefolder",
        "dual messenger",
        "dualapp",
        "dual app",
        "parallel",
        "private space",
        "b2b",
        "workspace"
    )

    enum class ProfileKind(val wireName: String) {
        PRIMARY("primary"),
        WORK_PROFILE("work_profile"),
        ISOLATED_CONTAINER("isolated_container"),
        SECONDARY_USER("secondary_user");

        companion object {
            fun fromWireName(value: String?): ProfileKind {
                return entries.firstOrNull { it.wireName == value } ?: SECONDARY_USER
            }
        }
    }

    data class DeviceUserSnapshot(
        val userId: Int,
        val name: String,
        val kind: ProfileKind,
        val allowed: Boolean
    )

    @JvmStatic
    fun classify(userInfo: UserInfo): ProfileKind {
        val userType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { userInfo.userType }.getOrNull()
        } else {
            null
        }
        return classify(
            userId = userInfo.id,
            userType = userType,
            flags = userInfo.flags,
            name = userInfo.name
        )
    }

    @JvmStatic
    fun classify(userId: Int, userType: String?, flags: Int, name: String?): ProfileKind {
        if (userId == 0) {
            return ProfileKind.PRIMARY
        }

        val lowerType = userType?.lowercase().orEmpty()
        val lowerName = name?.lowercase().orEmpty()

        // Isolated containers must be checked BEFORE managed/work profile because Samsung Knox
        // and Secure Folder often also carry FLAG_MANAGED_PROFILE or profile.MANAGED.
        val isIsolatedById = userId in 100..199 || userId == 95 || userId == 96 || userId == 128 || userId == 999
        val isIsolatedByType = userType == USER_TYPE_PROFILE_PRIVATE ||
            userType == USER_TYPE_PROFILE_CLONE ||
            ISOLATED_KEYWORDS.any { lowerType.contains(it) }
        val isIsolatedByName = ISOLATED_KEYWORDS.any { lowerName.contains(it) }
        val isIsolatedByFlag = (flags and FLAG_KNOX_WORKSPACE) != 0

        if (isIsolatedById || isIsolatedByType || isIsolatedByName || isIsolatedByFlag) {
            return ProfileKind.ISOLATED_CONTAINER
        }

        val isWorkProfile = userType == USER_TYPE_PROFILE_MANAGED ||
            (flags and UserInfo.FLAG_MANAGED_PROFILE) != 0
        if (isWorkProfile) {
            return ProfileKind.WORK_PROFILE
        }

        return ProfileKind.SECONDARY_USER
    }

    @JvmStatic
    fun isUserAllowed(userId: Int, kind: ProfileKind, config: ShizukuConfig): Boolean {
        if (userId == 0 || kind == ProfileKind.PRIMARY) {
            return true
        }
        val explicitAllowed = config.allowedUserIds?.contains(userId) == true
        return when (kind) {
            ProfileKind.PRIMARY -> true
            ProfileKind.ISOLATED_CONTAINER -> {
                // Isolated containers (Knox / Secure Folder / Private Space / Dual Messenger) are
                // blocked whenever blockIsolatedProfiles is enabled, and never auto-enabled even
                // when the global block is lifted (explicit per-user opt-in required).
                !config.blockIsolatedProfiles && explicitAllowed
            }
            ProfileKind.WORK_PROFILE -> config.allowWorkProfile || explicitAllowed
            ProfileKind.SECONDARY_USER -> config.allowSecondaryUsers || explicitAllowed
        }
    }

    @JvmStatic
    fun shouldDeliverBinder(
        wire: ClientRouting.Wire,
        config: ShizukuConfig,
        isAuthorized: Boolean
    ): Boolean {
        if (wire == ClientRouting.Wire.SHIZUKU && !config.allowLegacyWire) {
            return false
        }
        if (config.binderDeliveryMode == BINDER_DELIVERY_AUTHORIZED_ONLY && !isAuthorized) {
            return false
        }
        return true
    }

    @JvmStatic
    fun sanitizeDeliveryMode(mode: Int): Int {
        return if (mode == BINDER_DELIVERY_AUTHORIZED_ONLY) {
            BINDER_DELIVERY_AUTHORIZED_ONLY
        } else {
            BINDER_DELIVERY_ALL_DECLARED
        }
    }
}
