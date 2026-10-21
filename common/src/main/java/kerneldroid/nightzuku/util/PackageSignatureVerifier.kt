package kerneldroid.nightzuku.util

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest
import java.util.Collections

/**
 * Cryptographic package identity and signing certificate verifier.
 *
 * Computes SHA-256 certificate digests across Android API levels (supporting both legacy
 * `GET_SIGNATURES` and API 28+ `GET_SIGNING_CERTIFICATES` with key rotation lineage) and
 * distinguishes between confirmed package absence and transient Binder/PMS lookup failures.
 */
object PackageSignatureVerifier {

    private const val APP_ID_PER_USER_RANGE = 100_000
    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    /**
     *Immutable signing baseline pinned when a privileged server starts or when a client/UserService
     * record is authorized.
     */
    data class Baseline(
        val packageName: String,
        val appId: Int,
        val certDigests: Set<String>
    ) {
        /**
         * Verifies whether [snapshot] represents the exact same signing authority (including
         * single-signer Android P+ key rotation history).
         */
        fun matches(snapshot: Snapshot?): Boolean {
            if (snapshot == null || snapshot.appId != appId) return false
            if (certDigests == snapshot.certDigests) return true

            // Key rotation lineage is only valid on API 28+ for single-signer packages.
            if (certDigests.size != 1 || snapshot.hasMultipleSigners) return false
            val lineage = snapshot.rotationLineageDigests ?: return false
            val pinnedDigest = certDigests.first()
            return pinnedDigest in lineage
        }

        companion object {
            @JvmStatic
            fun fromSnapshot(snapshot: Snapshot): Baseline =
                Baseline(snapshot.packageName, snapshot.appId, snapshot.certDigests)
        }
    }

    /**
     * Observed package state for a specific Android user.
     */
    data class Snapshot(
        val packageName: String,
        val userId: Int,
        val appId: Int,
        val sourceDir: String?,
        val certDigests: Set<String>,
        val hasMultipleSigners: Boolean,
        val rotationLineageDigests: Set<String>?
    )

    sealed class ProbeResult {
        abstract val reason: String?

        data class Present(val snapshot: Snapshot) : ProbeResult() {
            override val reason: String? = null
        }

        data class Absent(
            override val reason: String = "package is not installed"
        ) : ProbeResult()

        data class LookupError(val cause: Throwable?) : ProbeResult() {
            override val reason: String? = cause?.message
        }
    }

    @JvmStatic
    fun signatureLookupFlags(): Long {
        @Suppress("DEPRECATION")
        val certFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES.toLong()
        } else {
            PackageManager.GET_SIGNATURES.toLong()
        }
        // Include MATCH_UNINSTALLED_PACKAGES so packages hidden via `pm hide` are not misread as
        // uninstalled; actual installation state is verified via ApplicationInfo.FLAG_INSTALLED.
        return PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong() or certFlag
    }

    @JvmStatic
    fun sha256Hex(signature: Signature?): String? =
        signature?.let { sha256Hex(it.toByteArray()) }

    @JvmStatic
    fun sha256Hex(data: ByteArray?): String? {
        if (data == null) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(data)
            val chars = CharArray(digest.size * 2)
            for (i in digest.indices) {
                val v = digest[i].toInt() and 0xFF
                chars[i * 2] = HEX_DIGITS[v ushr 4]
                chars[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
            }
            String(chars)
        } catch (_: Throwable) {
            null
        }
    }

    @JvmStatic
    fun computeSignerDigests(signatures: Array<Signature>?): Set<String> {
        if (signatures.isNullOrEmpty()) return emptySet()
        val set = LinkedHashSet<String>(signatures.size)
        for (sig in signatures) {
            val hex = sha256Hex(sig)
            if (!hex.isNullOrEmpty()) {
                set.add(hex)
            }
        }
        return Collections.unmodifiableSet(set)
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    fun extractSignerDigests(packageInfo: PackageInfo?): Set<String>? {
        if (packageInfo == null) return null
        val digests = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && packageInfo.signingInfo != null) {
            computeSignerDigests(packageInfo.signingInfo!!.apkContentsSigners)
        } else {
            computeSignerDigests(packageInfo.signatures)
        }
        return digests.takeIf { it.isNotEmpty() }
    }

    @JvmStatic
    fun extractBaseline(packageInfo: PackageInfo?): Baseline? {
        val ai = packageInfo?.applicationInfo ?: return null
        val digests = extractSignerDigests(packageInfo) ?: return null
        val appId = ai.uid % APP_ID_PER_USER_RANGE
        return Baseline(packageInfo.packageName, appId, digests)
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    fun extractSnapshot(packageInfo: PackageInfo?, userId: Int): Snapshot? {
        val ai = packageInfo?.applicationInfo ?: return null
        val digests = extractSignerDigests(packageInfo) ?: return null
        val appId = ai.uid % APP_ID_PER_USER_RANGE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo
            if (signingInfo != null) {
                val multiple = signingInfo.hasMultipleSigners()
                val lineage = if (multiple) {
                    null
                } else {
                    computeSignerDigests(signingInfo.signingCertificateHistory).takeIf { it.isNotEmpty() }
                }
                return Snapshot(packageInfo.packageName, userId, appId, ai.sourceDir, digests, multiple, lineage)
            }
        }
        return Snapshot(packageInfo.packageName, userId, appId, ai.sourceDir, digests, digests.size > 1, null)
    }

    @JvmStatic
    fun evaluatePackageInfo(packageInfo: PackageInfo?, userId: Int): ProbeResult {
        if (packageInfo == null) return ProbeResult.Absent()
        val ai = packageInfo.applicationInfo
            ?: return ProbeResult.LookupError(IllegalStateException("Missing ApplicationInfo for ${packageInfo.packageName}"))
        if ((ai.flags and ApplicationInfo.FLAG_INSTALLED) == 0) {
            return ProbeResult.Absent()
        }
        val snapshot = extractSnapshot(packageInfo, userId)
            ?: return ProbeResult.LookupError(IllegalStateException("Missing signing certificates for ${packageInfo.packageName}"))
        return ProbeResult.Present(snapshot)
    }
}
