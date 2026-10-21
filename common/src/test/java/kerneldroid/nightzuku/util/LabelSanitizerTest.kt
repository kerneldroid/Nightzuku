package kerneldroid.nightzuku.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelSanitizerTest {

    @Test
    fun `preserves standard ASCII, Unicode, CJK, and emoji labels`() {
        assertEquals("Termux", LabelSanitizer.sanitize("Termux", "com.termux"))
        assertEquals("Файлы 日本語 🚀", LabelSanitizer.sanitize("Файлы 日本語 🚀", "com.example"))
    }

    @Test
    fun `truncates at first line break to block multi-line dialog spoofing`() {
        assertEquals(
            "Innocent App",
            LabelSanitizer.sanitize("Innocent App\nGrant Root Access to System UI", "com.evil")
        )
        assertEquals(
            "Innocent App",
            LabelSanitizer.sanitize("Innocent App\r\nFake Second Line", "com.evil")
        )
        assertEquals(
            "Innocent App",
            LabelSanitizer.sanitize("Innocent App\u2028LineSeparator", "com.evil")
        )
        assertEquals(
            "Innocent App",
            LabelSanitizer.sanitize("Innocent App\u0085NextLine", "com.evil")
        )
    }

    @Test
    fun `strips Bidi override and isolate characters used for right-to-left spoofing`() {
        assertEquals(
            " System UI",
            " " + LabelSanitizer.sanitize("\u202ESystem UI\u202C", "com.evil")
        )
        assertEquals(
            "CleanLabel",
            LabelSanitizer.sanitize("\u202AClean\u202BLabel\u202D\u2066\u2067\u2068\u2069", "com.evil")
        )
        assertEquals(
            "NoMarks",
            LabelSanitizer.sanitize("\u200ENo\u200FMarks\u061C\u200B\uFEFF", "com.evil")
        )
    }

    @Test
    fun `strips control characters and collapses excessive whitespace`() {
        assertEquals(
            "App Name",
            LabelSanitizer.sanitize("  App\u0000\u0007   \t  Name  ", "com.example")
        )
    }

    @Test
    fun `falls back to package name when label is null, empty, or only control chars`() {
        assertEquals("com.fallback.pkg", LabelSanitizer.sanitize(null, "com.fallback.pkg"))
        assertEquals("com.fallback.pkg", LabelSanitizer.sanitize("   \u202E\u200B  ", "com.fallback.pkg"))
        assertEquals("com.fallback.pkg", LabelSanitizer.sanitize("\nSecondLineOnly", "com.fallback.pkg"))
    }

    @Test
    fun `caps code points without splitting surrogate pairs`() {
        assertEquals("abcd", LabelSanitizer.sanitize("abcdef", "pkg", maxCodePoints = 4))
        // Emoji 🚀 is a surrogate pair (2 UTF-16 chars, 1 Unicode code point)
        val withEmoji = LabelSanitizer.sanitize("ab🚀cd", "pkg", maxCodePoints = 3)
        assertEquals("ab🚀", withEmoji)
    }
}
