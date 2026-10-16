package kerneldroid.shizutest

import android.os.Bundle
import kerneldroid.nightzuku.api.NightClient
import kerneldroid.nightzuku.api.NightProtocol
import rikka.shizuku.ShizukuProvider

class NightTestProvider : ShizukuProvider() {

    override fun onCreate(): Boolean {
        disableAutomaticSuiInitialization()
        return super.onCreate()
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == NightProtocol.METHOD_SEND_BINDER && extras != null) {
            val binder = extras.getBinder(NightProtocol.EXTRA_BINDER)
            if (binder != null && context != null) {
                NightClient.onBinderReceived(binder, context!!.packageName)
            }
            return Bundle()
        }
        return super.call(method, arg, extras)
    }
}
