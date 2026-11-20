# StreamView TV – Architekturentscheidung (Phase 1)

## 1. Zielgerät: was sicher ist und was angenommen wird

| Merkmal | Status | Quelle/Begründung |
|---|---|---|
| Fire OS 7 = Android 9 (API 28) | **sicher** | Fire OS 7.x basiert auf Android 9 Pie |
| Build PS7715/5586 | **sicher** (deine Angabe) | Fire-OS-7-Buildnummer |
| Fire TV Stick 3. Gen. *oder* Fire TV Stick Lite | **Annahme** | Beide nutzen identische Hardware und Fire OS 7. Im Diagnosebericht steht das echte Modell (`Build.MODEL`). |
| SoC MediaTek MT8695D, 4 × 1,7 GHz, 32-Bit-ABI, GPU IMG GE8300 | **sehr wahrscheinlich** | gilt für Stick 3. Gen. und Lite |
| 1 GB RAM (DDR4) | **sehr wahrscheinlich** | wie oben; der Diagnosebericht zeigt den echten Wert |
| max. 1080p60 | **sicher** (kein 4K-Modell) | |
| WebView-Version | **unbekannt** | Fire OS nutzt die Amazon WebView (Chromium-basiert), Version hängt vom Systemstand ab → Diagnosebericht |
| Decoder (H.264, HEVC, VP9, AV1) | **unbekannt** | wird zur Laufzeit per `MediaCodecList` ermittelt und im Diagnosebericht angezeigt |

Konsequenz: 32-Bit-Gerät mit wenig RAM → nur eine WebView, keine zweite Browser-Engine, Filterlisten kompakt im Speicher, keine Hintergrunddienste.

## 2. Browser-Engine: Android WebView (Amazon WebView)

| Kriterium | Android/Amazon WebView | GeckoView |
|---|---|---|
| App-Größe | klein (Engine ist im System) | + ca. 50–80 MB pro ABI |
| RAM | geteilt mit System, ein Renderer | eigene Engine, deutlich mehr RAM |
| Werbefilter | nur eigener Filter über `shouldInterceptRequest` | uBlock Origin als WebExtension möglich (voller Filterumfang) |
| Kompatibilität | Chromium; Version abhängig vom Fire-OS-Stand | aktuelles Gecko, unabhängig vom System |
| DRM (Widevine) | über System, sofern vorhanden | eingeschränkt |

**Entscheidung: WebView.** Ausschlaggebend sind RAM (1 GB, 32 Bit) und Größe. TV Bro, das bekannteste Open-Source-Projekt dieser Art, hat GeckoView als zweite Engine ergänzt, empfiehlt aber ausdrücklich WebView für schwächere Geräte. Der Preis der Entscheidung ist ehrlich benannt: Der Filter kann nicht den vollen EasyList-Umfang abbilden (siehe 3).

**Media3/ExoPlayer** ist als *ergänzender* Player eingebaut – ausschließlich für Mediendateien, die die WebView als Download erhält (z. B. direkter Link auf `.mp4`/`.m3u8`). Es werden keine Streams aus Webseiten extrahiert, keine DRM- oder Zugriffssperren umgangen. Normale Wiedergabe läuft immer im Player der Webseite.

## 3. Werbefilter: eigene Engine für eine klar abgegrenzte ABP-Teilmenge

WebView liefert pro Anfrage nur URL, Header und „Hauptframe ja/nein“ – **keinen Ressourcentyp und keinen Frame-Ursprung**. Deshalb:

* **Unterstützt:** `||domain^`, Anker `|`, Platzhalter `*`, Trenner `^`, Ausnahmen `@@`, Optionen `third-party`/`first-party`, Typen (`script`, `image`, `stylesheet`, `xmlhttprequest`, `subdocument`, `media`, `font`, `object`, `websocket`, `ping`, `other`, `document`, `popup`, `all`), `domain=`, `important`, `match-case`, Seitenausnahmen `$document`, `$elemhide`, `$generichide`; Elementverbergung `##`, `domain##`, `#@#`.
* **Bewusst nicht unterstützt (Regel wird verworfen, nicht falsch angewendet):** reguläre Ausdrücke, `$csp`, `$redirect`, `$removeparam`, `$header`, `$badfilter` u. a., prozedurale Selektoren (`:has-text`, `:-abp-…`), Scriptlets (`+js(…)`).
* **Näherungen (dokumentiert):** Ressourcentyp wird aus Accept-Header und Dateiendung geschätzt. Ist er unbekannt, greifen nur Regeln, die Skripte/XHR betreffen – reine Bild-/CSS-/Frame-Regeln nicht (lieber eine Werbung zu viel als eine kaputte Seite). `third-party` und `domain=` beziehen sich auf die oberste Seite, nicht auf das einzelne iFrame.
* **Elementverbergung:** Standard nur *seitenspezifische* Regeln (wenige Hundert pro Seite). Die ~14.000 allgemeinen Regeln würden pro Frame großes CSS erzeugen – optional einschaltbar („mehr Arbeitsspeicher“).

