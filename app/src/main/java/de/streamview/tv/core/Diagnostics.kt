package de.streamview.tv.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DiagCat(val label: String) {
    PAGE("Seite"),
    BLOCKED_REQUEST("Filter"),
    BLOCKED_POPUP("Pop-up blockiert"),
    ALLOWED_POPUP("Pop-up erlaubt"),
    BLOCKED_NAV("Weiterleitung blockiert"),
    ALLOWED_NAV("Weiterleitung erlaubt"),
    BLOCKED_SCHEME("App-Aufruf blockiert"),
    NET_ERROR("Netzwerkfehler"),
    HTTP_ERROR("HTTP-Fehler"),
    SSL_ERROR("SSL-Fehler"),
    JS_ERROR("JavaScript-Fehler"),
    MEDIA("Video"),
    MEDIA_ERROR("Videofehler"),
    DRM("DRM"),
    PERMISSION("Berechtigung"),
    RENDERER("Renderer"),
    DOWNLOAD("Download"),
    MEMORY("Speicher"),
    INFO("Info"),
}

class DiagEntry(val time: Long, val cat: DiagCat, val text: String, val pageSite: String)

/**
 * Protokoll für den Diagnosemodus. Ringpuffer mit fester Größe (kein unbegrenztes Wachstum).
 * Texte werden VOR dem Speichern bereinigt (keine Query-Parameter, Tokens, E-Mail-Adressen).
 * Cookies, Passwörter und Seiteninhalte werden nie aufgezeichnet.
 */
class DiagLog(private val capacity: Int = 250, private val requestCapacity: Int = 120) {
    private val events = ArrayDeque<DiagEntry>()
    private val requests = ArrayDeque<DiagEntry>()
    private val blockedHostsOnPage = HashMap<String, Int>()
    @Volatile var currentSite: String = ""
        private set
    var blockedRequestsOnPage = 0; private set
    var blockedPopupsOnPage = 0; private set
    var blockedNavsOnPage = 0; private set
    var jsErrorsOnPage = 0; private set

    @Synchronized
    fun newPage(site: String, sanitizedUrl: String) {
        currentSite = site
        blockedHostsOnPage.clear()
        blockedRequestsOnPage = 0
        blockedPopupsOnPage = 0
        blockedNavsOnPage = 0
        jsErrorsOnPage = 0
        addInternal(DiagCat.PAGE, sanitizedUrl)
    }

    @Synchronized
    fun add(cat: DiagCat, text: String) {
        when (cat) {
            DiagCat.BLOCKED_POPUP -> blockedPopupsOnPage++
            DiagCat.BLOCKED_NAV, DiagCat.BLOCKED_SCHEME -> blockedNavsOnPage++
            DiagCat.JS_ERROR -> {
                jsErrorsOnPage++
                if (jsErrorsOnPage > 40) return // Flut begrenzen
            }
            else -> {}
        }
        addInternal(cat, Scrub.text(text, 220))
    }

    private fun addInternal(cat: DiagCat, text: String) {
        events.addLast(DiagEntry(System.currentTimeMillis(), cat, text, currentSite))
        while (events.size > capacity) events.removeFirst()
    }

    /** Vom Filter blockierte Anfrage (läuft im WebView-IO-Thread). */
    @Synchronized
    fun blockedRequest(url: String, rule: String?, typeLabel: String) {
        blockedRequestsOnPage++
        val host = UrlTools.hostOf(url) ?: "?"
        blockedHostsOnPage[host] = (blockedHostsOnPage[host] ?: 0) + 1
        requests.addLast(
            DiagEntry(
                System.currentTimeMillis(), DiagCat.BLOCKED_REQUEST,
                "${UrlTools.sanitizeForLog(url)} [$typeLabel] Regel: ${rule ?: "?"}", currentSite,
            ),
        )
        while (requests.size > requestCapacity) requests.removeFirst()
    }

