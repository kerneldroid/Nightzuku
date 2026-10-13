package moe.shizuku.common.util

import android.os.SELinux
import android.system.Os

object OsUtils {

    private val UID = Os.getuid()
    private val PID = Os.getpid()
    private val SELINUX_CONTEXT: String? = try {
        SELinux.getContext()
    } catch (tr: Throwable) {
        null
    }

    @JvmStatic
    fun getUid(): Int {
        return UID
    }

    @JvmStatic
    fun getPid(): Int {
        return PID
    }

    @JvmStatic
    fun getSELinuxContext(): String? {
        return SELINUX_CONTEXT
    }
}
