package moe.shizuku.starter.util

import android.content.AttributionSource
import android.content.IContentProvider
import android.os.Build
import android.os.Bundle
import android.system.Os

object IContentProviderCompat {

    @JvmStatic
    fun call(provider: IContentProvider, attributeTag: String?, callingPkg: String?, authority: String?, method: String?, arg: String?, extras: Bundle?): Bundle? {
        return if (Build.VERSION.SDK_INT >= 31) {
            try {
                provider.call(AttributionSource.Builder(Os.getuid()).setAttributionTag(attributeTag).setPackageName(callingPkg).build(), authority, method, arg, extras)
            } catch (e: LinkageError) {
                provider.call(callingPkg, attributeTag, authority, method, arg, extras)
            }
        } else if (Build.VERSION.SDK_INT >= 30) {
            provider.call(callingPkg, attributeTag, authority, method, arg, extras)
        } else if (Build.VERSION.SDK_INT >= 29) {
            provider.call(callingPkg, authority, method, arg, extras)
        } else {
            provider.call(callingPkg, method, arg, extras)
        }
    }
}