    @Synchronized fun events(): List<DiagEntry> = events.toList()
    @Synchronized fun requests(): List<DiagEntry> = requests.toList()
    @Synchronized fun topBlockedHosts(n: Int = 10): List<Pair<String, Int>> =
        blockedHostsOnPage.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }

    @Synchronized
    fun clear() {
        events.clear(); requests.clear(); blockedHostsOnPage.clear()
        blockedRequestsOnPage = 0; blockedPopupsOnPage = 0; blockedNavsOnPage = 0; jsErrorsOnPage = 0
    }

    companion object {
        // ThreadLocal.withInitial gibt es erst ab API 26 – daher klassisch
        private val fmt = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("HH:mm:ss", Locale.GERMANY)
        }
        fun time(t: Long): String = fmt.get()!!.format(Date(t))

        fun mediaErrorText(code: Int): String = when (code) {
            1 -> "MEDIA_ERR_ABORTED – Laden abgebrochen"
            2 -> "MEDIA_ERR_NETWORK – Netzwerkfehler beim Laden des Videos"
            3 -> "MEDIA_ERR_DECODE – Video konnte nicht dekodiert werden"
            4 -> "MEDIA_ERR_SRC_NOT_SUPPORTED – Format/Quelle nicht unterstützt oder nicht erreichbar"
            else -> "Unbekannter Medienfehler ($code)"
        }
    }
}

/**
 * Leitet aus dem Protokoll Hinweise ab. Jeder Hinweis beruht auf konkret protokollierten
 * Ereignissen der aktuellen Website – es werden keine Ursachen geraten.
 */
object DiagAnalyzer {

    class Facts(
        val site: String,
        val adblockActiveHere: Boolean,
        val blockedRequests: Int,
        val blockedPopups: Int,
        val blockedNavs: Int,
        val events: List<DiagEntry>,
        val webViewMajor: Int?,
        val documentStartScriptSupported: Boolean,
    )

    fun hints(f: Facts): List<String> {
        val out = ArrayList<String>()
        val here = f.events.filter { it.pageSite == f.site }
        fun count(c: DiagCat) = here.count { it.cat == c }

        if (f.adblockActiveHere && f.blockedRequests > 0) {
            out += "Der Werbefilter hat auf dieser Seite ${f.blockedRequests} Anfrage(n) blockiert. " +
                "Startet das Video nicht, testweise „Webseite funktioniert nicht?“ → „Werbeblocker vorübergehend aus“ wählen."
        }
        if (f.blockedPopups > 0) {
            out += "${f.blockedPopups} neue(s) Fenster wurde(n) blockiert. Öffnet sich der Player in einem neuen " +
                "Fenster, unter „Pop-up-Schutz für diese Seite“ „Nachfragen“ oder „Erlauben“ wählen."
        }
        if (f.blockedNavs > 0) {
            out += "${f.blockedNavs} Weiterleitung(en) oder App-Aufruf(e) wurden blockiert (Details im Protokoll)."
        }
        val mediaErr = here.filter { it.cat == DiagCat.MEDIA_ERROR }
        if (mediaErr.isNotEmpty()) {
            out += "Der Videoplayer der Seite hat Fehler gemeldet: " + mediaErr.takeLast(2).joinToString("; ") { it.text }
            if (mediaErr.any { it.text.contains("SRC_NOT_SUPPORTED") || it.text.contains("DECODE") }) {
                out += "Format- oder Decoderfehler: Prüfe im Abschnitt „Video-Decoder“, ob das Gerät den Codec " +
                    "(z. B. HEVC, VP9, AV1) unterstützt."
            }
        }
        if (count(DiagCat.SSL_ERROR) > 0) {
            out += "Es gab SSL-Zertifikatsfehler. Diese Verbindungen wurden aus Sicherheitsgründen abgebrochen."
        }
        if (count(DiagCat.NET_ERROR) > 0) {
            out += "Es gab ${count(DiagCat.NET_ERROR)} Netzwerkfehler (z. B. Server nicht erreichbar, Zeitüberschreitung)."
        }
        if (count(DiagCat.DRM) > 0) {
            out += "Die Seite hat DRM (geschützte Inhalte) angefordert – Ergebnis siehe Protokoll."
        }
        if (count(DiagCat.RENDERER) > 0) {
            out += "Der Seiten-Prozess wurde beendet (Absturz oder Speichermangel). Andere Apps schließen kann helfen."
        }
        if (f.webViewMajor != null && f.webViewMajor < 90) {
            out += "Die System-WebView ist Version ${f.webViewMajor}. Neuere Webseiten setzen oft neuere Versionen voraus; " +
                "ein Fire-OS-Systemupdate kann helfen."
        }
        if (!f.documentStartScriptSupported) {
            out += "Diese WebView unterstützt keine Skripte in eingebetteten Frames – Play/Pause per Fernbedienung " +
                "wirkt nur auf Videos im Hauptdokument."
        }
        if (out.isEmpty()) out += "Für diese Seite wurden keine auffälligen Ereignisse protokolliert."
        return out
    }
}
