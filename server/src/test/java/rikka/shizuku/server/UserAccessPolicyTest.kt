package rikka.shizuku.server

import android.content.pm.UserInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAccessPolicyTest {

    // UserInfo.FLAG_PRIMARY is @hide and absent from dev.rikka.hidden:stub 4.4.0.
    private val FLAG_PRIMARY = 0x00000001

    @Test
    fun `classify detects Knox, Secure Folder, Dual Messenger, Clone, and Private Space as isolated`() {
        assertEquals(
            UserAccessPolicy.ProfileKind.PRIMARY,
            UserAccessPolicy.classify(0, "android.os.usertype.full.SYSTEM", FLAG_PRIMARY, "Owner")
        )
        // Samsung Secure Folder (user 150) often carries FLAG_MANAGED_PROFILE
        assertEquals(
            UserAccessPolicy.ProfileKind.ISOLATED_CONTAINER,
            UserAccessPolicy.classify(150, "android.os.usertype.profile.MANAGED", UserInfo.FLAG_MANAGED_PROFILE, "Secure Folder")
        )
        // Samsung Dual Messenger (user 95)
        assertEquals(
            UserAccessPolicy.ProfileKind.ISOLATED_CONTAINER,
            UserAccessPolicy.classify(95, null, 0, "Dual Messenger")
        )
        // Android 15+ Private Space
        assertEquals(
            UserAccessPolicy.ProfileKind.ISOLATED_CONTAINER,
            UserAccessPolicy.classify(11, "android.os.usertype.profile.PRIVATE", 0, "Private space")
        )
        // Android 14+ Clone / Xiaomi Dual Apps (user 999)
        assertEquals(
            UserAccessPolicy.ProfileKind.ISOLATED_CONTAINER,
            UserAccessPolicy.classify(999, "android.os.usertype.profile.CLONE", 0, "Dual Apps")
        )
        // Standard Work Profile
        assertEquals(
            UserAccessPolicy.ProfileKind.WORK_PROFILE,
            UserAccessPolicy.classify(10, "android.os.usertype.profile.MANAGED", UserInfo.FLAG_MANAGED_PROFILE, "Work profile")
        )
        // Secondary User
        assertEquals(
            UserAccessPolicy.ProfileKind.SECONDARY_USER,
            UserAccessPolicy.classify(12, "android.os.usertype.full.SECONDARY", 0, "Guest")
        )
    }

    @Test
    fun `default config allows only primary user and blocks Knox, work profile, and secondary users`() {
        val config = ShizukuConfig()
        assertTrue(UserAccessPolicy.isUserAllowed(0, UserAccessPolicy.ProfileKind.PRIMARY, config))
        assertFalse(UserAccessPolicy.isUserAllowed(150, UserAccessPolicy.ProfileKind.ISOLATED_CONTAINER, config))
        assertFalse(UserAccessPolicy.isUserAllowed(95, UserAccessPolicy.ProfileKind.ISOLATED_CONTAINER, config))
        assertFalse(UserAccessPolicy.isUserAllowed(10, UserAccessPolicy.ProfileKind.WORK_PROFILE, config))
        assertFalse(UserAccessPolicy.isUserAllowed(12, UserAccessPolicy.ProfileKind.SECONDARY_USER, config))
    }

    @Test
    fun `isolated containers stay blocked while blockIsolatedProfiles is true even if allowWorkProfile is enabled`() {
        val config = ShizukuConfig().apply {
            allowWorkProfile = true
            allowSecondaryUsers = true
            blockIsolatedProfiles = true
            allowedUserIds = mutableListOf(150)
        }
        assertTrue(UserAccessPolicy.isUserAllowed(10, UserAccessPolicy.ProfileKind.WORK_PROFILE, config))
        assertTrue(UserAccessPolicy.isUserAllowed(12, UserAccessPolicy.ProfileKind.SECONDARY_USER, config))
        assertFalse(UserAccessPolicy.isUserAllowed(150, UserAccessPolicy.ProfileKind.ISOLATED_CONTAINER, config))
    }

    @Test
    fun `isolated container requires both blockIsolatedProfiles false and explicit user opt-in`() {
        val config = ShizukuConfig().apply {
            blockIsolatedProfiles = false
            allowedUserIds = mutableListOf()
        }
        assertFalse(UserAccessPolicy.isUserAllowed(150, UserAccessPolicy.ProfileKind.ISOLATED_CONTAINER, config))

        config.allowedUserIds!!.add(150)
        assertTrue(UserAccessPolicy.isUserAllowed(150, UserAccessPolicy.ProfileKind.ISOLATED_CONTAINER, config))
    }

    @Test
    fun `shouldDeliverBinder enforces authorized-only mode and legacy wire toggle`() {
        val config = ShizukuConfig()
        assertTrue(UserAccessPolicy.shouldDeliverBinder(ClientRouting.Wire.NIGHTZUKU, config, isAuthorized = false))
        assertTrue(UserAccessPolicy.shouldDeliverBinder(ClientRouting.Wire.SHIZUKU, config, isAuthorized = false))

        config.binderDeliveryMode = UserAccessPolicy.BINDER_DELIVERY_AUTHORIZED_ONLY
        assertFalse(UserAccessPolicy.shouldDeliverBinder(ClientRouting.Wire.NIGHTZUKU, config, isAuthorized = false))
        assertTrue(UserAccessPolicy.shouldDeliverBinder(ClientRouting.Wire.NIGHTZUKU, config, isAuthorized = true))

        config.allowLegacyWire = false
        assertFalse(UserAccessPolicy.shouldDeliverBinder(ClientRouting.Wire.SHIZUKU, config, isAuthorized = true))
        assertTrue(UserAccessPolicy.shouldDeliverBinder(ClientRouting.Wire.NIGHTZUKU, config, isAuthorized = true))
    }
}
