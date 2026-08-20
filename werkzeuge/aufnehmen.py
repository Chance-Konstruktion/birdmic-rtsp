#!/usr/bin/env python3
"""Nimmt mehrere BirdMic-Streams gleichzeitig auf.

Warum ein eigenes Programm und nicht drei Mal ffmpeg?

  1. ffmpeg ist auf diesem Rechner nicht installiert, und BirdMic sendet
     unkomprimiertes L16 -- da ist nichts zu dekodieren. Ein RTSP-Client
     dafuer passt in eine Datei ohne eine einzige Fremdbibliothek.

  2. Der eigentliche Grund: **die Zeitachse**. Drei Telefone haben drei
     Uhren. Wer die drei Aufnahmen einfach nebeneinanderlegt, vergleicht
     Signale, die um unbekannte Bruchteile von Sekunden gegeneinander
     verschoben sind. BirdMic schickt aber alle fuenf Sekunden einen RTCP
     Sender Report mit der Wanduhrzeit des Telefons (RtpSender.kt,
     `sendSenderReport`). Damit laesst sich fuer jede Datei ausrechnen,
     wann ihr erstes Sample entstanden ist -- und dann sind die drei
     Aufnahmen wirklich vergleichbar statt nur ungefaehr gleich lang.

     ffmpeg wirft diese Information weg.

  3. Paketverluste. Faellt ein RTP-Paket aus, schreibt ffmpeg die naechsten
     Daten einfach hinterher und die Datei wird kuerzer als die Zeit, die
     sie darstellt -- alles danach ist verschoben. Hier wird die Luecke
     anhand des RTP-Zeitstempels erkannt und mit Stille gefuellt, und die
     Anzahl steht hinterher im Bericht. Eine Aufnahme, die stumme Stellen
     hat, ist ein Messergebnis. Eine Aufnahme, die heimlich schrumpft, ist
     eine Falle.

Aufruf:

    python aufnehmen.py --dauer 300
    python aufnehmen.py --suchen
    python aufnehmen.py --dauer 60 rtsp://192.168.1.42:8554/audio.wav

Ohne Adressen liest das Programm `geraete.txt` neben dieser Datei.

Ergebnis je Lauf ein Ordner `aufnahmen/JJJJ-MM-TT_HHMM/` mit einer
`.wav` und einer `.json` je Geraet. Die JSON-Datei enthaelt die
Zeitreferenz, die Verlustzaehler und den Pegelverlauf; `auswerten.py`
braucht danach die WAV-Dateien gar nicht mehr anzufassen, um den
Vergleich zu rechnen.
"""

import argparse
import array
import datetime
import hashlib
import json
import os
import socket
import struct
import sys
import threading
import time
import wave

# Sekunden zwischen 1900-01-01 (NTP) und 1970-01-01 (Unix).
NTP_EPOCH = 2_208_988_800
# Groesse eines Blocks im Pegelverlauf: 1/10 Sekunde.
BLOCK_TEILER = 10
# Mehr Stille am Stueck wird nicht eingefuegt -- ein Zeitstempel, der weiter
# springt, ist kaputt und wuerde die Platte fuellen.
MAX_LUECKE_SEK = 30.0


class Leser:
    """Gepufferter Leser auf einem Socket: Zeilen und feste Laengen."""

    def __init__(self, sock):
        self.sock = sock
        self.puffer = bytearray()

    def _nachfuellen(self):
        daten = self.sock.recv(65536)
        if not daten:
            raise ConnectionError("Gegenstelle hat die Verbindung geschlossen")
        self.puffer += daten

    def genau(self, anzahl):
        while len(self.puffer) < anzahl:
            self._nachfuellen()
        stueck = bytes(self.puffer[:anzahl])
        del self.puffer[:anzahl]
        return stueck

    def zeile(self):
        while b"\r\n" not in self.puffer:
            self._nachfuellen()
        i = self.puffer.index(b"\r\n")
        stueck = bytes(self.puffer[:i])
        del self.puffer[: i + 2]
        return stueck.decode("utf-8", "replace")

    def erstes_byte(self):
        while not self.puffer:
            self._nachfuellen()
        return self.puffer[0]


