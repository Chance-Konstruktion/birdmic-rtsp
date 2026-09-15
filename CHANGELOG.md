# Änderungsverlauf

Format nach [Keep a Changelog](https://keepachangelog.com/de/1.1.0/).

## 2.8 — Das Logo wird zum App-Icon

Der Eisvogel mit Mikrofon aus `logo.png` ist jetzt das Symbol auf dem
Startbildschirm. Bisher stand dort eine selbstgezeichnete Mikrofonsilhouette.

Zwei Dinge waren dabei nicht offensichtlich:

- **`logo.png` hat keinen sauberen transparenten Rand.** Außerhalb des Kreises
  liegt halbdurchsichtiges Weiß. Unbehandelt gäbe das auf dunklen
  Startbildschirmen einen hellen Schleier ums Icon. Entfernt wird es durch
  Füllen von den Ecken aus — eine Farbschwelle hätte auch die weißen Flächen
  *innerhalb* gefressen: Mikrofonkorb und Vogelbrust.
- **Android maskiert adaptive Icons.** Garantiert sichtbar sind nur die inneren
  66 von 108 Einheiten. Das Motiv sitzt deshalb auf 72/108 — unter einer
  Kreismaske liegt der Logoring knapp innen, und der Schriftzug bleibt in jedem
  Fall stehen.

Dazu eine **einfarbige Ebene** für die getönten Symbole ab Android 13: Dort
färbt das System das Icon selbst ein, ein farbiges Logo wäre dafür unbrauchbar.
Diese Ebene ist der bisherige Vektor — eine schlichte Mikrofonsilhouette, genau
das, was dort gebraucht wird. Der Lint-Hinweis `MonochromeLauncherIcon` ist
damit erledigt.

`werkzeuge/icons.py` erzeugt alle Bilddateien neu aus `logo.png`. Es läuft
**nicht** im Bau — die fertigen Dateien liegen im Projekt, das Skript ist der
nachvollziehbare Weg dorthin.

### Keine APK mehr im Projekt

Ab dieser Version wird die APK nur noch ins Paketregister veröffentlicht. Im
Wurzelverzeichnis steht allein `BirdMic-1.2.apk` als erprobte Rückfallebene;
alles andere liegt unter `archiv/`. (Nachtrag 15.09.2026: 2.8 hat den
Dauerbetrieb inzwischen selbst nachgewiesen, die Rückfallebene ist nach
`archiv/` gewandert.)

**Wer noch 2.6 hat, muss einmal von Hand nachziehen.** 2.6 sucht ausschließlich
im Repository, und dort liegt nichts Neues mehr. Ab 2.7 kommt der Verweis aus
dem Paketregister und funktioniert weiter.

Der CI-Job `versionsdatei` prüft entsprechend nicht mehr, ob die Datei im
Projekt liegt, sondern ob ihr Name zur Version passt — er geht als Pfadteil in
die Downloadadresse ein.

## 2.7 — APKs kommen aus dem Paketregister

Bis 2.6 lag jede fertige APK im Repository. Das war der kürzeste Weg zu einer
installierbaren Datei, hat aber einen Preis, der erst mit der Zeit sichtbar wird:
**das Repository bestand am Ende zu 100 % aus APKs** — 17,3 MB in 7 Dateien, bei
4.000 Zeilen Quelltext. Jede Version legte dauerhaft 2,6 MB drauf, auch nach dem
Löschen, weil die Historie sie behält.

Ab jetzt: Ein Tag der Form `v2.7` löst einen CI-Job aus, der die gebaute APK ins
**generische Paketregister** legt und ein **Release** dazu anlegt.

- **Job-Artefakte wären die naheliegende Alternative — sie laufen aber ab.** Eine
  Adresse, die nach vier Wochen ins Leere zeigt, ist als Download-Verweis in
  einer App schlimmer als gar keiner. Pakete bleiben.
- **Kein `needs` im Job**, mit Absicht: So wartet die Veröffentlichung auf
  `build` *und* `test` vollständig. Mit `needs: android:assemble` wäre die APK
  schon draußen, während die Tests noch laufen.
- Der Job **vergleicht den Tag mit `build.gradle.kts`** und bricht ab, wenn beide
  nicht übereinstimmen. Sonst zeigt ein Release auf eine Datei mit anderer
  Versionsnummer, und niemand merkt es, weil beides existiert.
- Veröffentlicht wird nur auf einem Tag, nie durch einen Push. Damit ist es eine
  bewusste Handlung.

### Die CI signiert jetzt selbst — und prüft nach

Beim ersten Anlauf ging genau das schief, wovor dieser Abschnitt warnt. Die APK
aus `android:assemble` trägt eine **Wegwerfsignatur**: Der Container ist bei
jedem Job frisch, `~/.android/debug.keystore` liegt nicht im Cache, also würfelt
Gradle jedes Mal einen neuen Debug-Schlüssel. Gemessen an zwei aufeinander­
folgenden Läufen:

| Herkunft | Signatur |
| --- | --- |
| lokal gebaut, alle ausgelieferten APKs | `000c22b2…` |
| CI-Lauf 1032 | `b665a606…` |
| CI-Lauf 1033 | `4ce0f463…` |

Zwei CI-Builds können sich also nicht einmal gegenseitig aktualisieren. Das
erste v2.7 im Paketregister war damit nicht installierbar; es wurde gelöscht und
neu veröffentlicht.

Jetzt signiert der Job die gebaute APK mit dem echten Schlüssel (geschützte
CI-Variable, nur auf geschützten Tags verfügbar) und **prüft den Fingerabdruck
gegen den erwarteten Wert**. Stimmt er nicht, wird nichts veröffentlicht. Dazu
ein anonymer Testabruf des hochgeladenen Pakets — die App trägt kein Token, ein
Verweis, der eine Anmeldung verlangt, wäre wertlos.

Der Fingerabdruck steht offen in der CI-Datei. Er ist kein Geheimnis, er steckt
in jeder ausgelieferten APK; geheim ist allein die Schlüsseldatei.

### Übergang

`BirdMic-2.7.apk` liegt **zusätzlich noch im Wurzelverzeichnis**. Nötig, weil
Version 2.6 dort und nur dort sucht — ohne diese Kopie liefe die erste echte
Update-Meldung ins Leere. Ab 2.8 entfällt sie; wer dann noch 2.6 hat, muss einmal
von Hand nachziehen.

Ältere APKs stehen unter `archiv/`. Das ist Ordnung, keine Platzersparnis: Was in
der Historie liegt, bleibt dort. Nur das Wachstum hört auf.

### Geändert

- Die Update-Prüfung baut die Adresse jetzt aus Versionsname und Dateiname
  (`…/packages/generic/birdmic/2.7/BirdMic-2.7.apk`). **Beide werden geprüft** —
  der Versionsname darf nur Ziffern und Punkte enthalten, weil er als Pfadteil in
  die Adresse eingeht. Ein Test hält das fest.
- Die Stufen stehen jetzt ausdrücklich in `.gitlab-ci.yml`. Die gemeinsame
  Vorlage kennt nur `build` und `test`, und ein Job mit unbekanntem Stufennamen
  lässt die *gesamte* Pipeline vor dem ersten Job scheitern.

## 2.6 — Meldet neue Versionen

Die App sieht beim Öffnen nach, ob im Projekt eine neuere Fassung liegt, und
zeigt oben eine Zeile mit Knopf zum Herunterladen.

**Sie meldet nur.** Heruntergeladen und installiert wird über den Browser, von
Hand. Das ist Absicht, aus zwei Gründen: Zum Installieren bräuchte die App
`REQUEST_INSTALL_PACKAGES` — die heikelste Berechtigung, die Android kennt — und
der Gewinn wäre ein Fingertipp. Vor allem aber **ersetzt eine Installation die
laufende App und beendet den Dienst**. Bei einem Mikrofon, das seit Tagen sendet,
entscheidet darüber niemand außer dem Menschen davor. Ein Updater, der nachts
von selbst zuschlägt, wäre hier genau das falsche Verhalten.

### Wie es arbeitet

- Gefragt wird ausschließlich das Projekt-GitLab, anonym lesbar. **Kein Token in
  der App**, kein fremder Dienst, keine Kennzahlen nach außen.
- Nur über HTTPS. Über Klartext könnte jeder im Netz eine eigene Antwort
  unterschieben.
- Höchstens alle sechs Stunden, und nur solange die App offen ist. Kein
  Hintergrunddienst, kein Aufwachen in der Nacht.
- Der Dateiname aus der Versionsdatei wird streng geprüft: einfacher Name, Endung
  `.apk`, keine Pfade. Sonst könnte eine veränderte Versionsdatei den Knopf auf
  eine beliebige Adresse lenken — sichtbar wäre nur „Neue Version".
- Kein Netz, GitLab nicht erreichbar, Zeitüberschreitung: alles kein Fehler, es
  erscheint einfach nichts.

Abschaltbar unter *Dauerbetrieb*. Die Voreinstellung ist **an** — zusammen mit
den Empfängergrenzen die zweite Ausnahme von der Regel „alles Neue steht auf
aus". Grund: Eine Meldung, die man erst einschalten muss, erreicht genau die
Geräte nicht, um die es geht — die zwei Telefone, die man monatelang nicht
anfasst.

### Voraussetzung, die leicht übersehen wird

Ein Update über eine bestehende Installation verlangt **dieselbe Signatur**.
Alle bisherigen APKs tragen sie (`000c22b2…`, gültig bis 2056), sie stammt aus
`~/.android/debug.keystore` auf dem Rechner, auf dem gebaut wird. Geht diese
2,6-kB-Datei verloren, ist nie wieder ein Update möglich — dann hilft nur
deinstallieren und neu einrichten. **Sichern.**

### Neu

- `version.json` im Projektwurzelverzeichnis als Quelle der Wahrheit.
- **CI-Job `versionsdatei`**: wird rot, wenn `version.json` und
  `build.gradle.kts` auseinanderlaufen oder die benannte APK fehlt. Ohne den
  wäre der häufigste Fehler eine falsche Meldung bei grüner Pipeline.
- **8 weitere Unit-Tests** (jetzt 59), überwiegend darüber, was die Prüfung
  ablehnen muss: Unsinn statt JSON, unplausible Nummern, Dateinamen mit Pfad,
  Klartext-HTTP.

## 2.5 — Die Grenze aus 2.3 hat nicht gehalten

Am 27.08.2026 standen **250 Empfänger derselben Adresse** im Stream, bei einer
eingestellten Grenze von 4 insgesamt und 2 je Adresse. Der Empfängerzähler stand
auf **-219**. Beides waren Fehler in dieser App, nicht auf der Gegenseite.

### Was tatsächlich schiefging

Der Auslöser lag beim Empfänger: BirdNET-Pi konnte seine eigenen ffmpeg-Prozesse
nicht beenden (`ERROR:systemctl:kill PID … Operation not permitted`) und startete
laufend neue, während das Telefon unerreichbar war. Das ist der Grund, warum
überhaupt hunderte Verbindungen auf einmal ankamen.

Abfangen sollte das die Obergrenze aus 2.3. Sie tat es nicht, aus drei Gründen:

1. **Die Grenze griff zu spät.** Geprüft wurde erst bei `PLAY`. Bis dahin hatte
   jeder Client Verbindung, Handschlag und `SETUP` hinter sich — und `SETUP`
   belegt zwei UDP-Ports. 250 Verbindungen sind 500 Sockets, bevor irgendeine
   Grenze auch nur nachgesehen hat.

2. **Das Abräumen blockierte den, der es auslöste.** Es lief mit `join(1000)`
   je Opfer auf dem Thread des neuen Clients. Bei hundert Opfern stand der neue
   Client bis zu hundert Sekunden still — und galt in dieser Zeit selbst schon
   als spielend, also als Opfer für den nächsten. Unter Last hat sich die Grenze
   damit selbst ausgehebelt.

3. **Der Zähler hatte ein Zeitfenster.** Zwischen dem Setzen der Zustandsmarke
   und dem Hochzählen lag genau dieser blockierende Aufruf. Wer in diesem Fenster
   abgeräumt wurde, zählte herunter, ohne je hochgezählt zu haben. 304-mal, bis
   der Zähler bei -219 stand.

Bestätigt wird das durch die Datenmenge: 58,5 GB in 7,7 Tagen sind **ein**
Stream, nicht 85. Die gelisteten Empfänger waren im Zwischenzustand
steckengeblieben — als spielend geführt, aber ohne Sender.

### Behoben

- **Der Zähler ist weg.** Die Empfängerzahl wird aus den Zustandsmarken
  gezählt statt mitgeführt. Eine abgeleitete Zählung kann per Konstruktion nicht
  driften und niemals negativ werden.
- **Grenze schon beim Verbindungsaufbau**, nicht erst bei `PLAY`. Zugelassen
  wird das Doppelte der Streamgrenze an offenen Verbindungen — genug, damit ein
  Client eine Steuerverbindung halten und daneben eine neue aufbauen kann.
- **Abräumen wartet nicht mehr.** Ein geschlossener Socket und eine geschlossene
  Warteschlange genügen; die betroffenen Threads beenden sich von allein.
- **Reihenfolge bei `PLAY` umgedreht:** erst vollständig lebendig werden (Sender,
  Sink, Logbuch), dann aufräumen. Der Zwischenzustand „gilt als spielend, liefert
  aber nichts" existiert nicht mehr.

### Ebenfalls behoben: das Logbuch zeigte sieben Tage nichts

Dienst und Oberfläche hatten **je eine eigene Logbuch-Instanz**. Beide schrieben
in dieselbe Datei, aber jede hielt ihre eigene Liste im Speicher. Die Anzeige und
der Export zeigten damit den Stand vom Start der Oberfläche — im Bericht vom
27.08. war der letzte Eintrag sieben Tage alt, während der Dienst munter
weiterschrieb. Jetzt gibt es genau eine Instanz für den ganzen Prozess.

- **Gleiche Meldung direkt hintereinander wird zusammengefasst** (`[250×,
  zuletzt 15:29:41]`). Ein Empfänger in einer Reconnect-Schleife hat sonst das
  gesamte Logbuch überschrieben — und der Fehler davor, den man sehen wollte,
  war weg.
- Die Empfängerliste im Bericht fasst gleiche Adressen zusammen.

### Neu

- **5 weitere Unit-Tests** (jetzt 51), die genau dieses Fehlermuster festhalten:
  dass eine abgeleitete Zählung unter 16 Threads nie negativ wird, dass die
  ältesten weichen und nicht die neuesten, und dass unterhalb der Grenze niemand
  getrennt wird.

## 2.4 — Ferndiagnose

Beides dient demselben Zweck: herausfinden, was passiert ist, ohne danebenzustehen.

### Neu

- **Logbuch als Datei sichern.** Ein vollständiger Bericht über den
  Systemdialog — Gerät, Android-Version, alle Einstellungen, aktueller Zustand
  und das gesamte Logbuch mit Datum. Keine Speicherberechtigung nötig.

  Der Kopf der Datei ist der eigentliche Gewinn: Ein Logbuch allein beantwortet
  nicht, auf welchem Gerät, mit welcher Aufnahmequelle und seit wann. Genau
  danach fragt man sonst dreimal nach.

- **Optionale Weboberfläche** (Standard aus, Port 8080). Zeigt im Browser eines
  anderen Geräts Status, Pegel, ein live weiterlaufendes Spektrogramm, die
  Empfängerliste und die letzten 30 Logbucheinträge; `/log.txt` liefert
  denselben Bericht wie der Export.

  **Nur lesen.** Der Server nimmt ausschließlich `GET` und `HEAD` an, die
  Routenliste ist fest verdrahtet, keine Datei kommt vom Datenträger. Es gibt
  keinen Weg, darüber etwas zu ändern, zu starten oder zu stoppen — das ist
  Absicht. Höchstens vier Verbindungen gleichzeitig, 5 s Lesezeitlimit, kein
  Keep-Alive. Die Seite ist eine einzige einkompilierte HTML-Datei unter 6 kB
  und lädt nichts nach, also funktioniert sie auch ohne Internetzugang.

  Der Zugangsschutz ist derselbe wie bei RTSP; ohne ihn sieht jeder im WLAN die
  Seite. Mithören kann darüber niemand — es wird kein Ton übertragen.

  Scheitert der Start, weil der Port belegt ist, läuft der Ton unverändert
  weiter; es gibt nur eine Zeile im Logbuch.

- **11 weitere Unit-Tests** (jetzt 46). Der Webserver wird gegen echte Sockets
  geprüft: Routen, 404/405, `HEAD` ohne Rumpf, kompletter Digest-Ablauf,
  überlange Anfragezeilen, und ob der Port nach dem Stoppen wirklich frei ist.

### Anmerkung zu Android-Versionen

Unverändert `minSdk 26` / `targetSdk 34`. Das README begründet jetzt beides:
unterhalb von Android 8 gibt es keinen Vordergrunddienst mit Kanal, und ohne den
wird ein Mikrofonstream nach Minuten eingefroren. Nach oben läuft die App auf
15 und 16 weiter, bekommt deren strengere Regeln nur noch nicht aufgezwungen.

## 2.3 — Obergrenze für Empfänger

### Neu

- **Maximale Empfängerzahl**, insgesamt (Standard 4) und je Absenderadresse
  (Standard 2). 0 schaltet die jeweilige Grenze ab.

Aus einem echten Vorfall: Nachdem der Streamer kurz ohne WLAN war, hatte
BirdNET-Pi beim Wiederverbinden **13 ffmpeg-Prozesse** gestartet, ohne die alten
zu beenden. Alle 13 zogen gleichzeitig denselben Stream — rund 10 Mbit/s vom
Telefon, 100 % Last auf dem Pi. Erst ein Neustart von BirdNET-Pi hat es behoben.

Die eigentliche Ursache liegt auf der Empfängerseite. Der Stream sollte sich
davon aber nicht mitreißen lassen, deshalb jetzt die Obergrenze.

**Bei Überschreitung wird die älteste Session getrennt, nicht die neue
abgewiesen.** Andersherum würde eine hängende Verbindung den Platz dauerhaft
belegen und der Stream nie zurückkommen. Der UDP-Timeout hilft hier übrigens
nicht: die 13 Sessions waren nicht verwaist, sondern quicklebendig.

Dies sind die einzigen Voreinstellungen, die nicht auf „aus" stehen — der
Fehlerfall ist beobachtet, und unbegrenzt war genau die Einstellung, die ihn
möglich gemacht hat.

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
