package moe.shizuku.manager.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import rikka.core.util.ClipboardUtils
import rikka.html.text.HtmlCompat

object CustomTabsHelper {

    interface OnCreateIntentBuilderListener {
        fun onCreateHelpIntentBuilder(context: Context, builder: CustomTabsIntent.Builder)
    }

    private var sOnCreateIntentBuilderListener: OnCreateIntentBuilderListener? = null

    @JvmStatic
    fun setOnCreateIntentBuilderListener(onCreateIntentBuilderListener: OnCreateIntentBuilderListener?) {
        sOnCreateIntentBuilderListener = onCreateIntentBuilderListener
    }

    @JvmStatic
    fun createBuilder(): CustomTabsIntent.Builder {
        val builder = CustomTabsIntent.Builder()
        builder.setShowTitle(true)
        return builder
    }

    @JvmStatic
    fun launchHelp(context: Context, uri: Uri): Boolean {
        val builder = createBuilder()

        sOnCreateIntentBuilderListener?.onCreateHelpIntentBuilder(context, builder)

        val uriBuilder = uri.buildUpon()
        if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_YES) > 0) {
            uriBuilder.appendQueryParameter("night", "1")
        }

        return launchUrl(context, builder.build(), uriBuilder.build())
    }

    @JvmStatic
    fun launchUrl(context: Context, uri: Uri): Boolean {
        return launchUrl(context, createBuilder().build(), uri)
    }

    private fun launchUrl(context: Context, customTabsIntent: CustomTabsIntent, uri: Uri): Boolean {
        return try {
            customTabsIntent.launchUrl(context, uri)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    @JvmStatic
    fun launchUrlOrCopy(context: Context, url: String) {
        val uri = Uri.parse(url)
        if (!launchHelp(context, uri)) {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = uri

            try {
                context.startActivity(intent)
            } catch (tr: Throwable) {
                try {
                    ClipboardUtils.put(context, url)

                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.dialog_cannot_open_browser_title)
                        .setMessage(HtmlCompat.fromHtml(context.getString(R.string.toast_copied_to_clipboard_with_text, url)))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } catch (ignored: Throwable) {
                }
            }
        }
    }
}
