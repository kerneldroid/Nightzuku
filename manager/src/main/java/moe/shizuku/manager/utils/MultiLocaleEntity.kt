package moe.shizuku.manager.utils

import java.util.LinkedHashMap
import java.util.Locale
import moe.shizuku.manager.ShizukuSettings

class MultiLocaleEntity : LinkedHashMap<String, String>() {

    abstract class LocaleProvider {
        abstract fun get(): Locale
    }

    fun get(): String {
        return get(sLocaleProvider.get()) ?: ""
    }

    fun get(locale: Locale): String? {
        if (size > 0) {
            val language = locale.language
            val region = locale.country

            var l = Locale(language, region)
            for (key in keys) {
                if (l.toString() == key.replace('-', '_')) {
                    return get(key)
                }
            }

            l = Locale(language)
            for (key in keys) {
                if (l.toString() == key) {
                    return get(key)
                }
            }

            for (key in keys) {
                if (key.startsWith(l.toString())) {
                    return get(key)
                }
            }

            if (containsKey("en")) {
                return get("en")
            }

            if (containsKey("default")) {
                return get("default")
            }

            for (key in keys) {
                if ("overwrite_default" != key) {
                    return get(key)
                }
            }
        }
        return null
    }

    companion object {

        @JvmField
        val DEFAULT_LOCAL_PROVIDER: LocaleProvider = object : LocaleProvider() {
            override fun get(): Locale {
                return ShizukuSettings.getLocale()
            }
        }

        private var sLocaleProvider: LocaleProvider = DEFAULT_LOCAL_PROVIDER

        @JvmStatic
        fun setLocaleProvider(localeProvider: LocaleProvider) {
            sLocaleProvider = localeProvider
        }
    }
}
