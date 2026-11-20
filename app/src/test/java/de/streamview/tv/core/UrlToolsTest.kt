package de.streamview.tv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UrlToolsTest {

    private fun ok(input: String) = (UrlTools.normalizeUserInput(input) as InputResult.Ok).url

    @Test fun normalizesUserInput() {
        assertEquals("https://example.com", ok("example.com"))
        assertEquals("https://example.com", ok("  https://example.com "))
        assertEquals("http://example.com/x", ok("http://example.com/x"))
        assertEquals("https://localhost:8080/", ok("localhost:8080/"))
        assertEquals("https://192.168.1.10", ok("192.168.1.10"))
        assertEquals("https://xn--mnchen-3ya.de", ok("münchen.de"))
    }

    @Test fun rejectsInvalidInput() {
        for (bad in listOf("", "hallo welt", "javascript:alert(1)", "file:///etc", "intent://x",
            "nurwort", "ftp://x.com", "example..com")) {
            assertTrue(bad, UrlTools.normalizeUserInput(bad) is InputResult.Invalid)
        }
    }

    @Test fun hostParsing() {
        assertEquals("www.a.com", UrlTools.hostOf("https://user:pw@WWW.A.com:8443/p?q#f"))
        assertEquals("a.com", UrlTools.hostOf("https://a.com"))
        assertEquals("[::1]", UrlTools.hostOf("http://[::1]:80/"))
    }

    @Test fun sanitizeRemovesQueryAndTokens() {
        val s = UrlTools.sanitizeForLog(
            "https://cdn.x.com/v/abcdefghijklmnopqrstuvwxyz0123456789/seg.ts?token=SECRET&sig=1#t",
        )
        assertFalse(s.contains("SECRET"))
        assertFalse(s.contains("abcdefghijklmnopqrstuvwxyz0123456789"))
        assertTrue(s.startsWith("https://cdn.x.com/v/"))
        val t = Scrub.text("Fehler bei https://a.com/x?session=geheim von max@example.com Token eyJhbGciOiJIUzI1NiJ9.abcdefgh")
        assertFalse(t.contains("geheim"))
        assertFalse(t.contains("max@example.com"))
        assertFalse(t.contains("eyJhbGciOiJIUzI1NiJ9"))
    }

    @Test fun publicSuffixWithRealList() {
        val f = listOf(
            File("src/main/assets/public_suffix_list.dat"),
            File("app/src/main/assets/public_suffix_list.dat"),
        ).firstOrNull { it.exists() }
        if (f != null) f.inputStream().use { PublicSuffix.load(it) }
        assertEquals("bbc.co.uk", PublicSuffix.registrableDomain("news.bbc.co.uk"))
        assertEquals("example.com", PublicSuffix.registrableDomain("a.b.example.com"))
        assertEquals("example.de", PublicSuffix.registrableDomain("www.example.de"))
        if (f != null) {
            // nur mit echter Liste korrekt: github.io ist ein öffentliches Suffix
            assertEquals("user.github.io", PublicSuffix.registrableDomain("user.github.io"))
            assertFalse(PublicSuffix.isSameSite("a.github.io", "b.github.io"))
        }
        assertTrue(PublicSuffix.isSameSite("www.zdf.de", "api.zdf.de"))
        assertEquals("10.0.0.1", PublicSuffix.registrableDomain("10.0.0.1"))
    }
}
