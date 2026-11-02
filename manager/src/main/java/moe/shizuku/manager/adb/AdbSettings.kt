package moe.shizuku.manager.adb

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import kerneldroid.nightzuku.api.NightClient

/**
 * Writes the debug settings needed to bring up wireless ADB.
 *
 * Android 17 QPR3 tightened `SettingsProvider`: writing `adb_enabled`,
 * `adb_wifi_enabled` or `adb_allowed_connection_time` now also requires
 * `MANAGE_DEBUGGING`, which is signature|privileged and cannot be held by a
 * normal app even when `WRITE_SECURE_SETTINGS` was granted by Nightzuku's
 * server. Instead of letting the first write throw and abort the flow, this
 * helper:
 *
 * 1. skips keys that already have the desired value;
 * 2. tries the direct write when the app holds `WRITE_SECURE_SETTINGS`;
 * 3. falls back to the Nightzuku server (shell/root UID), where the same
 *    `settings put` command does hold `MANAGE_DEBUGGING`;
 * 4. verifies the resulting values before reporting success.
 */
object AdbSettings {

    private const val KEY_WIFI_ADB = "adb_wifi_enabled"
    private const val KEY_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"

    /** Keys written as integers; all must end up equal to the mapped value. */
    private val INT_KEYS = linkedMapOf(
        KEY_WIFI_ADB to 1,
        Settings.Global.ADB_ENABLED to 1
    )

    /** The system disables the ADB connection timer with 0. */
    private const val ALLOWED_CONNECTION_TIME = 0L

    /**
     * Enables wireless ADB and disables the connection timeout.
     *
     * @return true when every key holds the expected value afterwards.
     */
    fun enableWirelessAdb(context: Context): Boolean {
        val resolver = context.contentResolver
        if (isWirelessAdbEnabled(resolver)) {
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
        INT_KEYS.all { (key, value) -> readInt(resolver, key) == value } &&
            readLong(resolver, KEY_ALLOWED_CONNECTION_TIME) == ALLOWED_CONNECTION_TIME

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
