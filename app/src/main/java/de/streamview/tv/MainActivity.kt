package de.streamview.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import de.streamview.tv.core.InputResult
import de.streamview.tv.core.UrlTools

/** Startbildschirm: große Adresseingabe, letzte Seite, Favoriten, Einstellungen. */
class MainActivity : Activity() {

    private val app get() = App.instance
    private lateinit var urlInput: EditText
    private lateinit var lastButton: TextView
    private lateinit var favGrid: GridLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Bildschirmtastatur erst nach OK auf dem Eingabefeld, nicht automatisch
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            val p = Ui.dp(context, 40f)
            setPadding(p * 2, p, p * 2, p)
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.title(this, "StreamView TV"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val settings = Ui.button(this, "⚙  Einstellungen", 16f)
        settings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        header.addView(settings)
        page.addView(header, Ui.rowParams(this, 6f))

        status = Ui.label(this, "Werbefilter wird geladen …", 14f)
        page.addView(status, Ui.rowParams(this, 18f))

        val urlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        urlInput = EditText(this).apply {
            hint = "Adresse eingeben, z. B. example.com"
            setHintTextColor(0xFF78909C.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 22f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            background = Ui.focusBackground(context)
            val p = Ui.dp(context, 16f)
            setPadding(p, p, p, p)
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { submit(); true } else false
            }
        }
        urlRow.addView(urlInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = Ui.dp(this@MainActivity, 12f)
        })
        val go = Ui.button(this, "Öffnen", 20f)
        go.setOnClickListener { submit() }
        urlRow.addView(go)
        page.addView(urlRow, Ui.rowParams(this, 14f))

        lastButton = Ui.button(this, "", 17f)
        lastButton.setOnClickListener { app.prefs.lastUrl?.let { BrowserActivity.open(this, it) } }
        page.addView(lastButton, Ui.rowParams(this, 22f))

        page.addView(Ui.label(this, "Favoriten  ·  OK lange drücken = umbenennen/entfernen", 15f), Ui.rowParams(this, 10f))
        favGrid = GridLayout(this).apply { columnCount = 3 }
        val scroll = ScrollView(this).apply { addView(favGrid); isFillViewport = true }
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(page)
        urlInput.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        val last = app.prefs.lastUrl
        lastButton.visibility = if (last != null) View.VISIBLE else View.GONE
        lastButton.text = "↺  Letzte Seite: ${UrlTools.hostOf(last) ?: ""}"
        rebuildFavorites()
        app.filters.whenReady { updateStatus() }
        if (!app.filters.isReady) status.text = "Werbefilter wird geladen …"
    }

    private fun updateStatus() {
        val e = app.filters.engine ?: return
        status.text = if (app.prefs.adblockEnabled) {
            "Werbeblocker aktiv · ${"%,d".format(e.stats.networkRules).replace(',', '.')} Netzwerkregeln · " +
                "Pop-up-Schutz ${if (app.prefs.popupProtection) "an" else "aus"}"
        } else "Werbeblocker ausgeschaltet"
    }

    private fun submit() {
        when (val r = UrlTools.normalizeUserInput(urlInput.text.toString())) {
            is InputResult.Ok -> BrowserActivity.open(this, r.url)
            is InputResult.Invalid -> urlInput.error = r.message
        }
    }

    private fun rebuildFavorites() {
        favGrid.removeAllViews()
        val favs = app.prefs.favorites()
        val width = (resources.displayMetrics.widthPixels - Ui.dp(this, 200f)) / 3
        favs.forEachIndexed { index, f ->
            val b = Ui.button(this, "${f.name}\n${UrlTools.hostOf(f.url) ?: ""}", 17f)
            b.setOnClickListener { BrowserActivity.open(this, f.url) }
            b.setOnLongClickListener { editFavorite(index); true }
            favGrid.addView(b, GridLayout.LayoutParams().apply {
                this.width = width
                setMargins(0, 0, Ui.dp(this@MainActivity, 12f), Ui.dp(this@MainActivity, 12f))
            })
        }
        if (favs.isEmpty()) {
            favGrid.addView(Ui.label(this, "Noch keine Favoriten. Im Browser: ☰ Menü → „Als Favorit speichern“."))
        }
    }

    private fun editFavorite(index: Int) {
        val favs = app.prefs.favorites().toMutableList()
        val f = favs.getOrNull(index) ?: return
        Ui.dialog(this)
            .setTitle(f.name)
            .setItems(arrayOf("Umbenennen", "Nach vorne", "Entfernen")) { _, which ->
                when (which) {
                    0 -> rename(index)
                    1 -> { favs.removeAt(index); favs.add(0, f); app.prefs.saveFavorites(favs); rebuildFavorites() }
                    2 -> { favs.removeAt(index); app.prefs.saveFavorites(favs); rebuildFavorites() }
                }
            }
            .show()
    }

    private fun rename(index: Int) {
        val favs = app.prefs.favorites().toMutableList()
        val f = favs.getOrNull(index) ?: return
        val input = EditText(this).apply { setText(f.name); setSingleLine(true); selectAll() }
        Ui.dialog(this)
            .setTitle("Name des Favoriten")
            .setView(input)
            .setPositiveButton("Speichern") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) {
                    favs[index] = f.copy(name = n.take(60))
                    app.prefs.saveFavorites(favs)
                    rebuildFavorites()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
}
