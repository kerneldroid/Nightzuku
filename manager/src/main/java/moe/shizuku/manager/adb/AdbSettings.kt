package moe.shizuku.manager.adb

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kerneldroid.nightzuku.api.NightClient

/**
 * Manages the global debug settings needed for wireless ADB startup.
 *
 * Platform behavior split:
 * - Android 11–16 (API 30–36): `WRITE_SECURE_SETTINGS` (granted once by the Nightzuku server)
 *   is sufficient to write `adb_enabled`, `adb_wifi_enabled`, and `adb_allowed_connection_time`.
 * - Android 17+ (API 37, verified against Canary ZP11.261016.008):
 *   1. `SettingsProvider` requires `MANAGE_DEBUGGING` (`signature|privileged`) for writes.
 *   2. Both `ADB_ENABLED` and `ADB_WIFI_ENABLED` are annotated `@Readable(redactedValue = "0")`
 *      in `android/provider/Settings.java`, so `Settings.Global.getInt()` always returns `0` to
 *      normal apps even when wireless debugging is active.
 *   3. Native ADB Wi-Fi 2.0 automatically re-enables `adbd` on trusted Wi-Fi networks, so boot
 *      startup must defer directly to mDNS discovery instead of gating on redacted reads.
 */
object AdbSettings {

    enum class BootPrepareResult {
        /** Settings are confirmed enabled on API 30–36. */
        ENABLED,
        /** Android 17+ redacts reads and restricts writes; proceed directly to mDNS discovery. */
        REDACTION_DEFER_TO_MDNS,
        /** Settings could not be enabled on API 30–36. */
        UNAVAILABLE
    }

    private const val TAG = "AdbSettings"
    private const val API_ANDROID_17 = 37
    private const val KEY_WIFI_ADB = "adb_wifi_enabled"
    private const val KEY_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"

    /**
     * Keys written as integers in order: `adb_enabled` is written best-effort before
     * `adb_wifi_enabled` so ROMs that check USB debugging see it set first.
     */
    private val INT_KEYS = linkedMapOf(
        Settings.Global.ADB_ENABLED to 1,
        KEY_WIFI_ADB to 1
    )

    /** The system disables the ADB connection timer with 0. */
    private const val ALLOWED_CONNECTION_TIME = 0L

