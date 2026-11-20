package de.streamview.tv.core

import java.net.IDN
import java.net.URI

/** Ergebnis der Prüfung einer Benutzereingabe in der Adresszeile. */
sealed class InputResult {
    data class Ok(val url: String) : InputResult()
    data class Invalid(val message: String) : InputResult()
}

object UrlTools {

    /**
     * Wandelt eine eingegebene Adresse in eine ladbare URL um.
     * "example.com" -> "https://example.com" (HTTPS wird bevorzugt).
     * Nur http/https sind erlaubt.
     */
    fun normalizeUserInput(input: String): InputResult {
        val text = input.trim()
        if (text.isEmpty()) return InputResult.Invalid("Bitte eine Adresse eingeben, z. B. example.com")
        if (text.any { it.isWhitespace() }) {
            return InputResult.Invalid("Eine Webadresse darf keine Leerzeichen enthalten. Beispiel: example.com")
        }
        val schemeEnd = text.indexOf("://")
        val withScheme: String = if (schemeEnd > 0) {
            val scheme = text.substring(0, schemeEnd).lowercase()
            if (scheme != "http" && scheme != "https") {
                return InputResult.Invalid("Nur Adressen mit http:// oder https:// werden unterstützt.")
            }
            scheme + text.substring(schemeEnd)
        } else {
            val colon = text.indexOf(':')
            val firstSlash = text.indexOf('/')
            // "javascript:...", "file:..." usw. ablehnen, "localhost:8080" erlauben
            if (colon > 0 && (firstSlash < 0 || colon < firstSlash)) {
                val after = text.substring(colon + 1).takeWhile { it != '/' }
                if (after.isEmpty() || !after.all { it.isDigit() }) {
                    return InputResult.Invalid("Nur Adressen mit http:// oder https:// werden unterstützt.")
                }
            }
            "https://$text"
        }
        val hostRaw = hostOf(withScheme)
            ?: return InputResult.Invalid("„$text“ ist keine gültige Webadresse. Beispiel: example.com")
        val host = try {
            IDN.toASCII(hostRaw, IDN.ALLOW_UNASSIGNED).lowercase()
        } catch (_: Exception) {
            return InputResult.Invalid("„$hostRaw“ ist kein gültiger Servername.")
        }
        val validHost = host == "localhost" || PublicSuffix.isIpAddress(host) ||
            (host.contains('.') && !host.startsWith('.') && !host.endsWith('.') && !host.contains("..") &&
                host.all { it.isLetterOrDigit() || it == '.' || it == '-' })
        if (!validHost) {
            return InputResult.Invalid("„$text“ ist keine gültige Webadresse. Beispiel: example.com")
        }
        val finalUrl = if (host != hostRaw.lowercase()) withScheme.replaceFirst(hostRaw, host) else withScheme
        return try {
            URI(finalUrl)
            InputResult.Ok(finalUrl)
        } catch (_: Exception) {
            InputResult.Invalid("Die Adresse enthält ungültige Zeichen.")
        }
    }

    /** Schnelles Herauslösen des Hosts ohne URI-Objekt (für den Filter-Hotpath). */
    fun hostOf(url: String?): String? {
        if (url == null) return null
        val s = url.indexOf("://")
        if (s < 0) return null
        var start = s + 3
        var end = url.length
        for (i in start until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#') { end = i; break }
        }
        val at = url.lastIndexOf('@', end - 1)
        if (at >= start) start = at + 1
        if (start >= end) return null
        var host = url.substring(start, end)
        if (host.startsWith("[")) {
            val close = host.indexOf(']')
            return if (close > 0) host.substring(0, close + 1).lowercase() else null
        }
        val colon = host.indexOf(':')
        if (colon >= 0) host = host.substring(0, colon)
        return if (host.isEmpty()) null else host.lowercase()
    }

    fun schemeOf(url: String?): String? {
        if (url == null) return null
        val c = url.indexOf(':')
        if (c <= 0) return null
        return url.substring(0, c).lowercase()
    }

    fun isHttp(url: String?): Boolean {
        val s = schemeOf(url)
        return s == "http" || s == "https"
    }

    fun sameSite(urlA: String?, urlB: String?): Boolean =
        PublicSuffix.isSameSite(hostOf(urlA), hostOf(urlB))

    fun siteKey(url: String?): String = PublicSuffix.registrableDomain(hostOf(url))

    /**
     * Bereinigt eine URL für Protokolle: nur Schema, Host und gekürzter Pfad.
     * Query-Parameter und Fragmente (oft Tokens/Sitzungs-IDs) werden entfernt.
     */
    fun sanitizeForLog(url: String?): String {
        if (url == null) return "-"
        val scheme = schemeOf(url) ?: return "-"
        if (scheme == "data") return "data:…"
        if (scheme == "blob") return "blob:…"
        val host = hostOf(url) ?: return "$scheme:…"
        val hostStart = url.indexOf(host, ignoreCase = true)
        var path = ""
        if (hostStart >= 0) {
            var i = hostStart + host.length
            while (i < url.length && url[i] != '/' && url[i] != '?' && url[i] != '#') i++
            val pEnd = url.indexOfAny(charArrayOf('?', '#'), i).let { if (it < 0) url.length else it }
            path = if (i < pEnd) url.substring(i, pEnd) else ""
            path = Scrub.maskTokens(path)
            if (path.length > 60) path = path.take(57) + "…"
            if (pEnd < url.length) path += "?…"
        }
        return "$scheme://$host$path"
    }
}

/** Entfernt potenziell vertrauliche Inhalte aus Freitext (Konsolenmeldungen u. Ä.). */
object Scrub {
    private val urlRegex = Regex("""\b(?:https?|wss?)://[^\s"'<>)]+""", RegexOption.IGNORE_CASE)
    private val tokenRegex = Regex("""[A-Za-z0-9_\-+/=.]{24,}""")
    private val emailRegex = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    private val pathTokenRegex = Regex("""[A-Za-z0-9_\-+=.]{24,}""")

    fun maskTokens(s: String): String = pathTokenRegex.replace(s, "…")

    fun text(s: String?, maxLen: Int = 160): String {
        if (s == null) return ""
        var t = urlRegex.replace(s) { UrlTools.sanitizeForLog(it.value) }
        t = emailRegex.replace(t, "[E-Mail]")
        // lange Zeichenketten ohne Leerzeichen sind häufig Tokens/Schlüssel
        t = t.split(' ').joinToString(" ") { w ->
            val looksLikeToken = w.length >= 24 && !w.contains("://") && tokenRegex.matches(w) &&
                w.any { it.isDigit() } && w.any { it.isLetter() }
            if (looksLikeToken) "…" else w
        }
        t = t.replace('\n', ' ').replace('\r', ' ')
        return if (t.length > maxLen) t.take(maxLen - 1) + "…" else t
    }
}
