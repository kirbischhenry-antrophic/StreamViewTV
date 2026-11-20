package de.streamview.tv.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lädt die mitgelieferten echten Filterlisten (falls vorhanden) und prüft Plausibilität,
 * Ladezeit und typische Treffer/Nicht-Treffer.
 */
class RealListsTest {

    private fun assetsDir(): File? = listOf(File("src/main/assets"), File("app/src/main/assets"))
        .firstOrNull { File(it, "filters").isDirectory }

    @Test fun parsesBundledListsAndMatchesTypicalUrls() {
        val dir = assetsDir() ?: return
        val files = File(dir, "filters").listFiles { f -> f.name.endsWith(".txt") }?.sortedBy { it.name } ?: return
        if (files.none { it.name.startsWith("easylist") }) return
        File(dir, "public_suffix_list.dat").inputStream().use { PublicSuffix.load(it) }

        val rt = Runtime.getRuntime()
        System.gc(); Thread.sleep(200)
        val before = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        val b = FilterEngine.Builder(includeGenericCosmetics = false)
        for (f in files) f.bufferedReader().use { b.addList(it) }
        val e = b.build()
        val ms = (System.nanoTime() - t0) / 1_000_000
        System.gc(); Thread.sleep(200)
        val after = rt.totalMemory() - rt.freeMemory()
        val s = e.stats
        println("Listen: ${files.joinToString { it.name }}")
        println("Zeilen=${s.lines} Netzwerk=${s.networkRules} (davon reine Domains=${s.plainHostRules}) " +
            "Ausnahmen=${s.exceptionRules} Element(spezifisch)=${s.cosmeticSpecific} " +
            "Element(allgemein übersprungen)=${s.cosmeticGenericSkipped} nicht unterstützt=${s.unsupported}")
        println("Ladezeit (JVM, Desktop) = $ms ms, Heap-Zuwachs ≈ ${(after - before) / 1024 / 1024} MB")

        assertTrue(s.networkRules > 10_000)
        val page = "www.example.org"
        assertTrue(e.match("https://securepubads.g.doubleclick.net/tag/js/gpt.js", page, ReqType.SCRIPT).blocked)
        // EasyList enthält für spiegel.de eine Ausnahme für gpt.js – die Engine muss sie respektieren
        assertFalse(e.match("https://securepubads.g.doubleclick.net/tag/js/gpt.js", "www.spiegel.de", ReqType.SCRIPT).blocked)
        assertTrue(e.match("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", page, ReqType.SCRIPT).blocked)
        assertTrue(e.match("https://www.google-analytics.com/analytics.js", page, ReqType.SCRIPT).blocked)
        // Inhalte normaler Seiten dürfen nicht blockiert werden
        assertFalse(e.match("https://www.zdf.de/static/app.js", "www.zdf.de", ReqType.SCRIPT).blocked)
        assertFalse(e.match("https://upload.wikimedia.org/wikipedia/commons/a/a9/Example.jpg", "de.wikipedia.org", ReqType.IMAGE).blocked)
        assertFalse(e.match("https://www.youtube.com/s/player/abc/player_ias.vflset/de_DE/base.js", "www.youtube.com", ReqType.SCRIPT).blocked)
        assertFalse(e.match("https://cdn.jsdelivr.net/npm/hls.js@1/dist/hls.min.js", "example.org", ReqType.SCRIPT).blocked)

        // Geschwindigkeit des Abgleichs
        val urls = listOf(
            "https://www.example.com/assets/main.3f2a.js", "https://img.example.com/thumbs/123.jpg",
            "https://cdn.example.net/video/seg-00042.ts", "https://api.example.com/v1/items?page=2",
            "https://ad.doubleclick.net/ddm/adj/N123/B456", "https://stats.example.org/collect?x=1",
        )
        val t1 = System.nanoTime()
        var n = 0
        repeat(20_000) { for (u in urls) { e.match(u, "www.example.com", ReqType.UNKNOWN); n++ } }
        val us = (System.nanoTime() - t1) / 1000.0 / n
        println("Abgleich: %.1f µs pro Anfrage (JVM, Desktop)".format(us))
        assertTrue(us < 500)
    }
}
