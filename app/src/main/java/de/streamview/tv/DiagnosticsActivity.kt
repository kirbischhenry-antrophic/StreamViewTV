package de.streamview.tv

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.MediaCodecList
import android.media.MediaDrm
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.webkit.WebViewFeature
import de.streamview.tv.core.DiagAnalyzer
import de.streamview.tv.core.DiagLog
import de.streamview.tv.core.UrlTools
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Diagnose: Hinweise, Gerätedaten, Protokoll. Erstellt einen bereinigten Fehlerbericht. */
class DiagnosticsActivity : Activity() {

    private val app get() = App.instance
    private lateinit var text: TextView
    private var report = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            val p = Ui.dp(context, 28f)
            setPadding(p * 2, p, p * 2, p)
        }
        page.addView(Ui.title(this, "Diagnose", 26f), Ui.rowParams(this, 10f))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val save = Ui.button(this, "Bericht speichern", 16f)
        save.setOnClickListener { saveReport() }
        val share = Ui.button(this, "Bericht teilen", 16f)
        share.setOnClickListener { shareReport() }
        val clear = Ui.button(this, "Protokoll leeren", 16f)
        clear.setOnClickListener { app.diag.clear(); refresh() }
        listOf(save, share, clear).forEach {
            buttons.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = Ui.dp(this@DiagnosticsActivity, 12f) })
        }
        page.addView(buttons, Ui.rowParams(this, 12f))
        text = TextView(this).apply {
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 14f
            setTextIsSelectable(false)
            isFocusable = true // damit die Pfeiltasten scrollen
            background = Ui.focusBackground(context, Ui.CARD)
            val p = Ui.dp(context, 14f)
            setPadding(p, p, p, p)
        }
        page.addView(ScrollView(this).apply { addView(text) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(page)
        refresh()
        save.requestFocus()
    }

    private fun refresh() {
        report = buildReport(this, intent.getStringExtra(EXTRA_PAGE), intent.getBooleanExtra(EXTRA_DOC_START, false))
        text.text = report
    }

    private fun saveReport(): File? = try {
        val dir = getExternalFilesDir("berichte") ?: File(filesDir, "berichte")
        dir.mkdirs()
        val f = File(dir, "streamview-bericht-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.GERMANY).format(Date()) + ".txt")
        f.writeText(report)
        Ui.toast(this, "Gespeichert: ${f.absolutePath}", long = true)
        f
    } catch (e: Exception) {
        Ui.toast(this, "Speichern fehlgeschlagen: ${e.message}")
        null
    }

    private fun shareReport() {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "StreamView TV – Fehlerbericht")
            .putExtra(Intent.EXTRA_TEXT, report)
        if (i.resolveActivity(packageManager) != null) {
            startActivity(Intent.createChooser(i, "Bericht teilen"))
        } else {
            val f = saveReport()
            if (f != null) Ui.toast(this, "Keine App zum Teilen vorhanden – Bericht wurde gespeichert.", long = true)
        }
    }

    companion object {
        const val EXTRA_PAGE = "page"
        const val EXTRA_DOC_START = "doc_start"
        private val WIDEVINE: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

        fun buildReport(ctx: Context, pageUrl: String?, docStartFromBrowser: Boolean): String {
            val app = App.instance
            val sb = StringBuilder()
            val site = UrlTools.siteKey(pageUrl)
            val adActive = pageUrl != null && RequestFilter.isAdblockActive(pageUrl)
            val wv = WebData.webViewVersion(ctx)
            val docStart = docStartFromBrowser || safeFeature(WebViewFeature.DOCUMENT_START_SCRIPT)

            sb.appendLine("=== StreamView TV – bereinigter Diagnosebericht ===")
            sb.appendLine("Erstellt: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.GERMANY).format(Date())}")
            sb.appendLine("Enthält KEINE Cookies, Passwörter, Tokens, Formularinhalte oder Query-Parameter.")
            sb.appendLine()

            sb.appendLine("--- Hinweise zur aktuellen Seite ---")
            if (pageUrl != null) {
                sb.appendLine("Seite: ${UrlTools.sanitizeForLog(pageUrl)}")
                val facts = DiagAnalyzer.Facts(
                    site, adActive, app.diag.blockedRequestsOnPage, app.diag.blockedPopupsOnPage,
                    app.diag.blockedNavsOnPage, app.diag.events(), wv?.second?.substringBefore('.')?.toIntOrNull(), docStart,
                )
                DiagAnalyzer.hints(facts).forEach { sb.appendLine("• $it") }
                val cfg = app.sites[site]
                sb.appendLine("Einstellungen für $site: Werbeblocker ${if (adActive) "aktiv" else "inaktiv"}" +
                    (if (site in app.tempAdblockOff) " (vorübergehend aus)" else "") +
                    ", Pop-ups ${cfg.popups}, Ansicht ${if (cfg.desktop ?: app.prefs.desktopDefault) "Desktop" else "Mobil"}")
                val top = app.diag.topBlockedHosts()
                if (top.isNotEmpty()) sb.appendLine("Am häufigsten blockiert: " + top.joinToString { "${it.first} (${it.second})" })
            } else {
                sb.appendLine("(Diagnose wurde ohne geöffnete Seite aufgerufen)")
            }
            sb.appendLine()

            sb.appendLine("--- Gerät ---")
            sb.appendLine("Modell: ${Build.MANUFACTURER} ${Build.MODEL}  (Build: ${Build.DISPLAY})")
            sb.appendLine("Android-API: ${Build.VERSION.SDK_INT} (Android ${Build.VERSION.RELEASE}), ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                sb.appendLine("RAM gesamt: ${mi.totalMem / 1_048_576} MB, frei: ${mi.availMem / 1_048_576} MB" +
                    (if (mi.lowMemory) " (System meldet Speichermangel)" else "") + ", App-Heap-Limit: ${am.memoryClass} MB")
                val rt = Runtime.getRuntime()
                sb.appendLine("App-Heap belegt: ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MB")
            } catch (_: Exception) {}
            sb.appendLine("Freier Speicherplatz: ${ctx.filesDir.usableSpace / 1_048_576} MB")
            sb.appendLine()

            sb.appendLine("--- WebView ---")
            sb.appendLine("Paket: ${wv?.first ?: "unbekannt"}, Version: ${wv?.second ?: "unbekannt"}")
            sb.appendLine("Skripte in allen Frames (DOCUMENT_START_SCRIPT): ${if (docStart) "ja" else "nein"}")
            sb.appendLine("Nachrichtenkanal (WEB_MESSAGE_LISTENER): ${if (safeFeature(WebViewFeature.WEB_MESSAGE_LISTENER)) "ja" else "nein"}")
            sb.appendLine()

            sb.appendLine("--- Video-Decoder (laut MediaCodecList) ---")
            sb.append(decoderReport())
            sb.appendLine("Widevine-DRM: " + widevineReport())
            sb.appendLine()

            sb.appendLine("--- Werbefilter ---")
            val e = app.filters.engine
            sb.appendLine("Global: ${if (app.prefs.adblockEnabled) "an" else "aus"}, Pop-up-Schutz: ${if (app.prefs.popupProtection) "an" else "aus"}, " +
                "Elementausblendung: ${arrayOf("aus", "seitenspezifisch", "alle")[app.prefs.cosmeticMode]}")
            if (e != null) {
                val s = e.stats
                sb.appendLine("Regeln: ${s.networkRules} Netzwerk (davon ${s.plainHostRules} reine Domains), ${s.exceptionRules} Ausnahmen, " +
                    "${s.cosmeticSpecific} Elementregeln; ${s.unsupported} nicht unterstützte Regeln verworfen; Ladezeit ${app.filters.lastLoadMs} ms")
            } else sb.appendLine("Filter noch nicht geladen")
            for (l in FilterManager.LISTS) {
                val on = l.id in app.prefs.enabledLists
                val t = app.prefs.listUpdated(l.id)
                sb.appendLine("  ${if (on) "[x]" else "[ ]"} ${l.name}: " + if (t > 0) "aktualisiert ${DiagLog.time(t)} " +
                    SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date(t)) else "mitgelieferter Stand")
            }
            app.prefs.lastUpdateError?.let { sb.appendLine("Letzter Aktualisierungsfehler: $it") }
            sb.appendLine()

            sb.appendLine("--- Ereignisprotokoll (neueste zuletzt) ---")
            app.diag.events().takeLast(120).forEach { sb.appendLine("${DiagLog.time(it.time)} [${it.cat.label}] ${it.text}") }
            sb.appendLine()
            sb.appendLine("--- Zuletzt blockierte Anfragen ---")
            app.diag.requests().takeLast(60).forEach { sb.appendLine("${DiagLog.time(it.time)} ${it.text}") }
            return sb.toString()
        }

        private fun safeFeature(f: String) = try { WebViewFeature.isFeatureSupported(f) } catch (_: Exception) { false }

        private fun decoderReport(): String {
            val sb = StringBuilder()
            val wanted = linkedMapOf(
                "video/avc" to "H.264/AVC", "video/hevc" to "H.265/HEVC", "video/x-vnd.on2.vp8" to "VP8",
                "video/x-vnd.on2.vp9" to "VP9", "video/av01" to "AV1",
            )
            try {
                val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
                for ((mime, label) in wanted) {
                    val decs = infos.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
                    if (decs.isEmpty()) { sb.appendLine("$label: kein Decoder"); continue }
                    val desc = decs.joinToString { d ->
                        val caps = try { d.getCapabilitiesForType(mime).videoCapabilities } catch (_: Exception) { null }
                        val sw = d.name.startsWith("OMX.google.") || d.name.startsWith("c2.android.")
                        val res = caps?.let { " bis ${it.supportedWidths.upper}×${it.supportedHeights.upper}" } ?: ""
                        "${d.name}${if (sw) " (Software)" else ""}$res"
                    }
                    sb.appendLine("$label: $desc")
                }
            } catch (e: Exception) {
                sb.appendLine("Decoderliste nicht verfügbar: ${e.javaClass.simpleName}")
            }
            return sb.toString()
        }

        private fun widevineReport(): String = try {
            if (!MediaDrm.isCryptoSchemeSupported(WIDEVINE)) "nicht unterstützt"
            else {
                val drm = MediaDrm(WIDEVINE)
                val level = try { drm.getPropertyString("securityLevel") } catch (_: Exception) { "?" }
                if (Build.VERSION.SDK_INT >= 28) drm.close() else @Suppress("DEPRECATION") drm.release()
                "unterstützt, Sicherheitsstufe $level"
            }
        } catch (e: Exception) {
            "Abfrage fehlgeschlagen (${e.javaClass.simpleName})"
        }
    }
}
