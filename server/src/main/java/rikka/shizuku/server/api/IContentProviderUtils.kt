package rikka.shizuku.server.api

import android.content.AttributionSource
import android.content.IContentProvider
import android.os.Build
import android.os.Bundle
import rikka.shizuku.server.util.OsUtils

object IContentProviderUtils {

    fun callCompat(
        provider: IContentProvider,
        callingPkg: String?,
        authority: String?,
        method: String?,
        arg: String?,
        extras: Bundle?
    ): Bundle? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            provider.call(
                AttributionSource.Builder(OsUtils.getUid()).setPackageName(callingPkg).build(),
                authority, method, arg, extras
            )
        } else if (Build.VERSION.SDK_INT >= 30) {
            provider.call(callingPkg, null, authority, method, arg, extras)
        } else if (Build.VERSION.SDK_INT >= 29) {
            provider.call(callingPkg, authority, method, arg, extras)
        } else {
            provider.call(callingPkg, method, arg, extras)
        }
    }
}
