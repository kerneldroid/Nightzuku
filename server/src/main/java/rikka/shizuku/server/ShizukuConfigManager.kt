package rikka.shizuku.server

import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.system.Os
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.StandardCharsets
import kerneldroid.nightzuku.util.PackageSignatureVerifier
import rikka.hidden.compat.PackageManagerApis
import rikka.shizuku.common.util.InstalledPackagesCompat
import rikka.shizuku.server.ktx.workerHandler
import rikka.shizuku.server.util.Android17Compat
import rikka.shizuku.server.util.UserHandleCompat
import rikka.shizuku.server.util.UsersCompat

class ShizukuConfigManager : ConfigManager() {

    private val config: ShizukuConfig = load()

    private val mWriteRunner = Runnable { write(config) }

    init {
        var changed = false

        if (config.packages == null) {
            config.packages = ArrayList()
            changed = true
        }
        val configPackages = config.packages!!

        for (entry in ArrayList(configPackages)) {
            var entryPackages = entry.packages
            if (entryPackages == null) {
                entryPackages = ArrayList()
                entry.packages = entryPackages
            }

            val packages = PackageManagerApis.getPackagesForUidNoThrow(entry.uid)
            if (packages.isEmpty()) {
                ConfigManager.LOGGER.i("remove config for uid %d since it has gone", entry.uid)
                configPackages.remove(entry)
                changed = true
                continue
            }

            var packagesChanged = true
            for (packageName in entryPackages) {
                if (packages.contains(packageName)) {
                    packagesChanged = false
                    break
                }
            }

            val rawSize = entryPackages.size
            val s = LinkedHashSet(entryPackages)
            entryPackages.clear()
            entryPackages.addAll(s)
            val shrunkSize = entryPackages.size
            if (shrunkSize < rawSize) {
                ConfigManager.LOGGER.w("entry.packages has duplicate! Shrunk. (%d -> %d)", rawSize, shrunkSize)
                changed = true
            }

            if (packagesChanged) {
                ConfigManager.LOGGER.i("remove config for uid %d since the packages for it changed", entry.uid)
                configPackages.remove(entry)
                changed = true
                continue
            }

            val retainedPackages = entryPackages.filter { it in packages }
            if (entry.signers.isNullOrEmpty()) {
                val resolvedSigners = resolveSignersForUid(entry.uid, retainedPackages)
                if (!resolvedSigners.isNullOrEmpty()) {
                    entry.signers = resolvedSigners.toMutableList()
                    changed = true
                }
            } else {
                // Verify that the stored signers still match the current installation on disk
                val verified = retainedPackages.any { pkg ->
                    verifyPackageAgainstEntrySigners(entry.uid, pkg, entry.signers!!.toSet()) != false
                }
                if (!verified) {
                    ConfigManager.LOGGER.w("remove config for uid %d since signing certificate changed", entry.uid)
                    configPackages.remove(entry)
                    changed = true
                }
            }
        }

        // Import pre-existing runtime permission grants for Nightzuku's own declared permission
        // when no explicit decision is stored in the config database yet.
        for (userId in UsersCompat.getUserIdsNoThrow()) {
            for (pi in InstalledPackagesCompat.getInstalledPackagesNoThrow(
                PackageManager.GET_PERMISSIONS.toLong(), userId
            )) {
                val applicationInfo = pi.applicationInfo ?: continue
                val requestedPermissions = pi.requestedPermissions ?: continue
                if (!requestedPermissions.contains(ServerConstants.PERMISSION)) {
                    continue
                }

                val uid = applicationInfo.uid
                if (findLocked(uid) != null) {
                    continue
                }

                val allowed = try {
                    Android17Compat.checkPermission(ServerConstants.PERMISSION, uid) == PackageManager.PERMISSION_GRANTED
                } catch (e: Throwable) {
                    ConfigManager.LOGGER.w("checkPermission")
                    continue
                }

                if (allowed) {
                    val packages = ArrayList<String>()
                    packages.add(pi.packageName)
                    updateLocked(uid, packages, ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)
                    changed = true
                }
            }
        }

        if (changed) {
            persistNowLocked()
        }
    }

    private fun resolveSignersForUid(uid: Int, packages: List<String>): Set<String>? {
        val userId = UserHandleCompat.getUserId(uid)
        val expectedAppId = UserHandleCompat.getAppId(uid)
        for (packageName in packages) {
            val pi = Android17Compat.getPackageInfo(
                packageName,
                PackageSignatureVerifier.signatureLookupFlags(),
                userId
            ) ?: continue
            val snapshot = PackageSignatureVerifier.extractSnapshot(pi, userId) ?: continue
            if (snapshot.appId == expectedAppId && snapshot.certDigests.isNotEmpty()) {
                return snapshot.certDigests
            }
        }
        return null
    }

