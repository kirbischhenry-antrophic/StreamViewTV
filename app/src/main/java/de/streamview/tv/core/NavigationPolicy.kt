package de.streamview.tv.core

import java.net.URLDecoder

/** Umgang mit neuen Fenstern und Weiterleitungen pro Webseite. */
enum class PopupPolicy { ASK, ALLOW, BLOCK }

enum class Verdict { ALLOW, BLOCK, ASK }

data class NavDecision(val verdict: Verdict, val reason: String)

/**
 * Alle Signale, die WebView zu einer Navigation liefert, plus App-eigene Beobachtungen.
 * Bewusst als einfache Datenklasse – damit ist die Entscheidungslogik per Unit-Test prüfbar.
 */
data class NavContext(
    /** aktuell angezeigte Seite (null = noch keine) */
    val currentUrl: String?,
    val targetUrl: String,
    /** WebResourceRequest.hasGesture() bzw. isUserGesture bei onCreateWindow */
    val hasGesture: Boolean,
    /** WebResourceRequest.isRedirect() – serverseitige Weiterleitung */
    val isRedirect: Boolean = false,
    /** Link-Ziel unter dem letzten Klick (Hit-Test), falls bekannt */
    val clickedHref: String? = null,
    /** OK-Taste wurde in den letzten ~2 s gedrückt */
    val userClickedRecently: Boolean = false,
    /** Hat der Nutzer auf dieser Seite schon geklickt? */
    val pageHadUserInteraction: Boolean = false,
    val msSincePageStart: Long = Long.MAX_VALUE,
    val sitePolicy: PopupPolicy = PopupPolicy.ASK,
    /** Pop-up-/Weiterleitungsschutz global aktiv */
    val protectionEnabled: Boolean = true,
    /** Filterliste kennt das Ziel als Werbe-/Schadseite (nur wenn Werbeblocker hier aktiv) */
    val filterBlocked: Boolean = false,
)

/**
 * Entscheidet über Hauptframe-Navigationen und neue Fenster.
 *
 * Grundsatz (Priorität 1/2 vor 3): Im Zweifel fragen statt still blockieren, und
 * Navigationen innerhalb derselben Website nie behindern.
 */
object NavigationPolicy {

    /** Zeitfenster nach Seitenstart, in dem Weiterleitungen ohne Klick als "Lade-Weiterleitung" gelten. */
    const val LANDING_REDIRECT_MS = 4000L

    fun mainFrame(c: NavContext): NavDecision {
        if (!UrlTools.isHttp(c.targetUrl)) return NavDecision(Verdict.BLOCK, "Kein http/https-Ziel")
        if (c.filterBlocked) return NavDecision(Verdict.BLOCK, "Ziel steht auf einer Filterliste")
        val cur = c.currentUrl ?: return NavDecision(Verdict.ALLOW, "Erste Seite")
        if (!UrlTools.isHttp(cur)) return NavDecision(Verdict.ALLOW, "Vorherige Seite war keine Webseite")
        if (UrlTools.sameSite(cur, c.targetUrl)) return NavDecision(Verdict.ALLOW, "Gleiche Website")
        if (!c.protectionEnabled || c.sitePolicy == PopupPolicy.ALLOW) {
            return NavDecision(Verdict.ALLOW, "Weiterleitungen für diese Website erlaubt")
        }
        if (c.isRedirect) return NavDecision(Verdict.ALLOW, "Server-Weiterleitung einer bereits geprüften Navigation")

        val userInitiated = c.hasGesture || c.userClickedRecently
        if (userInitiated) {
            val href = c.clickedHref
            if (href != null && UrlTools.isHttp(href)) {
                return if (UrlTools.sameSite(href, c.targetUrl)) {
                    NavDecision(Verdict.ALLOW, "Nutzer hat diesen Link angeklickt")
                } else if (c.sitePolicy == PopupPolicy.BLOCK) {
                    NavDecision(Verdict.BLOCK, "Klick wurde auf fremde Domain umgeleitet")
                } else {
                    NavDecision(Verdict.ASK, "Klick wurde auf eine andere Domain umgeleitet als der angeklickte Link")
                }
            }
            return if (c.sitePolicy == PopupPolicy.BLOCK) {
                NavDecision(Verdict.BLOCK, "Seite wechselt nach Klick zu fremder Domain")
            } else {
                NavDecision(Verdict.ASK, "Seite wechselt nach einem Klick zu einer fremden Domain")
            }
        }
        if (!c.pageHadUserInteraction && c.msSincePageStart < LANDING_REDIRECT_MS) {
            return NavDecision(Verdict.ALLOW, "Weiterleitung direkt beim Laden")
        }
        return if (c.sitePolicy == PopupPolicy.BLOCK) {
            NavDecision(Verdict.BLOCK, "Automatische Weiterleitung ohne Klick")
        } else {
            NavDecision(Verdict.ASK, "Automatische Weiterleitung ohne Klick")
        }
    }

    fun popup(c: NavContext): NavDecision {
        if (!UrlTools.isHttp(c.targetUrl)) return NavDecision(Verdict.BLOCK, "Kein http/https-Ziel")
        if (c.filterBlocked) return NavDecision(Verdict.BLOCK, "Pop-up-Ziel steht auf einer Filterliste")
        if (!c.protectionEnabled || c.sitePolicy == PopupPolicy.ALLOW) {
            return NavDecision(Verdict.ALLOW, "Pop-ups für diese Website erlaubt")
        }
        if (c.sitePolicy == PopupPolicy.BLOCK) return NavDecision(Verdict.BLOCK, "Pop-ups für diese Website blockiert")
        if (!c.hasGesture) return NavDecision(Verdict.BLOCK, "Pop-up ohne Klick")
        val cur = c.currentUrl
        if (cur != null && UrlTools.sameSite(cur, c.targetUrl)) {
            return NavDecision(Verdict.ALLOW, "Neues Fenster auf derselben Website")
        }
        val href = c.clickedHref
        if (href != null && UrlTools.sameSite(href, c.targetUrl)) {
            return NavDecision(Verdict.ALLOW, "Angeklickter Link öffnet sich in neuem Fenster")
        }
        return NavDecision(Verdict.ASK, "Neues Fenster mit fremder Domain nach Klick")
    }

    /**
     * Liest `S.browser_fallback_url` aus einer intent://-Adresse (so wie Chrome).
     * Gestartet wird niemals eine App; nur eine http(s)-Ersatzadresse kommt in Frage.
     */
    fun intentFallbackUrl(intentUrl: String): String? {
        val marker = "S.browser_fallback_url="
        val i = intentUrl.indexOf(marker)
        if (i < 0) return null
        val end = intentUrl.indexOf(';', i).let { if (it < 0) intentUrl.length else it }
        val encoded = intentUrl.substring(i + marker.length, end)
        val decoded = try { URLDecoder.decode(encoded, "UTF-8") } catch (_: Exception) { return null }
        return if (UrlTools.isHttp(decoded)) decoded else null
    }

    /** Schemata, die in Unter-Frames harmlos sind und nicht gesperrt werden. */
    val harmlessSubframeSchemes = setOf("about", "data", "blob", "javascript", "http", "https")
}
