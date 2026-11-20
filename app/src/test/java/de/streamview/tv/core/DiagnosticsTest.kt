package de.streamview.tv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {

    @Test fun ringBufferIsBounded() {
        val log = DiagLog(capacity = 10, requestCapacity = 5)
        log.newPage("a.com", "https://a.com/")
        repeat(50) { log.add(DiagCat.INFO, "e$it") }
        repeat(50) { log.blockedRequest("https://ads.x.com/p?id=$it", "||x.com^", "script") }
        assertEquals(10, log.events().size)
        assertEquals(5, log.requests().size)
        assertEquals(50, log.blockedRequestsOnPage)
        assertEquals("ads.x.com" to 50, log.topBlockedHosts(1)[0])
    }

    @Test fun logEntriesAreSanitized() {
        val log = DiagLog()
        log.newPage("a.com", "https://a.com/")
        log.add(DiagCat.JS_ERROR, "Uncaught at https://a.com/app.js?auth=SECRET123")
        log.blockedRequest("https://t.com/pixel?uid=SECRET456", "||t.com^", "image")
        val all = (log.events() + log.requests()).joinToString { it.text }
        assertFalse(all.contains("SECRET"))
    }

    @Test fun hintsOnlyFromEvidence() {
        val log = DiagLog()
        log.newPage("film.example", "https://film.example/")
        val none = DiagAnalyzer.hints(
            DiagAnalyzer.Facts("film.example", true, 0, 0, 0, log.events(), 120, true),
        )
        assertEquals(1, none.size)
        assertTrue(none[0].contains("keine auffälligen"))

        log.add(DiagCat.MEDIA_ERROR, DiagLog.mediaErrorText(4))
        val hints = DiagAnalyzer.hints(
            DiagAnalyzer.Facts("film.example", true, 12, 2, 0, log.events(), 120, true),
        )
        assertTrue(hints.any { it.contains("12 Anfrage") })
        assertTrue(hints.any { it.contains("SRC_NOT_SUPPORTED") })
        assertTrue(hints.any { it.contains("Video-Decoder") })
        assertFalse(hints.any { it.contains("SSL") })
    }
}
