# StreamView TV

Einfacher Web-Viewer für den Amazon Fire TV Stick (Fire OS 7) mit Werbe- und Pop-up-Schutz, Diagnosemodus und vollständiger Bedienung per Fernbedienung.

* Beliebige Adressen öffnen, Favoriten, letzte Seite
* Werbefilter (EasyList, EasyList Germany, EasyPrivacy, URLhaus) – pro Website abschaltbar
* Schutz vor Pop-ups, Pop-unders und Weiterleitungen, mit Nachfrage statt stiller Fehlblockierung
* Vollbild-Video, Play/Pause und Spulen auch für eingebettete Player
* Zeiger- oder Fokussteuerung, Bildschirmtastatur für Eingaben
* „Webseite funktioniert nicht?“-Menü und Diagnosebericht

Weitere Dokumente: [Architektur & Entscheidungen](docs/ARCHITEKTUR.md) · [Testplan & Ergebnisse](docs/TESTPLAN.md)

---

## 1. APK erstellen (ohne Programmierkenntnisse, über GitHub)

1. Auf **github.com** kostenlos registrieren und ein neues Repository anlegen (Sichtbarkeit **Public**, damit der Fire TV die Datei ohne Anmeldung laden kann).
2. **Add file → Upload files**: den *Inhalt* dieses Ordners hochladen (auch den Ordner `.github`). Am PC geht das per Ziehen und Ablegen.
   *Nicht hochladen:* den separaten Ordner **StreamViewTV-Signaturschluessel**.
3. **Einmalig den Signaturschlüssel hinterlegen** (damit Updates ohne Neuinstallation funktionieren):
   Repository → **Settings → Secrets and variables → Actions → New repository secret**. Die drei Einträge stehen in `ANLEITUNG.txt` im Schlüssel-Ordner.
4. Reiter **Actions** öffnen → „APK bauen“ → **Run workflow** (läuft auch automatisch nach jedem Hochladen). Dauer ca. 5–8 Minuten.
5. Danach liegt die APK unter **Releases**. Die Adresse lautet immer:
   `https://github.com/DEIN-NAME/DEIN-REPO/releases/latest/download/StreamViewTV.apk`

Alternativ: Ordner in **Android Studio** öffnen → *Build → Build App Bundle(s)/APK(s) → Build APK(s)*.

## 2. Auf den Fire TV Stick übertragen und installieren (Fire OS 7)

1. Am Fire TV im **Appstore** die App **„Downloader“** (von AFTVnews) installieren.
2. **Einstellungen → Mein Fire TV → Entwickleroptionen → Apps unbekannter Herkunft installieren → Downloader: AN.**
   Fehlen die Entwickleroptionen: *Einstellungen → Mein Fire TV → Info* öffnen und **7-mal** auf den Gerätenamen („Fire TV Stick“) klicken.
3. Downloader öffnen, die Release-Adresse von oben eingeben → **Los** → nach dem Download **Installieren**.
4. Die Installationsdatei danach im Downloader löschen (spart Speicher).

**Starten:** Startseite → *Apps* (Kachel mit den drei Quadraten) → **StreamView TV**. Tipp: in der App-Übersicht die App markieren, ☰ drücken → „Nach vorne verschieben“.

**Aktualisieren:** Neuen Build bei GitHub erzeugen und wie in Schritt 3 erneut installieren – Favoriten und Einstellungen bleiben erhalten (sofern der Signaturschlüssel hinterlegt ist; ohne ihn muss die alte Version vorher deinstalliert werden). Die Filterlisten aktualisieren sich unabhängig davon wöchentlich selbst.

## 3. Bedienung

### Startbildschirm
* Oben die **Adresszeile**: OK drücken → Bildschirmtastatur → z. B. `example.com` → **Los**. `https://` wird automatisch ergänzt.
* **Letzte Seite**, **Favoriten** (OK lange drücken = umbenennen/entfernen), **Einstellungen**.

### Auf Webseiten

| Taste | Zeiger-Modus (Standard) | Fokus-Modus |
|---|---|---|
| Steuerkreuz | Zeiger bewegen, am Rand scrollen | zum nächsten anklickbaren Element springen; am Ende scrollen |
| OK | klicken (gedrückt halten = langes Drücken / Ziehen) | markiertes Element anklicken |
| Zurück | Vollbild verlassen → vorherige Seite → Startbildschirm | gleich |
| ☰ Menü | Menü öffnen | gleich |
| ⏯ | Video Pause/Weiter | gleich |
| ⏪ / ⏩ | 10 s (gehalten 30 s) spulen – ohne Video: Seite scrollen | gleich |

Im **Vollbild** verschwindet der Zeiger nach kurzer Zeit; dann schaltet OK zwischen Pause und Wiedergabe. Pfeiltaste drücken holt den Zeiger zurück, um Bedienelemente des Players zu nutzen.

**Menü (☰):** Adresse eingeben · Zurück/Vorwärts · Neu laden · Als Favorit speichern · Werbeblocker auf dieser Seite aus/an · **Webseite funktioniert nicht?** · Steuerung Zeiger/Fokus · Vergrößern/Verkleinern · Einstellungen · Startbildschirm.

### Wenn etwas nicht funktioniert
☰ → **„Webseite funktioniert nicht?“**
1. Webseite neu laden
2. Werbeblocker vorübergehend aus (bis zum App-Neustart)
3. Pop-ups & Weiterleitungen für diese Seite: Nachfragen / Erlauben / Blockieren
4. Desktop-/Mobilansicht wechseln
5. Cookies & Speicher dieser Seite löschen
6. Diagnose anzeigen (zeigt nur protokollierte Ursachen; Bericht speicherbar)

Fragt die App „Neues Fenster öffnen?“ oder „Zu einer anderen Seite wechseln?“, ist meist Werbung im Spiel. Startet der Film nicht, einmal **„Einmal öffnen“** wählen – war es der Player, für diese Seite **„immer erlauben“**.

**Werbeblocker ganz ausschalten:** Einstellungen → Werbeblocker: AUS.

## 4. Datenschutz

Die App selbst sendet nichts außer der Filterlisten-Aktualisierung (HTTPS). Kein Verlauf wird gespeichert oder übertragen (nur die „letzte Seite“, abschaltbar). Diagnoseberichte enthalten keine Cookies, Passwörter, Tokens oder Query-Parameter.

## 5. Lizenzen

Filterlisten: EasyList-Projekt (GPLv3 / CC BY-SA 3.0), URLhaus-Filter von malware-filter (MIT). Public Suffix List (MPL 2.0). AndroidX WebKit und Media3 (Apache 2.0).