    /**
     * Returns `true` if [packageName] matches [recordedSigners] (including key rotation lineage),
     * `false` if positively signed by a different authority or uninstalled, or `null` if lookup failed transiently.
     */
    private fun verifyPackageAgainstEntrySigners(
        uid: Int,
        packageName: String,
        recordedSigners: Set<String>
    ): Boolean? {
        val userId = UserHandleCompat.getUserId(uid)
        val expectedAppId = UserHandleCompat.getAppId(uid)
        val probe = try {
            val pi = Android17Compat.getPackageInfoOrThrow(
                packageName,
                PackageSignatureVerifier.signatureLookupFlags(),
                userId
            )
            PackageSignatureVerifier.evaluatePackageInfo(pi, userId)
        } catch (t: Throwable) {
            PackageSignatureVerifier.ProbeResult.LookupError(t)
        }
        return when (probe) {
            is PackageSignatureVerifier.ProbeResult.Present -> {
                val baseline = PackageSignatureVerifier.Baseline(packageName, expectedAppId, recordedSigners)
                baseline.matches(probe.snapshot)
            }
            is PackageSignatureVerifier.ProbeResult.Absent -> false
            is PackageSignatureVerifier.ProbeResult.LookupError -> null
        }
    }

    /**
     * Verifies that the stored grant for [uid] belongs to the installation of [packageName]
     * currently attaching. Automatically purges the config entry if the UID was reassigned or
     * the package was replaced with a different signing certificate.
     */
    fun verifyClientIdentity(uid: Int, packageName: String): Boolean {
        val entry = find(uid) ?: return false
        val recordedPackages = synchronized(this) { entry.packages?.toList().orEmpty() }
        val currentUidPackages = PackageManagerApis.getPackagesForUidNoThrow(uid)
        if (currentUidPackages.none { it in recordedPackages }) {
            synchronized(this) {
                if (findLocked(uid) === entry) {
                    config.packages!!.remove(entry)
                    ConfigManager.LOGGER.w("Purging config for uid %d: packages %s no longer belong to uid", uid, recordedPackages)
                    persistNowLocked()
                }
            }
            return false
        }

        val userId = UserHandleCompat.getUserId(uid)
        val probe = try {
            val pi = Android17Compat.getPackageInfoOrThrow(
                packageName,
                PackageSignatureVerifier.signatureLookupFlags(),
                userId
            )
            PackageSignatureVerifier.evaluatePackageInfo(pi, userId)
        } catch (t: Throwable) {
            PackageSignatureVerifier.ProbeResult.LookupError(t)
        }

        val snapshot = (probe as? PackageSignatureVerifier.ProbeResult.Present)?.snapshot ?: return false

        synchronized(this) {
            if (findLocked(uid) !== entry) return false
            val existingSigners = entry.signers
            if (existingSigners.isNullOrEmpty()) {
                entry.signers = snapshot.certDigests.toMutableList()
                scheduleWriteLocked()
                return true
            }
            val baseline = PackageSignatureVerifier.Baseline(
                packageName,
                UserHandleCompat.getAppId(uid),
                existingSigners.toSet()
            )
            if (baseline.matches(snapshot)) {
                return true
            }
            config.packages!!.remove(entry)
            ConfigManager.LOGGER.w("Purging config for uid %d (%s): signing certificate mismatch", uid, packageName)
            persistNowLocked()
            return false
        }
    }

    @Synchronized
    fun recordConnection(uid: Int, timestampMs: Long = System.currentTimeMillis()) {
        val entry = findLocked(uid) ?: return
        if (timestampMs > entry.lastConnectedAt) {
            entry.lastConnectedAt = timestampMs
            scheduleWriteLocked()
        }
    }

    @Synchronized
    fun allowedUids(): List<Int> {
        val list = ArrayList<Int>()
        for (entry in config.packages.orEmpty()) {
            if (entry.isAllowed()) {
                list.add(entry.uid)
            }
        }
        return list
    }

