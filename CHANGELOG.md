# Änderungsverlauf

Format nach [Keep a Changelog](https://keepachangelog.com/de/1.1.0/).

## 2.2 — Dunkles Thema

Inhaltlich 2.1 plus eine Oberflächenoption; die Härtungsarbeit aus 2.1 ist
unverändert enthalten.

### Neu

- **„Immer dunkel (AMOLED-Schwarz)"** — echtes `#000000` statt des üblichen
  Dunkelgraus, unabhängig vom System-Dunkelmodus. Standardmäßig aus.
- Die Titelleiste entfällt; der Statusblock oben übernimmt ihre Aufgabe.

**Kein Energiespar-Feature.** Im Dauerbetrieb ist der Bildschirm aus, dann
spart ein schwarzes Thema exakt nichts. Der Nutzen liegt woanders: Ablesen in
der Dämmerung ohne geblendet zu werden, und Schutz vor Einbrennen, falls das
Display als Statusanzeige dauerhaft läuft. Auf LCD-Geräten ändert sich am
Verbrauch ohnehin nichts, weil die Hintergrundbeleuchtung immer an ist.

## 2.1 — Härtung

Diese Version bringt keine neuen Funktionen, sondern behebt Fehler im
RTSP/RTP-Teil und entkoppelt die Aufnahme vom Netzwerk. Ausgangspunkt war ein
externes Code-Audit.

### Verhalten bei langsamen Empfängern

Die zugesicherte Semantik ist **verwerfen und aufholen**, nicht **puffern und
zurückfallen**: Ein Empfänger, der nicht mitkommt, verliert Pakete und bleibt
dabei nahe an der Gegenwart. Für die Vogelerkennung ist „live mit kleinen
Lücken" deutlich besser als „vollständig, aber Sekunden hinterher".

| Schicht | Rückstand | verwerfbar |
| --- | --- | --- |
| Ringpuffer | 5 Pakete = 100 ms | ja, ältestes zuerst |
| TCP-Sendepuffer | 16 kB ≈ 170 ms | nein — deshalb klein gehalten |
| **Summe** | **≈ 270 ms** | |

`SO_SNDBUF` ist bewusst auf 16 kB begrenzt. Der Ringpuffer kann nur verwerfen,
was er selbst hält; was schon im Kernelpuffer liegt, wird auf jeden Fall
zugestellt. Ein großer Sendepuffer würde bei einem hängenden Client sekundenlang
altes Audio ansammeln und genau den Effekt erzeugen, den der Ringpuffer
verhindern soll.

Ein Empfänger, der über rund 5 Sekunden durchgehend Pakete verliert, wird
getrennt. Java kennt keinen Schreib-Timeout, sonst würde ein hängendes `write()`
erst nach dem TCP-Timeout auffallen — Minuten später.

### Behoben

- **Ein langsamer Empfänger konnte die gesamte Aufnahme anhalten.** Der Versand
  lief synchron auf dem Aufnahme-Thread; ein blockierendes `write()` ließ den
  `AudioRecord`-Puffer überlaufen und erzeugte Lücken für *alle* Empfänger.
  Jeder Client hat jetzt eine eigene Warteschlange (5 Pakete = 100 ms) und einen
  eigenen Sender-Thread. Kommt einer nicht mit, verliert nur er Pakete.
- **Eingebettete RTP/RTCP-Rahmen beendeten die Verbindung.** Bei RTSP über TCP
  leitet `$` einen Medienrahmen ein (RFC 2326, 10.12); Clients schicken darüber
  ihre Receiver Reports. Der Parser hielt das für eine kaputte Textzeile und
  schloss die Verbindung. Solche Rahmen werden jetzt korrekt überlesen.
- **RTCP-Port war nur behauptet.** Der `SETUP`-Header meldete `server_port=N-(N+1)`,
  gebunden war aber nur ein Socket. Jetzt werden zwei aufeinanderfolgende Ports
  gebunden, RTP auf dem geraden (RFC 3550, 11).
- **Sender Report meldete den Zeitstempel des nächsten statt des letzten Pakets.**
- **Autostart scheiterte ab Android 14 stillschweigend.** Ein Mikrofon-Vordergrund­
  dienst darf dort nicht aus `BOOT_COMPLETED` starten. Statt wortlos auszufallen,
  erscheint jetzt eine Benachrichtigung, über die sich der Stream mit einem Tipp
  starten lässt.

### Sicherheit

- Digest-Nonce kommt aus `SecureRandom` statt aus `kotlin.random.Random`.
- RTSP-Parser begrenzt Zeilenlänge (4 kB), Kopfzeilenzahl (64) und Body (64 kB).
- Die Grenzen der Digest-Authentifizierung sind jetzt ehrlich dokumentiert:
  gleichbleibende Nonce, kein `qop`, unverschlüsselter Audiostrom.

### Neu

- **35 Unit-Tests** für Hochpassfilter, FFT, RTP-Paketbau, Digest und
  Warteschlange. Laufen ohne Gerät: `./gradlew testReleaseUnitTest`.
  Darunter zwei, die die Aufhol-Semantik oben festschreiben.
- Anzeige verworfener Pakete je Empfänger.

### Beim Nachprüfen des eigenen Umbaus gefunden

- **Doppeltes Herunterzählen des Client-Zählers.** `close()` kann aus vier
  Threads kommen (Verbindung, Sender, Reaper, `server.stop()`); die
  Prüfung auf `isPlaying` war nicht atomar. Jetzt `AtomicBoolean.compareAndSet`.
- **`sender` war nicht `@Volatile`**, wurde aber vom Sender-Thread gelesen und
  vom Verbindungs-Thread genullt.
- `PacketQueue.offer` kappt zu große Blöcke, statt auf dem Aufnahme-Thread
  eine Ausnahme auszulösen.

## 2.0 — Ausbau

### Neu

- Hochpassfilter 80–300 Hz, 24 dB/Oktave
- Spitzenpegel mit Nachlauf und Zähler für Übersteuerung
- Live-Spektrogramm (FFT, 1024 Punkte, Hann-Fenster)
- Wählbare Abtastrate 16–48 kHz
- Digest-Authentifizierung nach RFC 2617
- mDNS-Anmeldung als `_rtsp._tcp`
- Timeout für tote UDP-Sessions, TCP-Keepalive
- Akkutemperatur-Überwachung mit Warnschwelle
- Logbuch mit Zeitstempeln, 100 Einträge, übersteht Neustarts
- Kachel in den Schnelleinstellungen
- Laufzeit- und Datenmengenanzeige

Alle Neuerungen sind abschaltbar und stehen im Auslieferungszustand auf „aus".

### Behoben

- `AudioDeviceInfo.getAddress()` wurde ohne Versionsprüfung aufgerufen — auf
  Android 8 ein `NoSuchMethodError`. Von Lint gefunden.
- `startActivityAndCollapse(Intent)` wirft ab Android 14 eine Ausnahme.
- Kontext-Leck in der Akku-Überwachung.

## 1.2 — Mikrofonauswahl

### Neu

- Auswahl der Aufnahmequelle (sechs Varianten) — der einzige Weg, unter Android
  zu beeinflussen, welches physische Mikrofon der Treiber liefert
- Auswahl des Eingabegeräts, Stereo-Option
- Anzeige des tatsächlich aktiven Mikrofons samt Position (ab Android 9)
- Empfängeranzeige mit Adresse und Transportart

## 1.0 — Erste Fassung

- RTSP-1.0-Server mit unkomprimiertem PCM L16, 48 kHz
- RTP über UDP und TCP-interleaved, RTCP Sender Reports
- Foreground-Service, Wake- und WLAN-Locks, Watchdog um die Aufnahme
- Autostart nach Neustart