    /**
     * Returns true on Android 17+ where `ADB_ENABLED` and `ADB_WIFI_ENABLED` return redacted `0`.
     */
    @JvmStatic
    fun isReadRedactedPlatform(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= API_ANDROID_17

    /**
     * Prepares wireless ADB state for automated boot startup without aborting on Android 17+
     * read redaction.
     */
    @JvmStatic
    fun prepareWirelessAdbForBoot(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): BootPrepareResult {
        if (isReadRedactedPlatform(sdkInt)) {
            // Do not gate on Settings.Global.getInt() because API 37+ always redacts it to 0.
            // If the server is somehow already running, best-effort ensure timeout=0; otherwise
            // defer directly to mDNS so native ADB Wi-Fi 2.0 on trusted Wi-Fi is picked up.
            if (NightClient.pingBinder()) {
                writeThroughServer(listOf(KEY_ALLOWED_CONNECTION_TIME to ALLOWED_CONNECTION_TIME.toString()))
            }
            return BootPrepareResult.REDACTION_DEFER_TO_MDNS
        }
        return if (enableWirelessAdb(context)) {
            BootPrepareResult.ENABLED
        } else {
            BootPrepareResult.UNAVAILABLE
        }
    }

    /**
     * Pre-provisions persistent ADB settings while the Nightzuku server is alive (UID 2000 shell
     * holds MANAGE_DEBUGGING):
     * 1. Sets `adb_allowed_connection_time = 0` so paired TLS/RSA keys never expire after 7 days.
     * 2. On Android 11–16, ensures `WRITE_SECURE_SETTINGS` is granted to the manager package.
     */
    @JvmStatic
    fun preProvisionForAutostart(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean {
        if (!NightClient.pingBinder()) {
            return false
        }

        val timeoutOk = writeThroughServer(
            listOf(KEY_ALLOWED_CONNECTION_TIME to ALLOWED_CONNECTION_TIME.toString())
        )

        if (sdkInt < API_ANDROID_17 &&
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                val result = NightClient.execFast(
                    arrayOf("pm", "grant", context.packageName, Manifest.permission.WRITE_SECURE_SETTINGS)
                )
                if (!(result.exitCode == 0 && !result.timedOut)) {
                    Log.w(
                        TAG,
                        "Failed to pre-grant WRITE_SECURE_SETTINGS (exitCode=${result.exitCode}, timedOut=${result.timedOut}): ${result.stderr}"
                    )
                }
            }.onFailure { t ->
                Log.w(TAG, "Failed to pre-grant WRITE_SECURE_SETTINGS", t)
            }
        }

        return timeoutOk
    }

    /**
     * Enables wireless ADB and disables the connection timeout on a best-effort basis.
     *
     * Writes `adb_enabled = 1` best-effort before `adb_wifi_enabled = 1`, but gates the return
     * value strictly on `adb_wifi_enabled == 1` so devices where USB debugging stays `0`
     * (such as Android TV 14) still succeed.
     *
     * @return true when `adb_wifi_enabled` is set to `1` afterwards.
     */
    fun enableWirelessAdb(context: Context): Boolean {
        val resolver = context.contentResolver
        if (isWirelessAdbEnabled(resolver) &&
            readLong(resolver, KEY_ALLOWED_CONNECTION_TIME) == ALLOWED_CONNECTION_TIME
        ) {
            return true
        }

        val directAllowed = context.checkSelfPermission(
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
        val serverFallback = mutableListOf<Pair<String, String>>()

        for ((key, value) in INT_KEYS) {
            if (readInt(resolver, key) == value) {
                continue
            }
            if (directAllowed) {
                runCatching { Settings.Global.putInt(resolver, key, value) }
            }
            if (readInt(resolver, key) != value) {
                serverFallback += key to value.toString()
            }
        }

        // Best-effort: disable the ADB connection timeout without failing the overall check
        // if writing it fails (e.g. at boot when the server is not yet running and the app
        // does not hold WRITE_SECURE_SETTINGS).
        if (readLong(resolver, KEY_ALLOWED_CONNECTION_TIME) != ALLOWED_CONNECTION_TIME) {
            if (directAllowed) {
                runCatching {
                    Settings.Global.putLong(
                        resolver,
                        KEY_ALLOWED_CONNECTION_TIME,
                        ALLOWED_CONNECTION_TIME
                    )
                }
            }
            if (readLong(resolver, KEY_ALLOWED_CONNECTION_TIME) != ALLOWED_CONNECTION_TIME) {
                serverFallback += KEY_ALLOWED_CONNECTION_TIME to ALLOWED_CONNECTION_TIME.toString()
            }
        }

        if (serverFallback.isNotEmpty()) {
            writeThroughServer(serverFallback)
        }

        return isWirelessAdbEnabled(resolver)
    }

    fun isWirelessAdbEnabled(resolver: ContentResolver): Boolean =
        readInt(resolver, KEY_WIFI_ADB) == 1

    /**
     * Runs `settings put global <key> <value>` through the Nightzuku server.
     * The server runs as shell or root and therefore holds MANAGE_DEBUGGING.
     */
    private fun writeThroughServer(entries: List<Pair<String, String>>): Boolean {
        if (!NightClient.pingBinder()) {
            return false
        }
        var allSucceeded = true
        for ((key, value) in entries) {
            val ok = runCatching {
                val result = NightClient.execFast(
                    arrayOf("settings", "put", "global", key, value)
                )
                result.exitCode == 0 && !result.timedOut
            }.getOrDefault(false)
            if (!ok) {
                allSucceeded = false
            }
        }
        return allSucceeded
    }

    private fun readInt(resolver: ContentResolver, key: String): Int =
        runCatching { Settings.Global.getInt(resolver, key, -1) }.getOrDefault(-1)

    private fun readLong(resolver: ContentResolver, key: String): Long =
        runCatching { Settings.Global.getLong(resolver, key, -1L) }.getOrDefault(-1L)
}
