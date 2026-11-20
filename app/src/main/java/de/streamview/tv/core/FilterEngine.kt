package de.streamview.tv.core

import java.io.BufferedReader

/**
 * Ressourcenart einer Anfrage. Android-WebView liefert diese Information NICHT mit,
 * sie wird daher aus Accept-Header und Dateiendung geschätzt (siehe [RequestTypes]).
 */
object ReqType {
    const val OTHER = 1
    const val SCRIPT = 1 shl 1
    const val IMAGE = 1 shl 2
    const val STYLESHEET = 1 shl 3
    const val XHR = 1 shl 4
    const val SUBDOCUMENT = 1 shl 5
    const val MEDIA = 1 shl 6
    const val FONT = 1 shl 7
    const val OBJECT = 1 shl 8
    const val WEBSOCKET = 1 shl 9
    const val PING = 1 shl 10
    const val DOCUMENT = 1 shl 11
    const val POPUP = 1 shl 12
    const val ELEMHIDE = 1 shl 13
    const val GENERICHIDE = 1 shl 14

    /** Typen, für die eine Regel ohne Typ-Option gilt (wie in Adblock Plus: nicht document/popup). */
    const val DEFAULT = OTHER or SCRIPT or IMAGE or STYLESHEET or XHR or SUBDOCUMENT or MEDIA or
        FONT or OBJECT or WEBSOCKET or PING

    /**
     * Wenn der Typ nicht bestimmbar ist (Accept-Header ohne Typangabe und ohne Dateiendung), könnte es Skript, XHR oder
     * "other" sein. Regeln, die NUR für Bilder/CSS/Frames/Medien gelten, werden dann nicht
     * angewendet – lieber eine Werbung zu viel als eine kaputte Seite.
     */
    const val UNKNOWN = SCRIPT or XHR or OTHER or PING
}

/** Ergebnis einer Filterprüfung. */
class FilterMatch(val blocked: Boolean, val rule: String?) {
    companion object {
        val NONE = FilterMatch(false, null)
    }
}

class FilterStats {
    var networkRules = 0
    var plainHostRules = 0
    var exceptionRules = 0
    var cosmeticSpecific = 0
    var cosmeticGeneric = 0
    var cosmeticGenericSkipped = 0
    var unsupported = 0
    var lines = 0
}

/**
 * Filter-Engine für eine klar abgegrenzte TEILMENGE der Adblock-Plus-Syntax.
 *
 * Unterstützt:
 *  - Netzwerkregeln: `||host^`, `|https://`, `ende|`, `*`, `^`, Teilstrings, Ausnahmen `@@`
 *  - Optionen: third-party/first-party (3p/1p), script, image, stylesheet, xmlhttprequest,
 *    subdocument, media, font, object, websocket, ping, other, document, popup, all,
 *    domain=…, important, match-case, elemhide, generichide (bei Ausnahmen)
 *  - Elementverbergung: `##selektor`, `domain##selektor`, `#@#selektor`
 *
 * Bewusst NICHT unterstützt (die Regel wird verworfen und gezählt):
 *  - Reguläre Ausdrücke `/…/`, `$csp`, `$redirect`, `$removeparam`, `$header`, `$badfilter` u. a.
 *  - Erweiterte/prozedurale Selektoren (`:has-text`, `:-abp-…`, `#?#`, `#$#`, Scriptlets `+js()`)
 *
 * Der Typ einer Anfrage ist in WebView nur geschätzt; `$domain=` und third-party beziehen sich
 * auf die oberste Seite, nicht auf das einzelne iFrame. Das ist eine dokumentierte Näherung.
 */
