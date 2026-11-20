package de.streamview.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import de.streamview.tv.core.DiagCat
import de.streamview.tv.core.FilterEngine
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Verwaltet Filterlisten: Laden (Hintergrund-Thread), Aktualisieren (HTTPS, Größenlimit,
 * atomarer Austausch) und Bereitstellen der aktuellen [FilterEngine].
 *
 * Reihenfolge je Liste: zuletzt erfolgreich heruntergeladene Datei → mitgelieferter Stand (Asset).
 * Eine fehlgeschlagene Aktualisierung ändert nichts am bisherigen Stand.
 */
class FilterManager(private val app: App) {

    class FilterList(val id: String, val name: String, val url: String, val defaultOn: Boolean)

    @Volatile var engine: FilterEngine? = null; private set
    @Volatile var loading = false; private set
    @Volatile var lastLoadMs = 0L; private set
    @Volatile var updating = false; private set
    @Volatile private var reloadPending = false
    private val readyCallbacks = ArrayList<() -> Unit>()
    private val cssCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 12
    }

    private val dir: File get() = File(app.filesDir, "filters").apply { mkdirs() }

    val isReady: Boolean get() = engine != null

    /** Callback auf dem UI-Thread, sobald die Engine bereit ist (sofort, falls schon bereit). */
    fun whenReady(cb: () -> Unit) {
        synchronized(readyCallbacks) {
            if (engine == null) { readyCallbacks.add(cb); return }
        }
        app.main.post(cb)
    }

    fun loadAsync() {
        synchronized(this) {
            if (loading) { reloadPending = true; return }
            loading = true
        }
        app.io.execute {
            val t0 = System.currentTimeMillis()
            try {
                val b = FilterEngine.Builder(includeGenericCosmetics = app.prefs.cosmeticMode >= 2)
                readAsset("filters/streamview-base.txt")?.use { b.addList(it) }
                for (l in LISTS) {
                    if (l.id !in app.prefs.enabledLists) continue
                    val f = File(dir, "${l.id}.txt")
                    val reader = if (f.isFile && f.length() > 0) {
                        BufferedReader(InputStreamReader(f.inputStream(), Charsets.UTF_8), 32_768)
                    } else readAsset("filters/${l.id}.txt")
                    reader?.use { b.addList(it) }
                }
                val e = b.build()
                synchronized(cssCache) { cssCache.clear() }
                engine = e
                lastLoadMs = System.currentTimeMillis() - t0
                app.diag.add(
                    DiagCat.INFO,
                    "Filter geladen in $lastLoadMs ms: ${e.stats.networkRules} Netzwerkregeln, " +
                        "${e.stats.cosmeticSpecific} Elementregeln, ${e.stats.unsupported} nicht unterstützt",
                )
            } catch (t: Throwable) {
                // Auch bei OutOfMemory o. Ä. muss die App benutzbar bleiben – dann eben ohne Filter.
                app.diag.add(DiagCat.INFO, "Filter konnten nicht geladen werden: ${t.javaClass.simpleName}")
                if (engine == null) engine = FilterEngine.Builder(false).build()
            } finally {
                synchronized(this) { loading = false }
                if (reloadPending) { reloadPending = false; app.main.post { loadAsync() } }
                val cbs = synchronized(readyCallbacks) { ArrayList(readyCallbacks).also { readyCallbacks.clear() } }
                cbs.forEach { app.main.post(it) }
            }
        }
    }

    private fun readAsset(path: String): BufferedReader? = try {
        BufferedReader(InputStreamReader(app.assets.open(path), Charsets.UTF_8), 32_768)
    } catch (_: IOException) {
        null
    }

    fun hasAsset(id: String): Boolean = try {
        app.assets.open("filters/$id.txt").close(); true
    } catch (_: IOException) {
        false
    }

    /** Zwischengespeichertes Element-CSS für einen Host (klein, LRU). */
    fun cosmeticCss(pageUrl: String, frameHost: String): String {
        val mode = app.prefs.cosmeticMode
        if (mode == 0) return BUILTIN_CSS
        val e = engine ?: return BUILTIN_CSS
        val key = "$mode|$frameHost|${de.streamview.tv.core.UrlTools.hostOf(pageUrl)}"
        synchronized(cssCache) { cssCache[key]?.let { return it } }
        val sb = StringBuilder(BUILTIN_CSS)
        // Jede Regel einzeln: ein ungültiger Selektor macht so nicht den ganzen Block wirkungslos
        for (s in e.cosmeticSelectors(pageUrl, frameHost, includeGeneric = mode >= 2)) {
            sb.append('\n').append(s).append("{display:none!important}")
        }
        val css = sb.toString()
        synchronized(cssCache) { cssCache[key] = css }
        return css
    }

    fun dropCaches() = synchronized(cssCache) { cssCache.clear() }

    // ---------------------------------------------------------------------------------------
    // Aktualisierung
    // ---------------------------------------------------------------------------------------

    fun updateIfDue() {
        val oldest = LISTS.filter { it.id in app.prefs.enabledLists }.minOfOrNull { app.prefs.listUpdated(it.id) } ?: return
        val now = System.currentTimeMillis()
        if (now - oldest < UPDATE_INTERVAL) return
        if (now - app.prefs.lastUpdateAttempt < RETRY_INTERVAL) return
        if (!isOnline()) return
        updateNow(null)
    }

    /** @param done Ergebnistext für die Oberfläche (UI-Thread) */
    fun updateNow(done: ((String) -> Unit)?) {
        if (updating) { done?.invoke("Aktualisierung läuft bereits …"); return }
        updating = true
        app.io.execute {
            app.prefs.lastUpdateAttempt = System.currentTimeMillis()
            var ok = 0
            val errors = ArrayList<String>()
            for (l in LISTS) {
                if (l.id !in app.prefs.enabledLists) continue
                try {
                    download(l)
                    app.prefs.setListUpdated(l.id, System.currentTimeMillis())
                    ok++
                } catch (e: Exception) {
                    errors.add("${l.name}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            app.prefs.lastUpdateError = if (errors.isEmpty()) null else errors.joinToString("; ")
            val msg = buildString {
                append("$ok Liste(n) aktualisiert.")
                if (errors.isNotEmpty()) append(" Fehler: ").append(errors.joinToString("; ")).append(" – bisheriger Stand bleibt aktiv.")
            }
            app.diag.add(DiagCat.INFO, "Filteraktualisierung: $msg")
            updating = false
            if (ok > 0) reload()
            done?.let { cb -> app.main.post { cb(msg) } }
        }
    }

    /** Neu laden (z. B. nach Änderung der Listenauswahl). Die alte Engine bleibt bis zum Austausch aktiv. */
    fun reload() = loadAsync()

    private fun download(l: FilterList) {
        val url = URL(l.url)
        require(url.protocol == "https") { "nur HTTPS erlaubt" }
        var conn: HttpURLConnection? = null
        val tmp = File(dir, "${l.id}.download")
        try {
            conn = (url.openConnection() as HttpsURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "StreamViewTV-Filterupdate")
            }
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            // Weiterleitung auf http wäre unsicher
            if (conn.url.protocol != "https") throw IOException("Weiterleitung auf unsichere Adresse")
            val declared = conn.contentLengthLong
            if (declared > MAX_LIST_BYTES) throw IOException("Liste zu groß")
            var total = 0L
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(32_768)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_LIST_BYTES) throw IOException("Liste überschreitet ${MAX_LIST_BYTES / 1_048_576} MB")
                        out.write(buf, 0, n)
                    }
                }
            }
            validate(tmp)
            val target = File(dir, "${l.id}.txt")
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("Datei konnte nicht ersetzt werden")
            }
        } finally {
            conn?.disconnect()
            tmp.delete()
        }
    }

    /** Plausibilitätsprüfung: Kopfzeile oder genügend regelähnliche Zeilen, keine HTML-Fehlerseite. */
    private fun validate(f: File) {
        var rules = 0
        var header = false
        f.bufferedReader().useLines { lines ->
            for ((i, raw) in lines.withIndex()) {
                val line = raw.trim()
                if (i < 5 && (line.startsWith("[Adblock") || line.startsWith("! Title"))) header = true
                if (i < 5 && line.startsWith("<")) throw IOException("Antwort ist keine Filterliste")
                if (line.isNotEmpty() && !line.startsWith("!")) rules++
                if (rules > 200 && header) return
                if (i > 400_000) break
            }
        }
        if (rules < 50) throw IOException("Liste enthält zu wenige Regeln")
    }

    private fun isOnline(): Boolean = try {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) {
        false
    }

    companion object {
        const val UPDATE_INTERVAL = 7L * 24 * 60 * 60 * 1000
        const val RETRY_INTERVAL = 12L * 60 * 60 * 1000
        const val MAX_LIST_BYTES = 12L * 1024 * 1024

        val LISTS = listOf(
            FilterList("easylist", "EasyList (Werbung)", "https://easylist.to/easylist/easylist.txt", true),
            FilterList("easylistgermany", "EasyList Germany", "https://easylist.to/easylistgermany/easylistgermany.txt", true),
            FilterList("easyprivacy", "EasyPrivacy (Tracking)", "https://easylist.to/easylist/easyprivacy.txt", true),
            FilterList(
                "urlhaus", "URLhaus (Schadsoftware-Adressen)",
                "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-online.txt", true,
            ),
        )

        /** Kleine, immer aktive Grundregeln gegen typische Werbeflächen. */
        const val BUILTIN_CSS =
            "ins.adsbygoogle,[id^=\"google_ads_iframe\"],[id^=\"div-gpt-ad\"],iframe[src*=\"doubleclick.net\"]," +
                "iframe[src*=\"googlesyndication.com\"]{display:none!important}"
    }
}
