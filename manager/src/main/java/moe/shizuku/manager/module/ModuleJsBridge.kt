package moe.shizuku.manager.module

import android.os.ParcelFileDescriptor
import android.webkit.JavascriptInterface
import moe.shizuku.server.IShizukuService
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

class ModuleJsBridge(private val module: AdbModule) {

    @JavascriptInterface
    fun exec(command: String): String {
        val result = JSONObject()

        if (!module.enabled) {
            return result.apply {
                put("exitCode", -1)
                put("stdout", "")
                put("stderr", "Module is disabled.")
            }.toString()
        }

        val mode = ModuleSettings.getAccessMode()
        if (mode != ModuleSettings.AccessMode.FULL) {
            return result.apply {
                put("exitCode", -1)
                put("stdout", "")
                put("stderr", "Permission denied: requires Full access mode.")
            }.toString()
        }

        val binder = Shizuku.getBinder()
        if (binder == null) {
            return result.apply {
                put("exitCode", -1)
                put("stdout", "")
                put("stderr", "Shizuku service is not running.")
            }.toString()
        }

        return try {
            val service = IShizukuService.Stub.asInterface(binder)
            val env = arrayOf(
                "MODDIR=${module.directory.absolutePath}",
                "ASH_STANDALONE=1",
                "SHIZUKU_MODULE_ID=${module.id}",
                "SHIZUKU_MODULE_MODE=${mode.value}",
                "SHIZUKU_MODULE_BACKGROUND=${if (ModuleSettings.allowBackgroundActions()) "1" else "0"}"
            )
            val remote = service.newProcess(
                arrayOf("sh", "-c", command),
                env,
                module.directory.absolutePath
            )

            ParcelFileDescriptor.AutoCloseOutputStream(remote.outputStream).close()
            var stdout = ""
            var stderr = ""
            val stdoutThread = Thread {
                try {
                    stdout = ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream)
                        .bufferedReader()
                        .readText()
                } catch (ignore: Exception) {}
            }
            val stderrThread = Thread {
                try {
                    stderr = ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream)
                        .bufferedReader()
                        .readText()
                } catch (ignore: Exception) {}
            }
            stdoutThread.start()
            stderrThread.start()
            
            val finished = remote.waitForTimeout(120, TimeUnit.SECONDS.name)
            val exitCode = if (finished) {
                remote.exitValue()
            } else {
                remote.destroy()
                124
            }
            stdoutThread.join(1000)
            stderrThread.join(1000)

            result.apply {
                put("exitCode", exitCode)
                put("stdout", stdout.takeLast(64 * 1024))
                put("stderr", stderr.takeLast(64 * 1024))
            }.toString()
        } catch (e: Exception) {
            result.apply {
                put("exitCode", -1)
                put("stdout", "")
                put("stderr", e.message ?: "Unknown error")
            }.toString()
        }
    }
}
