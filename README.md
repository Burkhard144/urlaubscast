# UrlaubsCast

Android-App, um Urlaubsfotos und -videos vom Handy auf dem Fernseher zu zeigen – über Google Cast (Chromecast), z. B. mit dem **waipu.tv Stick (2. Generation)**.

**Installieren:** Rechts unter „Releases“ die neueste Version öffnen und die Datei `UrlaubsCast-….apk` herunterladen.
Android fragt einmal, ob Chrome bzw. die Dateien-App „unbekannte Apps“ installieren darf – erlauben.

Die APK wird bei jeder Änderung automatisch von GitHub gebaut (siehe „Aktionen“).

## Was die App kann

- **Einzelbild:** Foto oder Video in der Galerie antippen → erscheint sofort auf dem Fernseher. Mit „Weiter“ geht es zum nächsten Bild der Galerie.
- **Diashow:** Fotos und Videos gemischt in einem Ablauf. Anzeigedauer pro Foto einstellbar (2–60 Sekunden), Videos laufen bis zum Ende (oder wahlweise nur 10/20/30/60 Sekunden), danach geht es automatisch weiter.
- **Vorher festlegen:** In der Galerie lange drücken, weitere Bilder antippen oder mit „Tag“ einen ganzen Urlaubstag auswählen → „Zum Ablauf“. Reihenfolge mit ▲ ▼ ändern, mischen, als **Ablauf speichern** (z. B. „Schottland Tag 1“) und später mit einem Tipp starten.
- **Spontan:** Während die Show läuft, ein Foto antippen, einen Eintrag im Ablauf anspringen oder Bilder „Als Nächstes“ einreihen. Aus der Xiaomi-Galerie geht auch „Teilen → UrlaubsCast“.
- **Bildschirm spiegeln:** Knopf oben öffnet Google Home („Bildschirm streamen“) oder die Xiaomi-Funktion „Übertragen“.
- Läuft auch bei ausgeschaltetem Handy-Bildschirm weiter; Benachrichtigung mit Pause / Nächstes / Beenden.
- Fotos werden vor dem Senden richtig gedreht und verkleinert (Full HD oder 4K, auch HEIC-Fotos). Alles bleibt im eigenen WLAN – kein Hochladen ins Internet.

## Voraussetzungen

- Handy und waipu.tv Stick im **selben WLAN** – oder der Stick ist mit dem **Hotspot des Handys** verbunden (praktisch im Wohnmobil).
- Android 10 oder neuer mit Google-Play-Diensten.

## Tipps

- Spielt ein Video nicht ab: In der Xiaomi-Kamera unter Einstellungen → Videokodierer **H.264** wählen (H.265 kann nicht jeder Empfänger).
- Die Xiaomi-Funktion „Übertragen“ nutzt Miracast; das unterstützen viele Android-TV-Sticks nicht. Zum Spiegeln daher Google Home verwenden.

## Technik (für später)

- `app/src/main/assets/www/index.html` – Oberfläche (blaues Glas-Design, gleiche Farben wie die Mail-App). Im Browser geöffnet zeigt sie eine Vorschau mit Beispielbildern.
- `Slideshow.kt` – Ablaufsteuerung (Einzelbild, Diashow, Zeiten, Videos bis zum Ende).
- `CastControl.kt` – Verbindung zum Fernseher (Google Cast, „Default Media Receiver“).
- `MediaServer.kt` – kleiner Webserver im Handy, über den der Fernseher die Bilder/Videos abholt.
- `KeepAliveService.kt` – hält die Show bei dunklem Handy-Bildschirm am Laufen.
