# StreamView TV – Testplan, Ergebnisse, Einschränkungen

## 1. Was tatsächlich geprüft wurde (Stand der Lieferung)

| Prüfung | Ergebnis | Art |
|---|---|---|
| 38 Unit-Tests der Kernlogik (Filter-Engine, Navigations-/Pop-up-Richtlinie, URL-Eingabe, Public Suffix, Diagnose-Bereinigung) | **38/38 bestanden** | automatisiert, JVM (Kotlin 2.0.21) |
| Filter-Engine mit echten Listen (EasyList, EasyList Germany, EasyPrivacy) | 110.366 Netzwerkregeln, 13.304 Elementregeln, 943 nicht unterstützte Regeln verworfen; ≈ 0,5 s Ladezeit, ≈ 15 MB, ≈ 5 µs/Anfrage (Desktop-JVM) | automatisiert |
| Typische Werbe-URLs blockiert (doubleclick/gpt.js, adsbygoogle, google-analytics); normale Inhalte (ZDF-Skript, Wikimedia-Bild, YouTube-Player-Skript, hls.js-CDN) nicht blockiert; Seitenausnahme aus EasyList (gpt.js auf spiegel.de) respektiert | bestanden | automatisiert |
| Kompletter App-Quellcode gegen die echte Android-34-API (android.jar) typgeprüft | **fehlerfrei** – androidx.webkit/Media3 dabei gegen nachgebildete Schnittstellen geprüft | Kompilierung |
| AndroidManifest/Ressourcen-XML wohlgeformt | ja | automatisiert |
| **APK gebaut** | **nein** – in dieser Umgebung sind Google-Maven, Android SDK und Gradle-Plugin-Server gesperrt. Der Build läuft über die mitgelieferte GitHub-Actions-Konfiguration (dort laufen die Unit-Tests erneut mit echtem JUnit). | offen |
| Auf Fire-TV-Hardware getestet | **nein** | offen → Tests A–G |

Alles in den Abschnitten 2–3 ist implementiert, aber **noch nicht auf dem Gerät geprüft**.

## 2. Hardware-Tests auf deinem Fire TV Stick

Bitte nach jedem Test ✅/❌ eintragen. Bei ❌: ☰ → Webseite funktioniert nicht? → Diagnose anzeigen → **Bericht speichern** und mir den Inhalt bzw. ein Foto schicken.

### Test A – Normale Webseiten
| # | Schritt | Erwartung | Ergebnis |
|---|---|---|---|
| A1 | App starten | Startbildschirm < 3 s, Statuszeile zeigt „Werbeblocker aktiv · … Netzwerkregeln“ (kann einige Sekunden dauern) | |
| A2 | Adresszeile → OK → `wikipedia.org` → Los | Bildschirmtastatur erscheint, Seite lädt | |
| A3 | Ungültige Eingabe `hallo welt` | verständliche Fehlermeldung am Eingabefeld | |
| A4 | Zeiger bewegen, Link anklicken, Zurück | Zeiger flüssig, Link öffnet, Zurück funktioniert | |
| A5 | Suchfeld auf einer Seite anklicken, Text eingeben | Tastatur erscheint, Eingabe landet im Feld | |
| A6 | ☰ → Steuerung wechseln → Fokus; Pfeile; OK | blauer Rahmen springt, OK öffnet das markierte Element | |
| A7 | Zeiger an den unteren Rand drücken | Seite scrollt | |

### Test B – HTML5-Video
| # | Schritt | Erwartung | Ergebnis |
|---|---|---|---|
| B1 | ZDF-/ARD-Mediathek: Video starten | Wiedergabe startet | |
| B2 | Vollbild-Schaltfläche des Players | Vollbild, Zeiger verschwindet nach ~2,5 s | |
| B3 | ⏯ und OK (ohne Zeiger) | Pause/Weiter | |
| B4 | ⏩ / ⏪ | springt 10 s | |
| B5 | Zurück | Vollbild endet, Seite bleibt | |
| B6 | 30 min laufen lassen | kein Bildschirmschoner, kein Absturz | |

### Test C – Werbefenster und Weiterleitungen (Seite mit aggressiver Werbung deiner Wahl)
| # | Schritt | Erwartung | Ergebnis |
|---|---|---|---|
| C1 | Seite laden, nichts klicken | keine neuen Fenster, keine Weiterleitung | |
| C2 | Auf Play/Film klicken | entweder Film startet oder Nachfrage „Neues Fenster/Andere Seite?“ – **nie** ein stiller Wechsel zu fremder Seite | |
| C3 | Bei Nachfrage „Blockieren“ | Seite bleibt, nächster Klick startet Film | |
| C4 | Falls der Player auf fremder Domain liegt: „Einmal öffnen“ bzw. „immer erlauben“ | Player öffnet | |
| C5 | ☰ → Titelzeile | Zähler blockierter Anfragen/Fenster/Weiterleitungen steigen | |
| C6 | Nach Werbe-Pop-up: Zurück | man landet wieder auf der Ausgangsseite | |

