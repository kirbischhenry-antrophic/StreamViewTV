package de.streamview.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import de.streamview.tv.core.DiagCat
import de.streamview.tv.core.DiagLog
import de.streamview.tv.core.InputResult
import de.streamview.tv.core.NavContext
import de.streamview.tv.core.NavDecision
import de.streamview.tv.core.NavigationPolicy
import de.streamview.tv.core.PopupPolicy
import de.streamview.tv.core.RequestTypes
import de.streamview.tv.core.UrlTools
import de.streamview.tv.core.Verdict
import org.json.JSONObject

/**
 * Die Webseitenansicht: genau EINE WebView, bildschirmfüllend.
 * Steuerung per Fernbedienung (Zeiger- oder Fokusmodus), Menü über die ☰-Taste.
 */
class BrowserActivity : Activity(), MediaBridge.Callbacks {

    private val app get() = App.instance
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var fullscreenBox: FrameLayout
    private lateinit var cursor: CursorView
    private lateinit var progress: ProgressBar
    private lateinit var notice: TextView
    private var web: WebView? = null
    private var bridge: MediaBridge? = null
    private var probe: WebView? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var activeDialog: AlertDialog? = null

    private var pageUrl: String? = null
    private var pageStart = 0L
    private var pageInteracted = false
    private var lastClickTime = 0L
    private var lastClickHref: String? = null
    private var jsDialogsOnPage = 0
    private var loggedPlaying = false
    private var lastNotify = 0L
    private var rendererCrashes = 0
    private var lastCrash = 0L
    private var started = false

    private var navMode = NavMode.CURSOR
    private var mobileUa = ""
    private var desktopUa = ""
    private var density = 1f

    // Zeiger
    private var keyUp = false
    private var keyDown = false
    private var keyLeft = false
    private var keyRight = false
    private var moving = false
    private var moveStart = 0L
    private var okDown = false
    private var okDownTime = 0L
    private var lastHover = 0L
    private var lastWheel = 0L
    private var focusBusy = false

