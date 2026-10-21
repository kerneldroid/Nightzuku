package moe.shizuku.manager.module.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    private val checker = UpdateChecker.getInstance()

    @Test
    fun `compareSemanticVersions strips leading v prefix and build metadata`() {
        assertEquals(0, checker.compareSemanticVersions("1.2.0", "v1.2.0"))
        assertEquals(0, checker.compareSemanticVersions("V2.0.1+build42", "2.0.1"))
        assertTrue(checker.compareSemanticVersions("v1.2.1", "v1.2.0") > 0)
        assertTrue(checker.compareSemanticVersions("1.10.0", "v1.2.9") > 0)
        assertTrue(checker.compareSemanticVersions("v1.2.0", "1.3.0") < 0)
    }

    @Test
    fun `extractVersionCodeFromTag rejects dotted semver tags and parses integer release tags`() {
        // Dotted semantic version tags must NOT be concatenated into bogus versionCodes (e.g. v1.2.0 -> 120)
        assertNull(checker.extractVersionCodeFromTag("v1.2.0"))
        assertNull(checker.extractVersionCodeFromTag("1.0.0"))
        assertNull(checker.extractVersionCodeFromTag("V2.4.1-beta1"))

        // Explicit integer version code tags are extracted cleanly
        assertEquals(12L, checker.extractVersionCodeFromTag("v12"))
        assertEquals(45L, checker.extractVersionCodeFromTag("r45"))
        assertEquals(7L, checker.extractVersionCodeFromTag("build-7"))
        assertEquals(100L, checker.extractVersionCodeFromTag("100"))
    }
}
