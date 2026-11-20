package de.streamview.tv

/**
 * Alle JavaScript-Teile an einem Ort. Grundsätze:
 *  - kein addJavascriptInterface (keine Java-Objekte im Seitenkontext)
 *  - Kommunikation nur über WebMessageListener mit festen, kleinen JSON-Nachrichten
 *  - Skripte lesen keine Formularinhalte, Cookies oder Seitentexte aus
 */
object PageScripts {

    /**
     * Wird (falls unterstützt) zu Dokumentbeginn in JEDEN Frame eingefügt, auch in fremde
     * Player-iFrames. Meldet Medienzustände und nimmt CSS bzw. Steuerbefehle entgegen.
     */
    fun frameScript(bridge: String): String = """
(function(){
 if (window.__svInit) return; window.__svInit = 1;
 var B = window["$bridge"]; if (!B || !B.postMessage) return;
 function send(o){ try { B.postMessage(JSON.stringify(o)); } catch(e){} }
 var st = null;
 function css(c){ try {
   if (!st) { st = document.createElement('style'); st.setAttribute('data-sv','1'); }
   st.textContent = c;
   var p = document.head || document.documentElement;
   if (p && st.parentNode !== p) p.appendChild(st);
 } catch(e){} }
 document.addEventListener('DOMContentLoaded', function(){ if (st && !st.parentNode) css(st.textContent); });
 function isM(t){ return t && (t.tagName === 'VIDEO' || t.tagName === 'AUDIO'); }
 function pick(){
   var l = document.querySelectorAll('video,audio'), best = null, bs = -1;
   for (var i = 0; i < l.length; i++) { var m = l[i], s = 0;
     if (!m.paused && !m.ended) s += 1000;
     if (m.currentTime > 0) s += 100;
     var r = m.getBoundingClientRect(); s += Math.min(99, (r.width * r.height) / 20000);
     if (m.tagName === 'VIDEO' && r.width > 0) s += 10;
     if (s > bs) { bs = s; best = m; } }
   return best;
 }
 function act(a, v){ var m = pick(); if (!m) return;
   if (a === 'toggle') { if (m.paused) { var p = m.play(); if (p && p.catch) p.catch(function(){}); } else m.pause(); }
   else if (a === 'seek') { var t = m.currentTime + v, d = m.duration; if (isFinite(d)) t = Math.min(d - 0.5, t); m.currentTime = Math.max(0, t); }
 }
 B.onmessage = function(ev){ var m; try { m = JSON.parse(ev.data); } catch(e){ return; }
   if (!m) return;
   if (m.t === 'css') css(String(m.c || ''));
   else if (m.t === 'media') act(String(m.a), +m.v || 0);
 };
 ['playing','pause','ended'].forEach(function(n){
   document.addEventListener(n, function(e){ if (isM(e.target)) send({t:'m', s:n}); }, true); });
 document.addEventListener('loadedmetadata', function(e){ var t = e.target; if (isM(t)) send({t:'m', s:'meta', w:t.videoWidth|0, h:t.videoHeight|0}); }, true);
 document.addEventListener('error', function(e){ var t = e.target; if (isM(t) && t.error) send({t:'merr', c:t.error.code|0}); }, true);
 send({t:'hi'});
})();
""".trimIndent()

    /** Ersatz für alte WebViews ohne Frame-Skripte: nur Hauptdokument, per evaluateJavascript. */
    fun fallbackInject(cssJson: String): String = """
(function(){ try {
 var s = document.getElementById('__sv_css');
 if (!s) { s = document.createElement('style'); s.id = '__sv_css'; (document.head || document.documentElement).appendChild(s); }
 s.textContent = $cssJson;
} catch(e){} })();
""".trimIndent()

    fun fallbackMedia(action: String, value: Int): String = """
(function(){ var l = document.querySelectorAll('video,audio'), m = null;
 for (var i = 0; i < l.length; i++) { if (!l[i].paused) { m = l[i]; break; } }
 if (!m && l.length) m = l[0];
 if (!m) return 'none';
 if ('$action' === 'toggle') { if (m.paused) { var p = m.play(); if (p && p.catch) p.catch(function(){}); } else m.pause(); }
 else { m.currentTime = Math.max(0, m.currentTime + ($value)); }
 return 'ok'; })();
""".trimIndent()