    private fun scheduleWriteLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (workerHandler.hasCallbacks(mWriteRunner)) {
                return
            }
        } else {
            workerHandler.removeCallbacks(mWriteRunner)
        }
        workerHandler.postDelayed(mWriteRunner, WRITE_DELAY)
    }

    private fun persistNowLocked() {
        workerHandler.removeCallbacks(mWriteRunner)
        workerHandler.post(mWriteRunner)
    }

    private fun findLocked(uid: Int): ShizukuConfig.PackageEntry? {
        for (entry in config.packages!!) {
            if (uid == entry.uid) {
                return entry
            }
        }
        return null
    }

    @Synchronized
    override fun find(uid: Int): ShizukuConfig.PackageEntry? {
        return findLocked(uid)
    }

    private fun updateLocked(uid: Int, packages: MutableList<String>?, mask: Int, values: Int) {
        val found = findLocked(uid)
        val entry: ShizukuConfig.PackageEntry
        if (found == null) {
            entry = ShizukuConfig.PackageEntry(uid, mask and values)
            config.packages!!.add(entry)
        } else {
            val newValue = (found.flags and mask.inv()) or (mask and values)
            if (newValue == found.flags && packages == null) {
                return
            }
            found.flags = newValue
            entry = found
        }
        if (packages != null) {
            val entryPackages = entry.packages!!
            for (packageName in packages) {
                if (!entryPackages.contains(packageName)) {
                    entryPackages.add(packageName)
                }
            }
            if (entry.signers.isNullOrEmpty()) {
                entry.signers = resolveSignersForUid(uid, packages)?.toMutableList()
            }
        }
        persistNowLocked()
    }

    @Synchronized
    override fun update(uid: Int, packages: MutableList<String>?, mask: Int, values: Int) {
        updateLocked(uid, packages, mask, values)
    }

    private fun removeLocked(uid: Int) {
        val entry = findLocked(uid) ?: return
        config.packages!!.remove(entry)
        persistNowLocked()
    }

    @Synchronized
    override fun remove(uid: Int) {
        removeLocked(uid)
    }

    @Synchronized
    fun getNightDogEnabled(): Boolean = config.nightDogEnabled

    @Synchronized
    fun setNightDogEnabled(enabled: Boolean) {
        if (config.nightDogEnabled == enabled) return
        config.nightDogEnabled = enabled
        persistNowLocked()
    }

    @Synchronized
    fun isAccessPaused(): Boolean = config.accessPaused

    @Synchronized
    fun setAccessPaused(paused: Boolean) {
        if (config.accessPaused == paused) return
        config.accessPaused = paused
        persistNowLocked()
    }

    companion object {

        private val GSON_IN: Gson = GsonBuilder().create()
        private val GSON_OUT: Gson = GsonBuilder()
            .setVersion(ShizukuConfig.LATEST_VERSION.toDouble())
            .create()

        private const val WRITE_DELAY = 1_000L
        private const val SHELL_UID = 2000
        private const val MODE_0600 = 0x180

        private val FILE = File("/data/user_de/0/com.android.shell/shizuku.json")
        private val ATOMIC_FILE = AtomicFile(FILE)

        @JvmStatic
        internal fun parseReader(reader: Reader): ShizukuConfig {
            val element = JsonParser.parseReader(reader)
            val obj = element as? JsonObject ?: return ShizukuConfig()
            val version = obj.get("version")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asInt
            if (version == null || version != ShizukuConfig.LATEST_VERSION) {
                ConfigManager.LOGGER.w(
                    "Unsupported or missing config version %s (expected %d); starting empty",
                    version?.toString() ?: "null",
                    ShizukuConfig.LATEST_VERSION
                )
                return ShizukuConfig()
            }
            return (GSON_IN.fromJson(element, ShizukuConfig::class.java) ?: ShizukuConfig()).apply {
                if (packages == null) {
                    packages = ArrayList()
                }
            }
        }

        @JvmStatic
        internal fun toJson(config: ShizukuConfig): String = GSON_OUT.toJson(config)

        @JvmStatic
        fun load(): ShizukuConfig {
            val stream: FileInputStream = try {
                ATOMIC_FILE.openRead()
            } catch (_: FileNotFoundException) {
                ConfigManager.LOGGER.i("no existing config file " + ATOMIC_FILE.baseFile + "; starting empty")
                return ShizukuConfig()
            }

            return try {
                InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                    parseReader(reader)
                }
            } catch (tr: Throwable) {
                ConfigManager.LOGGER.w(tr, "load config")
                ShizukuConfig()
            }
        }

        @JvmStatic
        fun write(config: ShizukuConfig): Boolean {
            synchronized(ATOMIC_FILE) {
                val stream: FileOutputStream = try {
                    ATOMIC_FILE.startWrite()
                } catch (e: IOException) {
                    ConfigManager.LOGGER.w("failed to write state: $e")
                    return false
                }

                return try {
                    val json = toJson(config)
                    stream.write(json.toByteArray(StandardCharsets.UTF_8))

                    // Ensure shell (uid 2000) always owns the replacement inode even when the
                    // server was previously started as root (uid 0).
                    if (Process.myUid() == 0) {
                        runCatching { Os.fchown(stream.fd, SHELL_UID, SHELL_UID) }
                    }
                    runCatching { Os.fchmod(stream.fd, MODE_0600) }

                    ATOMIC_FILE.finishWrite(stream)
                    ConfigManager.LOGGER.v("config saved")
                    true
                } catch (tr: Throwable) {
                    ConfigManager.LOGGER.w(tr, "can't save %s, restoring backup.", ATOMIC_FILE.baseFile)
                    ATOMIC_FILE.failWrite(stream)
                    false
                }
            }
        }
    }
}
