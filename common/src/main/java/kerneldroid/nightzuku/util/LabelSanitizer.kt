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

    const val DEFAULT_MAX_CODE_POINTS = 200

    private const val LINE_BREAK_CHARS = "\n\r\u000B\u000C\u0085\u2028\u2029"
    private const val BIDI_CONTROL_CHARS = "\u200B\u200E\u200F\u061C\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069\uFEFF"

    @JvmStatic
    @JvmOverloads
    fun sanitize(rawLabel: CharSequence?, fallback: String = "", maxCodePoints: Int = DEFAULT_MAX_CODE_POINTS): String {
        if (rawLabel.isNullOrEmpty()) return fallback
        val limit = maxCodePoints.coerceAtLeast(1)
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

        val singleLine = sb.toString().replace(Regex(" +"), " ").trim()
        if (singleLine.isEmpty()) return fallback
        if (singleLine.codePointCount(0, singleLine.length) <= limit) return singleLine

        val end = singleLine.offsetByCodePoints(0, limit)
        return singleLine.substring(0, end).trimEnd().ifEmpty { fallback }
    }
}
