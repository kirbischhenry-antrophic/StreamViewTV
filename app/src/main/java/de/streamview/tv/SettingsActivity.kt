package de.streamview.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import de.streamview.tv.core.PopupPolicy
import java.text.DateFormat
import java.util.Date

/** Einstellungen als einfache, fernbedienbare Liste. */
class SettingsActivity : Activity() {

    private val app get() = App.instance
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            val p = Ui.dp(context, 36f)
            setPadding(p * 2, p, p * 2, p)
        }
        page.addView(Ui.title(this, "Einstellungen"), Ui.rowParams(this, 16f))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(ScrollView(this).apply { addView(list) })
        setContentView(page)
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun row(text: String, action: () -> Unit): Button {
        val b = Ui.button(this, text, 18f)
        b.setOnClickListener { action() }
        list.addView(b, Ui.rowParams(this, 8f))
        return b
    }

    private fun onOff(v: Boolean) = if (v) "AN" else "AUS"

    private fun rebuild() {
        val focusedIndex = (0 until list.childCount).firstOrNull { list.getChildAt(it).hasFocus() } ?: 0
        list.removeAllViews()
        val p = app.prefs
        row("Werbeblocker: ${onOff(p.adblockEnabled)}") {
            p.adblockEnabled = !p.adblockEnabled; RequestFilter.refresh(); rebuild()
        }
        row("Pop-up- und Weiterleitungsschutz: ${onOff(p.popupProtection)}") {
            p.popupProtection = !p.popupProtection; rebuild()
        }
        row("Filterlisten auswählen …") { chooseLists() }
        row("Filterlisten jetzt aktualisieren  (${listStatus()})") {
            Ui.toast(this, "Aktualisierung läuft …")
            app.filters.updateNow { msg -> Ui.toast(this, msg, long = true); rebuild() }
        }
        val cos = arrayOf("Aus", "Nur seitenspezifische Regeln (empfohlen)", "Alle Regeln (mehr Arbeitsspeicher)")
        row("Werbeflächen ausblenden: ${cos[p.cosmeticMode]}") {
            Ui.dialog(this).setTitle("Werbeflächen ausblenden").setSingleChoiceItems(cos, p.cosmeticMode) { d, w ->
                val reload = (w >= 2) != (p.cosmeticMode >= 2)
                p.cosmeticMode = w
                app.filters.dropCaches()
                if (reload) app.filters.reload()
                d.dismiss(); rebuild()
            }.show()
        }
        row("Steuerung im Browser: ${if (p.navMode == NavMode.CURSOR) "Zeiger" else "Fokus (Sprung zwischen Elementen)"}") {
            p.navMode = if (p.navMode == NavMode.CURSOR) NavMode.FOCUS else NavMode.CURSOR; rebuild()
        }
        val speeds = arrayOf("Langsam", "Normal", "Schnell")
        row("Zeigergeschwindigkeit: ${speeds[p.cursorSpeed.coerceIn(0, 2)]}") {
            p.cursorSpeed = (p.cursorSpeed + 1) % 3; rebuild()
        }
        row("Standardansicht für Webseiten: ${if (p.desktopDefault) "Desktop" else "Mobil"}") {
            p.desktopDefault = !p.desktopDefault; rebuild()
        }
        row("Drittanbieter-Cookies (für eingebettete Player/Logins): ${onOff(p.thirdPartyCookies)}") {
            p.thirdPartyCookies = !p.thirdPartyCookies; rebuild()
            Ui.toast(this, "Gilt ab dem nächsten Öffnen einer Seite")
        }
        row("Letzte Seite merken: ${onOff(p.rememberLastPage)}") { p.rememberLastPage = !p.rememberLastPage; rebuild() }
        row("Ausnahmen für Webseiten verwalten (${app.sites.all().size}) …") { manageSites() }
        row("Browserdaten löschen …") { confirmClear() }
        row("Diagnose & Fehlerbericht") { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        row("Info & Lizenzen") { showInfo() }
        list.getChildAt(focusedIndex.coerceAtMost(list.childCount - 1))?.requestFocus()
    }

    private fun listStatus(): String {
        val enabled = FilterManager.LISTS.filter { it.id in app.prefs.enabledLists }
        val newest = enabled.maxOfOrNull { app.prefs.listUpdated(it.id) } ?: 0L
        val err = app.prefs.lastUpdateError
        return when {
            app.filters.updating -> "läuft"
            newest == 0L -> "mitgelieferter Stand"
            else -> "zuletzt " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(newest)) +
                if (err != null) ", letzter Versuch mit Fehlern" else ""
        }
    }

    private fun chooseLists() {
        val lists = FilterManager.LISTS
        val names = lists.map { l -> l.name + if (!app.filters.hasAsset(l.id) && app.prefs.listUpdated(l.id) == 0L) " (erst nach Aktualisierung)" else "" }.toTypedArray()
        val checked = lists.map { it.id in app.prefs.enabledLists }.toBooleanArray()
        Ui.dialog(this)
            .setTitle("Filterlisten")
            .setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Übernehmen") { _, _ ->
                app.prefs.enabledLists = lists.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
                app.filters.reload()
                rebuild()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun manageSites() {
        val all = app.sites.all()
        if (all.isEmpty()) { Ui.toast(this, "Keine Ausnahmen gespeichert"); return }
        val keys = all.keys.toList()
        val labels = keys.map { k ->
            val c = all.getValue(k)
            val parts = ArrayList<String>()
            if (c.adblock == false) parts += "Werbeblocker aus"
            if (c.popups != PopupPolicy.ASK) parts += "Pop-ups: " + if (c.popups == PopupPolicy.ALLOW) "erlaubt" else "blockiert"
            c.desktop?.let { parts += if (it) "Desktop" else "Mobil" }
            c.drm?.let { parts += if (it) "DRM erlaubt" else "DRM abgelehnt" }
            "$k – ${parts.joinToString(", ")}"
        }.toTypedArray()
        Ui.dialog(this)
            .setTitle("Ausnahmen (Auswahl = zurücksetzen)")
            .setItems(labels) { _, i -> app.sites.reset(keys[i]); RequestFilter.refresh(); rebuild() }
            .setNeutralButton("Alle zurücksetzen") { _, _ -> app.sites.resetAll(); RequestFilter.refresh(); rebuild() }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun confirmClear() {
        Ui.dialog(this)
            .setTitle("Browserdaten löschen?")
            .setMessage("Cookies (Anmeldungen), Website-Speicher, Cache und die gemerkte letzte Seite werden gelöscht. Favoriten und Einstellungen bleiben erhalten.")
            .setPositiveButton("Löschen") { _, _ ->
                WebData.clearAll(this, null)
                Ui.toast(this, "Browserdaten gelöscht")
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showInfo() {
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        Ui.dialog(this)
            .setTitle("StreamView TV $version")
            .setMessage(
                "Einfacher Web-Viewer für Fire TV mit Werbe- und Pop-up-Schutz.\n\n" +
                    "Filterlisten (werden nur heruntergeladen bzw. als Datenstand mitgeliefert):\n" +
                    "• EasyList, EasyList Germany, EasyPrivacy – © The EasyList authors, GPLv3 / CC BY-SA 3.0 (easylist.to)\n" +
                    "• URLhaus Malicious URL Blocklist – malware-filter, MIT; Daten: abuse.ch URLhaus (CC0)\n" +
                    "• Public Suffix List – Mozilla Foundation, MPL 2.0 (publicsuffix.org)\n\n" +
                    "Bibliotheken: AndroidX WebKit, AndroidX Media3 (Apache 2.0).\n\n" +
                    "Die App enthält keine Tracker und überträgt keinen Verlauf. Netzwerkzugriffe der App selbst: " +
                    "nur die Filterlisten-Aktualisierung über HTTPS.",
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
