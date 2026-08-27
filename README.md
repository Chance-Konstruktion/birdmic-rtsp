# BirdMic

**Ein altes Android-Telefon als WLAN-Mikrofon für BirdNET-Pi.**

BirdMic macht aus einem ausgedienten Smartphone einen RTSP-Audioserver. Das
Mikrofon wird **unkomprimiert** ins Netz gestellt (PCM L16, 48 kHz), damit die
Artenerkennung auf dem echten Signal arbeitet und nicht auf Codec-Artefakten.

```
rtsp://192.168.1.42:8554/audio.wav
```

Gebaut für Dauerbetrieb: Foreground-Service, Wakelocks, Watchdog um die
Aufnahme, Autostart nach Neustart.

---

## Inhalt

- [Was die App kann](#was-die-app-kann)
- [Voraussetzungen](#voraussetzungen)
- [Installation](#installation)
- [Erste Einrichtung](#erste-einrichtung)
- [BirdNET-Pi anbinden](#birdnet-pi-anbinden)
- [Alle Einstellungen](#alle-einstellungen)
- [Die Anzeigen verstehen](#die-anzeigen-verstehen)
- [Welches Mikrofon wird benutzt?](#welches-mikrofon-wird-benutzt)
- [Checkliste für 24/7-Betrieb](#checkliste-für-247-betrieb)
- [Qualität und Stromverbrauch](#qualität-und-stromverbrauch)
- [Fehlersuche](#fehlersuche)
- [Technische Details](#technische-details)
- [Selbst bauen](#selbst-bauen)
- [Zugangsschutz, mDNS, Logbuch](#zugangsschutz-mdns-logbuch)
- [Weboberfläche](#weboberfläche)
- [Neue Versionen](#neue-versionen)
- [Mehrere Geräte gleichzeitig mitschneiden](#mehrere-geräte-gleichzeitig-mitschneiden)
- [Bekannte Grenzen](#bekannte-grenzen)
- [Aufbau des Codes](#aufbau-des-codes)
- [Ideen für später](#ideen-für-später)
- [Lizenz](#lizenz)

Änderungen je Version: [CHANGELOG.md](CHANGELOG.md)

---

## Was die App kann

### Audio

- **Unkomprimiertes PCM L16**, 48 kHz, 16 Bit — verlustfrei, kein Codec dazwischen
- **Mono oder Stereo** (Stereo mit automatischem Rückfall auf Mono, wenn das
  Gerät es nicht kann)
- **Auswahl der Aufnahmequelle** aus sechs Varianten — das ist der einzige Weg,
  unter Android zu beeinflussen, *welches* physische Mikrofon der Treiber liefert
- **Auswahl des Eingabegeräts** (intern, USB, Klinke, Bluetooth)
- **Externe Mikrofone werden automatisch bevorzugt**, wenn eines angesteckt ist
- **AGC, Rauschunterdrückung und Echounterdrückung werden aktiv abgeschaltet** —
  Androids Sprachoptimierung pumpt bei leisen Rufen und filtert breitbandige
  Anteile weg, also genau die Information, an der BirdNET Arten unterscheidet
- **Digitaler Vorverstärker** von 0,1× bis 20×
- **Hochpassfilter** gegen Wind, Verkehr und Gebäudevibration — 80 bis 300 Hz,
  24 dB pro Oktave (zwei kaskadierte Butterworth-Biquads)
- **Wählbare Abtastrate** von 16 bis 48 kHz
- **Anzeige des tatsächlich aktiven Mikrofons** inklusive Position
  (`unten`, `oben`, `Rückseite`) — ab Android 9

### Netzwerk

- **RTSP-1.0-Server** mit `OPTIONS`, `DESCRIBE`, `SETUP`, `PLAY`, `PAUSE`,
  `TEARDOWN`, `GET_PARAMETER`
- **RTP über UDP und über TCP-interleaved** — funktioniert mit
  `ffmpeg -rtsp_transport tcp` und mit UDP-Standardclients
- **RTCP Sender Reports** alle 5 Sekunden für die Zeitreferenz
- **Mehrere Empfänger gleichzeitig**, jeder mit eigener Session
- **Frei wählbarer Port und Pfad**
- **Digest-Authentifizierung** (RFC 2617) — optional, überträgt nur einen Hash
- **mDNS-Anmeldung** als `_rtsp._tcp`, damit der Stream im Netz auffindbar ist
- **Timeout für tote UDP-Sessions** — optional, weil UDP kein Abbruchsignal kennt
- **TCP-Keepalive** erkennt Gegenstellen, die ohne Abmeldung verschwinden
- Kompatibel mit ffmpeg, ffplay, VLC, GStreamer, BirdNET-Pi, BirdNET-Go

### Dauerbetrieb

- **Foreground-Service** mit Typ `microphone` — ohne das schaltet Android die
  Aufnahme im Hintergrund stumm
- **Watchdog um die Aufnahme**: nimmt ein anderer Prozess das Mikrofon weg oder
  steigt der Audiotreiber aus, wird `AudioRecord` mit wachsender Wartezeit
  (1 s → max. 30 s) neu geöffnet statt still zu verstummen
- **Zähler der Neustarts** als Stabilitätsindikator
- **Partial-Wakelock und WLAN-High-Performance-Lock**, damit CPU und Funkchip
  nicht in den Standby gehen
- **Autostart nach Neustart** (optional, **auf Android 14+ eingeschränkt**, siehe unten)
- **Direkter Weg in die Akku-Optimierung** per Knopf in der App
- **Akkutemperatur-Überwachung** mit Warnschwelle — ein Altgerät, das dauerhaft
  am Kabel hängt, bläht sonst unbemerkt den Akku auf
- **Warnung, wenn das Gerät nicht am Strom hängt**
- **Logbuch mit Zeitstempeln** über die letzten 100 Ereignisse, übersteht einen
  App-Neustart — verrät, welcher Mechanismus um drei Uhr nachts zuschlägt
- **Kachel in den Schnelleinstellungen** zum Starten und Stoppen
- **Keine Speicherallokation im Audiopfad** — ein wiederverwendeter Puffer statt
  Dauermüll für den Garbage Collector

### Oberfläche

- Live-**Pegelanzeige** mit Wurzelskalierung (spreizt die leisen Bereiche, wo
  Vogelrufe liegen)
- **Spitzenpegel mit Nachlauf und Zähler für Übersteuerung** — bei manueller
  Verstärkung merkt man Clipping sonst überhaupt nicht
- **Live-Spektrogramm** zum Prüfen der Aufstellung, ohne zum Pi zu wechseln
- **Anzahl und Adressen der verbundenen Empfänger** samt Transportart
- **Laufzeit, übertragene Datenmenge, Akkuzustand**
- **Diagnosebericht als Textdatei sichern** — Gerät, Einstellungen, Zustand und
  vollständiges Logbuch in einer Datei, die man verschicken kann
- **Optionale Weboberfläche** — Status, Logbuch und Spektrogramm im Browser
  eines anderen Geräts, ohne das Telefon anzufassen. Ab Werk aus.
- RTSP-Adresse anzeigen und **in die Zwischenablage kopieren**
- Dauerhafte **Benachrichtigung** mit Empfängerzahl, Warnungen und Stopp-Knopf

---

## Voraussetzungen

| | |
| --- | --- |
| Android | 8.0 (API 26) bis 14, erprobt auf 10 und 11 |
| Netzwerk | WLAN, dasselbe Netz wie der Pi |
| Strom | Dauerhaft am Ladekabel |
| Empfänger | BirdNET-Pi, BirdNET-Go oder irgendein RTSP-Client |

### Geräte im Test

Stand 19.08.2026 läuft ein Vergleichstest mit drei Geräten an derselben
Position, synchron aufgenommen:

| Gerät | Android | Anmerkung |
| --- | --- | --- |
| HUAWEI POT-LX1 | 10 | |
| HMD Global Nokia 2.3 | 11 | |
| Samsung Galaxy S7 | 10 | Custom-ROM auf S9-Firmware, zweiter Akku — genau der Fall, für den die App gedacht ist |

Alle drei Angaben sind aus der App abgelesen (Diagnosebericht bzw. Kopfzeile der
Weboberfläche), nicht aus Datenblättern abgeleitet.

Damit deckt das Testfeld **zwei** Android-Versionen ab, nicht drei — dafür ist
die 10 doppelt vertreten, einmal ab Werk und einmal als Custom-ROM auf
Neun-Jahre-alter Hardware.

Die Ergebnisse (Neustart-Zähler, Übersteuerung, welche Aufnahmequelle das untere
Mikrofon trifft) kommen hier hinein, sobald sie da sind. Aufgenommen und
verglichen wird mit den Programmen unter
[Mehrere Geräte gleichzeitig mitschneiden](#mehrere-geräte-gleichzeitig-mitschneiden). Ein Custom-ROM im
Testfeld ist ausdrücklich erwünscht: Wenn die App dort sauber läuft, sagt das
mehr über ihre Robustheit als drei Werksgeräte.

### Warum nicht älter als Android 8?

Die Untergrenze ist keine Bequemlichkeit, sondern der Punkt, ab dem die App
überhaupt das tun darf, was sie tut:

| Ab | Was erst dort existiert |
| --- | --- |
| 5.0 (21) | `AudioRecord.setPreferredDevice` — ohne das keine Geräteauswahl |
| 6.0 (23) | Laufzeitberechtigungen; darunter gibt es den Dialog gar nicht |
| 8.0 (26) | **Benachrichtigungskanäle und `startForegroundService`** |
| 9.0 (28) | `getActiveMicrophones()`, `AudioDeviceInfo.getAddress()` |

Der entscheidende Sprung ist 8.0. Genau dort hat Android den Hintergrundbetrieb
zugedreht: Ohne Vordergrunddienst mit Kanal wird eine App nach Minuten
eingefroren — und ein Mikrofonstream, der nach zehn Minuten verstummt, ist
nutzlos. Auf Android 4.3 wäre der Code zwar lauffähig zu bekommen, aber jede
Zeile mit Vordergrunddienst, Berechtigungsabfrage und Geräteauswahl bräuchte
einen zweiten Weg, den niemand testen kann. Der Aufwand steht in keinem
Verhältnis: Geräte mit 4.3 sind von 2013, ihr WLAN-Chip schafft 24/7 keinen
Dauerstream, und ihr Akku ist zwölf Jahre alt.

**Nach oben** ist es umgekehrt unkritisch: `targetSdk 34` (Android 14) ist die
strengste Stufe, gegen die die App gebaut ist, und die eine echte Einschränkung
dort — dass ein Mikrofon-Vordergrunddienst nicht mehr aus `BOOT_COMPLETED`
starten darf — ist behandelt (siehe [Autostart und Android 14](#autostart-und-android-14)).
Auf Android 15 und 16 läuft eine App mit `targetSdk 34` weiter; sie bekommt nur
die neuen Regeln noch nicht aufgezwungen. Wenn dort einmal getestet wird, ist
das Anheben des `targetSdk` eine Zeile plus ein Durchgang durch die
Verhaltensänderungen — kein Umbau.

---

## Installation

### Fertige APK

`BirdMic-2.6.apk` im Wurzelverzeichnis ist ein Release-Build, signiert mit dem
Debug-Schlüssel (APK Signature Scheme v2).

Im Wurzelverzeichnis liegen nur zwei APKs: die aktuelle und die erprobte
Rückfallebene. Ältere Fassungen stehen unter [`archiv/`](archiv/) mit einer
Übersicht, was sie jeweils bringen.

> `BirdMic-1.2.apk` liegt als **Rückfallebene** daneben. Version 2.0 bringt viele
> neue Funktionen, die nicht auf einem Gerät im Dauerbetrieb erprobt sind — alle
> Neuerungen sind abschaltbar und stehen im Auslieferungszustand auf „aus", so
> dass 2.0 sich wie 1.2 verhält. Falls doch etwas klemmt, ist 1.2 der Weg zurück.

1. APK aufs Telefon kopieren und antippen
2. „Installation aus unbekannten Quellen" für den Dateimanager erlauben
3. Installieren

Oder per ADB:

```bash
adb install -r BirdMic-2.6.apk
```

> Debug-signiert reicht für ein Gerät im eigenen WLAN. Für den Play Store
> bräuchte es einen eigenen Keystore.

---

## Erste Einrichtung

1. **App öffnen**, Mikrofonberechtigung erteilen.
2. **„Akku-Optimierung ausschalten"** antippen und bestätigen. Das ist mit
   Abstand der häufigste Grund für nächtliche Abbrüche — nicht überspringen.
3. **Aufnahmequelle wählen.** Standard ist „Automatisch"; für das untere
   Mikrofon „MIC (Hauptmikrofon)" nehmen. Siehe
   [Welches Mikrofon wird benutzt?](#welches-mikrofon-wird-benutzt)
4. **„Stream starten".** Die angezeigte Adresse ist die, die der Pi braucht.
5. **Vom Pi aus testen, bevor** du BirdNET umkonfigurierst:

   ```bash
   ffmpeg -rtsp_transport tcp -i rtsp://HANDY-IP:8554/audio.wav -t 10 test.wav
   ```

   Kommt eine brauchbare 10-Sekunden-Datei heraus, passt alles. Zum Reinhören:

   ```bash
   ffplay -rtsp_transport tcp -fflags nobuffer rtsp://HANDY-IP:8554/audio.wav
   ```

6. **Feste IP vergeben** — DHCP-Reservierung im Router. Ohne das zeigt die
   Konfiguration auf dem Pi irgendwann ins Leere.
   Fritzbox: *Heimnetz → Netzwerk → Gerät → „Immer die gleiche IPv4-Adresse
   zuweisen"*.
7. **Bildschirm ausschalten**, Telefon ans Ladekabel, fertig.

---

## BirdNET-Pi anbinden

In der Weboberfläche unter **Settings → Advanced** das Feld `RTSP_STREAM` setzen:

```
rtsp://192.168.1.42:8554/audio.wav
```

Danach die Dienste neu starten — über *Tools → Restart Services* oder:

```bash
sudo systemctl restart birdnet_recording.service
```

### Extraction Audio Format

Diese Einstellung hat **nichts** mit BirdMic zu tun und **keinen Einfluss auf die
Erkennung** — BirdNET analysiert den Stream, bevor etwas gespeichert wird. Sie
betrifft nur die Belegschnipsel auf der Platte.

| Format | 3-Sekunden-Schnipsel | Verlustfrei |
| --- | --- | --- |
| WAV | ~290 KB | ✅ |
| **FLAC** | **~150 KB** | **✅** |
| MP3 | ~50 KB | ❌ |
| Opus | ~25 KB | ❌ |

**Empfehlung: FLAC.** Verlustfrei wie WAV, halb so groß. WAV bringt gegenüber
FLAC exakt nichts und füllt die SD-Karte doppelt so schnell — bei 1000
Erkennungen am Tag sind das rund 9 GB im Monat statt 4. Bei kleiner Karte
(≤ 32 GB) ist MP3 die vernünftige Wahl.

---

## Alle Einstellungen

Die Auswahlfelder sind gesperrt, solange der Stream läuft. **Erst stoppen,
umstellen, neu starten.**

| Einstellung | Standard | Bedeutung |
| --- | --- | --- |
| **Aufnahmequelle** | Automatisch | Bestimmt indirekt, welches physische Mikrofon benutzt wird. Sechs Varianten, siehe unten. |
| **Eingabegerät** | Automatisch | Wählt ein konkretes Gerät. Zuverlässig bei USB/Klinke; die internen Mikrofone melden auf den meisten Telefonen nur *einen* gemeinsamen Eintrag, dann hilft nur die Quellenauswahl. |
| **Kanäle** | Mono | Stereo verdoppelt die Bandbreite. BirdNET analysiert ohnehin mono — Empfehlung: Mono lassen. |
| **Port** | 8554 | 1024–65535. |
| **Pfad** | `audio.wav` | Rein kosmetisch. Der Server liefert auf **jedem** Pfad denselben Track. Der Name sagt nichts über das Format aus. |
| **Verstärkung** | 1.0 | Digitaler Vorverstärker, 0.1–20. Über 1.0 nur bei sehr leiser Umgebung, sonst droht Übersteuerung. |
| **Abtastrate** | 48000 Hz | 48, 44,1, 32, 22,05 oder 16 kHz. **48 kHz lassen** — darunter fehlen die oberen Frequenzen, an denen BirdNET Arten unterscheidet. Nur ändern, wenn das Gerät 48 kHz nicht kann. |
| **Hochpassfilter** | aus | 80–300 Hz, 24 dB/Oktave. Siehe unten. |
| **mDNS** | aus | Meldet den Stream als `_rtsp._tcp` im Netz an. |
| **Weboberfläche** | aus | Nur-Lese-Ansicht im Browser. Siehe unten. |
| **Port der Weboberfläche** | 8080 | Muss ein anderer sein als der RTSP-Port. |
| **UDP-Timeout** | 0 (aus) | Sekunden ohne Lebenszeichen, nach denen eine UDP-Session verworfen wird. Betrifft **nur** UDP; TCP-Verbindungen werden über den Verbindungsabbruch erkannt. |
| **Max. Empfänger** | 4 | Obergrenze gleichzeitiger Empfänger, 0 = unbegrenzt. Bei Überschreitung weicht der älteste. Ab 2.5 zusätzlich beim Verbindungsaufbau geprüft, nicht erst bei `PLAY`. |
| **Max. je Adresse** | 2 | Dasselbe pro Absender-IP. Schützt vor Empfängern, die sich bei einem Verbindungsfehler vervielfachen. |
| **Zugangsschutz** | aus | Benutzername + Passwort für Digest-Auth. Beide leer = kein Schutz. |
| **Autostart** | ein | Startet nach Neustart wieder — aber nur, wenn der Stream beim Ausschalten lief. |
| **Akkuwarnung** | 40 °C | Warnschwelle der Akkutemperatur, 0 = aus. |
| **Nach neuer Version sehen** | an | Fragt beim Öffnen das Projekt-GitLab, höchstens alle sechs Stunden. Meldet nur — heruntergeladen und installiert wird von Hand. |
| **Immer dunkel** | aus | Echtes AMOLED-Schwarz statt System-Dunkelmodus. **Spart keinen Strom** (im Betrieb ist der Bildschirm aus) — hilft beim Ablesen in der Dämmerung und gegen Einbrennen. |

### Hochpassfilter

Unterhalb von etwa 150 Hz sitzt bei einer Außenaufnahme fast nur Störung: Wind,
Verkehr, Gebäudevibration. Die frisst Dynamikreserve und bringt für die
Erkennung nichts — die tiefste heimische Vogelstimme (Uhu) liegt bei rund 250 Hz.

| Einstellung | Wofür |
| --- | --- |
| Aus | Standard. Nichts wird verändert. |
| 80 Hz | Sehr vorsichtig, nur gegen Netzbrummen und tiefste Vibration |
| **120–150 Hz** | **Der übliche Fall.** Wind und Verkehr weg, alle Vogelstimmen unangetastet |
| 200–300 Hz | Nur bei starker Störung. Ab 300 Hz kann es den Uhu beschneiden. |

Fang mit 150 Hz an und prüfe das Ergebnis im Spektrogramm der App: der untere
Rand sollte dunkel werden, ohne dass darüber etwas verschwindet.

### Was NICHT einstellbar ist

- **Bittiefe:** fest 16 Bit. Mehr geben Telefonmikrofone nicht her.
- **Codec:** keiner, absichtlich. Siehe
  [Qualität und Stromverbrauch](#qualität-und-stromverbrauch).

---

## Die Anzeigen verstehen

| Anzeige | Bedeutung |
| --- | --- |
| **Statuszeile** | `Gestoppt`, `Bereit – wartet auf Verbindung`, `Sendet an N Empfänger` oder eine Fehlermeldung |
| **Adresse** | Die RTSP-URL. Vor dem Start eine Vorschau, danach die echte. |
| **Detailzeile** | Benutzte Quelle, Abtastrate, Kanäle, Gerät |
| **Aktives Mikrofon** | Was das System tatsächlich meldet, mit Position. Ab Android 9. **Damit prüfst du, ob die Quellenauswahl das Gewünschte trifft** — statt es zu vermuten. |
| **mDNS angemeldet als** | Der Name, unter dem der Dienst im Netz sichtbar ist |
| **⚠ Aufnahme N× neu gestartet** | Der Watchdog musste eingreifen. Nach Tagen noch 0 heißt: stabil. Steigende Zahl heißt: es greift noch ein Energiesparmechanismus. |
| **Pegelanzeige** | RMS, wurzelskaliert. Vogelrufe liegen oft bei wenigen Prozent — deshalb die Spreizung im unteren Bereich. |
| **Spitze N %** | Höchster Ausschlag mit Nachlauf. Sollte bei lauten Ereignissen unter 100 % bleiben. |
| **Übersteuert** | Zähler abgeschnittener Samples. **Steht hier eine Zahl, ist die Verstärkung zu hoch** — das zerstört genau die lauten Nahaufnahmen, die sonst die besten Erkennungen liefern. |
| **Spektrogramm** | Live-Ansicht des Frequenzbereichs bis 16 kHz, hohe Töne oben. Zum Prüfen von Aufstellung, Störquellen und Filterwirkung. |
| **🔊 N Empfänger verbunden** | Anzahl, IP-Adresse und Transportart (TCP/UDP) jedes Empfängers |
| **Laufzeit / gesendet / Akku** | Zeit seit dem Start, übertragene Datenmenge, Temperatur und Ladezustand |
| **Logbuch** | Die letzten Ereignisse mit Zeitstempel — Start, Stopp, Verbindungen, Watchdog-Eingriffe, Warnungen |

---

## Welches Mikrofon wird benutzt?

Android hat **keine** API, mit der man „nimm das untere Mikrofon" sagen könnte.
Welches der verbauten Mikrofone der Treiber liefert, hängt an der gewählten
**AudioSource** — und diese Zuordnung legt der Hersteller im Audio-HAL fest. Die
Tabelle beschreibt die üblichen Fälle, keine Garantie:

| Quelle | Üblicherweise | Bearbeitung |
| --- | --- | --- |
| Automatisch | oberes Mikrofon | keine |
| UNPROCESSED (roh) | oberes Referenzmikrofon | keine |
| **MIC (Hauptmikrofon)** | **unteres Mikrofon** | minimal |
| **Telefonmikrofon** | **unteres Mikrofon** | AEC + Rauschfilter, oft nicht abschaltbar |
| Spracherkennung | variiert | für Sprache optimiert |
| Kameramikrofon | Rückseite | für Video optimiert |

**Für das untere Mikrofon zuerst „MIC (Hauptmikrofon)" probieren.** Das ist auf
den meisten Geräten das Telefoniermikrofon und lässt die Nachbearbeitung
weitgehend außen vor.

„Telefonmikrofon" (`VOICE_COMMUNICATION`) trifft das untere Mikrofon noch
zuverlässiger, aktiviert aber im Treiber Echounterdrückung und Rauschfilter, die
sich per API nicht immer abschalten lassen. Für Vogelaufnahmen ein spürbarer
Nachteil — nur als zweite Wahl.

**Anhaltspunkt statt Raten:** Ab Android 9 zeigt die App unter „Aktives
Mikrofon" an, was `AudioRecord.getActiveMicrophones()` meldet, inklusive
Positionsangabe. Das ist die Auskunft des Systems, nicht zwingend der
vollständige physikalische Signalweg — manche Geräte melden mehrere Kapseln oder
ungenaue Positionen. Verlass dich am Ende auf den Hörtest: nach jeder Umstellung
ein kurzer `ffmpeg`-Mitschnitt.

---

## Checkliste für 24/7-Betrieb

- [ ] Akku-Optimierung für BirdMic aus
- [ ] **Hersteller-App-Killer aus** — die ignorieren die Standardeinstellung:
      - Xiaomi/Redmi: *Autostart* erlauben **und** *Keine Einschränkungen*
      - Huawei/Honor: *App-Start manuell verwalten*
      - Samsung: *Nicht optimierte Apps* eintragen
      - OnePlus/Oppo: *Erweiterte Optimierung* aus
- [ ] Feste IP per DHCP-Reservierung
- [ ] Autostart in der App aktiviert (**auf Android 14+ nicht wirksam**, siehe unten)
- [ ] Bildschirm aus, Telefon am Ladekabel
- [ ] Nach ein paar Tagen den Neustart-Zähler und das Logbuch prüfen
- [ ] Akkuwarnung auf 40 °C stehen lassen
- [ ] Ladebegrenzung auf 80 % aktivieren, falls das Gerät das kann

### Autostart und Android 14

**Auf Android 14 und neuer funktioniert der Autostart nicht.** Das ist keine
Nachlässigkeit, sondern eine Systemeinschränkung: Apps, die auf API 34 oder höher
zielen, dürfen aus `BOOT_COMPLETED` heraus **keinen Vordergrunddienst vom Typ
`microphone`** starten. Für die Mikrofon-Typen gilt das eine Version früher als
für die übrigen. Umgehen lässt sich das nicht.

| Android | Autostart |
| --- | --- |
| 8 – 13 | funktioniert |
| 14+ | **blockiert** — die App zeigt stattdessen eine Benachrichtigung, ein Tipp darauf startet den Stream |

Für ein fest verbautes Gerät heißt das: Nach einem Stromausfall muss auf
Android 14+ einmal jemand tippen. Auf einem Altgerät mit Android 8–11 — dem
typischen Fall für dieses Projekt — greift die Einschränkung nicht.

> **Hardware-Warnung:** Ein Telefon, das dauerhaft bei 100 % am Kabel hängt und
> dabei warm wird, bekommt über Monate einen aufgeblähten Akku. Das ist bei
> Altgeräten ein realer Brandrisiko-Faktor. Nicht auf brennbarem Untergrund
> einbauen und gelegentlich nach dem Akku sehen.

---

## Qualität und Stromverbrauch

### Ist die App energiesparend?

**Nein — und das kann sie nicht sein.** Hier gibt es einen echten Zielkonflikt:

| | Verbrauch | Qualität |
| --- | --- | --- |
| L16 unkomprimiert (diese App) | ~1,5–3 W | verlustfrei |
| Opus/AAC komprimiert | ~0,8–1,5 W | Artefakte im Vogelband |

Der Verbrauch geht fast vollständig auf den **dauerhaft wachen WLAN-Chip** und
die 770 kbit/s, nicht auf die Audioverarbeitung — die kostet wenige Prozent CPU.
Ein Wakelock rund um die Uhr *ist* das Gegenteil von energiesparend.

Da das Gerät am Kabel hängt, ist das praktisch irrelevant: 2–3 W sind über ein
Jahr etwa 20 kWh, also grob 7 € Strom. Innerhalb dieser Vorgabe ist alles
Vermeidbare vermieden — kein Polling, keine Allokationen, keine Leerlauf-Wakeups.

### Ist das maximale Qualität?

Für die **Übertragung** ja: 16 Bit / 48 kHz unkomprimiert, unbearbeitete Quelle,
Effekte abgeschaltet. Das ist auch das, was die eingebaute Audio-Hardware der
allermeisten Telefone über `AudioRecord` liefert. Einzelne Geräte können mehr —
über USB-Audio-Interfaces sind 24 Bit und höhere Raten durchaus möglich; diese
App nutzt das derzeit nicht.

**Der Flaschenhals ist danach das Mikrofon selbst.** Das verbaute
MEMS-Mikrofon ist auf Sprache aus 20 cm ausgelegt: begrenzter Störabstand,
Hochpass gegen Wind, wenig Empfindlichkeit für leise Rufe aus 30 m.

**Die größte Verbesserung an diesem Aufbau ist deshalb kein Software-Parameter,
sondern ein externes Mikrofon am USB-Port.** Die App nimmt es automatisch. Ein
einfaches USB-Lavalier bringt mehr als jede Einstellung hier drin.

### Bandbreite

| Konfiguration | Datenrate |
| --- | --- |
| 48 kHz mono | ~770 kbit/s |
| 48 kHz stereo | ~1,5 Mbit/s |

Im WLAN unkritisch, aber nichts für eine Mobilfunkverbindung.

---

## Fehlersuche

| Symptom | Ursache und Lösung |
| --- | --- |
| Stream reißt nachts ab | Akku-Optimierung oder Hersteller-App-Killer. Siehe Checkliste. |
| `Connection refused` | Falsche IP, falscher Port, oder Stream nicht gestartet. Adresse in der App gegenprüfen. |
| Stream läuft, aber Stille | Falsches Mikrofon abgedeckt, oder Verstärkung zu niedrig. Pegelanzeige beobachten. |
| Verzerrter, knackender Ton | Verstärkung zu hoch. Auf 1.0 zurück. |
| Pi findet Stream nach Neustart nicht | IP hat sich geändert. DHCP-Reservierung setzen. |
| „Port 8554 belegt" | Andere App nutzt den Port. Anderen Port wählen und `RTSP_STREAM` anpassen. |
| Neustart-Zähler steigt | Eine andere App greift aufs Mikrofon zu, oder ein Energiesparmechanismus. |
| Stereo eingestellt, Anzeige sagt mono | Das Gerät kann keine Stereoaufnahme. Automatischer Rückfall, kein Fehler. |
| Falsches Mikrofon (oben statt unten) | Aufnahmequelle umstellen, siehe oben. |
| Erkennungsrate schlechter als erwartet | Meist das Mikrofon oder der Standort, nicht die Übertragung. Spektrogramme im BirdNET-Webinterface prüfen. |
| „Übersteuert" zählt hoch | Verstärkung senken. Jedes gezählte Sample ist abgeschnittenes Signal. |
| Spektrogramm bleibt schwarz | Stream läuft nicht, oder es ist tatsächlich sehr leise. Pegelanzeige gegenprüfen. |
| Unten im Spektrogramm ein durchgehender heller Balken | Rumpeln oder Wind. Hochpassfilter auf 150 Hz stellen. |
| Client bekommt 401 Unauthorized | Zugangsschutz ist aktiv. Zugangsdaten in die URL: `rtsp://benutzer:passwort@ip:8554/audio.wav` |
| Kachel reagiert nicht | Ohne Mikrofonberechtigung öffnet sie nur die App. Einmal in der App starten. |
| Weboberfläche nicht erreichbar | Läuft nur, solange der Stream läuft. Sonst: Port belegt — das steht dann im Logbuch. |
| Weboberfläche zeigt alte Werte | Seite neu laden. Die Antworten sind mit `Cache-Control: no-store` versehen, ein Zwischenspeicher davor sollte nicht greifen. |
| Spektrogramm im Browser steht still | Der Tab ist im Hintergrund — dort pausiert es absichtlich. |

### Logs lesen

```bash
adb logcat -s BirdMic/Audio BirdMic/Rtsp BirdMic/Service BirdMic/Boot
```

---

## Technische Details

### Protokoll

```
RTSP/1.0 über TCP, Port 8554 (konfigurierbar)
Nutzlast: RTP, Payload Type 96 (dynamisch)
Codec:    L16 — PCM, 16 Bit, Big Endian (RFC 3551)
Rate:     48000 Hz, 1 oder 2 Kanäle
Paket:    20 ms = 960 Frames = 1920 Byte + 12 Byte RTP-Header
RTCP:     Sender Report alle 5 s
```

### SDP

```
v=0
o=- 0 0 IN IP4 192.168.1.42
s=BirdMic
i=Android microphone, uncompressed L16
c=IN IP4 0.0.0.0
t=0 0
a=tool:BirdMic
a=type:broadcast
a=range:npt=now-
m=audio 0 RTP/AVP 96
a=rtpmap:96 L16/48000/1
a=control:trackID=0
```

### Ist das eine WAV-Datei?

**Nein.** Der Pfad `audio.wav` ist nur ein Name. Übertragen wird RTP mit
PCM-Nutzlast.

Inhaltlich ist es allerdings genau das, was in einer unkomprimierten WAV steht:
eine WAV ist ein 44-Byte-Header plus rohe PCM-Samples. Über die Leitung gehen
dieselben Samples, nur ohne Header und in Netzwerk-Byteorder (Big Endian, WAV
nutzt Little Endian). Wenn ffmpeg daraus eine `.wav` schreibt, geht **kein
einziges Bit** verloren.

Wer die Rohbytes direkt verarbeiten will:

```bash
ffmpeg -f s16be -ar 48000 -ac 1 -i rohdaten.pcm ausgabe.wav
```

---

## Selbst bauen

### Mit Android Studio

1. *Open* → diesen Ordner wählen
2. Fehlende SDK-Komponenten installieren lassen (API 34, Build-Tools 34.0.0)
3. *Build → Build Bundle(s)/APK(s) → Build APK(s)*

### Auf der Kommandozeile

Braucht ein **JDK 17** (JDK 21+ funktioniert mit Gradle 8.7 nicht) und das
Android SDK.

```bash
./gradlew assembleRelease
```

Ergebnis: `app/build/outputs/apk/release/app-release.apk`

Falls das SDK nicht am Standardort liegt, eine `local.properties` anlegen:

```properties
sdk.dir=/pfad/zum/android/sdk
```

### Versionen

| | |
| --- | --- |
| Gradle | 8.7 |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 1.9.24 |
| compileSdk / targetSdk | 34 |
| minSdk | 26 (Android 8.0) |
| JVM-Ziel | 17 |

Abhängigkeiten: nur `androidx.core:core-ktx` und `androidx.appcompat`. Kein
Netzwerk-, Medien- oder DSP-Framework von Dritten — RTSP, RTP, Hochpassfilter
und FFT sind von Hand geschrieben. Insgesamt rund 4000 Zeilen Kotlin in 21
Dateien.

### Tests und statische Prüfung

```bash
./gradlew testReleaseUnitTest lintRelease
```

**59 Unit-Tests**, laufen auf der JVM ohne Gerät oder Emulator:

| Testklasse | Prüft |
| --- | --- |
| `HighPassFilterTest` | Frequenzgang: 30 Hz gedämpft, 250 Hz erhalten, 1 kHz unverändert, Kanaltrennung bei Stereo |
| `FftTest` | Bekannte Sinustöne landen im richtigen Bin, Stille bleibt leer, Amplitude skaliert linear |
| `RtpSenderTest` | RTP-Kopf nach RFC 3550, Sequenz- und Zeitstempelfortschritt, SSRC, Überlauf bei 65535 |
| `DigestAuthTest` | Gültige und ungültige Anmeldung, Methodenbindung, fremde Nonce, Zufälligkeit |
| `PacketQueueTest` | Reihenfolge, Verwerfen des ältesten Pakets, garantiert blockierungsfreies `offer` |
| `WebServerTest` | Läuft gegen echte Sockets: Routen, 404/405, HEAD ohne Rumpf, Digest-Ablauf, Überlängen, Port wirklich freigegeben |
| `UpdateCheckTest` | Was die Versionsprüfung ablehnen muss: Unsinn statt JSON, unplausible Nummern, Dateinamen mit Pfad, Klartext-HTTP |
| `ConnectionLimitTest` | Hält den Vorfall vom 27.08.2026 fest: abgeleitete Empfängerzählung wird unter 16 Threads nie negativ, die ältesten weichen statt der neuesten |

Lint muss fehlerfrei durchlaufen, sonst bricht der Build ab. Es fängt vor allem
API-Level-Fehler ab, die der Kotlin-Compiler nicht sieht — ein Aufruf, den es
erst ab Android 9 gibt, stürzt bei `minSdk 26` sonst erst auf dem Gerät ab.

**Was die Tests nicht abdecken:** alles, was ein Gerät braucht — `AudioRecord`,
der Foreground-Service, die Oberfläche, das Zusammenspiel mit einem echten
RTSP-Client. Dafür bleibt der `ffmpeg`-Test aus der Einrichtung.

---

## Zugangsschutz, mDNS, Logbuch

### Zugangsschutz

Ohne Benutzername und Passwort ist der Stream für jeden im selben WLAN offen —
in einem getrennten IoT-Netz unkritisch, in einem geteilten Gäste-WLAN nicht.

Mit ausgefüllten Feldern verlangt der Server Digest-Authentifizierung
(RFC 2617). Der Client hängt die Zugangsdaten an die URL:

```
rtsp://benutzer:passwort@192.168.1.42:8554/audio.wav
```

Das schützt gegen Mitlauschen des Passworts. **Der Ton selbst bleibt
unverschlüsselt** — RTSP kennt kein TLS. Wer den Datenstrom mitschneidet, hört
mit. Für ein Mikrofon im eigenen Netz ist das die angemessene Stufe.

### mDNS

Meldet den Stream als `_rtsp._tcp` im Netz an. Auf dem Pi:

```bash
avahi-browse -rt _rtsp._tcp
```

Das liefert die aktuelle IP, auch wenn sie sich geändert hat — praktisch zum
Wiederfinden nach einem Adresswechsel.

**Ersetzt aber keine feste IP.** Ob zusätzlich ein Name wie `birdmic.local`
auflöst, entscheidet die Android-Implementierung des Geräts; darauf sollte man
sich nicht verlassen. `RTSP_STREAM` braucht weiterhin eine feste Adresse.

### Logbuch

Die letzten 100 Ereignisse mit Zeitstempel, übersteht App- und Geräteneustarts:
Start, Stopp, Verbindungsaufbau und -abbruch, Watchdog-Eingriffe, Akkuwarnungen.

Der Zweck: Wenn der Stream nachts abbricht, steht hier **wann** — und daraus
lässt sich ableiten, welcher Mechanismus zuschlägt. Immer zur selben Uhrzeit
deutet auf eine geplante Systemwartung, nach Stunden Inaktivität auf Doze,
unregelmäßig auf eine andere App, die das Mikrofon greift.

### Logbuch exportieren

**Logbuch als Datei sichern** schreibt einen vollständigen Diagnosebericht über
den Systemdialog dorthin, wo man ihn braucht — Downloads, Drive, USB-Stick.
Keine Speicherberechtigung nötig.

Der Bericht enthält mehr als die Anzeige in der App:

```
BirdMic 2.4 (Build 8)
Bericht vom 19.08.2026 21:47:12

--- Gerät ----------------------------------------------
Modell:      samsung SM-G930F
Produkt:     samsung/heroltexx (herolte)
Android:     10 (API 29)
Build:       QQ3A.200805.001

--- Einstellungen --------------------------------------
Port:        8554, Pfad /audio.wav
Abtastrate:  48000 Hz, mono
Quelle:      Unbearbeitet (bestes Ergebnis, ab Android 7)
...

--- Zustand --------------------------------------------
Läuft seit:  2d 07:14:52
Empfänger:   1
             192.168.1.50 (TCP)
Neustarts:   0
Übersteuert: 0 Samples

--- Logbuch (100 Einträge, älteste zuerst) -------------
17.08.2026 14:32:01  ▶ Stream gestartet auf rtsp://…
```

Genau die Angaben, nach denen man sonst dreimal nachfragt: welches Gerät,
welche Quelle, seit wann, wie oft neu gestartet. Dieselbe Datei liefert die
Weboberfläche unter `/log.txt`.

---

## Neue Versionen

Die App sieht beim Öffnen nach, ob im Projekt eine neuere Fassung liegt, und
zeigt dann oben eine Zeile mit Knopf. Der Knopf öffnet den Browser auf der APK —
**heruntergeladen und installiert wird von Hand.**

Das ist Absicht. Eine Installation ersetzt die laufende App und **beendet damit
den Stream**. Bei einem Mikrofon, das seit Tagen sendet, ist das nichts, was im
Hintergrund passieren darf.

| | |
| --- | --- |
| Gefragt wird | ausschließlich das Projekt-GitLab, anonym lesbar — kein Token in der App, kein fremder Dienst |
| Verbindung | nur HTTPS; über Klartext könnte jeder im Netz eine eigene Antwort unterschieben |
| Häufigkeit | höchstens alle sechs Stunden, nur solange die App offen ist — kein Hintergrunddienst |
| Kein Netz | kein Fehler, es erscheint einfach nichts |
| Abschaltbar | unter *Dauerbetrieb*, Voreinstellung an |

### Damit ein Update überhaupt installierbar ist

Android nimmt eine Aktualisierung nur an, wenn die neue APK **dieselbe Signatur**
trägt wie die installierte. Sonst bricht die Installation mit „App nicht
installiert" ab, ohne zu sagen warum.

Alle APKs dieses Projekts sind mit demselben Debug-Schlüssel signiert:

```bash
apksigner verify --print-certs BirdMic-2.6.apk
```

```
Signer #1 certificate SHA-256 digest: 000c22b216ca8880f210b6edf3fb569485bc7f9a6c6c966f82da219b33bcbd10
```

Der Schlüssel liegt in `~/.android/debug.keystore` auf dem Rechner, auf dem
gebaut wird, und ist bis 2056 gültig. **Diese 2,6-kB-Datei sichern** — geht sie
verloren, ist über eine bestehende Installation nie wieder ein Update möglich,
dann hilft nur deinstallieren und alles neu einrichten. Nicht ins Projekt legen:
daraus ließe sich eine APK bauen, die sich als diese hier ausgibt.

### Beim Veröffentlichen

`version.json` im Wurzelverzeichnis ist die Quelle, aus der die App liest:

```json
{
  "versionCode": 10,
  "versionName": "2.6",
  "apk": "BirdMic-2.6.apk",
  "notes": "Meldet neue Versionen in der App."
}
```

Der CI-Job `versionsdatei` wird rot, wenn diese Datei und `build.gradle.kts`
auseinanderlaufen oder die benannte APK fehlt. Ohne ihn wäre der häufigste
Fehler eine falsche Meldung bei grüner Pipeline.

---

## Weboberfläche

Optional, **ab Werk aus**. Eingeschaltet öffnet sie einen zweiten Port
(Standard 8080) und zeigt im Browser eines beliebigen Geräts im selben Netz:

```
http://192.168.1.42:8080/
```

- Status, Adresse, Pegel und Spitzenpegel
- **Live-Spektrogramm**, das im Browser weiterläuft
- Aufnahmequelle, aktives Mikrofon, Laufzeit, Datenmenge, Neustarts,
  übersteuerte Samples, Akku
- Liste der verbundenen Empfänger
- Die letzten 30 Logbucheinträge und ein Verweis auf `/log.txt`

Praktisch, wenn das Telefon am Fenster hängt, im Gartenhaus steht oder man den
Standort einregelt, während man am Rechner sitzt und das Spektrogramm beobachtet.

### Was sie bewusst nicht kann

**Nur lesen.** Der Server nimmt ausschließlich `GET` und `HEAD` an; es gibt
keine Route, die etwas ändert, startet oder stoppt. Wer die Einstellungen
ändern will, muss das Telefon in die Hand nehmen — das ist Absicht, nicht
Unfertigkeit.

| | |
| --- | --- |
| Routen | fest verdrahtet, keine Datei vom Datenträger — Pfad-Angriffe gibt es hier nicht |
| Verbindungen | höchstens 4 gleichzeitig, 5 s Lesezeitlimit, kein Keep-Alive |
| Seite | eine einzige HTML-Datei unter 6 kB, einkompiliert, ohne Nachladen — läuft ohne Internetzugang |
| Zugangsschutz | dieselben Zugangsdaten wie RTSP; der Browser fragt sie ab |
| Last | zwei Abrufe pro Sekunde je offenem Browser; im Hintergrundtab pausiert das Spektrogramm von selbst |

Fällt der Webserver aus — belegter Port etwa —, läuft der Ton unverändert
weiter; es gibt nur eine Zeile im Logbuch. Die beiden teilen sich die
Datenquelle, nicht den Lebenslauf.

### Sicherheit

Ohne Zugangsschutz sieht **jeder im selben WLAN** Status, Logbuch und
Spektrogramm. Nichts davon ist geheim, und mithören kann darüber niemand — die
Seite überträgt keinen Ton. Trotzdem: ab Werk aus, und in einem geteilten Netz
gehört der Zugangsschutz gesetzt. Der Verkehr ist unverschlüsseltes HTTP.

## Mehrere Geräte gleichzeitig mitschneiden

Im Ordner [`werkzeuge/`](werkzeuge/) liegen zwei Programme, mit denen sich
mehrere BirdMic-Streams **gleichzeitig** aufnehmen und anschließend
vergleichen lassen. Genau das braucht der Vergleichstest aus
[Geräte im Test](#geräte-im-test): drei Telefone, dieselbe Position, eine
Nacht — und danach eine Antwort auf die Frage, welches davon BirdNET das
bessere Signal liefert.

Beide brauchen **nur Python** und keine einzige Fremdbibliothek. Kein
ffmpeg, kein numpy, keine Installation.

### Aufnehmen

```bash
python werkzeuge/aufnehmen.py --suchen
```

Sucht per mDNS nach `_rtsp._tcp` und schreibt die gefundenen Geräte nach
`werkzeuge/geraete.txt`. Danach genügt:

```bash
python werkzeuge/aufnehmen.py --dauer 300
```

Alle Sitzungen werden erst vollständig aufgebaut und dann gemeinsam
gestartet, damit die Aufnahmen ohne Vorlauf nebeneinanderliegen. Je Gerät
entsteht eine `.wav` und eine `.json` in `werkzeuge/aufnahmen/<Zeitpunkt>/`.

Der Mitschnitt läuft über **TCP-interleaved**. Im WLAN ist das die
robustere Variante, und es müssen keine UDP-Ports offen sein.

### Warum nicht einfach drei Mal ffmpeg?

Wegen zweier Dinge, die BirdMic mitliefert und die ffmpeg wegwirft:

**Die Zeitreferenz.** Alle fünf Sekunden schickt die App einen RTCP Sender
Report mit der Wanduhrzeit des Telefons. Daraus lässt sich für jede Datei
ausrechnen, wann ihr erstes Sample entstanden ist. Ohne diese Zahl sind
drei gleich lange Dateien nur *ungefähr* gleichzeitig — mit ihr sind sie
wirklich vergleichbar.

**Die Lücken.** Fällt ein RTP-Paket aus, hängt ffmpeg die nächsten Daten
einfach hinterher: Die Datei wird kürzer als der Zeitraum, den sie
darstellt, und alles danach ist verschoben. `aufnehmen.py` erkennt die
Lücke am RTP-Zeitstempel, füllt sie mit Stille und schreibt die Anzahl in
den Bericht. Eine Aufnahme mit stummen Stellen ist ein Messergebnis. Eine
Aufnahme, die heimlich schrumpft, ist eine Falle.

### Auswerten

```bash
python werkzeuge/auswerten.py
```

Nimmt den neuesten Lauf und schreibt `bericht.md` in denselben Ordner:

| Abschnitt | Was darin steht |
| --- | --- |
| Was angekommen ist | Dauer, verlorene Pakete, eingefügte Stille, Übersteuerung, Taktdrift in ppm |
| Zeitachse | Versatz der Geräte zueinander — **zweimal getrennt bestimmt** |
| Pegel | Grundrauschen, Median, Spitze, Abstand zwischen Rauschen und lautestem Ereignis |
| Frequenzverteilung | Pegel je Band, mit 2–8 kHz eigens hervorgehoben |
| Lauteste Stellen | Wo im Mitschnitt etwas passiert ist, mit Zeitangabe |

Der Punkt an der Zeitachse ist, dass der Versatz **zweimal auf getrennten
Wegen** bestimmt wird: einmal aus den Uhren der Telefone, einmal aus der
Kreuzkorrelation des gemessenen Lautstärkeverlaufs. Stimmen beide überein,
sind die Aufnahmen nachweislich synchron. Weichen sie ab, gehen die
Telefonuhren auseinander — und das steht dann im Bericht, statt dass jede
weitere Zahl still auf einer falschen Annahme aufbaut.

### Ohne Telefone prüfen

```bash
python werkzeuge/attrappe.py
```

Startet drei Attrappen auf `127.0.0.1:8554` bis `8556`, die dasselbe
Protokoll sprechen wie die App: unterschiedlich laut, mit 300 ms
Startversatz, und eine verliert absichtlich jedes 50. Paket. Damit lässt
sich nachprüfen, ob die beiden Programme tun, was sie behaupten — die
300 ms müssen im Bericht in **beiden** Spalten der Zeitachse auftauchen,
einmal aus der Uhr und einmal aus dem gemessenen Pegelverlauf.

### Über den Runner statt über einen Rechner

Der GitLab-Runner steht im selben Netz wie die Telefone. Damit kann er den
Mitschnitt übernehmen — nachts, ohne dass ein Rechner laufen muss.

**Starten:** In GitLab unter *Build → Pipelines → Run pipeline* die Variable
`MITSCHNITT_DAUER` mit der Sekundenzahl setzen. Ohne diese Variable läuft der
Job nicht, auch nicht bei einem normalen Push.

**Stoppen:** gar nicht — der Job beendet sich nach `MITSCHNITT_DAUER` selbst
und lädt dann erst seine Artefakte hoch. Ein Abbruch über *Cancel* beendet
zwar die Aufnahme, aber **die Artefakte gehen dabei verloren**. Wer früher
Schluss machen will, nimmt lieber gleich eine kürzere Dauer.

**Regelmäßig:** unter *Build → Pipeline schedules* ein Zeitplan mit derselben
Variablen. Für die Morgendämmerung ist `30 4 * * *` der naheliegende Wert.

Zwei Dinge sind im Container anders als auf einem Rechner, und beide sind
im Job schon berücksichtigt:

- **mDNS geht nicht.** Multicast kommt nicht durch die Netzwerkbrücke eines
  Containers. Die Suche über `_rtsp._tcp` findet dort grundsätzlich nichts —
  egal wie gut die App sich anmeldet. Deshalb fragt `aufnehmen.py --netz`
  jede Adresse einmal auf Port 8554 und prüft per `DESCRIBE`, ob wirklich
  BirdMic antwortet. Ein offener Port allein würde auch eine Kamera als
  Mikrofon zählen.
- **Das eigene Netz des Containers ist das Docker-Netz**, nicht das WLAN.
  Ein automatisch ermitteltes Netz wäre also das falsche — darum steht
  `MITSCHNITT_NETZ` ausdrücklich im Job.

Rohaudio geht **nicht** ins Artefakt: eine Stunde mit drei Geräten sind rund
1 GB, die Grenze liegt bei 100 MB. Aufgehoben werden der Bericht, die
Messwerte je Gerät und Ausschnitte um die lautesten Stellen.

Dass die Telefone dabei mehrere Empfänger gleichzeitig bedienen, ist keine
Theorie: Während der Mitschnitt läuft, hängt BirdNET-Pi weiter am selben
Stream. Jede Sitzung bekommt eine eigene SSRC.

### Die App gegen echte Geräte prüfen

Die Tests unter `app/src/test/` prüfen die Rechenteile — Digest, FFT,
Hochpass, PacketQueue, RtpSender — ohne Gerät und ohne Netz. Genau das ist
ihre Grenze. Ob das fertige Telefon im WLAN wirklich RTSP spricht, weiß
keiner von ihnen.

```bash
python werkzeuge/pruefen.py --netz 192.168.1.0/24
```

Sucht die laufenden Geräte und prüft an jedem, was in dieser README als
Zusage steht: dass `OPTIONS` alle Methoden nennt · dass `DESCRIBE` L16 mit
Abtastrate und Kanalzahl liefert · dass `SETUP` TCP-interleaved mit Session
und Timeout beantwortet · dass die `seq` und `rtptime` aus `PLAY` **zum
ersten wirklich ankommenden Paket passen** · dass 100 Pakete ohne
Sequenzsprung und ohne Zeitstempelsprung durchlaufen · dass binnen sechs
Sekunden ein RTCP Sender Report kommt · dass die Uhr des Telefons stimmt ·
dass überhaupt Ton im Strom ist · dass zwei Empfänger gleichzeitig bedient
werden, jeder mit eigener SSRC · dass `GET_PARAMETER` die Sitzung wachhält
und `TEARDOWN` sie sauber beendet · und dass die Weboberfläche einen
gesunden Zustand meldet.

Mit `--bericht ergebnis.xml` entsteht eine JUnit-Datei. In der Pipeline
landet sie im Reiter *Tests*: je Gerät eine Suite, je Zusage ein Fall.

Über den Runner läuft das mit der Variablen `GERAETETEST` — von Hand oder
aus einem Zeitplan. Auch dieser Job ist bewusst gesperrt, solange die
Variable fehlt: Ein ausgeschaltetes Telefon wäre sonst ein roter Build ohne
einen Fehler im Code.

Ein Beispiel dafür, dass das keine Formsache ist: Beim ersten Lauf gegen die
beiden Testgeräte meldete die Prüfung *„Uhr des Geräts weicht um +2,9 s ab"*
auf dem einen und −1,9 s auf dem anderen. Zusammen 4,8 Sekunden — dieselbe
Zahl, die unabhängig davon auch der Mitschnitt-Bericht aus den Sender
Reports errechnet hatte. Zwei verschiedene Wege, dasselbe Ergebnis: die
Uhren gehen wirklich auseinander.

### Grenzen, die dabei bestehen bleiben

- Der Pegelvergleich arbeitet mit **100 ms** Auflösung. Für die Frage, ob
  die Uhren stimmen, reicht das. Für eine Richtungsbestimmung aus
  Laufzeitunterschieden reicht es **nicht**.
- Bei einem **periodischen** Geräusch — Pumpe, Verkehr, Brummen — passt der
  Lautstärkeverlauf an mehreren Stellen gleich gut. Der Bericht schreibt
  dann `mehrdeutig` hin und behauptet nichts.
- Das Grundrauschen ist nur zwischen Geräten mit **gleicher Verstärkung**
  vergleichbar. Die Spalte *Abstand Rausch/Spitze* ist davon unabhängig.
- Ein Vergleich sagt etwas über *diese* Aufnahme aus. Ein Gerät, das eine
  ruhige Nacht lang gut war, kann im Regen ausfallen.

---

## Bekannte Grenzen

- **Version 2.6 ist nicht im Dauerbetrieb erprobt.** Die geräteunabhängigen
  Teile sind durch 59 Unit-Tests abgedeckt und Lint läuft fehlerfrei, aber die
  Version ist nicht über Tage auf einem Gerät gelaufen. 1.2 hat das — deshalb
  liegt sie als Rückfallebene bei.
- **Android 8 und 9 sind ungetestet.** `minSdk` ist 26, und die
  Fallunterscheidungen für diese Versionen sind vorhanden (Lint erzwingt sie),
  aber gelaufen ist die App bislang nur auf 10 und 11. Wer ein Gerät mit 8 oder
  9 hat: Rückmeldung willkommen. Auf 8.x bleibt die Anzeige „Aktives Mikrofon"
  systembedingt leer — `getActiveMicrophones()` gibt es erst ab 9.
- **Android 12 bis 14 sind ebenfalls ungetestet.** Der Autostart ab 14 ist
  behandelt und meldet sich, statt still auszufallen — aber auf einem Gerät
  dieser Generation ist die App noch nicht gelaufen.
- **Kein Session-Zustandsautomat.** Ungewöhnliche Methodenfolgen (`PLAY` vor
  `SETUP`, `PAUSE` ohne `PLAY`) werden abgefangen, aber nicht systematisch über
  einen expliziten Zustand geprüft.
- **Nur IPv4.** Die App ermittelt und meldet ausschließlich IPv4-Adressen.
- **Autostart ab Android 14 blockiert** (siehe oben) — Systemeinschränkung.
- **Der Ton ist unverschlüsselt**, auch mit Zugangsschutz.
- **Das Spektrogramm ist eine Stichprobe**, keine lückenlose Darstellung: die
  Anzeige holt sich etwa achtmal pro Sekunde die letzten 1024 Samples. Zum
  Prüfen von Aufstellung, Störquellen und Filterwirkung reicht das; als
  Messwerkzeug taugt es nicht.
- **Der UDP-Timeout verlässt sich auf Keepalives.** Clients, die keine schicken,
  würden nach Ablauf getrennt. Deshalb ist er standardmäßig aus, und ffmpeg —
  das Keepalives schickt und ohnehin TCP nutzt — ist davon nicht betroffen.
- **Nur ein Track**, nur Audio, kein Video — bewusst.
- **Mono ist die sinnvolle Wahl.** Das eingebaute Mikrofon eines Telefons taugt
  nicht für Richtwirkung, und BirdNET wertet keine Stereoinformation aus.
- **`WIFI_MODE_FULL_HIGH_PERF` ist ab Android 10 veraltet.** Auf Android 8–11
  wirkt es wie beabsichtigt; auf neueren Versionen wirkungslos, aber nicht
  schädlich. Der Build warnt entsprechend.
- **Bei WLAN-Aussetzern** bricht die Verbindung ab. ffmpeg auf dem Pi baut sie
  selbstständig neu auf, in den Aufnahmen entsteht eine Lücke.
- **IPv6 wird nicht angeboten** — die App meldet nur IPv4-Adressen.
- **Nicht auf einem Gerät automatisiert getestet.** Es gibt keine Unit- oder
  Instrumentationstests; die Verifikation lief über ffmpeg gegen ein echtes
  Telefon.

---

## Aufbau des Codes

| Datei | Aufgabe |
| --- | --- |
| [`AudioEngine.kt`](app/src/main/java/net/birdmic/rtsp/AudioEngine.kt) | `AudioRecord`-Thread, Watchdog, 20-ms-Blöcke, Little→Big-Endian, Pegel, Effektabschaltung |
| [`AudioDevices.kt`](app/src/main/java/net/birdmic/rtsp/AudioDevices.kt) | Aufzählung und stabile Schlüssel für Eingabegeräte, Quellenbeschreibungen, Positionsdeutung |
| [`HighPassFilter.kt`](app/src/main/java/net/birdmic/rtsp/HighPassFilter.kt) | Zwei kaskadierte Butterworth-Biquads, 24 dB/Oktave |
| [`Fft.kt`](app/src/main/java/net/birdmic/rtsp/Fft.kt) | Radix-2-FFT mit Hann-Fenster für die Anzeige |
| [`SpectrogramView.kt`](app/src/main/java/net/birdmic/rtsp/SpectrogramView.kt) | Rollendes Spektrogramm auf einer Ringpuffer-Bitmap |
| [`DigestAuth.kt`](app/src/main/java/net/birdmic/rtsp/DigestAuth.kt) | RTSP-Digest-Authentifizierung nach RFC 2617 |
| [`MdnsAdvertiser.kt`](app/src/main/java/net/birdmic/rtsp/MdnsAdvertiser.kt) | Dienstanmeldung per `NsdManager` |
| [`BatteryWatch.kt`](app/src/main/java/net/birdmic/rtsp/BatteryWatch.kt) | Temperatur, Ladezustand, Warnschwelle mit Hysterese |
| [`EventLog.kt`](app/src/main/java/net/birdmic/rtsp/EventLog.kt) | Ringpuffer mit Zeitstempeln, überlebt App-Neustarts |
| [`PacketQueue.kt`](app/src/main/java/net/birdmic/rtsp/PacketQueue.kt) | Ringpuffer je Client, entkoppelt Aufnahme und Netzwerk |
| [`QuickTileService.kt`](app/src/main/java/net/birdmic/rtsp/QuickTileService.kt) | Kachel in den Schnelleinstellungen |
| [`RtpSender.kt`](app/src/main/java/net/birdmic/rtsp/RtpSender.kt) | RTP-Header, RTCP Sender Reports, UDP- und TCP-interleaved-Transport |
| [`RtspServer.kt`](app/src/main/java/net/birdmic/rtsp/RtspServer.kt) | RTSP-Methoden, SDP, eine Session pro Client |
| [`RtspService.kt`](app/src/main/java/net/birdmic/rtsp/RtspService.kt) | Foreground-Service, Wake- und WLAN-Locks, Benachrichtigung, Zustand für die UI |
| [`MainActivity.kt`](app/src/main/java/net/birdmic/rtsp/MainActivity.kt) | Oberfläche, Berechtigungen, Einstellungen |
| [`Prefs.kt`](app/src/main/java/net/birdmic/rtsp/Prefs.kt) | Einstellungen, IPv4-Ermittlung |
| [`BootReceiver.kt`](app/src/main/java/net/birdmic/rtsp/BootReceiver.kt) | Autostart nach Neustart |

### Berechtigungen und warum

| Berechtigung | Zweck |
| --- | --- |
| `RECORD_AUDIO` | Das Mikrofon, offensichtlich |
| `INTERNET` | Serversocket öffnen |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | WLAN-Lock |
| `ACCESS_NETWORK_STATE` | Eigene IP ermitteln |
| `WAKE_LOCK` | CPU wach halten |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | Aufnahme im Hintergrund |
| `RECEIVE_BOOT_COMPLETED` | Autostart |
| `POST_NOTIFICATIONS` | Statusbenachrichtigung ab Android 13 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Der Ein-Tipp-Dialog für den Dauerbetrieb |

Die App sendet **keine Daten nach außen**, hat keine Analytics und keine
Werbe-SDKs. Der einzige Netzwerkverkehr ist der RTSP-Stream zu Clients, die sich
selbst verbinden.

---

## Ideen für später

### Opus- oder AAC-Modus für schwache Verbindungen

Für den Fall, dass der Stream mal über etwas Langsameres als WLAN laufen soll.
Bewusst nicht gebaut, weil es dem eigentlichen Zweck widerspricht: Kompression
schadet genau dem Frequenzbereich, an dem BirdNET Arten unterscheidet. Wer es
umsetzt, sollte AAC-LC nehmen — der Opus-Encoder von `MediaCodec` existiert erst
ab Android 10 und fehlt auf genau den Altgeräten, für die diese App gedacht ist.

### Lückenloses Spektrogramm

Aktuell eine Stichprobe (acht Fenster pro Sekunde). Für eine lückenlose
Darstellung müsste die Engine die FFT-Fenster selbst aufsammeln, statt dass die
Oberfläche sich den jeweils letzten Block abholt.

### Aufnahme auf die SD-Karte, wenn das Netz weg ist

Würde Lücken bei WLAN-Ausfällen schließen. Braucht aber Speicherverwaltung,
Rotation und eine Strategie fürs Nachliefern — deutlich mehr Aufwand, als es
zunächst aussieht.

### Automatische Verstärkungskalibrierung

Einmalig 30 Sekunden Umgebung messen und daraus eine Verstärkung vorschlagen.
Ausdrücklich *keine* laufende Regelung — die wäre wieder AGC, und die ist hier
bewusst abgeschaltet.

### Health-Endpunkt für die Überwachung vom Pi aus

Ein kleiner HTTP-Endpunkt `/status` mit Laufzeit, Neustarts, Clientzahl,
Clipping, Akkutemperatur. Dann könnte der Pi selbst merken, dass das Mikrofon
seit einer Stunde nichts mehr liefert, statt dass es erst in den Auswertungen
auffällt. Für einen autonomen Außenstandort die wohl nützlichste Erweiterung —
kostet aber einen zweiten Server im Prozess und damit zusätzliche Angriffsfläche.

### mDNS-TXT-Records

`path`, `codec`, `rate`, `channels` als TXT-Record mitgeben, damit ein Client
den Stream vollständig automatisch einrichten kann.

### Pegel in dBFS statt Prozent

„Peak −8,6 dBFS / RMS −39,2 dBFS" ist für jemanden, der Aufnahmen einrichtet,
deutlich aussagekräftiger als „Spitze 37 %". Daraus ließe sich auch ein
Rauschteppich-Indikator ableiten.

### RTSP-Integrationstests

Erledigt -- siehe [Die App gegen echte Geräte prüfen](#die-app-gegen-echte-geräte-prüfen).

---

## Lizenz

MIT — siehe [LICENSE](LICENSE).
