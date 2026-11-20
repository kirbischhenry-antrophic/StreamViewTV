package de.streamview.tv

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Kommunikation mit den Skripten in allen Frames (Hauptseite und eingebettete Player).
 * Nutzt die offiziellen androidx.webkit-APIs WebMessageListener und DocumentStartScript.
 * Nachrichten aus der Seite gelten als nicht vertrauenswürdig: nur feste Felder werden gelesen,
 * es wird nie Code aus Nachrichten ausgeführt.
 */
class MediaBridge(private val callbacks: Callbacks) : WebViewCompat.WebMessageListener {

    interface Callbacks {
        /** CSS für einen Frame (Werbeelemente ausblenden). */
        fun cssForFrame(frameOrigin: Uri, isMainFrame: Boolean): String
        fun onMediaState(state: String, isMainFrame: Boolean, width: Int, height: Int)
        fun onMediaError(code: Int, frameOrigin: Uri)
    }

    private class Frame(val proxy: JavaScriptReplyProxy, val isMain: Boolean) {
        var hasMedia = false
        var playing = false
        var seen = System.currentTimeMillis()
    }

    private val frames = ArrayList<Frame>()
    private var scriptHandler: ScriptHandler? = null

    var listenerSupported = false; private set
    var documentStartSupported = false; private set

    /** Installiert Listener und Frame-Skript; muss vor dem ersten loadUrl aufgerufen werden. */
    fun install(web: WebView, bridgeName: String) {
        listenerSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        documentStartSupported = listenerSupported &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (!listenerSupported) return
        try {
            WebViewCompat.addWebMessageListener(web, bridgeName, setOf("*"), this)
            if (documentStartSupported) {
                scriptHandler = WebViewCompat.addDocumentStartJavaScript(web, PageScripts.frameScript(bridgeName), setOf("*"))
            }
        } catch (_: Exception) {
            listenerSupported = false
            documentStartSupported = false
        }
    }

    /** Ohne Document-Start-Unterstützung: Skript nachträglich ins Hauptdokument einfügen. */
    fun injectLate(web: WebView, bridgeName: String) {
        if (listenerSupported && !documentStartSupported) {
            web.evaluateJavascript(PageScripts.frameScript(bridgeName), null)
        }
    }

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        val data = message.data ?: return
        if (data.length > 300) return
        val o = try { JSONObject(data) } catch (_: Exception) { return }
        val frame = frames.firstOrNull { it.proxy === replyProxy } ?: Frame(replyProxy, isMainFrame).also {
            frames.add(it)
            while (frames.size > MAX_FRAMES) frames.removeAt(0)
        }
        frame.seen = System.currentTimeMillis()
        when (o.optString("t")) {
            "hi" -> {
                val css = callbacks.cssForFrame(sourceOrigin, isMainFrame)
                if (css.isNotEmpty()) {
                    try { replyProxy.postMessage(JSONObject().put("t", "css").put("c", css).toString()) } catch (_: Exception) {}
                }
            }
            "m" -> {
                val s = o.optString("s")
                if (s !in setOf("playing", "pause", "ended", "meta")) return
                frame.hasMedia = true
                if (s == "playing") frame.playing = true
                if (s == "pause" || s == "ended") frame.playing = false
                callbacks.onMediaState(s, isMainFrame, o.optInt("w"), o.optInt("h"))
            }
            "merr" -> {
                frame.hasMedia = true
                callbacks.onMediaError(o.optInt("c"), sourceOrigin)
            }
        }
    }

    val anyMediaKnown: Boolean get() = frames.any { it.hasMedia }
    val anyPlaying: Boolean get() = frames.any { it.playing }

    /**
     * Steuerbefehl an den "aktiven" Player: bevorzugt Frames, deren Video gerade läuft,
     * sonst Frames mit bekanntem Medium. So wird nicht blind das erste video-Element gesteuert.
     */
    fun sendMedia(action: String, value: Int = 0): Boolean {
        val targets = frames.filter { it.playing }.ifEmpty { frames.filter { it.hasMedia } }
        if (targets.isEmpty()) return false
        val msg = JSONObject().put("t", "media").put("a", action).put("v", value).toString()
        for (f in targets) try { f.proxy.postMessage(msg) } catch (_: Exception) {}
        return true
    }

    /** Neue Hauptseite: alte Zustände verwerfen. */
    fun onNewPage() {
        val cutoff = System.currentTimeMillis() - 1500
        frames.removeAll { it.seen < cutoff }
        frames.forEach { it.playing = false; it.hasMedia = false }
    }

    fun clear() {
        frames.clear()
        try { scriptHandler?.remove() } catch (_: Exception) {}
        scriptHandler = null
    }

    companion object {
        private const val MAX_FRAMES = 24
    }
}
