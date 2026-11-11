package moe.shizuku.manager.adb

/**
 * Extracts the wireless-debugging pairing code (and optional endpoint) from texts shown by the
 * system pairing dialog.
 *
 * Android TV vendors use different Settings packages and layouts, so matching is driven by the
 * shape of the data, not by one hardcoded package or string: a six-digit code in a settings/pairing
 * window, plus an optional IPv4:port. The caller supplies all visible texts of the candidate window.
 */
object PairingCodeParser {

    private val IPV4_PORT = Regex("""(?<!\d)((?:\d{1,3}\.){3}\d{1,3}):(\d{2,5})(?!\d)""")
    private val PORT_ONLY = Regex("""(?i)(?:port|порт)\D{0,8}(\d{2,5})""")
    private val SIX_DIGITS = Regex("""(?<!\d)(\d{6})(?!\d)""")
    private val PAIR_HINT = Regex("""(?i)pair|сопряж|спар|code|код|wireless|отладк""")

    data class Result(val code: String, val host: String?, val port: Int?)

    /**
     * @param texts every text node of the window that may be the pairing dialog
     * @param packageName package that owns the window; Settings shells are matched loosely
     */
    fun parse(texts: List<String>, packageName: String? = null): Result? {
        val joined = texts.joinToString("\n")
        if (joined.isBlank()) return null

        val settingsWindow = packageName?.contains("settings", ignoreCase = true) == true
        val hasPairHint = PAIR_HINT.containsMatchIn(joined)
        if (!settingsWindow && !hasPairHint) return null

        val endpoint = IPV4_PORT.find(joined)
        val host = endpoint?.groupValues?.get(1)
        val port = endpoint?.groupValues?.get(2)?.toIntOrNull()
            ?: PORT_ONLY.find(joined)?.groupValues?.get(1)?.toIntOrNull()

        // Prefer a standalone six-digit node; fall back to the first six-digit run. Skip values
        // that are clearly part of a longer number (dates, build ids, IP octets).
        val code = texts.asSequence()
            .map { it.trim() }
            .firstOrNull { SIX_DIGITS.matches(it) }
            ?.let { SIX_DIGITS.matchEntire(it)?.value }
            ?: SIX_DIGITS.find(joined)?.value

        if (code == null) return null
        if (port != null && port !in 1024..65535) return null
        // A bare six-digit number is not enough: the same Settings screen can show build numbers.
        // The pairing screen either carries pairing wording (localized labels still usually keep
        // the word "code" or the app name) or an IP:port endpoint.
        if (port == null && !hasPairHint) return null

        return Result(code, host, port)
    }
}
