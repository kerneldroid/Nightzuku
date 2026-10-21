package moe.shizuku.manager.shell

import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import rikka.rish.Rish
import rikka.rish.RishConfig
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants

class Shell(private val mainHandler: Handler? = null) : Rish() {

    override fun requestPermission(onGrantedRunnable: Runnable) {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            onGrantedRunnable.run()
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            System.err.println("Permission denied")
            System.err.flush()
            System.exit(1)
        } else {
            val permissionTimeout = Runnable {
                System.err.println("Permission request timed out")
                System.err.flush()
                System.exit(1)
            }
            mainHandler?.postDelayed(permissionTimeout, PERMISSION_PROMPT_TIMEOUT_MS)

            Shizuku.addRequestPermissionResultListener(object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    mainHandler?.removeCallbacks(permissionTimeout)
                    Shizuku.removeRequestPermissionResultListener(this)

                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        onGrantedRunnable.run()
                    } else {
                        System.err.println("Permission denied")
                        System.err.flush()
                        System.exit(1)
                    }
                }
            })
            Shizuku.requestPermission(0)
        }
    }

    companion object {

        private const val PERMISSION_PROMPT_TIMEOUT_MS = 60_000L

        @JvmStatic
        fun main(args: Array<String>, packageName: String, binder: IBinder, handler: Handler) {
            // Clear any pending loader discovery timeout so interactive shell sessions are not killed after 5s
            handler.removeCallbacksAndMessages(null)

            RishConfig.init(binder, ShizukuApiConstants.BINDER_DESCRIPTOR, 30000)
            Shizuku.onBinderReceived(binder, packageName)
            Shizuku.addBinderReceivedListenerSticky {
                val version = Shizuku.getVersion()
                if (version < 12) {
                    System.err.println("Rish requires server 12 (running " + version + ")")
                    System.err.flush()
                    System.exit(1)
                }
                Shell(handler).start(args)
            }
        }
    }
}
