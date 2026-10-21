package rikka.shizuku.server.util

import android.content.Context
import android.content.pm.UserInfo
import android.os.IBinder
import android.os.IUserManager
import android.os.RemoteException
import android.os.ServiceManager
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import rikka.hidden.compat.UserManagerApis
import rikka.hidden.compat.util.SystemServiceBinder

/**
 * Multi-user enumeration and foreground user resolution helpers compatible with Android 17 QPR1+.
 *
 * Android 17 QPR1 narrowed `IUserManager.getUsers` to the single-boolean signature
 * `getUsers(boolean excludeDying)`, causing 3-boolean hidden-compat calls to throw `NoSuchMethodError`.
 */
object UsersCompat {

    private val LOGGER = Logger("UsersCompat")

    private val userManagerBinder = SystemServiceBinder<IUserManager>(Context.USER_SERVICE) {
        IUserManager.Stub.asInterface(it)
    }

    @Volatile
    private var cachedActivityManager: Any? = null

    @Volatile
    private var cachedGetCurrentUserMethod: Method? = null

    @JvmStatic
    @Throws(RemoteException::class)
    fun getUsers(
        excludePartial: Boolean = true,
        excludeDying: Boolean = true,
        excludePreCreated: Boolean = true,
        singleArgFallback: (Boolean) -> List<UserInfo> = { dying ->
            userManagerBinder.get()?.getUsers(dying).orEmpty()
        }
    ): List<UserInfo> {
        return try {
            UserManagerApis.getUsers(excludePartial, excludeDying, excludePreCreated)
        } catch (_: NoSuchMethodError) {
            singleArgFallback(excludeDying)
        }
    }

    @JvmStatic
    fun getUserIdsNoThrow(): List<Int> {
        return try {
            val users = getUsers(excludePartial = true, excludeDying = true, excludePreCreated = true)
            val ids = LinkedHashSet<Int>(users.size)
            for (user in users) {
                ids.add(user.id)
            }
            if (ids.isEmpty()) listOf(0) else ids.toList()
        } catch (t: Throwable) {
            LOGGER.w(t, "Failed to enumerate users, falling back to user 0")
            listOf(0)
        }
    }

    @JvmStatic
    fun getUserInfo(userId: Int): UserInfo? {
        return try {
            UserManagerApis.getUserInfo(userId)
        } catch (t: Throwable) {
            LOGGER.w(t, "getUserInfo(%d) failed", userId)
            null
        }
    }

    @Synchronized
    fun invalidateActivityManager() {
        cachedActivityManager = null
    }

    @Synchronized
    @Throws(Exception::class)
    private fun resolveActivityManager(): Any {
        cachedActivityManager?.let { return it }
        val resolved = try {
            val binder = ServiceManager.getService(Context.ACTIVITY_SERVICE)
                ?: throw IllegalStateException("activity service unavailable")
            val stubClass = Class.forName("android.app.IActivityManager\$Stub")
            stubClass.getDeclaredMethod("asInterface", IBinder::class.java).invoke(null, binder)
        } catch (_: ClassNotFoundException) {
            Class.forName("android.app.ActivityManagerNative")
                .getDeclaredMethod("getDefault")
                .invoke(null)
        } ?: throw IllegalStateException("IActivityManager proxy is null")
        cachedActivityManager = resolved
        return resolved
    }

    @Synchronized
    @Throws(Exception::class)
    private fun resolveGetCurrentUserMethod(): Method {
        cachedGetCurrentUserMethod?.let { return it }
        val method = Class.forName("android.app.IActivityManager").getMethod("getCurrentUser")
        cachedGetCurrentUserMethod = method
        return method
    }

    /**
     * Returns the foreground Android user ID currently displayed on screen, or `null` if it
     * cannot be determined. Retries once after invalidating the cached proxy in case
     * `system_server` restarted.
     */
    @JvmStatic
    fun getForegroundUserId(): Int? {
        repeat(2) { attempt ->
            try {
                val am = resolveActivityManager()
                val userInfo = resolveGetCurrentUserMethod().invoke(am) as? UserInfo
                return userInfo?.id
            } catch (t: Throwable) {
                val cause = (t as? InvocationTargetException)?.targetException ?: t
                if (attempt == 0) {
                    invalidateActivityManager()
                } else {
                    LOGGER.w(cause, "Unable to read foreground user ID")
                }
            }
        }
        return null
    }

    @JvmStatic
    fun isUserForeground(userId: Int): Boolean {
        val fg = getForegroundUserId() ?: return userId == 0
        return fg == userId
    }
}
