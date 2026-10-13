package rikka.shizuku.server

import android.content.pm.PackageManager
import android.os.Build
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.UserManagerApis
import rikka.shizuku.common.util.InstalledPackagesCompat
import rikka.shizuku.server.ktx.workerHandler
import rikka.shizuku.server.util.Android17Compat

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
            }

            if (packagesChanged) {
                ConfigManager.LOGGER.i("remove config for uid %d since the packages for it changed", entry.uid)
                configPackages.remove(entry)
                changed = true
            }
        }

        for (userId in UserManagerApis.getUserIdsNoThrow()) {
            for (pi in InstalledPackagesCompat.getInstalledPackagesNoThrow(
                PackageManager.GET_PERMISSIONS.toLong(), userId
            )) {
                val applicationInfo = pi.applicationInfo ?: continue
                val requestedPermissions = pi.requestedPermissions ?: continue
                if (!requestedPermissions.contains(ServerConstants.PERMISSION)) {
                    continue
                }

                val uid = applicationInfo.uid
                val allowed = try {
                    Android17Compat.checkPermission(ServerConstants.PERMISSION, uid) == PackageManager.PERMISSION_GRANTED
                } catch (e: Throwable) {
                    ConfigManager.LOGGER.w("checkPermission")
                    continue
                }

                val packages = ArrayList<String>()
                packages.add(pi.packageName)

                updateLocked(uid, packages, ConfigManager.MASK_PERMISSION, if (allowed) ConfigManager.FLAG_ALLOWED else 0)
                changed = true
            }
        }

        if (changed) {
            scheduleWriteLocked()
        }
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
            if (newValue == found.flags) {
                return
            }
            found.flags = newValue
            entry = found
        }
        if (packages != null) {
            val entryPackages = entry.packages!!
            for (packageName in packages) {
                if (entryPackages.contains(packageName)) {
                    continue
                }
                entryPackages.add(packageName)
            }
        }
        scheduleWriteLocked()
    }

    @Synchronized
    override fun update(uid: Int, packages: MutableList<String>, mask: Int, values: Int) {
        updateLocked(uid, packages, mask, values)
    }

    private fun removeLocked(uid: Int) {
        val entry = findLocked(uid) ?: return
        config.packages!!.remove(entry)
        scheduleWriteLocked()
    }

    @Synchronized
    override fun remove(uid: Int) {
        removeLocked(uid)
    }

    @Synchronized
    fun getNightDogEnabled(): Boolean {
        return config.nightDogEnabled
    }

    @Synchronized
    fun setNightDogEnabled(enabled: Boolean) {
        if (config.nightDogEnabled == enabled) {
            return
        }
        config.nightDogEnabled = enabled
        scheduleWriteLocked()
    }

    companion object {

        private val GSON_IN: Gson = GsonBuilder()
            .create()
        private val GSON_OUT: Gson = GsonBuilder()
            .setVersion(ShizukuConfig.LATEST_VERSION.toDouble())
            .create()

        private const val WRITE_DELAY = 10 * 1000L

        private val FILE = File("/data/user_de/0/com.android.shell/shizuku.json")
        private val ATOMIC_FILE = AtomicFile(FILE)

        @JvmStatic
        fun load(): ShizukuConfig {
            val stream: FileInputStream = try {
                ATOMIC_FILE.openRead()
            } catch (e: FileNotFoundException) {
                ConfigManager.LOGGER.i("no existing config file " + ATOMIC_FILE.baseFile + "; starting empty")
                return ShizukuConfig()
            }

            var config: ShizukuConfig? = null
            try {
                config = GSON_IN.fromJson(InputStreamReader(stream), ShizukuConfig::class.java)
            } catch (tr: Throwable) {
                ConfigManager.LOGGER.w(tr, "load config")
            } finally {
                try {
                    stream.close()
                } catch (e: IOException) {
                    ConfigManager.LOGGER.w("failed to close: " + e)
                }
            }
            return config ?: ShizukuConfig()
        }

        @JvmStatic
        fun write(config: ShizukuConfig) {
            synchronized(ATOMIC_FILE) {
                val stream: FileOutputStream = try {
                    ATOMIC_FILE.startWrite()
                } catch (e: IOException) {
                    ConfigManager.LOGGER.w("failed to write state: " + e)
                    return
                }

                try {
                    val json = GSON_OUT.toJson(config)
                    stream.write(json.toByteArray())

                    ATOMIC_FILE.finishWrite(stream)
                    ConfigManager.LOGGER.v("config saved")
                } catch (tr: Throwable) {
                    ConfigManager.LOGGER.w(tr, "can't save %s, restoring backup.", ATOMIC_FILE.baseFile)
                    ATOMIC_FILE.failWrite(stream)
                }
            }
        }
    }
}
