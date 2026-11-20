package moe.shizuku.manager.settings

import android.os.Bundle
import android.os.Parcel
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.ServerConstants

data class SecurityPolicyState(
    val accessPaused: Boolean = false,
    val blockIsolatedProfiles: Boolean = true,
    val allowWorkProfile: Boolean = false,
    val allowSecondaryUsers: Boolean = false,
    val allowLegacyWire: Boolean = true,
    val binderDeliveryMode: Int = BINDER_DELIVERY_ALL_DECLARED,
    val allowedUserIds: Set<Int> = emptySet()
) {
    companion object {
        const val BINDER_DELIVERY_ALL_DECLARED = 0
        const val BINDER_DELIVERY_AUTHORIZED_ONLY = 1
    }
}

data class DeviceUserEntry(
    val userId: Int,
    val name: String,
    val kind: String,
    val allowed: Boolean
) {
    val isPrimary: Boolean get() = userId == 0 || kind == KIND_PRIMARY
    val isIsolated: Boolean get() = kind == KIND_ISOLATED_CONTAINER
    val isWorkProfile: Boolean get() = kind == KIND_WORK_PROFILE

    companion object {
        const val KIND_PRIMARY = "primary"
        const val KIND_WORK_PROFILE = "work_profile"
        const val KIND_ISOLATED_CONTAINER = "isolated_container"
        const val KIND_SECONDARY_USER = "secondary_user"
    }
}

object SecurityPolicyClient {

    fun isServerAvailable(): Boolean {
        val binder = runCatching { Shizuku.getBinder() }.getOrNull() ?: return false
        return runCatching { binder.pingBinder() }.getOrDefault(false)
    }

    fun getSecurityPolicy(): SecurityPolicyState? {
        val binder = runCatching { Shizuku.getBinder() }.getOrNull() ?: return null
        if (!runCatching { binder.pingBinder() }.getOrDefault(false)) return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            if (!binder.transact(ServerConstants.BINDER_TRANSACTION_getSecurityPolicy, data, reply, 0)) {
                return null
            }
            reply.readException()
            val bundle = reply.readBundle(SecurityPolicyClient::class.java.classLoader) ?: return null
            SecurityPolicyState(
                accessPaused = bundle.getBoolean("accessPaused", false),
                blockIsolatedProfiles = bundle.getBoolean("blockIsolatedProfiles", true),
                allowWorkProfile = bundle.getBoolean("allowWorkProfile", false),
                allowSecondaryUsers = bundle.getBoolean("allowSecondaryUsers", false),
                allowLegacyWire = bundle.getBoolean("allowLegacyWire", true),
                binderDeliveryMode = bundle.getInt(
                    "binderDeliveryMode",
                    SecurityPolicyState.BINDER_DELIVERY_ALL_DECLARED
                ),
                allowedUserIds = bundle.getIntArray("allowedUserIds")?.toSet().orEmpty()
            )
        } catch (_: Throwable) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun setSecurityPolicy(state: SecurityPolicyState): Boolean {
        val binder = runCatching { Shizuku.getBinder() }.getOrNull() ?: return false
        if (!runCatching { binder.pingBinder() }.getOrDefault(false)) return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            val bundle = Bundle().apply {
                putBoolean("accessPaused", state.accessPaused)
                putBoolean("blockIsolatedProfiles", state.blockIsolatedProfiles)
                putBoolean("allowWorkProfile", state.allowWorkProfile)
                putBoolean("allowSecondaryUsers", state.allowSecondaryUsers)
                putBoolean("allowLegacyWire", state.allowLegacyWire)
                putInt("binderDeliveryMode", state.binderDeliveryMode)
                putIntArray("allowedUserIds", state.allowedUserIds.sorted().toIntArray())
            }
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            data.writeBundle(bundle)
            if (!binder.transact(ServerConstants.BINDER_TRANSACTION_setSecurityPolicy, data, reply, 0)) {
                return false
            }
            reply.readException()
            true
        } catch (_: Throwable) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun getDeviceUsers(): List<DeviceUserEntry> {
        val binder = runCatching { Shizuku.getBinder() }.getOrNull() ?: return emptyList()
        if (!runCatching { binder.pingBinder() }.getOrDefault(false)) return emptyList()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            if (!binder.transact(ServerConstants.BINDER_TRANSACTION_getDeviceUsers, data, reply, 0)) {
                return emptyList()
            }
            reply.readException()
            val bundle = reply.readBundle(SecurityPolicyClient::class.java.classLoader) ?: return emptyList()
            val ids = bundle.getIntArray("userIds") ?: return emptyList()
            val names = bundle.getStringArrayList("userNames") ?: ArrayList()
            val kinds = bundle.getStringArrayList("userKinds") ?: ArrayList()
            val allowed = bundle.getBooleanArray("userAllowed") ?: BooleanArray(ids.size)
            ids.indices.map { i ->
                DeviceUserEntry(
                    userId = ids[i],
                    name = names.getOrElse(i) { "User ${ids[i]}" },
                    kind = kinds.getOrElse(i) { DeviceUserEntry.KIND_SECONDARY_USER },
                    allowed = if (i < allowed.size) allowed[i] else (ids[i] == 0)
                )
            }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