Gemessen mit echten Listen (EasyList, EasyList Germany, EasyPrivacy, Stand 19.11.2026) auf einem Desktop-JVM: 110.366 Netzwerkregeln (87.665 davon reine Domains), 13.304 seitenspezifische Elementregeln, 943 nicht unterstützte Regeln verworfen; Ladezeit ≈ 0,5 s, Speicher ≈ 15 MB, Abgleich ≈ 5 µs pro Anfrage. **Auf dem Fire TV Stick ist die Ladezeit voraussichtlich mehrfach höher – noch nicht gemessen.**

Filterlisten: Mitgelieferter Stand (GitHub Actions lädt beim Bauen die aktuellen Listen) → wöchentliche Aktualisierung im Hintergrund während die App läuft, nur HTTPS, max. 12 MB pro Liste, Plausibilitätsprüfung, atomarer Austausch. Schlägt sie fehl, bleibt der letzte Stand aktiv.

Weitere Ebenen: Service-Worker-Anfragen werden ebenfalls gefiltert (`ServiceWorkerController`).

## 4. Pop-ups und Weiterleitungen

Signale, die WebView tatsächlich liefert: `hasGesture()`, `isRedirect()`, `isUserGesture` (neue Fenster) und der Hit-Test (welcher Link wurde berührt). Daraus entscheidet `NavigationPolicy` (reines Kotlin, per Unit-Test geprüft):

| Situation | Entscheidung |
|---|---|
| gleiche Website (registrierbare Domain via Public Suffix List) | erlauben |
| Ziel steht auf Filterliste (strenges Blockieren wie uBlock) | blockieren |
| Klick auf Link, Ziel = Link-Ziel | erlauben (auch fremde Player-Domain) |
| Klick, aber Ziel ≠ angeklickter Link („Klick-Hijacking“) | **nachfragen** |
| Weiterleitung ohne Klick nach Nutzerinteraktion | **nachfragen** |
| Weiterleitung beim Laden (< 4 s, noch kein Klick) | erlauben |
| Server-Weiterleitung einer bereits erlaubten Navigation | erlauben |
| Neues Fenster ohne Klick | blockieren |
| Neues Fenster nach Klick, fremde Domain, nicht der angeklickte Link | **nachfragen** |
| `intent://`, `market://` u. a. | blockiert; nur `browser_fallback_url` (http/https) wird geprüft geladen |

Nachfrage: *Blockieren* (vorausgewählt) · *Einmal öffnen* · *Für diese Seite immer erlauben* · *Für diese Seite immer blockieren*. Während eine Nachfrage offen ist, werden weitere Versuche still blockiert. Eingebettete Player-iFrames anderer Domains werden **nicht** über die Pop-up-Logik behandelt, sondern nur über die Filterliste – damit bleiben sie funktionsfähig.

Die Ziel-URL neuer Fenster wird über eine kurzlebige WebView **ohne JavaScript** ermittelt, die sofort wieder zerstört wird.

## 5. Video

* Vollbild über die offiziellen `WebChromeClient.onShowCustomView/onHideCustomView`.
* Play/Pause/Spulen: Ein kleines Skript wird per `WebViewCompat.addDocumentStartJavaScript` in **alle Frames** (auch fremde Player-iFrames) eingefügt und meldet über `addWebMessageListener` Zustände. Gesteuert wird der Frame, dessen Video gerade läuft – nicht blind das erste `<video>`-Element. Fehlen diese Funktionen in der WebView, greift ein Ersatz nur für das Hauptdokument.
* DRM (Widevine): wird pro Website nachgefragt, nie automatisch gewährt.

