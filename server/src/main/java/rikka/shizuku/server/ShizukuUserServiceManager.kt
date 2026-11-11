package rikka.shizuku.server

import android.content.pm.PackageInfo
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kerneldroid.nightzuku.util.PackageSignatureVerifier
import moe.shizuku.starter.ServiceStarter
import rikka.shizuku.server.util.Android17Compat
import rikka.shizuku.server.util.UserHandleCompat

/**
 * Nightzuku implementation of [UserServiceManager].
 *
 * Portions of the record reuse verification (`canReuseUserServiceRecord`) and `/proc/<pid>/stat`
 * host process identity tracking (`super.claimUserServiceLaunch` / `HostProcess`) are adapted from
 * [d4rken-org/porter-api](https://github.com/d4rken-org/porter-api) (`eu.darken.porter`).
 */
class ShizukuUserServiceManager : UserServiceManager() {

    private val activeTokens = ConcurrentHashMap<String, UserServiceRecord>()
    private val recordBaselines = ConcurrentHashMap<UserServiceRecord, PackageSignatureVerifier.Baseline>()
    private val recordUids = ConcurrentHashMap<UserServiceRecord, Int>()

    @Volatile
    private var managerBaseline: PackageSignatureVerifier.Baseline? = null

    @Volatile
    private var accessPaused: Boolean = false

    fun setManagerBaseline(baseline: PackageSignatureVerifier.Baseline) {
        this.managerBaseline = baseline
    }

    fun setAccessPaused(paused: Boolean) {
        this.accessPaused = paused
        if (paused) {
            removeAllUserServices()
        }
    }

    fun removeAllUserServices() {
        val records = ArrayList(recordBaselines.keys)
        for (record in records) {
            try {
                record.removeSelf()
            } catch (_: Throwable) {
            }
        }
    }

    override fun ensureCallingPackageForUserService(packageName: String, appId: Int, userId: Int): PackageInfo {
        val packageInfo = Android17Compat.getPackageInfo(
            packageName,
            PackageSignatureVerifier.signatureLookupFlags(),
            userId
        ) ?: return super.ensureCallingPackageForUserService(packageName, appId, userId)
        val ai = packageInfo.applicationInfo
            ?: throw SecurityException("unable to find package $packageName")
        if (UserHandleCompat.getAppId(ai.uid) != appId) {
            throw SecurityException("package $packageName is not owned by $appId")
        }
        return packageInfo
    }

    /**
     * Removes only the [UserServiceRecord] instances belonging to [uid], avoiding collateral
     * termination of the same package's UserServices running in other Android users.
     */
    override fun removeUserServicesForUid(uid: Int): Boolean {
        var matchedAny = super.removeUserServicesForUid(uid)
        for ((record, recordUid) in ArrayList(recordUids.entries)) {
            if (recordUid == uid) {
                matchedAny = true
                try {
                    record.removeSelf()
                } catch (_: Throwable) {
                }
            }
        }
        return matchedAny
    }

    override fun canReuseUserServiceRecord(record: UserServiceRecord, packageInfo: PackageInfo): Boolean {
        if (accessPaused || record.pendingDestroy || record.isRemoved) return false
        val baseline = recordBaselines[record] ?: return true
        val ai = packageInfo.applicationInfo ?: return false
        val userId = UserHandleCompat.getUserId(ai.uid)
        val fullPi = if (packageInfo.signatures != null ||
            (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && packageInfo.signingInfo != null)
        ) {
            packageInfo
        } else {
            Android17Compat.getPackageInfo(
                packageInfo.packageName,
                PackageSignatureVerifier.signatureLookupFlags(),
                userId
            ) ?: packageInfo
        }
        val snapshot = PackageSignatureVerifier.extractSnapshot(fullPi, userId)
        return baseline.matches(snapshot)
    }

