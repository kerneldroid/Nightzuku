package kerneldroid.nightzuku.util

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PackageSignatureVerifierTest {

    @Test
    fun `signatureLookupFlags requests both uninstalled packages and signing certificates`() {
        val flags = PackageSignatureVerifier.signatureLookupFlags()
        assertTrue((flags and PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()) != 0L)
        assertTrue((flags and PackageManager.GET_SIGNING_CERTIFICATES.toLong()) != 0L)
    }

    @Test
    fun `evaluatePackageInfo classifies null and data-only residue as Absent`() {
        val nullResult = PackageSignatureVerifier.evaluatePackageInfo(null, 0)
        assertTrue(nullResult is PackageSignatureVerifier.ProbeResult.Absent)

        val residue = createPackageInfo(arrayOf(CERT_ORIGINAL), arrayOf(CERT_ORIGINAL))
        residue.applicationInfo!!.flags =
            residue.applicationInfo!!.flags and ApplicationInfo.FLAG_INSTALLED.inv()
        val residueResult = PackageSignatureVerifier.evaluatePackageInfo(residue, 0)
        assertTrue(residueResult is PackageSignatureVerifier.ProbeResult.Absent)
    }

    @Test
    fun `evaluatePackageInfo keeps pm-hidden package as Present when FLAG_INSTALLED is set`() {
        val hidden = createPackageInfo(arrayOf(CERT_ORIGINAL), arrayOf(CERT_ORIGINAL))
        val probe = PackageSignatureVerifier.evaluatePackageInfo(hidden, 0)
        assertTrue(probe is PackageSignatureVerifier.ProbeResult.Present)
        val snapshot = (probe as PackageSignatureVerifier.ProbeResult.Present).snapshot
        assertEquals(TEST_PKG, snapshot.packageName)
        assertEquals(TEST_APP_ID, snapshot.appId)
        assertEquals(0, snapshot.userId)
    }

    @Test
    fun `evaluatePackageInfo classifies missing ApplicationInfo or empty signers as LookupError`() {
        val missingAi = PackageInfo().apply { packageName = TEST_PKG }
        assertTrue(
            PackageSignatureVerifier.evaluatePackageInfo(missingAi, 0) is
                PackageSignatureVerifier.ProbeResult.LookupError
        )

        val emptySigners = createPackageInfo(emptyArray(), emptyArray())
        assertTrue(
            PackageSignatureVerifier.evaluatePackageInfo(emptySigners, 0) is
                PackageSignatureVerifier.ProbeResult.LookupError
        )
        assertNull(PackageSignatureVerifier.extractSnapshot(emptySigners, 0))
    }

    @Test
    fun `Baseline matches identical single signer and rotated key in signingCertificateHistory`() {
        val initialSnapshot = PackageSignatureVerifier.extractSnapshot(
            createPackageInfo(arrayOf(CERT_ORIGINAL), arrayOf(CERT_ORIGINAL)),
            0
        )!!
        val baseline = PackageSignatureVerifier.Baseline.fromSnapshot(initialSnapshot)

        // Same signer after normal upgrade
        val upgradedSnapshot = PackageSignatureVerifier.extractSnapshot(
            createPackageInfo(arrayOf(CERT_ORIGINAL), arrayOf(CERT_ORIGINAL)),
            0
        )
        assertTrue(baseline.matches(upgradedSnapshot))

        // Key rotation: current signer is CERT_ROTATED, lineage includes CERT_ORIGINAL
        val rotatedSnapshot = PackageSignatureVerifier.extractSnapshot(
            createPackageInfo(arrayOf(CERT_ROTATED), arrayOf(CERT_ORIGINAL, CERT_ROTATED)),
            0
        )
        assertTrue(baseline.matches(rotatedSnapshot))
    }

    @Test
    fun `Baseline rejects foreign signer, mismatched appId, or dropped co-signer`() {
        val initialSnapshot = PackageSignatureVerifier.extractSnapshot(
            createPackageInfo(arrayOf(CERT_ORIGINAL), arrayOf(CERT_ORIGINAL)),
            0
        )!!
        val baseline = PackageSignatureVerifier.Baseline.fromSnapshot(initialSnapshot)

        val foreignSnapshot = PackageSignatureVerifier.extractSnapshot(
            createPackageInfo(arrayOf(CERT_FOREIGN), arrayOf(CERT_FOREIGN)),
            0
        )
        assertFalse(baseline.matches(foreignSnapshot))

        val differentUidPi = createPackageInfo(arrayOf(CERT_ORIGINAL), arrayOf(CERT_ORIGINAL)).apply {
            applicationInfo!!.uid = TEST_UID + 1
        }
        val differentUidSnapshot = PackageSignatureVerifier.extractSnapshot(differentUidPi, 0)
        assertFalse(baseline.matches(differentUidSnapshot))

        // Multi-signer baseline requires the full co-signer set, not a single member
        val multiSnapshot = PackageSignatureVerifier.extractSnapshot(
            createPackageInfo(arrayOf(CERT_ORIGINAL, CERT_CO_SIGNER), null),
            0
        )!!
        val multiBaseline = PackageSignatureVerifier.Baseline.fromSnapshot(multiSnapshot)

        val reorderedSnapshot = PackageSignatureVerifier.extractSnapshot(
            createPackageInfo(arrayOf(CERT_CO_SIGNER, CERT_ORIGINAL), null),
            0
        )
        assertTrue(multiBaseline.matches(reorderedSnapshot))
        assertFalse(multiBaseline.matches(initialSnapshot))
    }

    @Test
    fun `sha256Hex produces deterministic 64-char lowercase hex digest`() {
        val digest = requireNotNull(PackageSignatureVerifier.sha256Hex(byteArrayOf(0x01, 0x02, 0x03)))
        assertEquals(64, digest.length)
        assertEquals(digest.lowercase(), digest)
    }

    companion object {
        private const val TEST_PKG = "com.example.client"
        private const val TEST_APP_ID = 10234
        private const val TEST_UID = 10234

        private val CERT_ORIGINAL = Signature("3082010a0282010100aabbccddeeff")
        private val CERT_ROTATED = Signature("3082010a0282010100112233445566")
        private val CERT_FOREIGN = Signature("3082010a0282010100998877665544")
        private val CERT_CO_SIGNER = Signature("3082010a0282010100deadbeefcafe")

        private fun createPackageInfo(
            signers: Array<Signature>,
            history: Array<Signature>?
        ): PackageInfo {
            return PackageInfo().apply {
                packageName = TEST_PKG
                applicationInfo = ApplicationInfo().apply {
                    uid = TEST_UID
                    flags = ApplicationInfo.FLAG_INSTALLED
                    sourceDir = "/data/app/$TEST_PKG/base.apk"
                }
                val signing = mock(SigningInfo::class.java)
                `when`(signing.hasMultipleSigners()).thenReturn(signers.size > 1)
                `when`(signing.apkContentsSigners).thenReturn(signers)
                `when`(signing.signingCertificateHistory).thenReturn(
                    if (signers.size > 1) null else history
                )
                signingInfo = signing
            }
        }
    }
}
