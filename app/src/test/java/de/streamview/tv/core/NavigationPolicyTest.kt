package de.streamview.tv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationPolicyTest {

    private val page = "https://www.videoseite.example/film/123"

    @Test fun sameSiteNavigationAlwaysAllowed() {
        val d = NavigationPolicy.mainFrame(NavContext(page, "https://cdn.videoseite.example/x", hasGesture = false))
        assertEquals(Verdict.ALLOW, d.verdict)
    }

    @Test fun clickOnLinkToOtherSiteAllowed() {
        val d = NavigationPolicy.mainFrame(
            NavContext(page, "https://player.other.example/e/1", hasGesture = true,
                clickedHref = "https://player.other.example/e/1"),
        )
        assertEquals(Verdict.ALLOW, d.verdict)
    }

    @Test fun clickHijackAsks() {
        // Nutzer klickt auf Link zu Seite A, Werbeskript leitet stattdessen zu B
        val d = NavigationPolicy.mainFrame(
            NavContext(page, "https://werbung.example/landing", hasGesture = true,
                clickedHref = "https://www.videoseite.example/film/124"),
        )
        assertEquals(Verdict.ASK, d.verdict)
    }

    @Test fun clickHijackBlockedWhenSitePolicyBlock() {
        val d = NavigationPolicy.mainFrame(
            NavContext(page, "https://werbung.example/landing", hasGesture = true,
                clickedHref = "https://www.videoseite.example/film/124", sitePolicy = PopupPolicy.BLOCK),
        )
        assertEquals(Verdict.BLOCK, d.verdict)
    }

    @Test fun automaticRedirectAfterInteractionAsks() {
        val d = NavigationPolicy.mainFrame(
            NavContext(page, "https://werbung.example/", hasGesture = false,
                pageHadUserInteraction = true, msSincePageStart = 20_000),
        )
        assertEquals(Verdict.ASK, d.verdict)
    }

    @Test fun landingRedirectAllowed() {
        val d = NavigationPolicy.mainFrame(
            NavContext("https://kurz.example/abc", "https://ziel.example/artikel", hasGesture = false,
                msSincePageStart = 800),
        )
        assertEquals(Verdict.ALLOW, d.verdict)
    }

    @Test fun serverRedirectAllowed() {
        val d = NavigationPolicy.mainFrame(
            NavContext(page, "https://login.anbieter.example/", hasGesture = false, isRedirect = true,
                pageHadUserInteraction = true, msSincePageStart = 60_000),
        )
        assertEquals(Verdict.ALLOW, d.verdict)
    }

    @Test fun filterListedTargetBlocked() {
        val d = NavigationPolicy.mainFrame(
            NavContext(page, "https://ads.example/", hasGesture = true, clickedHref = "https://ads.example/",
                filterBlocked = true),
        )
        assertEquals(Verdict.BLOCK, d.verdict)
    }

    @Test fun nonHttpBlocked() {
        assertEquals(Verdict.BLOCK, NavigationPolicy.mainFrame(NavContext(page, "market://details?id=x", true)).verdict)
    }

    @Test fun popupWithoutGestureBlocked() {
        assertEquals(Verdict.BLOCK, NavigationPolicy.popup(NavContext(page, "https://pop.example/", false)).verdict)
    }

    @Test fun popupSameSiteAllowed() {
        assertEquals(Verdict.ALLOW,
            NavigationPolicy.popup(NavContext(page, "https://www.videoseite.example/player", true)).verdict)
    }

    @Test fun popupMatchingClickedLinkAllowed() {
        assertEquals(Verdict.ALLOW, NavigationPolicy.popup(
            NavContext(page, "https://doku.example/", true, clickedHref = "https://doku.example/"),
        ).verdict)
    }

    @Test fun popupForeignAfterClickAsks() {
        assertEquals(Verdict.ASK, NavigationPolicy.popup(NavContext(page, "https://pop.example/", true)).verdict)
    }

    @Test fun popupSitePolicies() {
        assertEquals(Verdict.ALLOW, NavigationPolicy.popup(
            NavContext(page, "https://pop.example/", false, sitePolicy = PopupPolicy.ALLOW)).verdict)
        assertEquals(Verdict.BLOCK, NavigationPolicy.popup(
            NavContext(page, "https://www.videoseite.example/x", true, sitePolicy = PopupPolicy.BLOCK)).verdict)
    }

    @Test fun intentFallback() {
        val u = "intent://scan/#Intent;scheme=zxing;package=com.x;" +
            "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Fapp;end"
        assertEquals("https://example.com/app", NavigationPolicy.intentFallbackUrl(u))
        assertNull(NavigationPolicy.intentFallbackUrl("intent://x#Intent;S.browser_fallback_url=market%3A%2F%2Fx;end"))
        assertNull(NavigationPolicy.intentFallbackUrl("intent://x#Intent;package=a;end"))
    }
}