def zerlege_url(url):
    """rtsp://benutzer:passwort@host:port/pfad -> (host, port, pfad, benutzer, passwort)"""
    if not url.startswith("rtsp://"):
        raise ValueError("Adresse muss mit rtsp:// beginnen: " + url)
    rest = url[len("rtsp://"):]
    pfad = "/"
    if "/" in rest:
        rest, hinten = rest.split("/", 1)
        pfad = "/" + hinten
    benutzer = passwort = None
    if "@" in rest:
        zugang, rest = rest.rsplit("@", 1)
        benutzer, _, passwort = zugang.partition(":")
    host, _, port = rest.partition(":")
    return host, int(port or 554), pfad, benutzer, passwort


def digest_antwort(kopf, methode, uri, benutzer, passwort):
    """Authorization-Header nach RFC 2617 bauen (ohne qop, so wie DigestAuth.kt es prueft)."""
    felder = {}
    for teil in kopf.split(","):
        name, _, wert = teil.strip().partition("=")
        felder[name.strip().lower().replace("digest ", "")] = wert.strip().strip('"')
    realm = felder.get("realm", "")
    nonce = felder.get("nonce", "")
    ha1 = hashlib.md5(f"{benutzer}:{realm}:{passwort}".encode()).hexdigest()
    ha2 = hashlib.md5(f"{methode}:{uri}".encode()).hexdigest()
    antwort = hashlib.md5(f"{ha1}:{nonce}:{ha2}".encode()).hexdigest()
    return (
        f'Digest username="{benutzer}", realm="{realm}", nonce="{nonce}", '
        f'uri="{uri}", response="{antwort}"'
    )


