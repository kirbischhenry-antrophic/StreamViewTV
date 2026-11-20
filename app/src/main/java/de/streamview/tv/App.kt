package de.streamview.tv

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.Looper
import de.streamview.tv.core.DiagCat
import de.streamview.tv.core.DiagLog
import de.streamview.tv.core.PublicSuffix
import java.io.File
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class App : Application() {

    lateinit var prefs: Prefs; private set
    lateinit var sites: SiteStore; private set
    lateinit var filters: FilterManager; private set
    val diag = DiagLog()
    val main = Handler(Looper.getMainLooper())

    /** Ein einziger Hintergrund-Thread für Datei-/Netzwerkarbeit (keine Dauerdienste). */
    val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "streamview-io").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    /** Websites, für die der Werbeblocker bis zum App-Neustart aus ist (Kompatibilitätsmodus). */
    val tempAdblockOff: MutableSet<String> = Collections.synchronizedSet(HashSet())

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        sites = SiteStore(this)
        filters = FilterManager(this)
        io.execute {
            try {
                assets.open("public_suffix_list.dat").use { PublicSuffix.load(it) }
            } catch (_: Exception) {
                diag.add(DiagCat.INFO, "Public-Suffix-Liste fehlt – Näherung für Domainvergleich aktiv")
            }
        }
        filters.loadAsync()
        main.postDelayed({ filters.updateIfDue() }, 45_000)
        io.execute { trimWebViewCacheIfLarge() }
    }

    /** WebView verwaltet ihren HTTP-Cache selbst; wird er zu groß, leeren wir ihn beim Start. */
    private fun trimWebViewCacheIfLarge() {
        try {
            val dir = File(cacheDir, "WebView")
            val size = dirSize(dir, 0)
            if (size > MAX_WEB_CACHE) {
                dir.deleteRecursively()
                diag.add(DiagCat.MEMORY, "WebView-Cache war ${size / 1_048_576} MB groß und wurde geleert")
            }
        } catch (_: Exception) {
        }
    }

    private fun dirSize(f: File, depth: Int): Long {
        if (depth > 12) return 0
        if (f.isFile) return f.length()
        return f.listFiles()?.sumOf { dirSize(it, depth + 1) } ?: 0
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            filters.dropCaches()
            diag.add(DiagCat.MEMORY, "System meldet knappen Arbeitsspeicher (Stufe $level) – Caches geleert")
        }
    }

    companion object {
        lateinit var instance: App; private set
        private const val MAX_WEB_CACHE = 120L * 1024 * 1024
    }
}
