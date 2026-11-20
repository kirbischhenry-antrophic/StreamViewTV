package de.streamview.tv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterEngineTest {

    private fun engine(vararg lines: String, generic: Boolean = false): FilterEngine {
        val b = FilterEngine.Builder(generic)
        lines.forEach { b.addLine(it) }
        return b.build()
    }

    @Test fun plainDomainBlocksSubdomains() {
        val e = engine("||doubleclick.net^")
        assertTrue(e.match("https://ad.doubleclick.net/x.js", "example.com", ReqType.SCRIPT).blocked)
        assertTrue(e.match("https://doubleclick.net/", "example.com", ReqType.IMAGE).blocked)
        assertFalse(e.match("https://notdoubleclick.net/", "example.com", ReqType.IMAGE).blocked)
    }

    @Test fun plainDomainRuleDoesNotBlockDocumentRequestsButBlocksNavigationStrictly() {
        val e = engine("||ads.example^")
        assertFalse(e.match("https://ads.example/", null, ReqType.DOCUMENT).blocked)
        assertTrue(e.matchNavigation("https://ads.example/landing", "site.com", popup = true).blocked)
    }

    @Test fun exceptionOverridesBlock() {
        val e = engine("||cdn.example.com^", "@@||cdn.example.com/player/")
        assertTrue(e.match("https://cdn.example.com/ad.js", "x.com", ReqType.SCRIPT).blocked)
        assertFalse(e.match("https://cdn.example.com/player/main.js", "x.com", ReqType.SCRIPT).blocked)
    }

    @Test fun importantBeatsException() {
        val e = engine("||tracker.example^\$important", "@@||tracker.example^")
        assertTrue(e.match("https://tracker.example/p", "x.com", ReqType.IMAGE).blocked)
    }

    @Test fun thirdPartyOption() {
        val e = engine("||widgets.example^\$third-party")
        assertTrue(e.match("https://widgets.example/w.js", "other.com", ReqType.SCRIPT).blocked)
        assertFalse(e.match("https://widgets.example/w.js", "www.widgets.example", ReqType.SCRIPT).blocked)
    }

    @Test fun typeOptionsAndUnknownType() {
        val e = engine("/banner/*\$image", "/adscript.\$script")
        assertTrue(e.match("https://x.com/banner/1.png", "x.com", ReqType.IMAGE).blocked)
        assertFalse(e.match("https://x.com/banner/1.png", "x.com", ReqType.SCRIPT).blocked)
        // Typ unbekannt: Bild-Regel greift nicht, Skript-Regel schon
        assertFalse(e.match("https://x.com/banner/1", "x.com", ReqType.UNKNOWN).blocked)
        assertTrue(e.match("https://x.com/adscript.php", "x.com", ReqType.UNKNOWN).blocked)
    }

    @Test fun domainOption() {
        val e = engine("/sponsor.\$domain=news.com|~video.news.com")
        assertTrue(e.match("https://cdn.com/sponsor.js", "www.news.com", ReqType.SCRIPT).blocked)
        assertFalse(e.match("https://cdn.com/sponsor.js", "video.news.com", ReqType.SCRIPT).blocked)
        assertFalse(e.match("https://cdn.com/sponsor.js", "other.com", ReqType.SCRIPT).blocked)
    }

    @Test fun separatorAndAnchors() {
        val e = engine("|https://bad.example/ad|", "||example.org/ads^", "-ad-banner.")
        assertTrue(e.match("https://bad.example/ad", null, ReqType.IMAGE).blocked)
        assertFalse(e.match("https://bad.example/ad2", null, ReqType.IMAGE).blocked)
        assertTrue(e.match("https://example.org/ads?x=1", null, ReqType.IMAGE).blocked)
        assertTrue(e.match("https://example.org/ads", null, ReqType.IMAGE).blocked)
        assertFalse(e.match("https://example.org/adsense", null, ReqType.IMAGE).blocked)
        assertTrue(e.match("https://a.com/img/top-ad-banner.gif", null, ReqType.IMAGE).blocked)
    }

    @Test fun wildcardBacktracking() {
        val e = engine("||cdn.example^*/ads/*.mp4")
        assertTrue(e.match("https://cdn.example/v/ads/x/clip.mp4", null, ReqType.MEDIA).blocked)
        assertFalse(e.match("https://cdn.example/v/movie.mp4", null, ReqType.MEDIA).blocked)
    }

    @Test fun unsupportedRulesAreSkippedNotMisapplied() {
        val e = engine(
            "||example.com^\$csp=script-src 'none'",
            "/^https?:\\/\\/[a-z]+\\.ads\\./",
            "||example.com^\$redirect=noopjs",
            "example.com##+js(abort-on-property-read, x)",
            "example.com#?#div:-abp-has(.ad)",
            "\$script",
        )
        assertFalse(e.match("https://example.com/a.js", "example.com", ReqType.SCRIPT).blocked)
        assertEquals(6, e.stats.unsupported)
    }

    @Test fun documentAllowlist() {
        val e = engine("@@||trusted.example^\$document")
        assertTrue(e.isPageAllowlisted("https://www.trusted.example/page"))
        assertFalse(e.isPageAllowlisted("https://other.example/"))
    }

    @Test fun cosmeticSpecificGenericAndExceptions() {
        val e = engine(
            "##.ad-box",
            "news.com##.sponsor",
            "~video.news.com##.teaser-ad",
            "news.com#@#.ad-box",
            "news.com##div:has-text(Anzeige)",
            "evil.com##body{display:none}",
            generic = true,
        )
        val sel = e.cosmeticSelectors("https://www.news.com/", "www.news.com", includeGeneric = true)
        assertTrue(".sponsor" in sel)
        assertFalse(".ad-box" in sel)
        assertTrue(".teaser-ad" in sel)
        val sel2 = e.cosmeticSelectors("https://video.news.com/", "video.news.com", includeGeneric = true)
        assertFalse(".teaser-ad" in sel2)
        val other = e.cosmeticSelectors("https://x.org/", "x.org", includeGeneric = false)
        assertTrue(other.isEmpty())
        // CSS-Injection über geschweifte Klammern wird verworfen
        assertTrue(e.cosmeticSelectors("https://evil.com/", "evil.com", true).none { it.contains("{") })
    }

    @Test fun elemhideException() {
        val e = engine("example.com##.ad", "@@||example.com^\$elemhide")
        assertTrue(e.cosmeticSelectors("https://example.com/", "example.com", true).isEmpty())
    }

    @Test fun requestTypeInference() {
        assertEquals(ReqType.SCRIPT, RequestTypes.infer("https://a.com/x.js?v=1", "*/*", false))
        assertEquals(ReqType.IMAGE, RequestTypes.infer("https://a.com/x", "image/avif,image/webp,*/*", false))
        assertEquals(ReqType.SUBDOCUMENT, RequestTypes.infer("https://a.com/embed", "text/html,application/xhtml+xml", false))
        assertEquals(ReqType.MEDIA, RequestTypes.infer("https://a.com/live/index.m3u8", "*/*", false))
        assertEquals(ReqType.UNKNOWN, RequestTypes.infer("https://a.com/api/data", "*/*", false))
        assertEquals(ReqType.DOCUMENT, RequestTypes.infer("https://a.com/", null, true))
    }
}