class FilterEngine private constructor(
    private val plainBlockedHosts: HashSet<String>,
    private val blockByHost: HashMap<String, Any>,
    private val exceptionByHost: HashMap<String, Any>,
    private val blockByToken: HashMap<String, Any>,
    private val exceptionByToken: HashMap<String, Any>,
    private val blockFallback: ArrayList<NetRule>,
    private val exceptionFallback: ArrayList<NetRule>,
    private val cosmeticSpecific: HashMap<String, ArrayList<String>>,
    private val cosmeticSpecificExceptions: HashMap<String, HashSet<String>>,
    private val cosmeticGeneric: ArrayList<String>,
    private val cosmeticGenericExceptions: HashSet<String>,
    private val cosmeticGenericExcludedOn: HashMap<String, HashSet<String>>,
    val stats: FilterStats,
) {

    // ---------------------------------------------------------------------------------------
    // Netzwerk
    // ---------------------------------------------------------------------------------------

    /**
     * Prüft eine Unteranfrage.
     * @param url        vollständige URL der Anfrage
     * @param pageHost   Host der obersten Seite (für third-party und $domain=)
     * @param type       ReqType-Bit oder [ReqType.UNKNOWN]
     */
    fun match(url: String, pageHost: String?, type: Int): FilterMatch {
        val lower = url.lowercase()
        val reqHost = UrlTools.hostOf(lower) ?: return FilterMatch.NONE
        val ctx = Ctx(url, lower, reqHost, pageHost, type)
        val block = findBlock(ctx) ?: return FilterMatch.NONE
        if (!block.important && findException(ctx) != null) return FilterMatch.NONE
        return FilterMatch(true, block.describe())
    }

    /**
     * Prüfung für Hauptdokumente und Pop-ups ("strenges" Blockieren wie in uBlock Origin):
     * Neben ausdrücklichen $document/$popup-Regeln zählen auch reine Domain-Regeln `||host^`.
     */
    fun matchNavigation(url: String, pageHost: String?, popup: Boolean): FilterMatch {
        val lower = url.lowercase()
        val reqHost = UrlTools.hostOf(lower) ?: return FilterMatch.NONE
        val type = if (popup) ReqType.POPUP else ReqType.DOCUMENT
        val ctx = Ctx(url, lower, reqHost, pageHost, type)
        if (findException(ctx) != null) return FilterMatch.NONE
        val explicit = findBlock(ctx)
        if (explicit != null) return FilterMatch(true, explicit.describe())
        // strenge Domain-Blockierung
        var h = reqHost
        while (true) {
            if (h in plainBlockedHosts) return FilterMatch(true, "||$h^")
            val byHost = blockByHost[h]
            if (byHost != null) {
                for (r in asList(byHost)) {
                    if (r.pattern == null && r.typeMask and ReqType.DEFAULT != 0 &&
                        r.partyAndDomainOk(ctx)
                    ) return FilterMatch(true, r.describe())
                }
            }
            h = parentDomain(h) ?: break
        }
        return FilterMatch.NONE
    }

    /** Liefert die greifende Ausnahmeregel (für Diagnose/Tests). */
    fun findExceptionRule(url: String, pageHost: String?, type: Int): String? {
        val lower = url.lowercase()
        val reqHost = UrlTools.hostOf(lower) ?: return null
        return findException(Ctx(url, lower, reqHost, pageHost, type))?.describe()
    }

    /** Ausnahme `@@||site^$document`: Seite vollständig vom Filtern ausgenommen. */
    fun isPageAllowlisted(pageUrl: String): Boolean = hasPageException(pageUrl, ReqType.DOCUMENT)

    private fun hasPageException(pageUrl: String, bit: Int): Boolean {
        val lower = pageUrl.lowercase()
        val host = UrlTools.hostOf(lower) ?: return false
        val ctx = Ctx(pageUrl, lower, host, host, bit)
        return findException(ctx) != null
    }

    internal class Ctx(
        val url: String,
        val lower: String,
        val reqHost: String,
        val pageHost: String?,
        val type: Int,
    ) {
        val thirdParty: Boolean? = if (pageHost.isNullOrEmpty()) null
        else !PublicSuffix.isSameSite(reqHost, pageHost)
    }

    private fun findBlock(ctx: Ctx): NetRule? {
        if (ctx.type and ReqType.DEFAULT != 0) {
            var h = ctx.reqHost
            while (true) {
                if (h in plainBlockedHosts) return PLAIN_HOST_RULE.withHost(h)
                h = parentDomain(h) ?: break
            }
        }
        return search(ctx, blockByHost, blockByToken, blockFallback)
    }

    private fun findException(ctx: Ctx): NetRule? =
        search(ctx, exceptionByHost, exceptionByToken, exceptionFallback)

    private fun search(
        ctx: Ctx,
        byHost: HashMap<String, Any>,
        byToken: HashMap<String, Any>,
        fallback: ArrayList<NetRule>,
    ): NetRule? {
        var h = ctx.reqHost
        while (true) {
            val e = byHost[h]
            if (e != null) for (r in asList(e)) if (r.matches(ctx)) return r
            h = parentDomain(h) ?: break
        }
        if (byToken.isNotEmpty()) {
            val s = ctx.lower
            var i = 0
            val n = s.length
            while (i < n) {
                while (i < n && !isTokenChar(s[i])) i++
                val start = i
                while (i < n && isTokenChar(s[i])) i++
                if (i - start >= 2) {
                    val e = byToken[s.substring(start, i)]
                    if (e != null) for (r in asList(e)) if (r.matches(ctx)) return r
                }
            }
        }
        for (r in fallback) if (r.matches(ctx)) return r
        return null
    }

    // ---------------------------------------------------------------------------------------
    // Elementverbergung
    // ---------------------------------------------------------------------------------------

    /**
     * CSS zum Ausblenden von Elementen für einen Host.
     * @param includeGeneric auch allgemeine (nicht seitenspezifische) Regeln – deutlich mehr CSS
     */
    fun cosmeticSelectors(pageUrl: String, frameHost: String, includeGeneric: Boolean): List<String> {
        if (hasPageException(pageUrl, ReqType.ELEMHIDE)) return emptyList()
        val generic = includeGeneric && !hasPageException(pageUrl, ReqType.GENERICHIDE)
        val result = LinkedHashSet<String>()
        val excluded = HashSet<String>()
        var h: String? = frameHost.lowercase()
        while (h != null) {
            cosmeticSpecificExceptions[h]?.let { excluded.addAll(it) }
            cosmeticGenericExcludedOn[h]?.let { excluded.addAll(it) }
            h = parentDomain(h)
        }
        h = frameHost.lowercase()
        while (h != null) {
            cosmeticSpecific[h]?.let { list -> for (s in list) if (s !in excluded) result.add(s) }
            h = parentDomain(h)
        }
        if (generic) {
            for (s in cosmeticGeneric) {
                if (s !in excluded && s !in cosmeticGenericExceptions) result.add(s)
            }
        }
        return result.toList()
    }

    // ---------------------------------------------------------------------------------------
    // Regeln
    // ---------------------------------------------------------------------------------------

    internal class NetRule(
        /** Muster ohne Anker, kleingeschrieben (außer matchCase). null = reine Host-Regel. */
        val pattern: String?,
        val anchor: Int,          // 0 kein, 1 '|', 2 '||'
        val endAnchor: Boolean,
        val typeMask: Int,
        val party: Int,           // 0 egal, 1 nur third-party, 2 nur first-party
        val incDomains: Array<String>?,
        val excDomains: Array<String>?,
        val important: Boolean,
        val matchCase: Boolean,
        val hostKey: String?,     // bei Host-Regeln: der Host
    ) {
        fun withHost(h: String) = NetRule(null, 2, false, typeMask, 0, null, null, false, false, h)

        fun describe(): String {
            val p = if (pattern == null) "||$hostKey^" else when (anchor) {
                2 -> "||$pattern"
                1 -> "|$pattern"
                else -> pattern
            } + if (endAnchor) "|" else ""
            return p.take(80)
        }

        fun partyAndDomainOk(ctx: Ctx): Boolean {
            if (party != 0) {
                val tp = ctx.thirdParty ?: return false
                if (party == 1 && !tp) return false
                if (party == 2 && tp) return false
            }
            val page = ctx.pageHost
            if (excDomains != null && page != null) {
                for (d in excDomains) if (isSubdomainOrSame(page, d)) return false
            }
            if (incDomains != null) {
                if (page == null) return false
                var ok = false
                for (d in incDomains) if (isSubdomainOrSame(page, d)) { ok = true; break }
                if (!ok) return false
            }
            return true
        }

        fun matches(ctx: Ctx): Boolean {
            if (typeMask and ctx.type == 0) return false
            if (!partyAndDomainOk(ctx)) return false
            val p = pattern ?: return true // Host-Regel: Host-Treffer genügt
            if (p.isEmpty()) return true    // z. B. "$script,domain=x.com" – Optionen allein entscheiden
            val subject = if (matchCase) ctx.url else ctx.lower
            return when (anchor) {
                1 -> glob(p, subject, 0, endAnchor)
                2 -> {
                    val hs = subject.indexOf("://")
                    if (hs < 0) return false
                    val hostStart = hs + 3
                    var hostEnd = subject.length
                    for (i in hostStart until subject.length) {
                        val c = subject[i]
                        if (c == '/' || c == '?' || c == '#' || c == ':') { hostEnd = i; break }
                    }
                    if (glob(p, subject, hostStart, endAnchor)) return true
                    for (i in hostStart until hostEnd) {
                        if (subject[i] == '.' && glob(p, subject, i + 1, endAnchor)) return true
                    }
                    false
                }
                else -> {
                    val first = p[0]
                    if (first == '*' || first == '^') {
                        for (i in 0..subject.length) if (glob(p, subject, i, endAnchor)) return true
                        false
                    } else {
                        var idx = subject.indexOf(first)
                        while (idx >= 0) {
                            if (glob(p, subject, idx, endAnchor)) return true
                            idx = subject.indexOf(first, idx + 1)
                        }
                        false
                    }
                }
            }
        }
    }

    companion object {
        private val PLAIN_HOST_RULE = NetRule(null, 2, false, ReqType.DEFAULT, 0, null, null, false, false, "")

        private val extendedCosmetic = Regex("#@?[?$%]#")

        private val stopTokens = hashSetOf("http", "https", "www", "com", "net", "org", "html", "js", "php", "de")

        private val unsupportedOptions = hashSetOf(
            "csp", "redirect", "redirect-rule", "rewrite", "removeparam", "queryprune", "header",
            "permissions", "badfilter", "replace", "urltransform", "method", "to", "denyallow",
            "strict1p", "strict3p", "genericblock", "inline-script", "inline-font", "empty", "mp4",
            "cname", "sitekey", "webrtc", "uritransform", "urlskip", "jsonprune", "hls", "network",
            "app", "stealth", "cookie", "extension", "jsinject", "content", "specifichide", "shide",
        )

        private val proceduralMarkers = listOf(
            ":-abp-", ":has-text(", ":contains(", ":xpath(", ":upward(", ":remove(", ":style(",
            ":matches-css", ":min-text-length(", ":watch-attr(", ":others(", ":if(", ":if-not(",
            ":matches-path(", ":nth-ancestor(", ":remove-attr(", ":remove-class(", ":matches-attr(",
            ":matches-prop(", ":matches-media(", "+js(", ":shadow-dom",
        )

        fun isTokenChar(c: Char) = (c in 'a'..'z') || (c in '0'..'9') || c == '%'

        fun parentDomain(h: String): String? {
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.length - 1) return null
            val p = h.substring(dot + 1)
            return if (p.indexOf('.') < 0) null else p // nie die reine TLD
        }

        fun isSubdomainOrSame(host: String, domain: String): Boolean =
            host == domain || (host.length > domain.length && host.endsWith(domain) &&
                host[host.length - domain.length - 1] == '.')

        private fun isSeparator(c: Char): Boolean =
            !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '%')

        /** Wildcard-Abgleich: '*' beliebig, '^' Trennzeichen oder Ende. */
        internal fun glob(p: String, s: String, start: Int, endAnchor: Boolean): Boolean {
            var pi = 0
            var si = start
            var starP = -1
            var starS = 0
            val pl = p.length
            val sl = s.length
            while (true) {
                if (pi < pl) {
                    val c = p[pi]
                    if (c == '*') {
                        starP = pi
                        starS = si
                        pi++
                        continue
                    }
                    if (si < sl && (c == s[si] || (c == '^' && isSeparator(s[si])))) {
                        pi++; si++; continue
                    }
                    if (c == '^' && si == sl) { pi++; continue }
                } else {
                    if (!endAnchor || si == sl) return true
                }
                if (starP >= 0 && starS < sl) {
                    starS++
                    si = starS
                    pi = starP + 1
                    continue
                }
                return false
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun asList(e: Any): List<NetRule> =
            if (e is NetRule) listOf(e) else e as List<NetRule>

        private fun addIndexed(map: HashMap<String, Any>, key: String, r: NetRule) {
            when (val e = map[key]) {
                null -> map[key] = r
                is NetRule -> map[key] = arrayListOf(e, r)
                else -> @Suppress("UNCHECKED_CAST") (e as ArrayList<NetRule>).add(r)
            }
        }

        private fun bestToken(pattern: String, anchor: Int, endAnchor: Boolean): String? {
            var best: String? = null
            var bestStop: String? = null
            var i = 0
            val n = pattern.length
            while (i < n) {
                while (i < n && !isTokenChar(pattern[i])) i++
                val start = i
                while (i < n && isTokenChar(pattern[i])) i++
                if (i - start < 2) continue
                val before = if (start == 0) (if (anchor != 0) '|' else '*') else pattern[start - 1]
                val after = if (i == n) (if (endAnchor) '|' else '*') else pattern[i]
                if (before == '*' || after == '*') continue
                val t = pattern.substring(start, i)
                if (t in stopTokens) {
                    if (bestStop == null) bestStop = t
                } else if (best == null || t.length > best.length) best = t
            }
            return best ?: bestStop
        }

    }

        /** Baut eine Engine aus beliebig vielen Listen (zeilenweise). */
        class Builder(private val includeGenericCosmetics: Boolean) {
            private val plainBlockedHosts = HashSet<String>(65_536)
            private val blockByHost = HashMap<String, Any>(8_192)
            private val exceptionByHost = HashMap<String, Any>(1_024)
            private val blockByToken = HashMap<String, Any>(16_384)
            private val exceptionByToken = HashMap<String, Any>(4_096)
            private val blockFallback = ArrayList<NetRule>()
            private val exceptionFallback = ArrayList<NetRule>()
            private val cosmeticSpecific = HashMap<String, ArrayList<String>>(8_192)
            private val cosmeticSpecificExceptions = HashMap<String, HashSet<String>>()
            private val cosmeticGeneric = ArrayList<String>()
            private val cosmeticGenericExceptions = HashSet<String>()
            private val cosmeticGenericExcludedOn = HashMap<String, HashSet<String>>()
            private val stats = FilterStats()
            private val strings = HashMap<String, String>() // Deduplizierung von Selektoren

            fun addList(reader: BufferedReader) {
                reader.useLines { lines -> lines.forEach { addLine(it) } }
            }

            fun addLine(rawLine: String) {
                val line = rawLine.trim()
                if (line.isEmpty() || line[0] == '!' || line[0] == '[') return
                stats.lines++
                try {
                    if (!parseCosmetic(line)) parseNetwork(line)
                } catch (_: Exception) {
                    stats.unsupported++
                }
            }

            private fun intern(s: String): String = strings.getOrPut(s) { s }

            /** @return true, wenn die Zeile eine Elementregel (oder eine verworfene Variante) war */
            private fun parseCosmetic(line: String): Boolean {
                val ex = line.indexOf("#@#")
                val hh = line.indexOf("##")
                val idx: Int
                val exception: Boolean
                if (ex >= 0 && (hh < 0 || ex < hh)) { idx = ex; exception = true }
                else if (hh >= 0) { idx = hh; exception = false }
                else {
                    // Erweiterte Syntax (#?#, #$#, #%#, #@?#, #@$#) erkennen und verwerfen
                    if (extendedCosmetic.containsMatchIn(line)) {
                        val d = line.substringBefore('#')
                        if (d.all { it.isLetterOrDigit() || it in ".,~*-" }) {
                            stats.unsupported++
                            return true
                        }
                    }
                    return false
                }
                val domains = line.substring(0, idx).lowercase()
                if (!domains.all { it.isLetterOrDigit() || it in ".,~*-" }) return false
                val selector = line.substring(idx + if (exception) 3 else 2).trim()
                if (selector.isEmpty() || selector.contains('{') || selector.contains('}') ||
                    selector.contains("</") || proceduralMarkers.any { selector.contains(it) } ||
                    selector.startsWith("+js") || selector.startsWith("^")
                ) {
                    stats.unsupported++
                    return true
                }
                if (domains.contains('*')) { stats.unsupported++; return true }
                val sel = intern(selector)
                val inc = ArrayList<String>()
                val exc = ArrayList<String>()
                if (domains.isNotEmpty()) for (d in domains.split(',')) {
                    if (d.isEmpty()) continue
                    if (d.startsWith("~")) exc.add(d.substring(1)) else inc.add(d)
                }
                if (exception) {
                    if (inc.isEmpty()) cosmeticGenericExceptions.add(sel)
                    else for (d in inc) cosmeticSpecificExceptions.getOrPut(d) { HashSet() }.add(sel)
                    return true
                }
                if (inc.isEmpty()) {
                    if (!includeGenericCosmetics) { stats.cosmeticGenericSkipped++; return true }
                    cosmeticGeneric.add(sel)
                    stats.cosmeticGeneric++
                    for (d in exc) cosmeticGenericExcludedOn.getOrPut(d) { HashSet() }.add(sel)
                } else {
                    for (d in inc) cosmeticSpecific.getOrPut(d) { ArrayList(2) }.add(sel)
                    for (d in exc) cosmeticSpecificExceptions.getOrPut(d) { HashSet() }.add(sel)
                    stats.cosmeticSpecific++
                }
                return true
            }

            private fun parseNetwork(lineIn: String) {
                var line = lineIn
                val exception = line.startsWith("@@")
                if (exception) line = line.substring(2)

                var options: String? = null
                val dollar = line.lastIndexOf('$')
                if (dollar >= 0 && dollar < line.length - 1) {
                    val opt = line.substring(dollar + 1)
                    val firstName = opt.takeWhile { it != ',' && it != '=' }.removePrefix("~")
                    if (opt.all { it.isLetterOrDigit() || it in "~_,=|.*-" }) {
                        options = opt.lowercase()
                        line = line.substring(0, dollar)
                    } else if (firstName.isNotEmpty() && firstName.all { it.isLetter() || it == '-' || it == '_' }) {
                        // Optionen mit Werten wie $csp=script-src 'none' – nicht unterstützt
                        stats.unsupported++
                        return
                    }
                }
                if (line.length > 1 && line.startsWith("/") && line.endsWith("/")) {
                    stats.unsupported++ // reguläre Ausdrücke
                    return
                }

                var typeMaskPos = 0
                var typeMaskNeg = 0
                var party = 0
                var inc: Array<String>? = null
                var exc: Array<String>? = null
                var important = false
                var matchCase = false
                if (options != null) {
                    for (o in options.split(',')) {
                        if (o.isEmpty()) continue
                        val neg = o.startsWith("~")
                        val name = if (neg) o.substring(1) else o
                        val bit = typeBit(name)
                        when {
                            bit != 0 -> if (neg) typeMaskNeg = typeMaskNeg or bit else typeMaskPos = typeMaskPos or bit
                            name == "third-party" || name == "3p" -> party = if (neg) 2 else 1
                            name == "first-party" || name == "1p" -> party = if (neg) 1 else 2
                            name == "all" && !neg -> typeMaskPos = typeMaskPos or ReqType.DEFAULT or ReqType.DOCUMENT or ReqType.POPUP
                            name == "important" -> important = true
                            name == "match-case" -> matchCase = true
                            name.startsWith("domain=") || name.startsWith("from=") -> {
                                val i2 = ArrayList<String>()
                                val e2 = ArrayList<String>()
                                for (d in name.substringAfter('=').split('|')) {
                                    if (d.isEmpty()) continue
                                    if (d.contains('*')) { stats.unsupported++; return }
                                    if (d.startsWith("~")) e2.add(d.substring(1)) else i2.add(d)
                                }
                                if (i2.isNotEmpty()) inc = i2.toTypedArray()
                                if (e2.isNotEmpty()) exc = e2.toTypedArray()
                            }
                            name.substringBefore('=') in unsupportedOptions -> { stats.unsupported++; return }
                            else -> { stats.unsupported++; return }
                        }
                    }
                }
                val typeMask = (if (typeMaskPos == 0) ReqType.DEFAULT else typeMaskPos) and typeMaskNeg.inv()
                if (typeMask == 0) { stats.unsupported++; return }
                // Seiten-Ausnahmen (elemhide/generichide/document) nur bei @@ sinnvoll
                if (!exception && typeMask and (ReqType.ELEMHIDE or ReqType.GENERICHIDE) != 0) {
                    stats.unsupported++; return
                }

                var anchor = 0
                var endAnchor = false
                var p = line
                if (p.startsWith("||")) { anchor = 2; p = p.substring(2) }
                else if (p.startsWith("|")) { anchor = 1; p = p.substring(1) }
                if (p.endsWith("|")) { endAnchor = true; p = p.substring(0, p.length - 1) }
                if (anchor == 0) p = p.trimStart('*')
                if (!endAnchor) p = p.trimEnd('*')
                if (!matchCase) p = p.lowercase()
                if (p.isEmpty() && inc == null) {
                    stats.unsupported++ // würde alles treffen
                    return
                }

                // Reine Host-Regel? "||example.com^" bzw. "||example.com"
                var hostOnly: String? = null
                if (anchor == 2 && !matchCase) {
                    val h = if (p.endsWith("^")) p.substring(0, p.length - 1) else p
                    if (h.isNotEmpty() && h.contains('.') &&
                        h.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }
                    ) {
                        hostOnly = h
                    }
                }
                if (hostOnly != null && !exception && options == null) {
                    plainBlockedHosts.add(hostOnly)
                    stats.plainHostRules++
                    stats.networkRules++
                    return
                }
                val rule = NetRule(
                    if (hostOnly != null) null else p, anchor, endAnchor, typeMask, party,
                    inc, exc, important, matchCase, hostOnly,
                )
                if (exception) stats.exceptionRules++ else stats.networkRules++
                val byHost = if (exception) exceptionByHost else blockByHost
                val byToken = if (exception) exceptionByToken else blockByToken
                val fallback = if (exception) exceptionFallback else blockFallback
                if (hostOnly != null) { addIndexed(byHost, hostOnly, rule); return }
                val token = if (p.isEmpty()) null else bestToken(p, anchor, endAnchor)
                if (token != null) addIndexed(byToken, token, rule) else fallback.add(rule)
            }

            private fun typeBit(n: String): Int = when (n) {
                "script" -> ReqType.SCRIPT
                "image" -> ReqType.IMAGE
                "stylesheet", "css" -> ReqType.STYLESHEET
                "xmlhttprequest", "xhr" -> ReqType.XHR
                "subdocument", "frame" -> ReqType.SUBDOCUMENT
                "media" -> ReqType.MEDIA
                "font" -> ReqType.FONT
                "object", "object-subrequest" -> ReqType.OBJECT
                "websocket" -> ReqType.WEBSOCKET
                "ping", "beacon" -> ReqType.PING
                "other" -> ReqType.OTHER
                "document", "doc" -> ReqType.DOCUMENT
                "popup", "popunder" -> ReqType.POPUP
                "elemhide", "ehide" -> ReqType.ELEMHIDE
                "generichide", "ghide" -> ReqType.GENERICHIDE
                else -> 0
            }

            fun build(): FilterEngine {
                strings.clear()
                return FilterEngine(
                    plainBlockedHosts, blockByHost, exceptionByHost, blockByToken, exceptionByToken,
                    blockFallback, exceptionFallback, cosmeticSpecific, cosmeticSpecificExceptions,
                    cosmeticGeneric, cosmeticGenericExceptions, cosmeticGenericExcludedOn, stats,
                )
            }
        }
}