    /**
     * Fokusnavigation (Modus A) im Hauptdokument: wählt das nächste sichtbare, anklickbare
     * Element in Pfeilrichtung und zeichnet einen Rahmen darum. Liefert JSON mit der Position
     * (CSS-Pixel), damit die App dort einen echten Tipp auslösen kann.
     */
    fun focusMove(dir: String): String = """
(function(dir){
 var SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,video,iframe,label[for],' +
   '[tabindex]:not([tabindex="-1"]),[role=button],[role=link],[role=menuitem],[role=tab],[role=option],[role=checkbox],[onclick]';
 var vw = window.innerWidth, vh = window.innerHeight;
 function vis(el){ var r = el.getBoundingClientRect();
   if (r.width < 4 || r.height < 4 || r.bottom < 0 || r.top > vh || r.right < 0 || r.left > vw) return null;
   var s = getComputedStyle(el); if (s.visibility === 'hidden' || s.display === 'none' || +s.opacity === 0) return null;
   return r; }
 var ring = document.getElementById('__sv_ring');
 if (!ring) { ring = document.createElement('div'); ring.id = '__sv_ring';
   ring.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;border:4px solid #4FC3F7;border-radius:6px;box-shadow:0 0 0 3px rgba(0,0,0,.6);display:none';
   (document.body || document.documentElement).appendChild(ring);
   window.addEventListener('scroll', function(){ var c = window.__svF; if (c && c.isConnected) place(c.getBoundingClientRect()); }, true); }
 function place(r){ ring.style.display = 'block'; ring.style.left = (r.left - 4) + 'px'; ring.style.top = (r.top - 4) + 'px';
   ring.style.width = (r.width + 8) + 'px'; ring.style.height = (r.height + 8) + 'px'; }
 window.__svPlace = place;
 var cur = window.__svF; if (cur && !cur.isConnected) cur = null;
 var cr = cur ? cur.getBoundingClientRect() : null;
 if (cr && (cr.bottom < 0 || cr.top > vh)) { cur = null; cr = null; }
 var els = document.querySelectorAll(SEL), best = null, bs = 1e12;
 var cx = cr ? cr.left + cr.width / 2 : 0, cy = cr ? cr.top + cr.height / 2 : 0;
 for (var i = 0; i < els.length && i < 1500; i++) { var el = els[i]; if (el === cur || el.id === '__sv_ring') continue;
   var r = vis(el); if (!r) continue;
   var x = r.left + r.width / 2, y = r.top + r.height / 2, score;
   if (!cr) { score = r.top * 4 + r.left; }
   else { var dx = x - cx, dy = y - cy;
     if (dir === 'up' && !(r.bottom <= cr.top + 2 || dy < -cr.height / 2)) continue;
     if (dir === 'down' && !(r.top >= cr.bottom - 2 || dy > cr.height / 2)) continue;
     if (dir === 'left' && !(r.right <= cr.left + 2 || dx < -cr.width / 2)) continue;
     if (dir === 'right' && !(r.left >= cr.right - 2 || dx > cr.width / 2)) continue;
     var along = (dir === 'up' || dir === 'down') ? Math.abs(dy) : Math.abs(dx);
     var perp = (dir === 'up' || dir === 'down') ? Math.abs(dx) : Math.abs(dy);
     score = along + perp * 2.5; }
   if (score < bs) { bs = score; best = el; } }
 if (!best) { if (cur) place(cr); return JSON.stringify({r:'none'}); }
 window.__svF = best;
 try { best.focus({preventScroll:true}); } catch(e){}
 var b = best.getBoundingClientRect();
 if (b.top < 40 || b.bottom > vh - 40) { best.scrollIntoView({block:'center'}); b = best.getBoundingClientRect(); }
 place(b);
 var a = best.closest ? best.closest('a[href]') : null;
 return JSON.stringify({r:'moved', x:b.left, y:b.top, w:b.width, h:b.height, vw:vw, href:a ? a.href : ''});
})('$dir')
""".trimIndent()

    /** Position des aktuell markierten Elements (für den Klick per OK). */
    const val FOCUS_RECT = """
(function(){ var e = window.__svF; if (!e || !e.isConnected) return JSON.stringify({r:'none'});
 var b = e.getBoundingClientRect(); if (window.__svPlace) window.__svPlace(b);
 var a = e.closest ? e.closest('a[href]') : null;
 return JSON.stringify({r:'ok', x:b.left, y:b.top, w:b.width, h:b.height, vw:window.innerWidth, href:a ? a.href : ''}); })()
"""

    const val FOCUS_CLEAR = """
(function(){ var r = document.getElementById('__sv_ring'); if (r) r.style.display = 'none'; window.__svF = null; })()
"""
}
