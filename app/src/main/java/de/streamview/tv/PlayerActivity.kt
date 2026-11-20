package de.streamview.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import de.streamview.tv.core.DiagCat
import de.streamview.tv.core.RequestTypes
import de.streamview.tv.core.UrlTools

/**
 * Optionaler eingebauter Player (Media3/ExoPlayer) – NUR für Mediendateien, die die WebView
 * als Download erhalten hat (z. B. direkter Link auf .mp4/.m3u8). Es werden keine Streams aus
 * Webseiten extrahiert und keine DRM- oder Zugriffsbeschränkungen umgangen.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var error: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url == null || !UrlTools.isHttp(url)) { finish(); return }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply { keepScreenOn = true }
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        error = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER; visibility = View.GONE
            setBackgroundColor(0xCC000000.toInt())
            val p = Ui.dp(context, 24f); setPadding(p, p, p, p)
        }
        root.addView(error, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)

        val ua = intent.getStringExtra(EXTRA_UA)
        val referer = intent.getStringExtra(EXTRA_REFERER)
        val headers = HashMap<String, String>()
        // Nur der Ursprung als Referer (wie Browser-Standard "strict-origin"), nie die volle Adresse
        if (referer != null && UrlTools.isHttp(referer)) {
            val host = UrlTools.hostOf(referer)
            if (host != null) headers["Referer"] = "${UrlTools.schemeOf(referer)}://$host/"
        }
        // Bewusst KEINE Cookies: der Player folgt Weiterleitungen selbst, Cookies könnten so an
        // fremde Server gelangen. Nur User-Agent und Referer-Ursprung werden gesendet.
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(headers)
        if (ua != null) http.setUserAgent(ua)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        player = p
        playerView.player = p
        p.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                App.instance.diag.add(DiagCat.MEDIA_ERROR, "Eingebauter Player: ${e.errorCodeName} – ${UrlTools.sanitizeForLog(url)}")
                error.text = "Wiedergabe nicht möglich (${e.errorCodeName}).\nZurück = Webseite"
                error.visibility = View.VISIBLE
            }
        })
        val mime = intent.getStringExtra(EXTRA_MIME)?.lowercase()
        val ext = RequestTypes.extension(url)
        val item = MediaItem.Builder().setUri(Uri.parse(url)).apply {
            when {
                ext == "m3u8" || mime == "application/x-mpegurl" || mime == "application/vnd.apple.mpegurl" ->
                    setMimeType(MimeTypes.APPLICATION_M3U8)
                ext == "mpd" || mime == "application/dash+xml" -> setMimeType(MimeTypes.APPLICATION_MPD)
            }
        }.build()
        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (!playerView.isControllerFullyVisible && event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
        ) {
            player?.let { it.playWhenReady = !it.playWhenReady }
            playerView.showController()
            return true
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_REFERER = "referer"
        private const val EXTRA_UA = "ua"

        fun start(a: Activity, url: String, mime: String?, referer: String?, ua: String?) {
            a.startActivity(
                Intent(a, PlayerActivity::class.java)
                    .putExtra(EXTRA_URL, url).putExtra(EXTRA_MIME, mime)
                    .putExtra(EXTRA_REFERER, referer).putExtra(EXTRA_UA, ua),
            )
        }
    }
}
