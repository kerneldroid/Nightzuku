package moe.shizuku.manager.adb

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import moe.shizuku.manager.R

/**
 * Reads the wireless-debugging pairing code from the Settings dialog on Android TV shells where
 * the notification flow cannot be used. It runs only inside an explicit, two-minute arm window
 * requested from Nightzuku, ignores non-Settings windows, and disables itself afterwards.
 */
class AdbPairingAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var handled = false

    private val timeout = Runnable {
        if (!handled) {
            handled = true
            Toast.makeText(this, R.string.adb_pairing_auto_timeout, Toast.LENGTH_LONG).show()
            AdbPairingAutoDetect.disarm(this)
            disableSelf()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!AdbPairingAutoDetect.isArmed(this)) {
            // Avoid an always-on accessibility service when the user enabled it in Settings
            // without starting the TV pairing flow.
            Log.i(TAG, "No active pairing request, disabling")
            disableSelf()
            return
        }
        Toast.makeText(this, R.string.adb_pairing_auto_waiting, Toast.LENGTH_LONG).show()
        handler.postDelayed(timeout, ARM_TIMEOUT_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (handled || !AdbPairingAutoDetect.isArmed(this)) return
        if (event == null || event.packageName == packageName) return

        val root = rootInActiveWindow ?: return
        val owner = root.packageName?.toString() ?: event.packageName?.toString()
        val texts = collectTexts(root)
        if (texts.isEmpty()) return

        val result = PairingCodeParser.parse(texts, owner) ?: run {
            // OEM shells sometimes put the dialog in a non-"settings" package; retry when the
            // window itself contains pairing wording.
            PairingCodeParser.parse(texts, "pairing")
        } ?: return

        handled = true
        handler.removeCallbacks(timeout)
        Log.i(TAG, "Pairing code detected in $owner (port=${result.port ?: "mDNS"})")
        AdbPairingAutoDetect.submit(this, result.code, result.host, result.port)
        disableSelf()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        super.onDestroy()
    }

    private fun collectTexts(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>(32)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_NODES && out.size < MAX_TEXTS) {
            val node = queue.removeFirst()
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::add)
            }
        }
        return out
    }

    private companion object {
        const val TAG = "AdbPairingAuto"
        const val ARM_TIMEOUT_MS = 2 * 60 * 1000L
        const val MAX_NODES = 400
        const val MAX_TEXTS = 120
    }
}