## 6. Fernbedienung

* **Zeiger (Standard):** Steuerkreuz bewegt den Zeiger (beschleunigend), am Rand wird per synthetischem **Mausrad** gescrollt – das scrollt das Element unter dem Zeiger, auch in iFrames. OK = echter Touch (Gedrückthalten = langes Drücken, mit Bewegung = Ziehen, z. B. Video-Fortschrittsbalken). Hover-Ereignisse für Menüs.
* **Fokus:** Pfeile springen geometrisch zum nächsten anklickbaren Element (Rahmen), OK löst einen echten Tipp in dessen Mitte aus (zählt als Nutzergeste, öffnet z. B. die Bildschirmtastatur). Inhalte *innerhalb* fremder iFrames sind so nicht erreichbar → Zeiger nutzen.

## 7. Sicherheit (Auszug)

Keine Berechtigungen außer Internet/Netzwerkstatus · kein `addJavascriptInterface` (nur WebMessageListener mit festen, kleinen Nachrichten, Objektname pro Installation zufällig) · SSL-Fehler werden immer abgebrochen · Mixed Content nie „ALWAYS_ALLOW“ · kein Datei-/Content-Zugriff · Downloads blockiert (APK nie) · keine App-Starts aus Webseiten · Berechtigungsanfragen werden abgelehnt (DRM: Nachfrage) · `onbeforeunload`-Fallen und Dialog-Spam werden unterbunden · `allowBackup=false` · keine Tracker, kein Verlaufs-Upload · Signaturschlüssel nicht im Repository.

## 8. Vergleich mit der ersten Version (TVBrowser)

Der Quellcode von TVBrowser lag vor (er wurde in dieser Sitzung erstellt) und wurde geprüft:

| Schwäche in TVBrowser | StreamView TV |
|---|---|
| Nur Domainabgleich gegen eine Hosts-Liste | ABP-Teilmengen-Engine mit EasyList/EasyPrivacy/EasyList Germany/URLhaus, Ausnahmen, Optionen, Elementverbergung; nicht Unterstütztes wird gezählt statt falsch angewendet |
| Pop-ups auf fremde Domains immer blockiert → Player auf anderer Domain konnte nicht öffnen | Unterscheidung nach Geste, angeklicktem Link, Filterliste, Seitenregel; im Zweifel Nachfrage |
| Steuerung immer des ersten `<video>` im Hauptdokument | aktives Video in allen Frames per WebMessageListener |
| Keine Diagnose | Diagnosemodus mit belegbaren Hinweisen, Decoder-/DRM-/WebView-Infos, bereinigtem Bericht |
| Signaturschlüssel mit Passwort im Repository | Schlüssel getrennt, nur über GitHub-Secrets |
| `MIXED_CONTENT_COMPATIBILITY_MODE` + keine SSL-Behandlung | SSL-Fehler explizit abgebrochen und protokolliert |
| Bildschirm immer an, Polling jede Sekunde | Bildschirm nur bei laufendem Video an, kein Polling |
| Renderer-Absturz beendete die Ansicht | WebView wird neu erstellt und die Seite neu geladen (max. 2× pro Minute) |
| Kein Seitenabhängiger Werbeblocker | pro Website aus/an, temporär aus, Pop-up-Regel, Desktop/Mobil, Cookies löschen |

Übernommen (nach Prüfung): Grundidee des Zeigers, Vollbild-Container, Medientasten.

## 9. Geprüfte Referenzen

* **TV Bro** (Open Source, `com.phlox.tvwebbrowser`): zwei Engines; WebView für schwache Geräte empfohlen. Es gab eine Sicherheitslücke beim Umgang mit externen Intents aus der WebView – StreamView startet grundsätzlich keine Intents aus Webseiten.
* **mrowser** (Android-TV-Browser): übergibt HLS an einen nativen Player; DASH-only-Seiten (z. B. YouTube) und DRM bleiben in der WebView. StreamView geht bewusst defensiver vor und nutzt den nativen Player nur für direkt abgerufene Mediendateien.
* Keine Komponente aus diesen Projekten wurde kopiert. Für eine native Filter-Engine (z. B. Brave adblock-rust per JNI) wurde abgewogen: vollständiger, aber Rust/NDK-Build, größere APK und mehr RAM – für den ersten Stand verworfen, als Option in „Nächste Schritte“.
