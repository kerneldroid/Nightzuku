package kerneldroid.nightzuku.util

/**
 * Sanitizes untrusted application labels before rendering them in security-critical
 * permission dialogs or notifications.
 *
 * Strips Unicode bidirectional override controls (RLO/LRO/FSI/PDI) that could spoof
 * another package's identity, drops ISO control characters, truncates at the first
 * line break so multi-line labels cannot inject fake dialog text, and avoids splitting
 * UTF-16 surrogate pairs.
 */
object LabelSanitizer {

    const val DEFAULT_MAX_CHARS = 200

    private const val LINE_BREAK_CHARS = "\n\r\u000B\u000C\u0085\u2028\u2029"
    private const val BIDI_CONTROL_CHARS = "\u200E\u200F\u061C\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069"

    @JvmStatic
    @JvmOverloads
    fun sanitize(rawLabel: CharSequence?, fallback: String = "", maxChars: Int = DEFAULT_MAX_CHARS): String {
        if (rawLabel.isNullOrEmpty()) return fallback
        val limit = maxChars.coerceAtLeast(1)
        val sb = StringBuilder(minOf(rawLabel.length, limit + 16))

        for (i in 0 until rawLabel.length) {
            val ch = rawLabel[i]
            if (LINE_BREAK_CHARS.indexOf(ch) >= 0) {
                break
            }
            if (Character.isISOControl(ch) || BIDI_CONTROL_CHARS.indexOf(ch) >= 0) {
                continue
            }
            sb.append(ch)
        }

        val singleLine = sb.toString().trim()
        if (singleLine.isEmpty()) return fallback
        if (singleLine.length <= limit) return singleLine

        val safeEnd = if (Character.isHighSurrogate(singleLine[limit - 1])) limit - 1 else limit
        return singleLine.substring(0, safeEnd).trimEnd().ifEmpty { fallback }
    }
}