    // -------------------------------------------------------------------------------------------
    // Lebenszyklus
    // -------------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        density = resources.displayMetrics.density
        navMode = app.prefs.navMode
        buildViews()
        createWebView()
        val restored = savedInstanceState != null && web?.restoreState(savedInstanceState) != null
        if (restored) {
            started = true
            return
        }
        val target = intent.getStringExtra(EXTRA_URL) ?: app.prefs.lastUrl ?: "https://www.zdf.de/"
        // Kurz auf die Filter warten, damit die erste Seite schon gefiltert lädt – aber nie lange.
        val go = Runnable {
            if (!started && !isFinishing) {
                started = true
                open(target)
            }
        }
        app.filters.whenReady { go.run() }
        ui.postDelayed(go, FILTER_WAIT_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_URL)?.let { open(it) }
    }

    override fun onResume() {
        super.onResume()
        navMode = app.prefs.navMode
        cursor.visibility = if (navMode == NavMode.CURSOR) View.VISIBLE else View.GONE
        RequestFilter.refresh()
        web?.resumeTimers()
        web?.onResume()
        showCursor()
    }

    override fun onPause() {
        resetKeys()
        web?.onPause()
        web?.pauseTimers()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web?.saveState(outState)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        activeDialog?.dismiss()
        destroyProbe()
        destroyWebView()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            app.diag.add(DiagCat.MEMORY, "Arbeitsspeicher kritisch (Stufe $level)")
        }
    }

    // -------------------------------------------------------------------------------------------
    // Aufbau
    // -------------------------------------------------------------------------------------------

    private fun buildViews() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 4f), Gravity.TOP))

        notice = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            val p = Ui.dp(this@BrowserActivity, 14f)
            setPadding(p * 2, p, p * 2, p)
            background = GradientDrawable().apply { setColor(0xE0202830.toInt()); cornerRadius = Ui.dp(context, 12f).toFloat() }
            visibility = View.GONE
        }
        root.addView(notice, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = Ui.dp(this@BrowserActivity, 32f) })

        fullscreenBox = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        root.addView(fullscreenBox, match())

        cursor = CursorView(this)
        cursor.visibility = if (navMode == NavMode.CURSOR) View.VISIBLE else View.GONE
        root.addView(cursor, match())
        setContentView(root)
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        val w = WebView(this)
        root.addView(w, 0, match())
        web = w

        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        // false: Play/Pause per Fernbedienung muss Videos starten können (kein Web-"Gesture")
        s.mediaPlaybackRequiresUserGesture = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportMultipleWindows(true)                 // neue Fenster laufen über onCreateWindow
        s.javaScriptCanOpenWindowsAutomatically = false   // Pop-ups ohne Klick blockt Chromium selbst
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE // nie ALWAYS_ALLOW
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setGeolocationEnabled(false)
        s.cacheMode = WebSettings.LOAD_DEFAULT

        mobileUa = s.userAgentString.replace("; wv", "")
        val chrome = Regex("Chrome/([0-9.]+)").find(mobileUa)?.groupValues?.get(1) ?: "120.0.0.0"
        desktopUa = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
        s.userAgentString = mobileUa

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(w, app.prefs.thirdPartyCookies)

        w.setBackgroundColor(Color.BLACK)
        w.isFocusable = true
        w.isFocusableInTouchMode = true
        w.webViewClient = client
        w.webChromeClient = chromeClient
        w.setDownloadListener { url, _, _, mime, _ -> onDownload(url, mime) }
        if (Build.VERSION.SDK_INT >= 26) {
            // Im Hintergrund darf das System den Seitenprozess zuerst beenden
            w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        }
        bridge = MediaBridge(this).also { it.install(w, app.prefs.bridgeName) }
        installServiceWorkerFilter()
        w.requestFocus()
    }

    private fun destroyWebView() {
        bridge?.clear()
        bridge = null
        web?.let {
            root.removeView(it)
            it.stopLoading()
            it.webChromeClient = null
            it.destroy()
        }
        web = null
    }

    private fun open(url: String) {
        val w = web ?: return
        applyUaFor(url)
        w.loadUrl(url)
    }

    private fun siteOf(url: String?) = UrlTools.siteKey(url)

    private fun applyUaFor(url: String) {
        val w = web ?: return
        val want = if (app.sites[siteOf(url)].desktop ?: app.prefs.desktopDefault) desktopUa else mobileUa
        if (w.settings.userAgentString != want) w.settings.userAgentString = want
    }

    private fun uaChangeNeeded(url: String): Boolean {
        val w = web ?: return false
        val want = if (app.sites[siteOf(url)].desktop ?: app.prefs.desktopDefault) desktopUa else mobileUa
        return w.settings.userAgentString != want
    }

    // -------------------------------------------------------------------------------------------
    // WebViewClient
    // -------------------------------------------------------------------------------------------

    private val client = object : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = try {
            RequestFilter.intercept(request.url.toString(), request.requestHeaders["Accept"], request.isForMainFrame)
        } catch (_: Throwable) {
            null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            onNavigate(request.url.toString(), request.isForMainFrame, request.hasGesture(), request.isRedirect)

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            pageUrl = url
            RequestFilter.setPage(url)
            pageStart = SystemClock.uptimeMillis()
            pageInteracted = false
            jsDialogsOnPage = 0
            loggedPlaying = false
            bridge?.onNewPage()
            app.diag.newPage(siteOf(url), UrlTools.sanitizeForLog(url))
            progress.visibility = View.VISIBLE
            hideNotice()
            setKeepScreenOn(false)
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            lateInjection(view)
        }

        override fun onPageFinished(view: WebView, url: String) {
            lateInjection(view)
            if (app.prefs.rememberLastPage && UrlTools.isHttp(url)) app.prefs.lastUrl = url
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (UrlTools.isHttp(url)) pageUrl = url
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            val main = request.isForMainFrame
            app.diag.add(
                DiagCat.NET_ERROR,
                "${if (main) "Hauptseite" else "Unterressource"}: ${error.description} (Code ${error.errorCode}) – " +
                    UrlTools.sanitizeForLog(request.url.toString()),
            )
            if (main) {
                showNotice("Seite konnte nicht geladen werden: ${error.description}\n☰ Menü → „Webseite funktioniert nicht?“")
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            val url = request.url.toString()
            val isMedia = RequestTypes.infer(url, request.requestHeaders["Accept"], false) == de.streamview.tv.core.ReqType.MEDIA
            if (request.isForMainFrame || isMedia) {
                app.diag.add(DiagCat.HTTP_ERROR, "HTTP ${response.statusCode} – ${UrlTools.sanitizeForLog(url)}")
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            // Zertifikatsfehler werden NIE ignoriert.
            handler.cancel()
            val reason = sslText(error.primaryError)
            app.diag.add(DiagCat.SSL_ERROR, "$reason – ${UrlTools.sanitizeForLog(error.url)}")
            if (UrlTools.hostOf(error.url) == UrlTools.hostOf(pageUrl)) {
                showNotice("Sichere Verbindung abgelehnt: $reason.\nDie Seite wurde aus Sicherheitsgründen nicht geladen.")
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            handleRendererGone(view, detail.didCrash())
            return true
        }
    }

    private fun sslText(code: Int) = when (code) {
        SslError.SSL_NOTYETVALID -> "Zertifikat noch nicht gültig"
        SslError.SSL_EXPIRED -> "Zertifikat abgelaufen"
        SslError.SSL_IDMISMATCH -> "Zertifikat passt nicht zur Adresse"
        SslError.SSL_UNTRUSTED -> "Zertifikat nicht vertrauenswürdig"
        SslError.SSL_DATE_INVALID -> "Datum des Zertifikats ungültig"
        else -> "Zertifikat ungültig"
    }

    private fun lateInjection(view: WebView) {
        val b = bridge
        if (b != null && b.listenerSupported) {
            b.injectLate(view, app.prefs.bridgeName)
            return
        }
        // Alte WebView ohne WebMessageListener: CSS nur ins Hauptdokument
        val page = pageUrl ?: return
        if (!RequestFilter.active) return
        val css = app.filters.cosmeticCss(page, UrlTools.hostOf(page) ?: return)
        view.evaluateJavascript(PageScripts.fallbackInject(JSONObject.quote(css)), null)
    }

    private fun installServiceWorkerFilter() {
        if (swInstalled) return
        swInstalled = true
        try {
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = try {
                    RequestFilter.intercept(request.url.toString(), request.requestHeaders["Accept"], false)
                } catch (_: Throwable) {
                    null
                }
            })
        } catch (_: Exception) {
        }
    }

    // -------------------------------------------------------------------------------------------
    // Navigation, Pop-ups, Weiterleitungen
    // -------------------------------------------------------------------------------------------

    private fun recentHref(): String? {
        if (SystemClock.uptimeMillis() - lastClickTime > CLICK_WINDOW_MS) return null
        lastClickHref?.let { return it }
        val r = web?.hitTestResult ?: return null
        return if (r.type == WebView.HitTestResult.SRC_ANCHOR_TYPE) r.extra else null
    }

    private fun filterBlocksNavigation(url: String, popup: Boolean): Boolean {
        if (!RequestFilter.active) return false
        return app.filters.engine?.matchNavigation(url, RequestFilter.pageHost, popup)?.blocked == true
    }

    private fun navContext(url: String, gesture: Boolean, redirect: Boolean, popup: Boolean) = NavContext(
        currentUrl = pageUrl,
        targetUrl = url,
        hasGesture = gesture,
        isRedirect = redirect,
        clickedHref = recentHref(),
        userClickedRecently = SystemClock.uptimeMillis() - lastClickTime < CLICK_WINDOW_MS,
        pageHadUserInteraction = pageInteracted,
        msSincePageStart = SystemClock.uptimeMillis() - pageStart,
        sitePolicy = app.sites[siteOf(pageUrl)].popups,
        protectionEnabled = app.prefs.popupProtection,
        filterBlocked = filterBlocksNavigation(url, popup),
    )

    /** @return true = Navigation abbrechen */
    private fun onNavigate(url: String, mainFrame: Boolean, gesture: Boolean, redirect: Boolean): Boolean {
        val scheme = UrlTools.schemeOf(url)
        if (scheme != "http" && scheme != "https") {
            if (!mainFrame) return scheme !in NavigationPolicy.harmlessSubframeSchemes
            if (scheme == "about") return false
            if (scheme == "intent") {
                val fallback = NavigationPolicy.intentFallbackUrl(url)
                app.diag.add(DiagCat.BLOCKED_SCHEME, "intent:// – Start einer anderen App verhindert" +
                    if (fallback != null) "; Ersatzadresse wird geprüft" else "")
                if (fallback != null) ui.post { navigateChecked(fallback, gesture) }
                else notify("Aufruf einer anderen App blockiert")
                return true
            }
            app.diag.add(DiagCat.BLOCKED_SCHEME, "$scheme: – Aufruf blockiert")
            notify("Aufruf einer anderen App blockiert")
            return true
        }
        if (!mainFrame) return false
        val d = NavigationPolicy.mainFrame(navContext(url, gesture, redirect, popup = false))
        return applyDecision(d, url, popup = false)
    }

    private fun navigateChecked(url: String, gesture: Boolean) {
        if (!onNavigate(url, true, gesture, false)) web?.loadUrl(url)
    }

    /** @return true = ursprüngliche Navigation abbrechen */
    private fun applyDecision(d: NavDecision, url: String, popup: Boolean): Boolean {
        val log = UrlTools.sanitizeForLog(url)
        return when (d.verdict) {
            Verdict.ALLOW -> {
                if (!UrlTools.sameSite(pageUrl, url) && pageUrl != null) {
                    app.diag.add(if (popup) DiagCat.ALLOWED_POPUP else DiagCat.ALLOWED_NAV, "${d.reason}: $log")
                }
                if (popup) {
                    openAllowed(url)
                    true
                } else if (uaChangeNeeded(url)) {
                    applyUaFor(url)
                    ui.post { web?.loadUrl(url) }
                    true
                } else false
            }
            Verdict.BLOCK -> {
                app.diag.add(if (popup) DiagCat.BLOCKED_POPUP else DiagCat.BLOCKED_NAV, "${d.reason}: $log")
                notify(if (popup) "Pop-up blockiert" else "Weiterleitung blockiert")
                true
            }
            Verdict.ASK -> {
                askUser(url, d.reason, popup)
                true
            }
        }
    }

    private fun openAllowed(url: String) {
        applyUaFor(url)
        web?.loadUrl(url)
    }

    private fun askUser(url: String, reason: String, popup: Boolean) {
        val cat = if (popup) DiagCat.BLOCKED_POPUP else DiagCat.BLOCKED_NAV
        if (activeDialog?.isShowing == true) {
            app.diag.add(cat, "Weiteres ${if (popup) "Fenster" else "Umleitungsziel"} während offener Nachfrage blockiert: ${UrlTools.sanitizeForLog(url)}")
            return
        }
        resetKeys()
        val site = siteOf(pageUrl)
        val target = UrlTools.hostOf(url) ?: url
        val head = TextView(this).apply {
            text = (if (popup) "Neues Fenster öffnen?" else "Zu einer anderen Seite wechseln?") +
                "\n$target\n($reason – häufig Werbung)"
            setTextColor(Color.WHITE)
            textSize = 18f
            val p = Ui.dp(context, 20f)
            setPadding(p, p, p, p / 2)
        }
        val items = arrayOf(
            "Blockieren",
            "Einmal öffnen",
            "Für $site immer erlauben",
            "Für $site immer blockieren",
        )
        activeDialog = Ui.dialog(this)
            .setCustomTitle(head)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> app.diag.add(cat, "Vom Nutzer blockiert: ${UrlTools.sanitizeForLog(url)}")
                    1 -> {
                        app.diag.add(if (popup) DiagCat.ALLOWED_POPUP else DiagCat.ALLOWED_NAV, "Einmalig erlaubt: ${UrlTools.sanitizeForLog(url)}")
                        openAllowed(url)
                    }
                    2 -> {
                        app.sites.update(site) { it.copy(popups = PopupPolicy.ALLOW) }
                        openAllowed(url)
                    }
                    3 -> {
                        app.sites.update(site) { it.copy(popups = PopupPolicy.BLOCK) }
                        app.diag.add(cat, "Für $site dauerhaft blockiert")
                    }
                }
            }
            .setOnCancelListener { app.diag.add(cat, "Nachfrage abgebrochen – blockiert: ${UrlTools.sanitizeForLog(url)}") }
            .show()
    }

    private fun decidePopup(url: String, gesture: Boolean) {
        val d = NavigationPolicy.popup(navContext(url, gesture, false, popup = true))
        applyDecision(d, url, popup = true)
    }

    private fun destroyProbe() {
        probe?.let {
            it.stopLoading()
            it.destroy()
        }
        probe = null
    }

    // -------------------------------------------------------------------------------------------
    // WebChromeClient
    // -------------------------------------------------------------------------------------------

    private val chromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
            progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customCallback = callback
            fullscreenBox.addView(view, match())
            fullscreenBox.visibility = View.VISIBLE
            web?.visibility = View.INVISIBLE
            progress.visibility = View.GONE
            hideNotice()
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
            setKeepScreenOn(true)
            cursor.alpha = 0f
            app.diag.add(DiagCat.MEDIA, "Vollbild gestartet")
        }

        override fun onHideCustomView() {
            exitFullscreen(notifyPage = false)
        }

        override fun getDefaultVideoPoster(): Bitmap = POSTER

        override fun onPermissionRequest(request: PermissionRequest) {
            ui.post { handlePermission(request) }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            activeDialog?.dismiss()
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            app.diag.add(DiagCat.PERMISSION, "Standortabfrage abgelehnt (${UrlTools.hostOf(origin)})")
            callback.invoke(origin, false, false)
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val policy = app.sites[siteOf(pageUrl)].popups
            if (!isUserGesture && app.prefs.popupProtection && policy != PopupPolicy.ALLOW) {
                app.diag.add(DiagCat.BLOCKED_POPUP, "Pop-up ohne Klick")
                notify("Pop-up blockiert")
                return false
            }
            if (probe != null) {
                app.diag.add(DiagCat.BLOCKED_POPUP, "Mehrere Fenster gleichzeitig – weiteres blockiert")
                return false
            }
            // Ziel-URL über eine kurzlebige WebView OHNE JavaScript ermitteln, dann entscheiden.
            val p = WebView(this@BrowserActivity)
            p.settings.javaScriptEnabled = false
            probe = p
            var done = false
            fun finish(url: String?) {
                if (done) return
                done = true
                ui.post { destroyProbe() }
                if (url == null || url == "about:blank") {
                    app.diag.add(DiagCat.BLOCKED_POPUP, "Fenster ohne erkennbares Ziel verworfen")
                    return
                }
                ui.post { decidePopup(url, isUserGesture) }
            }
            p.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    finish(r.url.toString()); return true
                }
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse {
                    val u = r.url.toString()
                    if (r.isForMainFrame) ui.post { finish(u) }
                    return RequestFilter.empty()
                }
                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    if (url != null && url != "about:blank") finish(url)
                }
            }
            ui.postDelayed({ finish(null) }, 4000)
            (resultMsg.obj as WebView.WebViewTransport).webView = p
            resultMsg.sendToTarget()
            return true
        }

        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                app.diag.add(DiagCat.JS_ERROR, "${m.message()} (${UrlTools.hostOf(m.sourceId()) ?: "?"}:${m.lineNumber()})")
            }
            return true
        }

        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean =
            limitJsDialogs(result) || super.onJsAlert(view, url, message, result)

        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean =
            limitJsDialogs(result) || super.onJsConfirm(view, url, message, result)

        override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean =
            limitJsDialogs(result) || super.onJsPrompt(view, url, message, defaultValue, result)

        override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult): Boolean {
            result.confirm() // Seiten dürfen das Verlassen nicht verhindern
            return true
        }

        override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean = false
    }

    /** Ab dem 4. Dialog pro Seite werden JavaScript-Dialoge automatisch geschlossen (Dialog-Spam). */
    private fun limitJsDialogs(result: JsResult): Boolean {
        jsDialogsOnPage++
        if (jsDialogsOnPage > 3) {
            result.cancel()
            app.diag.add(DiagCat.BLOCKED_POPUP, "JavaScript-Dialog unterdrückt (zu viele auf dieser Seite)")
            return true
        }
        return false
    }

    private fun handlePermission(request: PermissionRequest) {
        val res = request.resources
        val origin = request.origin.host ?: "?"
        if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID !in res) {
            app.diag.add(DiagCat.PERMISSION, "Berechtigung abgelehnt: ${res.joinToString()} ($origin)")
            request.deny()
            return
        }
        val site = siteOf(pageUrl)
        when (app.sites[site].drm) {
            true -> { request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)); app.diag.add(DiagCat.DRM, "DRM erlaubt (gespeichert) für $origin") }
            false -> { request.deny(); app.diag.add(DiagCat.DRM, "DRM abgelehnt (gespeichert) für $origin") }
            null -> {
                resetKeys()
                activeDialog = Ui.dialog(this)
                    .setTitle("Geschützte Inhalte (DRM) abspielen?")
                    .setMessage("$origin möchte DRM-geschützte Videos abspielen. Dafür wird eine Gerätekennung für den Kopierschutz verwendet.")
                    .setPositiveButton("Erlauben") { _, _ ->
                        app.sites.update(site) { it.copy(drm = true) }
                        request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                        app.diag.add(DiagCat.DRM, "DRM vom Nutzer erlaubt für $origin")
                    }
                    .setNegativeButton("Ablehnen") { _, _ ->
                        app.sites.update(site) { it.copy(drm = false) }
                        request.deny()
                        app.diag.add(DiagCat.DRM, "DRM vom Nutzer abgelehnt für $origin")
                    }
                    .setOnCancelListener { request.deny() }
                    .show()
            }
        }
    }

    private fun exitFullscreen(notifyPage: Boolean) {
        val v = customView ?: return
        val cb = customCallback
        fullscreenBox.removeView(v)
        fullscreenBox.visibility = View.GONE
        web?.visibility = View.VISIBLE
        customView = null
        customCallback = null
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        setKeepScreenOn(bridge?.anyPlaying == true)
        web?.requestFocus()
        cursor.visibility = if (navMode == NavMode.CURSOR) View.VISIBLE else View.GONE
        showCursor()
        if (notifyPage) cb?.onCustomViewHidden()
    }

    private fun handleRendererGone(view: WebView, crashed: Boolean) {
        app.diag.add(
            DiagCat.RENDERER,
            if (crashed) "Seitenprozess abgestürzt" else "Seitenprozess vom System beendet (vermutlich Speichermangel)",
        )
        val url = pageUrl
        if (view === web) {
            exitFullscreen(notifyPage = false)
            destroyWebView()
        } else {
            view.destroy()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastCrash > 60_000) rendererCrashes = 0
        rendererCrashes++
        lastCrash = now
        createWebView()
        if (rendererCrashes <= 2 && url != null) {
            open(url)
            showNotice("Die Seite wurde neu geladen, weil ihr Prozess beendet wurde.")
        } else {
            showNotice("Die Seite stürzt wiederholt ab. Andere Apps schließen oder ☰ → „Webseite funktioniert nicht?“ → Diagnose.")
        }
    }

    // -------------------------------------------------------------------------------------------
    // MediaBridge.Callbacks
    // -------------------------------------------------------------------------------------------

    override fun cssForFrame(frameOrigin: Uri, isMainFrame: Boolean): String {
        val page = pageUrl ?: return ""
        if (!RequestFilter.active) return ""
        val host = frameOrigin.host ?: UrlTools.hostOf(page) ?: return ""
        return app.filters.cosmeticCss(page, host)
    }

    override fun onMediaState(state: String, isMainFrame: Boolean, width: Int, height: Int) {
        when (state) {
            "playing" -> {
                setKeepScreenOn(true)
                if (!loggedPlaying) {
                    loggedPlaying = true
                    app.diag.add(DiagCat.MEDIA, "Wiedergabe gestartet (${if (isMainFrame) "Hauptseite" else "eingebetteter Player"})")
                }
            }
            "pause", "ended" -> if (customView == null) setKeepScreenOn(false)
            "meta" -> if (width > 0) app.diag.add(DiagCat.MEDIA, "Video erkannt: ${width}×$height")
        }
    }

    override fun onMediaError(code: Int, frameOrigin: Uri) {
        app.diag.add(DiagCat.MEDIA_ERROR, DiagLog.mediaErrorText(code) + " (${frameOrigin.host ?: "?"})")
    }

    private fun setKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // -------------------------------------------------------------------------------------------
    // Downloads
    // -------------------------------------------------------------------------------------------

    private fun onDownload(url: String, mime: String?) {
        val ext = RequestTypes.extension(url)
        val m = mime?.lowercase() ?: ""
        val isMedia = m.startsWith("video/") || m.startsWith("audio/") ||
            m in setOf("application/x-mpegurl", "application/vnd.apple.mpegurl", "application/dash+xml") ||
            ext in setOf("m3u8", "mpd", "mp4", "webm", "mkv", "mp3", "m4a")
        if (!isMedia || !UrlTools.isHttp(url)) {
            app.diag.add(DiagCat.DOWNLOAD, "Download blockiert (${m.ifEmpty { "unbekannter Typ" }}): ${UrlTools.sanitizeForLog(url)}")
            notify("Download blockiert – StreamView TV speichert keine Dateien.")
            return
        }
        resetKeys()
        activeDialog = Ui.dialog(this)
            .setTitle("Video-Datei")
            .setMessage("Diese Adresse liefert eine Mediendatei (${m.ifEmpty { ext }}), die die Webseite nicht selbst abspielt.\nIm eingebauten Player öffnen?")
            .setPositiveButton("Abspielen") { _, _ ->
                app.diag.add(DiagCat.MEDIA, "Mediendatei im Player geöffnet: ${UrlTools.sanitizeForLog(url)}")
                PlayerActivity.start(this, url, mime, pageUrl, web?.settings?.userAgentString)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // -------------------------------------------------------------------------------------------
    // Fernbedienung
    // -------------------------------------------------------------------------------------------

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0
        when (e.keyCode) {
            KeyEvent.KEYCODE_MENU -> { if (first) showMenu(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (first) mediaToggle()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) seekOrScroll(if (e.repeatCount > 3) 30 else 10); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) seekOrScroll(if (e.repeatCount > 3) -30 else -10); return true }
        }
        val dir = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> 0
            KeyEvent.KEYCODE_DPAD_DOWN -> 1
            KeyEvent.KEYCODE_DPAD_LEFT -> 2
            KeyEvent.KEYCODE_DPAD_RIGHT -> 3
            else -> -1
        }
        val isOk = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER ||
            e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || e.keyCode == KeyEvent.KEYCODE_BUTTON_A
        if (dir < 0 && !isOk) return super.dispatchKeyEvent(e)

        // Vollbild-Video: OK = Pause/Weiter (wenn kein Zeiger sichtbar), Pfeile zeigen den Zeiger
        val cursorMode = navMode == NavMode.CURSOR || customView != null
        if (cursorMode) {
            if (customView != null && cursor.visibility != View.VISIBLE) cursor.visibility = View.VISIBLE
            if (dir >= 0) {
                when (dir) { 0 -> keyUp = down; 1 -> keyDown = down; 2 -> keyLeft = down; 3 -> keyRight = down }
                if (down) startMoving()
                return true
            }
            if (customView != null && cursor.alpha < 0.5f) {
                if (first) mediaToggle()
                return true
            }
            if (first) okPressed() else if (e.action == KeyEvent.ACTION_UP) okReleased()
            return true
        }
        // Fokusmodus
        if (dir >= 0) {
            if (down) focusMove(arrayOf("up", "down", "left", "right")[dir])
            return true
        }
        if (first) focusActivate()
        return true
    }

    private fun resetKeys() {
        keyUp = false; keyDown = false; keyLeft = false; keyRight = false
        if (okDown) okReleased()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) resetKeys()
    }

    private val hideCursor = Runnable { cursor.animate().alpha(0f).setDuration(350).start() }

    private fun showCursor() {
        if (navMode != NavMode.CURSOR && customView == null) return
        cursor.animate().cancel()
        cursor.alpha = 1f
        ui.removeCallbacks(hideCursor)
        ui.postDelayed(hideCursor, if (customView != null) 2500 else 8000)
    }

    private fun startMoving() {
        if (moving) return
        moving = true
        moveStart = SystemClock.uptimeMillis()
        ui.removeCallbacks(moveTick)
        ui.post(moveTick)
    }

    private val moveTick = object : Runnable {
        override fun run() {
            if (!(keyUp || keyDown || keyLeft || keyRight)) { moving = false; return }
            val speed = app.prefs.cursorSpeed.coerceIn(0, 2)
            val base = floatArrayOf(2.5f, 4f, 6f)[speed]
            val max = floatArrayOf(12f, 20f, 30f)[speed]
            val t = (SystemClock.uptimeMillis() - moveStart) / 1000f
            val v = density * minOf(base + t * base * 3f, max)
            val dx = (if (keyRight) 1 else 0) - (if (keyLeft) 1 else 0)
            val dy = (if (keyDown) 1 else 0) - (if (keyUp) 1 else 0)
            val nx = cursor.cursorX + dx * v
            val ny = cursor.cursorY + dy * v
            cursor.setCursor(nx, ny)
            // Am Rand: Seite (bzw. Element unter dem Zeiger) per Mausrad scrollen
            val atEdgeY = (dy < 0 && ny <= 0f) || (dy > 0 && ny >= cursor.height - 1)
            val atEdgeX = (dx < 0 && nx <= 0f) || (dx > 0 && nx >= cursor.width - 1)
            val now = SystemClock.uptimeMillis()
            if ((atEdgeX || atEdgeY) && now - lastWheel > 70) {
                lastWheel = now
                val ex = cursor.cursorX.coerceIn(8f, cursor.width - 8f)
                val ey = cursor.cursorY.coerceIn(8f, cursor.height - 8f)
                wheel(ex, ey, if (atEdgeY) -dy.toFloat() else 0f, if (atEdgeX) dx.toFloat() else 0f)
            }
            if (okDown) touch(MotionEvent.ACTION_MOVE, cursor.cursorX, cursor.cursorY, okDownTime)
            else if (now - lastHover > 80) { lastHover = now; hover(cursor.cursorX, cursor.cursorY) }
            showCursor()
            ui.postDelayed(this, 16)
        }
    }

    private fun touchTarget(): View? = customView ?: web

    private fun okPressed() {
        showCursor()
        okDown = true
        okDownTime = SystemClock.uptimeMillis()
        cursor.held = true
        markInteraction(null)
        touch(MotionEvent.ACTION_DOWN, cursor.cursorX, cursor.cursorY, okDownTime)
    }

    private fun okReleased() {
        if (!okDown) return
        okDown = false
        cursor.held = false
        touch(MotionEvent.ACTION_UP, cursor.cursorX, cursor.cursorY, okDownTime)
        cursor.showClick()
    }

    private fun markInteraction(href: String?) {
        lastClickTime = SystemClock.uptimeMillis()
        lastClickHref = href
        pageInteracted = true
    }

    private fun touch(action: Int, x: Float, y: Float, downTime: Long) {
        val target = touchTarget() ?: return
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0)
        target.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun mouseEvent(action: Int, x: Float, y: Float, vScroll: Float, hScroll: Float): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
            setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
        })
        val t = SystemClock.uptimeMillis()
        return MotionEvent.obtain(t, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
    }

    private fun hover(x: Float, y: Float) {
        val target = touchTarget() ?: return
        val ev = mouseEvent(MotionEvent.ACTION_HOVER_MOVE, x, y, 0f, 0f)
        target.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    /** Mausrad-Ereignis: scrollt das Element unter der Position – auch in eingebetteten Frames. */
    private fun wheel(x: Float, y: Float, v: Float, h: Float) {
        val target = touchTarget() ?: return
        val ev = mouseEvent(MotionEvent.ACTION_SCROLL, x, y, v, h)
        target.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    private fun tapAt(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, x, y, t)
        ui.postDelayed({ touch(MotionEvent.ACTION_UP, x, y, t) }, 60)
    }

    private fun focusMove(dir: String) {
        val w = web ?: return
        if (focusBusy) return
        focusBusy = true
        w.evaluateJavascript(PageScripts.focusMove(dir)) { res ->
            focusBusy = false
            val o = WebData.parseJsResult(res)
            if (o == null || o.optString("r") == "none") {
                val cx = w.width / 2f
                val cy = w.height / 2f
                when (dir) {
                    "up" -> repeat(3) { wheel(cx, cy, 1f, 0f) }
                    "down" -> repeat(3) { wheel(cx, cy, -1f, 0f) }
                    "left" -> repeat(3) { wheel(cx, cy, 0f, -1f) }
                    "right" -> repeat(3) { wheel(cx, cy, 0f, 1f) }
                }
            }
        }
    }

    private fun focusActivate() {
        val w = web ?: return
        w.evaluateJavascript(PageScripts.FOCUS_RECT) { res ->
            val o = WebData.parseJsResult(res)
            if (o == null || o.optString("r") != "ok") {
                focusMove("init")
                return@evaluateJavascript
            }
            val vw = o.optDouble("vw", 0.0)
            if (vw <= 0) return@evaluateJavascript
            val scale = (w.width / vw).toFloat()
            val x = ((o.optDouble("x") + o.optDouble("w") / 2) * scale).toFloat()
            val y = ((o.optDouble("y") + o.optDouble("h") / 2) * scale).toFloat()
            markInteraction(o.optString("href").ifEmpty { null })
            tapAt(x.coerceIn(1f, w.width - 1f), y.coerceIn(1f, w.height - 1f))
        }
    }

    private fun mediaToggle() {
        markInteraction(null)
        if (bridge?.sendMedia("toggle") == true) return
        web?.evaluateJavascript(PageScripts.fallbackMedia("toggle", 0), null)
    }

    private fun seekOrScroll(seconds: Int) {
        val b = bridge
        if (customView != null || b?.anyMediaKnown == true) {
            if (b?.sendMedia("seek", seconds) != true) {
                web?.evaluateJavascript(PageScripts.fallbackMedia("seek", seconds), null)
            }
            return
        }
        val w = web ?: return
        repeat(6) { wheel(w.width / 2f, w.height / 2f, if (seconds > 0) -1f else 1f, 0f) }
    }

    @Deprecated("Fire OS 7 (API 28) nutzt onBackPressed")
    override fun onBackPressed() {
        if (activeDialog?.isShowing == true) { activeDialog?.dismiss(); return }
        if (customView != null) { exitFullscreen(notifyPage = true); return }
        val w = web
        if (w != null && w.canGoBack()) { w.goBack(); return }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // -------------------------------------------------------------------------------------------
    // Menü
    // -------------------------------------------------------------------------------------------

    private class Item(val label: String, val action: () -> Unit)

    private fun showMenu() {
        resetKeys()
        val w = web ?: return
        val url = pageUrl
        val site = siteOf(url)
        val adOn = RequestFilter.active
        val items = ArrayList<Item>()
        items += Item("Adresse eingeben …") { showAddressDialog() }
        if (w.canGoBack()) items += Item("Zurück") { w.goBack() }
        if (w.canGoForward()) items += Item("Vorwärts") { w.goForward() }
        items += Item("Neu laden") { w.reload() }
        items += Item("Als Favorit speichern") {
            if (url != null) {
                app.prefs.addFavorite(w.title?.takeIf { it.isNotBlank() } ?: (UrlTools.hostOf(url) ?: url), url)
                Ui.toast(this, "Favorit gespeichert")
            }
        }
        if (app.prefs.adblockEnabled) {
            items += Item(if (adOn) "Werbeblocker auf $site ausschalten" else "Werbeblocker auf $site einschalten") {
                val off = adOn
                app.sites.update(site) { it.copy(adblock = if (off) false else null) }
                if (!off) app.tempAdblockOff.remove(site)
                RequestFilter.refresh()
                w.reload()
            }
        }
        items += Item("Webseite funktioniert nicht?") { showCompatMenu() }
        items += Item(if (navMode == NavMode.CURSOR) "Steuerung: Zeiger → zu Fokus wechseln" else "Steuerung: Fokus → zu Zeiger wechseln") {
            toggleNavMode()
        }
        items += Item("Vergrößern") { w.zoomIn() }
        items += Item("Verkleinern") { w.zoomOut() }
        items += Item("Einstellungen") { startActivity(Intent(this, SettingsActivity::class.java)) }
        items += Item("Startbildschirm") { finish() }

        val d = app.diag
        val title = "${UrlTools.hostOf(url) ?: "StreamView TV"}\n" +
            (if (adOn) "Blockiert: ${d.blockedRequestsOnPage} Anfragen, ${d.blockedPopupsOnPage} Fenster, ${d.blockedNavsOnPage} Weiterleitungen"
            else "Werbeblocker auf dieser Seite aus")
        activeDialog = Ui.dialog(this)
            .setCustomTitle(dialogTitle(title))
            .setItems(items.map { it.label }.toTypedArray()) { _, i -> items[i].action() }
            .show()
    }

    private fun dialogTitle(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 17f
        val p = Ui.dp(context, 18f)
        setPadding(p, p, p, p / 2)
    }

    private fun showCompatMenu() {
        val w = web ?: return
        val url = pageUrl ?: return
        val site = siteOf(url)
        val cfg = app.sites[site]
        val tempOff = site in app.tempAdblockOff
        val desktop = cfg.desktop ?: app.prefs.desktopDefault
        val items = ArrayList<Item>()
        items += Item("1. Webseite neu laden") { w.reload() }
        items += Item(if (tempOff) "2. Werbeblocker wieder einschalten" else "2. Werbeblocker vorübergehend aus (bis App-Neustart)") {
            if (tempOff) app.tempAdblockOff.remove(site) else app.tempAdblockOff.add(site)
            RequestFilter.refresh()
            app.diag.add(DiagCat.INFO, if (tempOff) "Werbeblocker für $site wieder an" else "Werbeblocker für $site vorübergehend aus")
            w.reload()
        }
        items += Item("3. Pop-ups & Weiterleitungen: ${policyLabel(cfg.popups)} – ändern") { choosePopupPolicy(site) }
        items += Item("4. Ansicht wechseln (jetzt: ${if (desktop) "Desktop" else "Mobil"})") {
            app.sites.update(site) { it.copy(desktop = !desktop) }
            applyUaFor(url)
            w.reload()
        }
        items += Item("5. Cookies & Speicher dieser Seite löschen") {
            WebData.clearSite(url)
            app.diag.add(DiagCat.INFO, "Cookies/Speicher für $site gelöscht")
            Ui.toast(this, "Daten von $site gelöscht")
            w.reload()
        }
        items += Item("6. Diagnose anzeigen") { openDiagnostics() }
        activeDialog = Ui.dialog(this)
            .setCustomTitle(dialogTitle("Webseite funktioniert nicht?\n$site"))
            .setItems(items.map { it.label }.toTypedArray()) { _, i -> items[i].action() }
            .show()
    }

    private fun policyLabel(p: PopupPolicy) = when (p) {
        PopupPolicy.ASK -> "Nachfragen"
        PopupPolicy.ALLOW -> "Erlauben"
        PopupPolicy.BLOCK -> "Blockieren"
    }

    private fun choosePopupPolicy(site: String) {
        val opts = arrayOf(
            "Nachfragen (Standard)",
            "Erlauben – Player öffnet sich in neuem Fenster",
            "Immer blockieren",
        )
        val cur = app.sites[site].popups.ordinal
        activeDialog = Ui.dialog(this)
            .setTitle("Pop-ups & Weiterleitungen für $site")
            .setSingleChoiceItems(opts, cur) { d, which ->
                app.sites.update(site) { it.copy(popups = PopupPolicy.values()[which]) }
                d.dismiss()
            }
            .show()
    }

    private fun toggleNavMode() {
        navMode = if (navMode == NavMode.CURSOR) NavMode.FOCUS else NavMode.CURSOR
        app.prefs.navMode = navMode
        if (navMode == NavMode.CURSOR) {
            web?.evaluateJavascript(PageScripts.FOCUS_CLEAR, null)
            cursor.visibility = View.VISIBLE
            showCursor()
        } else {
            cursor.visibility = View.GONE
            focusMove("init")
        }
        Ui.toast(this, if (navMode == NavMode.CURSOR) "Zeiger-Steuerung" else "Fokus-Steuerung: Pfeile springen zwischen Elementen")
    }

    private fun openDiagnostics() {
        startActivity(Intent(this, DiagnosticsActivity::class.java).putExtra(DiagnosticsActivity.EXTRA_PAGE, pageUrl)
            .putExtra(DiagnosticsActivity.EXTRA_DOC_START, bridge?.documentStartSupported == true))
    }

    private fun showAddressDialog() {
        resetKeys()
        val input = EditText(this).apply {
            setText(pageUrl ?: "")
            selectAll()
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            hint = "z. B. example.com"
        }
        val box = FrameLayout(this).apply {
            val p = Ui.dp(context, 20f)
            setPadding(p, p / 2, p, 0)
            addView(input)
        }
        val dialog = Ui.dialog(this)
            .setTitle("Adresse öffnen")
            .setView(box)
            .setPositiveButton("Öffnen", null)
            .setNegativeButton("Abbrechen", null)
            .create()
        fun submit() {
            when (val r = UrlTools.normalizeUserInput(input.text.toString())) {
                is InputResult.Ok -> { dialog.dismiss(); markInteraction(null); open(r.url) }
                is InputResult.Invalid -> input.error = r.message
            }
        }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_GO) { submit(); true } else false }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() } }
        activeDialog = dialog
        dialog.show()
    }

    // -------------------------------------------------------------------------------------------
    // Hinweise
    // -------------------------------------------------------------------------------------------

    private val hideNoticeRunnable = Runnable { notice.visibility = View.GONE }

    private fun showNotice(text: String) {
        notice.text = text
        notice.visibility = View.VISIBLE
        ui.removeCallbacks(hideNoticeRunnable)
        ui.postDelayed(hideNoticeRunnable, 9000)
    }

    private fun hideNotice() {
        ui.removeCallbacks(hideNoticeRunnable)
        notice.visibility = View.GONE
    }

    /** Kurzer Hinweis, höchstens alle 4 Sekunden (keine Toast-Flut bei aggressiven Seiten). */
    private fun notify(msg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastNotify < 4000 || customView != null) return
        lastNotify = now
        Ui.toast(this, msg)
    }

    companion object {
        const val EXTRA_URL = "url"
        private const val CLICK_WINDOW_MS = 2500L
        private const val FILTER_WAIT_MS = 3000L
        private var swInstalled = false
        private val POSTER: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }

        fun open(activity: Activity, url: String) {
            activity.startActivity(Intent(activity, BrowserActivity::class.java).putExtra(EXTRA_URL, url))
        }
    }
}
