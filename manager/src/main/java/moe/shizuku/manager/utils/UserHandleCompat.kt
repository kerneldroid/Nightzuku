package moe.shizuku.manager.utils

import android.system.Os

object UserHandleCompat {

    const val PER_USER_RANGE = 100000

    private val MY_USER_ID = getUserId(Os.getuid())

    @JvmStatic
    fun getUserId(uid: Int): Int = uid / PER_USER_RANGE

    @JvmStatic
    fun getAppId(uid: Int): Int = uid % PER_USER_RANGE

    @JvmStatic
    fun myUserId(): Int = MY_USER_ID
}