/** Schätzt den Ressourcentyp aus Accept-Header und Dateiendung. */
object RequestTypes {
    private val imageExt = setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp")
    private val mediaExt = setOf("mp4", "m4s", "m4v", "webm", "m3u8", "mpd", "ts", "aac", "mp3", "m4a", "ogg", "vtt")
    private val fontExt = setOf("woff", "woff2", "ttf", "otf", "eot")

    fun infer(url: String, accept: String?, isMainFrame: Boolean): Int {
        if (isMainFrame) return ReqType.DOCUMENT
        val a = accept?.lowercase() ?: ""
        if (a.startsWith("text/html") || a.contains("application/xhtml")) return ReqType.SUBDOCUMENT
        if (a.startsWith("text/css")) return ReqType.STYLESHEET
        if (a.startsWith("image/")) return ReqType.IMAGE
        if (a.startsWith("video/") || a.startsWith("audio/")) return ReqType.MEDIA
        val ext = extension(url)
        return when {
            ext == "js" || ext == "mjs" -> ReqType.SCRIPT
            ext == "css" -> ReqType.STYLESHEET
            ext in imageExt -> ReqType.IMAGE
            ext in mediaExt -> ReqType.MEDIA
            ext in fontExt -> ReqType.FONT
            url.startsWith("ws:") || url.startsWith("wss:") -> ReqType.WEBSOCKET
            else -> ReqType.UNKNOWN
        }
    }

    fun extension(url: String): String {
        var end = url.length
        val q = url.indexOf('?'); if (q >= 0) end = q
        val f = url.indexOf('#'); if (f in 0 until end) end = f
        val slash = url.lastIndexOf('/', end - 1)
        val dot = url.lastIndexOf('.', end - 1)
        if (dot <= slash || dot < 0) return ""
        val e = url.substring(dot + 1, end).lowercase()
        return if (e.length in 1..5) e else ""
    }
}
