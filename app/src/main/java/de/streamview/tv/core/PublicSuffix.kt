package de.streamview.tv.core

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.IDN

/**
 * Ermittelt die "registrierbare Domain" (eTLD+1), z. B. "news.bbc.co.uk" -> "bbc.co.uk".
 * Grundlage ist die Public Suffix List (publicsuffix.org, MPL 2.0), die als Asset mitgeliefert wird.
 * Solange die Liste nicht geladen ist, greift eine einfache Näherung.
 *
 * Reines Kotlin ohne Android-Abhängigkeiten (per JVM-Unit-Test prüfbar).
 */
object PublicSuffix {

    @Volatile private var rules: Set<String>? = null
    @Volatile private var wildcards: Set<String> = emptySet()
    @Volatile private var exceptions: Set<String> = emptySet()

    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 512
    }

    val isLoaded: Boolean get() = rules != null

    fun load(input: InputStream) {
        val r = HashSet<String>(16_000)
        val w = HashSet<String>(64)
        val e = HashSet<String>(16)
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("//")) continue
                val rule = line.substringBefore(' ')
                when {
                    rule.startsWith("!") -> e.add(ascii(rule.substring(1)))
                    rule.startsWith("*.") -> w.add(ascii(rule.substring(2)))
                    else -> r.add(ascii(rule))
                }
            }
        }
        wildcards = w
        exceptions = e
        rules = r
        synchronized(cache) { cache.clear() }
    }

    private fun ascii(s: String): String = try {
        IDN.toASCII(s, IDN.ALLOW_UNASSIGNED).lowercase()
    } catch (_: Exception) {
        s.lowercase()
    }

    /** Liefert die registrierbare Domain; für IP-Adressen den Host selbst; für null einen Leerstring. */
    fun registrableDomain(host: String?): String {
        if (host.isNullOrEmpty()) return ""
        val h = host.lowercase().trimEnd('.')
        if (isIpAddress(h)) return h
        synchronized(cache) { cache[h]?.let { return it } }
        val result = compute(h)
        synchronized(cache) { cache[h] = result }
        return result
    }

    fun isSameSite(hostA: String?, hostB: String?): Boolean {
        if (hostA.isNullOrEmpty() || hostB.isNullOrEmpty()) return false
        return registrableDomain(hostA) == registrableDomain(hostB)
    }

    private fun compute(h: String): String {
        val r = rules ?: return heuristic(h)
        // Startpositionen aller Kandidaten (vom längsten zum kürzesten Suffix)
        val starts = ArrayList<Int>(6)
        starts.add(0)
        for (i in h.indices) if (h[i] == '.') starts.add(i + 1)
        val labelCount = starts.size
        var suffixLabels = 1 // Standardregel "*"
        for ((idx, start) in starts.withIndex()) {
            val cand = h.substring(start)
            val candLabels = labelCount - idx
            if (cand in exceptions) {
                suffixLabels = candLabels - 1
                break
            }
            if (cand in r) {
                suffixLabels = candLabels
                break
            }
            val dot = cand.indexOf('.')
            if (dot > 0 && cand.substring(dot + 1) in wildcards) {
                suffixLabels = candLabels
                break
            }
        }
        if (labelCount <= suffixLabels) return h
        return h.substring(starts[labelCount - suffixLabels - 1])
    }

    private val twoLevel = setOf("co", "com", "org", "net", "gov", "ac", "edu", "or", "ne", "go", "gv")

    private fun heuristic(h: String): String {
        val parts = h.split('.')
        if (parts.size <= 2) return h
        val tld = parts[parts.size - 1]
        val sld = parts[parts.size - 2]
        val n = if (tld.length == 2 && sld in twoLevel) 3 else 2
        return parts.takeLast(n).joinToString(".")
    }

    fun isIpAddress(h: String): Boolean {
        if (h.startsWith("[") || h.contains(':')) return true
        var dots = 0
        for (c in h) {
            if (c == '.') dots++ else if (c !in '0'..'9') return false
        }
        return dots == 3
    }
}
