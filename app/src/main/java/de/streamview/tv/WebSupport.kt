package de.streamview.tv

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import de.streamview.tv.core.ReqType
import de.streamview.tv.core.RequestTypes
import de.streamview.tv.core.UrlTools
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Netzwerkfilter für WebView-Anfragen. Läuft in WebView-Hintergrund-Threads,
 * daher nur @Volatile-Zustand und keine UI-Zugriffe.
 */
object RequestFilter {
    @Volatile var pageUrl: String? = null; private set
    @Volatile var pageHost: String? = null; private set
    @Volatile var active: Boolean = true; private set

    fun setPage(url: String) {
        pageUrl = url
        pageHost = UrlTools.hostOf(url)
        active = isAdblockActive(url)
    }

    /** Werbefilter für diese Seite aktiv? (global an, nicht pro Seite/temporär aus, keine $document-Ausnahme) */
    fun isAdblockActive(url: String): Boolean {
        val app = App.instance
        if (!app.prefs.adblockEnabled) return false
        val site = UrlTools.siteKey(url)
        if (app.sites[site].adblock == false) return false
        if (site in app.tempAdblockOff) return false
        return app.filters.engine?.isPageAllowlisted(url) != true
    }

    fun refresh() { pageUrl?.let { setPage(it) } }

    fun intercept(url: String, accept: String?, isMainFrame: Boolean): WebResourceResponse? {
        if (isMainFrame) {
            setPage(url)
            return null
        }
        if (!active) return null
        val engine = App.instance.filters.engine ?: return null
        val scheme = UrlTools.schemeOf(url)
        if (scheme != "http" && scheme != "https" && scheme != "ws" && scheme != "wss") return null
        val type = RequestTypes.infer(url, accept, false)
        val m = engine.match(url, pageHost, type)
        if (!m.blocked) return null
        App.instance.diag.blockedRequest(url, m.rule, typeLabel(type))
        return empty()
    }

    fun empty() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun typeLabel(t: Int) = when (t) {
        ReqType.SCRIPT -> "Skript"
        ReqType.IMAGE -> "Bild"
        ReqType.STYLESHEET -> "CSS"
        ReqType.SUBDOCUMENT -> "Frame"
        ReqType.MEDIA -> "Medien"
        ReqType.FONT -> "Schrift"
        ReqType.WEBSOCKET -> "WebSocket"
        else -> "unbekannt"
    }
}

object WebData {

    /** Cookies (soweit über CookieManager erreichbar) und Web-Speicher einer Website löschen. */
    fun clearSite(url: String) {
        val host = UrlTools.hostOf(url) ?: return
        val site = UrlTools.siteKey(url)
        val cm = CookieManager.getInstance()
        val hosts = linkedSetOf(host, site, "www.$site")
        for (h in hosts) for (scheme in listOf("https", "http")) {
            val u = "$scheme://$h/"
            val cookies = cm.getCookie(u) ?: continue
            for (c in cookies.split(";")) {
                val name = c.substringBefore("=").trim()
                if (name.isEmpty()) continue
                val base = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
                val secure = if (scheme == "https") "; Secure" else ""
                cm.setCookie(u, base + secure)
                cm.setCookie(u, "$base; Domain=.$site$secure")
                cm.setCookie(u, "$base; Domain=$h$secure")
            }
            WebStorage.getInstance().deleteOrigin("$scheme://$h")
        }
        cm.flush()
    }

    /** Alle Browserdaten löschen. */
    fun clearAll(context: Context, web: WebView?) {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        if (web != null) {
            web.clearCache(true)
            web.clearHistory()
            web.clearFormData()
        } else {
            File(context.cacheDir, "WebView").deleteRecursively()
        }
        try {
            WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
            @Suppress("DEPRECATION") WebViewDatabase.getInstance(context).clearFormData()
        } catch (_: Exception) {
        }
        App.instance.prefs.clearBrowsingTraces()
    }

    /** Ergebnis von evaluateJavascript (JSON-kodierter String) in ein JSONObject wandeln. */
    fun parseJsResult(result: String?): JSONObject? = try {
        val v = JSONTokener(result ?: "null").nextValue()
        if (v is String) JSONObject(v) else null
    } catch (_: Exception) {
        null
    }

    fun webViewVersion(context: Context): Pair<String, String>? = try {
        val pi = if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage()
        else context.packageManager.getPackageInfo("com.amazon.webview.chromium", 0)
        pi?.let { it.packageName to (it.versionName ?: "?") }
    } catch (_: Exception) {
        null
    }

    fun webViewMajor(context: Context): Int? =
        webViewVersion(context)?.second?.substringBefore('.')?.toIntOrNull()
}