### Test D – Unterschiedliche Player
| # | Fall | Erwartung | Ergebnis |
|---|---|---|---|
| D1 | Eingebettetes Video (z. B. YouTube-Video in einem Nachrichtenartikel) | spielt, ⏯ wirkt (Diagnose: „DOCUMENT_START_SCRIPT: ja“) | |
| D2 | iFrame-Player einer anderen Domain | spielt, wird nicht als Werbung blockiert | |
| D3 | HLS-Stream (z. B. Live-TV der Mediatheken) | spielt | |
| D4 | DASH-Stream | spielt (abhängig von WebView/Decoder) | |
| D5 | Direkter Link auf eine `.mp4`- oder `.m3u8`-Datei | Nachfrage „Im Player öffnen?“ → Media3-Player spielt | |

### Test E – YouTube
| # | Schritt | Erwartung | Ergebnis |
|---|---|---|---|
| E1 | `youtube.com` öffnen, Video wählen | lädt und spielt | |
| E2 | Vollbild, ⏯, Zurück | funktioniert | |
| E3 | Werbung | **keine Zusage**: YouTube liefert Werbung oft über dieselben Server wie Videos; ein Netzwerkfilter kann sie nicht zuverlässig entfernen. Bitte nur notieren, ob Werbung erscheint und ob das Video danach normal läuft. | |

### Test F – Stabilität bei Pop-up-lastigen Seiten
Wie Test C, zusätzlich: zehnmal hintereinander Play klicken und Zurück drücken. Erwartung: kein Absturz, keine hängende Nachfrage, App bleibt bedienbar.

### Test G – Leistung
| # | Prüfung | Erwartung | Ergebnis |
|---|---|---|---|
| G1 | Kaltstart bis Startbildschirm | < 3 s | |
| G2 | Zeit bis Statuszeile „Werbeblocker aktiv“ | notieren (erwartet: einige Sekunden) | |
| G3 | Diagnose → „RAM frei“ und „App-Heap belegt“ nach 10 min Video | notieren | |
| G4 | Reaktion des Zeigers | ohne spürbare Verzögerung | |
| G5 | Viele Apps offen, dann große Seite laden | kein App-Absturz; ggf. Hinweis „Seite wurde neu geladen“ | |

## 3. Bekannte Einschränkungen

* **Kein vollständiger EasyList-Support.** Reguläre Ausdrücke, `$csp`, `$redirect`, `$removeparam`, Scriptlets und prozedurale Selektoren werden verworfen (gezählt im Diagnosebericht). Werbung, die nur so blockierbar ist, bleibt sichtbar.
* **Ressourcentyp geschätzt.** WebView meldet den Typ einer Anfrage nicht; `third-party`/`domain=` beziehen sich auf die Hauptseite.
* **YouTube-Werbung** wird nicht zuverlässig blockiert.
* **Werbung innerhalb von Videos** (Server-seitig eingefügt) lässt sich nicht blockieren.
* **Fokusmodus** erreicht keine Elemente in fremden iFrames – dafür den Zeiger nutzen.
* **Pop-up-Erkennung ist heuristisch.** Manche Werbung kommt durch, manche legitime Weiterleitung führt zu einer Nachfrage.
* **DRM-Inhalte** (Netflix, Disney+ u. a.) funktionieren in einer WebView in der Regel nicht; die offiziellen Apps verwenden.
* **Der Media3-Player** bekommt keine Cookies (Sicherheitsentscheidung) – passwortgeschützte Direktlinks können dort scheitern.
* **WebView-Version** hängt vom Fire-OS-Stand ab und kann veraltet sein; ältere Versionen ohne Frame-Skripte steuern nur Videos im Hauptdokument.
* Filterladen dauert auf dem Stick voraussichtlich mehrere Sekunden; die erste Seite wartet höchstens 3 s darauf.
* Oberflächentexte nur auf Deutsch.

## 4. Vorschlag für die nächste Optimierungsrunde

Nach deinen Tests A–G, in dieser Reihenfolge:

1. **Diagnoseberichte auswerten:** Welche Regeln haben auf deinen Seiten Player-Anfragen blockiert? → gezielte Ausnahmen in `assets/filters/streamview-base.txt` oder Anpassung der Typschätzung.
2. **Ladezeit der Filter auf dem Gerät messen** (Diagnose: „Ladezeit … ms“). Ist sie > 3 s: vorkompilierte Binärform der Regeln im App-Speicher ablegen, damit der Start nicht jedes Mal neu parst.
3. **Pop-up-Nachfragen zählen:** Kommen zu viele Nachfragen auf seriösen Seiten, Schwellen in `NavigationPolicy` anpassen (z. B. Lade-Weiterleitungsfenster, Behandlung von Bild-Links).
4. **Speicher:** Werte aus G3 prüfen. Falls knapp: EasyPrivacy standardmäßig aus, CSS-Cache verkleinern.
5. **Optional:** native Filter-Engine (Brave adblock-rust, MPL 2.0) per JNI für vollständigere Regelunterstützung – nur wenn die Tests zeigen, dass die Teilmenge nicht reicht und genug RAM frei ist.