    /**
     * Periodically verifies that all active UserService host packages remain installed and signed
     * by the same certificate authority. Required under ADB (`u:r:shell:s0`) where SELinux blocks
     * `inotify` (`FileObserver`) on `/data/app`.
     */
    fun reconcileUserServicePackages() {
        for ((record, baseline) in ArrayList(recordBaselines.entries)) {
            val uid = recordUids[record] ?: continue
            val userId = UserHandleCompat.getUserId(uid)
            val probe = try {
                val pi = Android17Compat.getPackageInfoOrThrow(
                    baseline.packageName,
                    PackageSignatureVerifier.signatureLookupFlags(),
                    userId
                )
                PackageSignatureVerifier.evaluatePackageInfo(pi, userId)
            } catch (t: Throwable) {
                PackageSignatureVerifier.ProbeResult.LookupError(t)
            }

            val shouldRemove = when (probe) {
                is PackageSignatureVerifier.ProbeResult.Present -> !baseline.matches(probe.snapshot)
                is PackageSignatureVerifier.ProbeResult.Absent -> true
                is PackageSignatureVerifier.ProbeResult.LookupError -> false
            }
            if (shouldRemove) {
                UserServiceManager.LOGGER.w(
                    "Terminating UserService %s: package %s (uid=%d) uninstalled or signature changed",
                    record.token,
                    baseline.packageName,
                    uid
                )
                try {
                    record.removeSelf()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun resolveVerifiedManagerApkPath(): String {
        val expected = managerBaseline
        val pi = ShizukuService.getManagerPackageInfo()
            ?: throw SecurityException("Manager package not found in user 0")
        val ai = pi.applicationInfo
            ?: throw SecurityException("Manager ApplicationInfo missing in user 0")
        if (expected != null) {
            val snapshot = PackageSignatureVerifier.extractSnapshot(pi, 0)
            if (!expected.matches(snapshot)) {
                throw SecurityException("Manager APK signature mismatch against server baseline")
            }
        }
        return ai.sourceDir
    }

    override fun getUserServiceStartCmd(
        record: UserServiceRecord,
        key: String,
        token: String,
        packageName: String,
        classname: String,
        processNameSuffix: String,
        callingUid: Int,
        use32Bits: Boolean,
        debug: Boolean
    ): String {
        if (accessPaused) {
            throw SecurityException("Client access is globally paused")
        }
        recordUids[record] = callingUid
        activeTokens[token] = record

        val baseline = recordBaselines[record] ?: run {
            val userId = UserHandleCompat.getUserId(callingUid)
            val pi = Android17Compat.getPackageInfo(
                packageName,
                PackageSignatureVerifier.signatureLookupFlags(),
                userId
            )
            val snapshot = if (pi != null) PackageSignatureVerifier.extractSnapshot(pi, userId) else null
            if (snapshot == null || snapshot.appId != UserHandleCompat.getAppId(callingUid)) {
                throw SecurityException("Cannot verify package identity for UserService host $packageName (uid=$callingUid)")
            }
            PackageSignatureVerifier.Baseline.fromSnapshot(snapshot).also {
                recordBaselines[record] = it
            }
        }

        var appProcess = "/system/bin/app_process"
        if (use32Bits && File("/system/bin/app_process32").exists()) {
            appProcess = "/system/bin/app_process32"
        }
        return ServiceStarter.commandForUserService(
            appProcess,
            resolveVerifiedManagerApkPath(),
            token,
            packageName,
            classname,
            processNameSuffix,
            callingUid,
            debug,
            baseline.certDigests
        )
    }

    /**
     * Validates that [token] belongs to a currently starting or active [UserServiceRecord]
     * and that its host package still matches its pinned cryptographic identity before the
     * privileged starter loads the target package's code. Also records [rikka.shizuku.server.util.HostProcess]
     * identity (`pid`, `startTime`, `uid`) via [super.claimUserServiceLaunch] to prevent PID-reuse kills.
     */
    override fun claimUserServiceLaunch(token: String?, hostPid: Int, expectedUid: Int): Boolean {
        if (token == null || accessPaused) return false
        val record = activeTokens[token] ?: return false
        val service = record.service
        if (!record.starting && (service == null || !service.pingBinder())) {
            activeTokens.remove(token)
            return false
        }
        val baseline = recordBaselines[record]
        val uid = recordUids[record]
        if (baseline != null && uid != null) {
            val userId = UserHandleCompat.getUserId(uid)
            val pi = Android17Compat.getPackageInfo(
                baseline.packageName,
                PackageSignatureVerifier.signatureLookupFlags(),
                userId
            )
            val snapshot = if (pi != null) PackageSignatureVerifier.extractSnapshot(pi, userId) else null
            if (!baseline.matches(snapshot)) {
                UserServiceManager.LOGGER.w(
                    "Rejecting UserService launch claim for token %s: package %s failed identity verification",
                    token,
                    baseline.packageName
                )
                try {
                    record.removeSelf()
                } catch (_: Throwable) {
                }
                return false
            }
        }
        return super.claimUserServiceLaunch(token, hostPid, expectedUid)
    }

    override fun onUserServiceRecordCreated(record: UserServiceRecord, packageInfo: PackageInfo) {
        if (accessPaused) {
            record.removeSelf()
            throw SecurityException("Client access is globally paused")
        }
        super.onUserServiceRecordCreated(record, packageInfo)
        activeTokens[record.token] = record

        val ai = packageInfo.applicationInfo
        if (ai == null) {
            record.removeSelf()
            throw SecurityException("PackageInfo for ${packageInfo.packageName} is missing ApplicationInfo")
        }
        recordUids[record] = ai.uid

        val userId = UserHandleCompat.getUserId(ai.uid)
        val fullPi = if (packageInfo.signatures != null ||
            (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && packageInfo.signingInfo != null)
        ) {
            packageInfo
        } else {
            Android17Compat.getPackageInfo(
                packageInfo.packageName,
                PackageSignatureVerifier.signatureLookupFlags(),
                userId
            ) ?: packageInfo
        }
        val snapshot = PackageSignatureVerifier.extractSnapshot(fullPi, userId)
        if (snapshot == null) {
            record.removeSelf()
            throw SecurityException("Cannot extract signing identity for ${packageInfo.packageName}")
        }
        val baseline = PackageSignatureVerifier.Baseline.fromSnapshot(snapshot)
        recordBaselines[record] = baseline
        // Upgrades and uninstalls are caught by reconcileUserServicePackages(), which runs
        // every 3s. FileObserver on /data/app is denied by SELinux for uid 2000.
    }

    override fun onUserServiceRecordRemoved(record: UserServiceRecord) {
        super.onUserServiceRecordRemoved(record)
        activeTokens.remove(record.token)
        recordBaselines.remove(record)
        recordUids.remove(record)
    }
}