class Aufnahme:
    """Eine RTSP-Sitzung zu einem Telefon, mitsamt der Datei, die dabei entsteht."""

    def __init__(self, name, url, ordner, melden):
        self.name = name
        self.url = url
        self.ordner = ordner
        self.melden = melden
        self.host, self.port, self.pfad, self.benutzer, self.passwort = zerlege_url(url)
        self.basis = f"rtsp://{self.host}:{self.port}{self.pfad}"

        self.cseq = 0
        self.sitzung = None
        self.auth_kopf = None
        self.rate = 48000
        self.kanaele = 1

        # Zaehler und Zeitreferenz
        self.pakete = 0
        self.verloren = 0
        self.stille_frames = 0
        self.frames = 0
        self.sender_reports = []
        self.erster_rtp = None
        self.letzte_seq = None
        self.erwartet = 0          # naechster erwarteter Frame-Index (erweitert)
        self.ts_hoch = 0           # Ueberlaufzaehler des 32-Bit-Zeitstempels
        self.letzter_ts = None

        # Pegelverlauf
        self.block_groesse = 0
        self.block_summe = 0
        self.block_spitze = 0
        self.block_anzahl = 0
        self.verlauf = []
        self.uebersteuert = 0

        self.fehler = None
        self.wav = None
        self.sock = None
        self.leser = None

    # -- Protokoll ---------------------------------------------------------

    def _senden(self, methode, uri, kopfzeilen=None):
        self.cseq += 1
        zeilen = [f"{methode} {uri} RTSP/1.0", f"CSeq: {self.cseq}", "User-Agent: BirdMic-Mitschnitt"]
        if self.sitzung:
            zeilen.append(f"Session: {self.sitzung}")
        if self.auth_kopf:
            zeilen.append(f"Authorization: {self.auth_kopf}")
        for k, v in (kopfzeilen or {}).items():
            zeilen.append(f"{k}: {v}")
        roh = ("\r\n".join(zeilen) + "\r\n\r\n").encode()
        self.sock.sendall(roh)

    def _antwort(self):
        statuszeile = self.leser.zeile()
        teile = statuszeile.split(" ", 2)
        code = int(teile[1]) if len(teile) > 1 else 0
        kopf = {}
        while True:
            zeile = self.leser.zeile()
            if not zeile:
                break
            name, _, wert = zeile.partition(":")
            kopf[name.strip().lower()] = wert.strip()
        laenge = int(kopf.get("content-length", "0") or 0)
        rumpf = self.leser.genau(laenge).decode("utf-8", "replace") if laenge else ""
        return code, kopf, rumpf

    def anfrage(self, methode, uri, kopfzeilen=None):
        """Eine Anfrage stellen; bei 401 einmal mit Digest wiederholen."""
        self._senden(methode, uri, kopfzeilen)
        code, kopf, rumpf = self._antwort()
        if code == 401 and self.benutzer and not self.auth_kopf:
            self.auth_kopf = digest_antwort(
                kopf.get("www-authenticate", ""), methode, uri, self.benutzer, self.passwort
            )
            self._senden(methode, uri, kopfzeilen)
            code, kopf, rumpf = self._antwort()
        if code != 200:
            raise ConnectionError(f"{methode} beantwortet mit {code}")
        # Die Session-ID kommt mit der SETUP-Antwort und haengt ein
        # ";timeout=60" an. PLAY ohne sie wird abgewiesen.
        if not self.sitzung and "session" in kopf:
            self.sitzung = kopf["session"].split(";")[0].strip()
        return kopf, rumpf

    def verbinden(self):
        """OPTIONS, DESCRIBE, SETUP -- alles bis kurz vor dem ersten Ton."""
        self.sock = socket.create_connection((self.host, self.port), timeout=10)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.leser = Leser(self.sock)

        self.anfrage("OPTIONS", self.basis)
        _, sdp = self.anfrage("DESCRIBE", self.basis, {"Accept": "application/sdp"})
        for zeile in sdp.splitlines():
            if zeile.startswith("a=rtpmap:"):
                # a=rtpmap:96 L16/48000/1
                teile = zeile.split(" ", 1)[1].split("/")
                if len(teile) >= 2:
                    self.rate = int(teile[1])
                if len(teile) >= 3:
                    self.kanaele = int(teile[2])

        # TCP-interleaved statt UDP: im WLAN geht dabei nichts an NAT oder
        # Paketverlust verloren, und der Mitschnitt braucht keine offenen Ports.
        self.anfrage(
            "SETUP",
            self.basis + "/trackID=0",
            {"Transport": "RTP/AVP/TCP;unicast;interleaved=0-1"},
        )

        self.block_groesse = (self.rate // BLOCK_TEILER) * self.kanaele
        pfad = os.path.join(self.ordner, self.name + ".wav")
        self.wav = wave.open(pfad, "wb")
        self.wav.setnchannels(self.kanaele)
        self.wav.setsampwidth(2)
        self.wav.setframerate(self.rate)
        self.melden(f"{self.name}: bereit, {self.rate} Hz, {self.kanaele} Kanal/Kanaele")

    def abspielen(self):
        self.start_wanduhr = time.time()
        self.anfrage("PLAY", self.basis, {"Range": "npt=now-"})

    # -- Daten -------------------------------------------------------------

    def _rtp(self, paket):
        if len(paket) < 12:
            return
        seq = struct.unpack(">H", paket[2:4])[0]
        ts = struct.unpack(">I", paket[4:8])[0]
        nutz = paket[12:]
        if not nutz:
            return

        self.pakete += 1
        if self.letzte_seq is not None:
            luecke = (seq - self.letzte_seq - 1) & 0xFFFF
            if 0 < luecke < 1000:
                self.verloren += luecke
        self.letzte_seq = seq

        # 32-Bit-Zeitstempel laeuft nach gut einem Tag ueber (48 kHz).
        if self.letzter_ts is not None and ts < self.letzter_ts and self.letzter_ts - ts > 2**31:
            self.ts_hoch += 1
        self.letzter_ts = ts
        lang = ts + (self.ts_hoch << 32)

        if self.erster_rtp is None:
            self.erster_rtp = lang
            self.erwartet = 0

        position = lang - self.erster_rtp
        if position < self.erwartet:
            return  # verspaetet oder doppelt -- die Stelle ist schon geschrieben
        if position > self.erwartet:
            fehlt = position - self.erwartet
            if fehlt > MAX_LUECKE_SEK * self.rate:
                # Unglaubwuerdiger Sprung: nicht fuellen, sondern neu ansetzen.
                self.erster_rtp = lang - self.erwartet
            else:
                self._schreiben(array.array("h", [0]) * (fehlt * self.kanaele))
                self.stille_frames += fehlt

        werte = array.array("h")
        werte.frombytes(nutz if len(nutz) % 2 == 0 else nutz[:-1])
        if sys.byteorder == "little":
            werte.byteswap()  # L16 kommt in Netzwerkreihenfolge
        self._schreiben(werte)

    def _schreiben(self, werte):
        if not werte:
            return
        self.wav.writeframes(werte.tobytes())
        self.frames += len(werte) // self.kanaele
        self.erwartet += len(werte) // self.kanaele
        # Pegelverlauf mitfuehren, solange die Daten ohnehin in der Hand sind.
        for wert in werte:
            self.block_summe += wert * wert
            b = -wert if wert < 0 else wert
            if b > self.block_spitze:
                self.block_spitze = b
            if b >= 32700:
                self.uebersteuert += 1
            self.block_anzahl += 1
            if self.block_anzahl >= self.block_groesse:
                self.verlauf.append(
                    [round((self.block_summe / self.block_anzahl) ** 0.5, 1), self.block_spitze]
                )
                self.block_summe = 0
                self.block_spitze = 0
                self.block_anzahl = 0

    def _rtcp(self, paket):
        # Nur den Sender Report auswerten (PT=200). Er ist der Grund, warum
        # sich die drei Aufnahmen hinterher aufeinanderlegen lassen.
        if len(paket) < 28 or paket[1] != 200:
            return
        ntp_sek, ntp_bruch, rtp_ts, pakete, oktette = struct.unpack(">IIIII", paket[8:28])
        self.sender_reports.append(
            {
                "ntp": ntp_sek - NTP_EPOCH + ntp_bruch / 2**32,
                "rtp": rtp_ts,
                "pakete": pakete,
                "oktette": oktette,
                "empfangen": time.time(),
            }
        )

    def mitschneiden(self, ende, stopp):
        self.sock.settimeout(1.0)
        while time.time() < ende and not stopp.is_set():
            try:
                if self.leser.erstes_byte() != 0x24:
                    self.leser.zeile()  # unerwarteter Text -- ueberspringen
                    continue
                kopf = self.leser.genau(4)
                kanal = kopf[1]
                laenge = struct.unpack(">H", kopf[2:4])[0]
                paket = self.leser.genau(laenge)
                if kanal == 0:
                    self._rtp(paket)
                elif kanal == 1:
                    self._rtcp(paket)
            except socket.timeout:
                continue

    def beenden(self):
        try:
            if self.sock:
                self._senden("TEARDOWN", self.basis)
        except Exception:
            pass
        try:
            if self.sock:
                self.sock.close()
        except Exception:
            pass
        if self.wav:
            self.wav.close()
            self.wav = None

    def bericht(self):
        """Zeitreferenz und Zaehler -- die Grundlage der Auswertung."""
        erstes_sample_ntp = None
        drift_ppm = None
        if self.sender_reports and self.erster_rtp is not None:
            erster = self.sender_reports[0]
            # Der Report sagt: RTP-Zeitstempel X entstand zur Wanduhrzeit T.
            # Das erste Sample der Datei hat den Zeitstempel self.erster_rtp.
            versatz = (erster["rtp"] - (self.erster_rtp & 0xFFFFFFFF)) / self.rate
            erstes_sample_ntp = erster["ntp"] - versatz
            if len(self.sender_reports) >= 2:
                letzter = self.sender_reports[-1]
                d_uhr = letzter["ntp"] - erster["ntp"]
                d_rtp = ((letzter["rtp"] - erster["rtp"]) & 0xFFFFFFFF) / self.rate
                if d_uhr > 30:
                    # Weicht der Audiotakt von der Uhr ab, laufen die Geraete
                    # ueber Stunden auseinander. In ppm ist das vergleichbar.
                    drift_ppm = round((d_rtp - d_uhr) / d_uhr * 1e6, 1)
        return {
            "name": self.name,
            "url": self.url,
            "rate": self.rate,
            "kanaele": self.kanaele,
            "sekunden": round(self.frames / self.rate, 3) if self.rate else 0,
            "frames": self.frames,
            "pakete": self.pakete,
            "verlorene_pakete": self.verloren,
            "eingefuegte_stille_frames": self.stille_frames,
            "eingefuegte_stille_sekunden": round(self.stille_frames / self.rate, 3),
            "uebersteuerte_samples": self.uebersteuert,
            "start_wanduhr_hier": getattr(self, "start_wanduhr", None),
            "erstes_sample_ntp": erstes_sample_ntp,
            "taktdrift_ppm": drift_ppm,
            "sender_reports": len(self.sender_reports),
            "block_sekunden": 1.0 / BLOCK_TEILER,
            "verlauf": self.verlauf,
            "fehler": self.fehler,
        }


def mdns_suchen(sekunden=4.0):
    """Fragt per mDNS nach `_rtsp._tcp` und gibt gefundene Adressen zurueck.

    BirdMic meldet sich selbst an (MdnsAdvertiser.kt), damit man keine
    IP-Adresse abtippen muss. Genau dafuer ist das hier.
    """
    frage = bytearray(struct.pack(">HHHHHH", 0, 0, 1, 0, 0, 0))
    for teil in (b"_rtsp", b"_tcp", b"local"):
        frage.append(len(teil))
        frage += teil
    frage.append(0)
    frage += struct.pack(">HH", 12, 1)  # PTR, IN

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.settimeout(0.5)
    try:
        sock.sendto(bytes(frage), ("224.0.0.251", 5353))
    except OSError as e:
        print("mDNS-Anfrage nicht moeglich:", e)
        return []

    namen = {}
    adressen = {}
    dienste = {}
    ende = time.time() + sekunden
    while time.time() < ende:
        try:
            daten, _ = sock.recvfrom(9000)
        except socket.timeout:
            continue
        except OSError:
            break
        try:
            _lese_antwort(daten, namen, adressen, dienste)
        except Exception:
            continue
    sock.close()

    gefunden = []
    for name, (host, port) in dienste.items():
        ip = adressen.get(host)
        if ip:
            kurz = name.split(".")[0].replace(" ", "-")
            gefunden.append((kurz, f"rtsp://{ip}:{port}/audio.wav"))
    return sorted(set(gefunden))


def _name_lesen(daten, pos):
    teile = []
    gesprungen = False
    ende = pos
    for _ in range(128):
        laenge = daten[pos]
        if laenge == 0:
            pos += 1
            if not gesprungen:
                ende = pos
            break
        if laenge & 0xC0 == 0xC0:
            zeiger = struct.unpack(">H", daten[pos:pos + 2])[0] & 0x3FFF
            if not gesprungen:
                ende = pos + 2
            gesprungen = True
            pos = zeiger
            continue
        teile.append(daten[pos + 1:pos + 1 + laenge].decode("utf-8", "replace"))
        pos += 1 + laenge
        if not gesprungen:
            ende = pos
    return ".".join(teile), ende


def _lese_antwort(daten, namen, adressen, dienste):
    _, _, fragen, antworten, autoritaet, zusatz = struct.unpack(">HHHHHH", daten[:12])
    pos = 12
    for _ in range(fragen):
        _, pos = _name_lesen(daten, pos)
        pos += 4
    for _ in range(antworten + autoritaet + zusatz):
        name, pos = _name_lesen(daten, pos)
        typ, _klasse, _ttl, laenge = struct.unpack(">HHIH", daten[pos:pos + 10])
        pos += 10
        rumpf = pos
        if typ == 12:  # PTR
            ziel, _ = _name_lesen(daten, rumpf)
            namen[ziel] = name
        elif typ == 33:  # SRV
            port = struct.unpack(">H", daten[rumpf + 4:rumpf + 6])[0]
            host, _ = _name_lesen(daten, rumpf + 6)
            dienste[name] = (host, port)
        elif typ == 1 and laenge == 4:  # A
            adressen[name] = ".".join(str(b) for b in daten[rumpf:rumpf + 4])
        pos += laenge


def eigene_ip():
    """Die eigene Adresse im lokalen Netz -- ohne ein Paket zu senden."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 9))  # Adresse aus dem Dokumentationsnetz
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def ist_birdmic(ip, port, zeit_grenze=1.5):
    """Prueft, ob hinter einem offenen Port wirklich BirdMic steckt.

    Ein offener Port 8554 kann auch eine Kamera sein. Gefragt wird deshalb
    per DESCRIBE nach der Beschreibung: BirdMic nennt sich dort beim Namen.
    Ist der Zugangsschutz an, kommt statt der Beschreibung eine 401 -- dann
    steht der Name im Realm, und das reicht genauso.
    """
    try:
        with socket.create_connection((ip, port), timeout=zeit_grenze) as s:
            s.settimeout(zeit_grenze)
            basis = f"rtsp://{ip}:{port}/"
            s.sendall(
                f"DESCRIBE {basis} RTSP/1.0\r\nCSeq: 1\r\n"
                "Accept: application/sdp\r\n\r\n".encode()
            )
            antwort = b""
            while len(antwort) < 4096:
                stueck = s.recv(4096)
                if not stueck:
                    break
                antwort += stueck
                if b"\r\n\r\n" in antwort and b"BirdMic" in antwort:
                    break
            text = antwort.decode("utf-8", "replace")
            if "BirdMic" not in text:
                return None
            # Den Pfad aus der Beschreibung uebernehmen, falls er nicht
            # der uebliche ist -- er ist in der App frei einstellbar.
            return f"rtsp://{ip}:{port}/audio.wav"
    except OSError:
        return None


def netz_scannen(netz=None, port=8554, faeden=64):
    """Sucht im lokalen Netz nach BirdMic, ohne mDNS.

    Warum zusaetzlich zu mDNS: In einem Container -- und damit in jedem
    CI-Job -- kommt Multicast nicht durch die Netzwerkbruecke. mDNS findet
    dort grundsaetzlich nichts, ganz gleich wie gut die App sich anmeldet.
    Ein gezielter Verbindungsversuch auf einen einzigen Port geht dagegen
    durch, weil er ganz normaler ausgehender Verkehr ist.
    """
    if netz is None:
        eigen = eigene_ip()
        netz = eigen.rsplit(".", 1)[0] + ".0/24"
    if "/" in netz:
        basis, bits = netz.split("/")
        bits = int(bits)
        if bits != 24:
            raise ValueError("Nur /24 wird unterstuetzt, oder eine einzelne Adresse")
        praefix = basis.rsplit(".", 1)[0]
        ziele = [f"{praefix}.{i}" for i in range(1, 255)]
    else:
        ziele = [netz]

    gefunden = []
    sperre = threading.Lock()
    naechstes = [0]

    def arbeiten():
        while True:
            with sperre:
                if naechstes[0] >= len(ziele):
                    return
                ip = ziele[naechstes[0]]
                naechstes[0] += 1
            url = ist_birdmic(ip, port)
            if url:
                with sperre:
                    gefunden.append((ip.replace(".", "-"), url))

    gruppe = [threading.Thread(target=arbeiten, daemon=True) for _ in range(min(faeden, len(ziele)))]
    for t in gruppe:
        t.start()
    for t in gruppe:
        t.join(timeout=60)
    return sorted(gefunden)


def geraete_lesen(pfad):
    quellen = []
    if not os.path.exists(pfad):
        return quellen
    with open(pfad, encoding="utf-8") as f:
        for zeile in f:
            zeile = zeile.split("#", 1)[0].strip()
            if not zeile:
                continue
            teile = zeile.split()
            if len(teile) == 1:
                quellen.append((f"geraet{len(quellen) + 1}", teile[0]))
            else:
                quellen.append((teile[0], teile[1]))
    return quellen


def main():
    p = argparse.ArgumentParser(description="Mehrere BirdMic-Streams gleichzeitig aufnehmen")
    p.add_argument("adressen", nargs="*", help="rtsp://... (ohne Angabe: geraete.txt)")
    p.add_argument("--dauer", type=float, default=120.0, help="Sekunden (Vorgabe 120)")
    p.add_argument("--ordner", default=None, help="Zielordner (Vorgabe: aufnahmen/<Zeit>)")
    p.add_argument("--suchen", action="store_true", help="Nur suchen und beenden")
    p.add_argument("--netz", default=None,
                   help="Netz fuer die Suche, z.B. 192.168.1.0/24 (Vorgabe: eigenes Netz)")
    p.add_argument("--port", type=int, default=8554, help="Port beim Suchen (Vorgabe 8554)")
    args = p.parse_args()

    hier = os.path.dirname(os.path.abspath(__file__))

    if args.suchen:
        gefunden = []
        if not args.netz:
            print("Suche vier Sekunden lang per mDNS nach _rtsp._tcp ...")
            gefunden = mdns_suchen()
            for name, url in gefunden:
                print(f"  {name}  {url}")
        if not gefunden:
            # In einem Container kommt Multicast nicht durch die Bruecke.
            # Dann bleibt der direkte Weg: jede Adresse einmal anfragen.
            netz = args.netz or (eigene_ip().rsplit(".", 1)[0] + ".0/24")
            print(f"Kein Treffer per mDNS. Frage {netz} auf Port {args.port} ab ...")
            gefunden = netz_scannen(netz, args.port)
            for name, url in gefunden:
                print(f"  {name}  {url}")
        if not gefunden:
            print("  nichts gefunden -- laeuft der Stream in der App?")
            return 1
        # Das Ergebnis gleich festhalten, damit spaeter niemand IP-Adressen
        # abtippen muss. Eine vorhandene Liste wird nicht ueberschrieben.
        ziel = os.path.join(hier, "geraete.txt")
        if os.path.exists(ziel):
            print(f"\n{ziel} bleibt unveraendert (Datei ist schon da).")
        else:
            with open(ziel, "w", encoding="utf-8") as f:
                f.write("# Von 'aufnehmen.py --suchen' gefunden. Namen duerfen\n")
                f.write("# geaendert werden -- sie werden zu den Dateinamen.\n")
                for name, url in gefunden:
                    f.write(f"{name} {url}\n")
            print(f"\nGeschrieben nach {ziel} -- ab jetzt reicht 'aufnehmen.py --dauer 300'.")
        return 0

    quellen = [(f"geraet{i + 1}", u) for i, u in enumerate(args.adressen)]
    if not quellen:
        quellen = geraete_lesen(os.path.join(hier, "geraete.txt"))
    if not quellen:
        print("Keine Adressen. Suche per mDNS ...")
        quellen = mdns_suchen()
    if not quellen:
        netz = args.netz or (eigene_ip().rsplit(".", 1)[0] + ".0/24")
        print(f"Nichts per mDNS. Frage {netz} auf Port {args.port} ab ...")
        quellen = netz_scannen(netz, args.port)
    if not quellen:
        print("Nichts gefunden. Adressen in werkzeuge/geraete.txt eintragen "
              "oder als Argument uebergeben.")
        return 1

    stempel = datetime.datetime.now().strftime("%Y-%m-%d_%H%M%S")
    ordner = args.ordner or os.path.join(hier, "aufnahmen", stempel)
    os.makedirs(ordner, exist_ok=True)

    sperre = threading.Lock()

    def melden(text):
        with sperre:
            print(text, flush=True)

    aufnahmen = [Aufnahme(name, url, ordner, melden) for name, url in quellen]
    print(f"{len(aufnahmen)} Geraete, {args.dauer:.0f} Sekunden, Ziel: {ordner}\n")

    # Erst alle verbinden, dann alle gemeinsam starten. Der Startbefehl geht
    # damit innerhalb weniger Millisekunden an alle drei -- die genaue
    # Zuordnung macht spaeter ohnehin die Uhr aus dem Sender Report, aber
    # ein enger Start haelt die Dateien ohne Vorlauf nebeneinander.
    lebende = []
    for a in aufnahmen:
        try:
            a.verbinden()
            lebende.append(a)
        except Exception as e:
            a.fehler = f"{type(e).__name__}: {e}"
            melden(f"{a.name}: NICHT erreichbar -- {a.fehler}")

    if not lebende:
        print("\nKein einziges Geraet erreichbar. Laeuft der Stream in der App?")
        return 1

    stopp = threading.Event()
    tor = threading.Barrier(len(lebende))

    def lauf(a):
        try:
            # Alle warten hier, bis der letzte bereit ist. Danach gehen die
            # PLAY-Befehle innerhalb weniger Millisekunden hinaus.
            tor.wait(timeout=15)
            a.abspielen()
            a.mitschneiden(time.time() + args.dauer, stopp)
        except Exception as e:
            a.fehler = f"{type(e).__name__}: {e}"
            melden(f"{a.name}: abgebrochen -- {a.fehler}")
        finally:
            a.beenden()

    faeden = [threading.Thread(target=lauf, args=(a,), daemon=True) for a in lebende]
    beginn = time.time()
    for f in faeden:
        f.start()

    try:
        while any(f.is_alive() for f in faeden):
            time.sleep(2.0)
            vergangen = time.time() - beginn
            teile = []
            for a in lebende:
                pegel = a.verlauf[-1][0] if a.verlauf else 0
                teile.append(f"{a.name} {a.frames / max(a.rate, 1):6.1f}s "
                             f"P{a.verloren:<3d} RMS{pegel:6.0f}")
            print(f"[{vergangen:5.0f}s] " + " | ".join(teile), flush=True)
    except KeyboardInterrupt:
        print("\nAbbruch -- Dateien werden sauber geschlossen ...")
        stopp.set()
    for f in faeden:
        f.join(timeout=15)

    zusammen = {
        "aufgenommen_am": stempel,
        "dauer_angefordert": args.dauer,
        "geraete": [],
    }
    for a in aufnahmen:
        b = a.bericht()
        zusammen["geraete"].append({k: v for k, v in b.items() if k != "verlauf"})
        with open(os.path.join(ordner, a.name + ".json"), "w", encoding="utf-8") as f:
            json.dump(b, f, ensure_ascii=False, indent=1)
    with open(os.path.join(ordner, "lauf.json"), "w", encoding="utf-8") as f:
        json.dump(zusammen, f, ensure_ascii=False, indent=1)

    print("\nFertig.")
    for a in aufnahmen:
        if a.fehler:
            print(f"  {a.name}: {a.fehler}")
        else:
            print(f"  {a.name}: {a.frames / max(a.rate, 1):.1f} s, "
                  f"{a.verloren} Pakete verloren, "
                  f"{a.stille_frames / max(a.rate, 1):.2f} s Stille eingefuegt, "
                  f"{a.sender_reports and len(a.sender_reports) or 0} Sender Reports")
    print(f"\nOrdner: {ordner}")
    print("Auswertung:  python " + os.path.join(hier, "auswerten.py") + " " + ordner)
    return 0


if __name__ == "__main__":
    sys.exit(main())
