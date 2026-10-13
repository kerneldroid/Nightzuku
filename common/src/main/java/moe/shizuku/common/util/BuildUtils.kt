package moe.shizuku.common.util

import android.os.Build

object BuildUtils {

    private val SDK = Build.VERSION.SDK_INT

    private val PREVIEW_SDK = if (SDK >= 23) Build.VERSION.PREVIEW_SDK_INT else 0

    @JvmStatic
    fun atLeast31(): Boolean {
        return SDK >= 31 || SDK == 30 && PREVIEW_SDK > 0
    }

    @JvmStatic
    fun atLeast30(): Boolean {
        return SDK >= 30
    }

    @JvmStatic
    fun atLeast29(): Boolean {
        return SDK >= 29
    }

    @JvmStatic
    fun atLeast28(): Boolean {
        return SDK >= 28
    }

    @JvmStatic
    fun atLeast26(): Boolean {
        return SDK >= 26
    }

    @JvmStatic
    fun atLeast24(): Boolean {
        return SDK >= 24
    }

    @JvmStatic
    fun atLeast23(): Boolean {
        return SDK >= 23
    }
}
