#!/usr/bin/env python3
"""Prueft laufende BirdMic-Geraete aktiv gegen ihre eigenen Zusagen.

Die Unit-Tests unter `app/src/test/` pruefen die Rechenteile: Digest,
FFT, Hochpass, PacketQueue, RtpSender. Sie laufen ohne Geraet und ohne
Netz -- und genau das ist ihre Grenze. Ob das fertige Telefon im WLAN
wirklich RTSP spricht, ob die Sequenznummern zusammenpassen, ob nach
fuenf Sekunden ein Sender Report kommt und ob ueberhaupt Ton im Paket
ist: davon wissen sie nichts.

Dieses Programm schliesst die Luecke. Es redet mit echten Geraeten und
prueft, was in der README als Zusage steht:

    OPTIONS nennt alle Methoden
    DESCRIBE liefert L16 mit Abtastrate und Kanalzahl
    SETUP beantwortet TCP-interleaved mit Session und Timeout
    PLAY nennt seq und rtptime -- und beide stimmen mit dem ersten
        wirklich ankommenden Paket ueberein
    RTP zaehlt Sequenznummer und Zeitstempel luecken- und sprungfrei
    RTCP schickt binnen sechs Sekunden einen Sender Report
    die Uhr des Telefons weicht nicht von der eigenen ab
    im Ton ist etwas drin -- ein stummes Mikrofon faellt auf
    mehrere Empfaenger gleichzeitig bekommen beide Daten
    GET_PARAMETER haelt die Sitzung wach, TEARDOWN beendet sie

Aufruf:

    python pruefen.py --netz 192.168.1.0/24
    python pruefen.py rtsp://192.168.1.183:8554/audio.wav

Mit `--bericht ergebnis.xml` entsteht eine JUnit-Datei; GitLab zeigt die
Ergebnisse dann als Testbericht an. Rueckgabewert ist 1, sobald eine
Pruefung fehlschlaegt -- damit wird die Pipeline rot.

Gebraucht wird nur die Standardbibliothek.
"""

import argparse
import importlib.util
import json
import os
import socket
import struct
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

_hier = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("aufnehmen", os.path.join(_hier, "aufnehmen.py"))
aufnehmen = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(aufnehmen)

# So viele Pakete werden auf Luecken und Spruenge geprueft. 100 Pakete
# sind bei 20 ms rund zwei Sekunden -- lang genug, um einen Fehler zu
# sehen, kurz genug, um nicht zu langweilen.
PAKETE = 100
# Der Sender Report kommt laut RtpSender.kt alle fuenf Sekunden.
SR_FRIST = 7.0
# Zulaessige Abweichung der Telefonuhr von der eigenen.
UHR_TOLERANZ = 2.0


class Ergebnis:
    """Sammelt Pruefungen fuer einen JUnit-Bericht."""

    def __init__(self):
        self.faelle = []

    def dazu(self, geraet, name, dauer, fehler=None, uebersprungen=None, text=""):
        self.faelle.append((geraet, name, dauer, fehler, uebersprungen, text))
        if fehler:
            print(f"  FEHLER  {name}: {fehler}")
        elif uebersprungen:
            print(f"  offen   {name}: {uebersprungen}")
        else:
            print(f"  ok      {name}{(' -- ' + text) if text else ''}")

    @property
    def fehlerzahl(self):
        return sum(1 for f in self.faelle if f[3])

    def xml(self, pfad):
        wurzel = ET.Element("testsuites")
        nach_geraet = {}
        for fall in self.faelle:
            nach_geraet.setdefault(fall[0], []).append(fall)
        for geraet, faelle in nach_geraet.items():
            suite = ET.SubElement(
                wurzel, "testsuite", name=geraet, tests=str(len(faelle)),
                failures=str(sum(1 for f in faelle if f[3])),
                skipped=str(sum(1 for f in faelle if f[4])),
                time=f"{sum(f[2] for f in faelle):.3f}",
            )
            for _, name, dauer, fehler, uebersprungen, text in faelle:
                fall = ET.SubElement(suite, "testcase", classname=geraet,
                                     name=name, time=f"{dauer:.3f}")
                if fehler:
                    ET.SubElement(fall, "failure", message=str(fehler)[:400]).text = str(fehler)
                elif uebersprungen:
                    ET.SubElement(fall, "skipped", message=str(uebersprungen))
                elif text:
                    ET.SubElement(fall, "system-out").text = text
        ET.ElementTree(wurzel).write(pfad, encoding="utf-8", xml_declaration=True)


class Sitzung:
    """Eine RTSP-Sitzung, die sich Schritt fuer Schritt pruefen laesst."""

    def __init__(self, url):
        self.a = aufnehmen.Aufnahme("pruefung", url, ".", lambda t: None)

    def verbinden(self):
        a = self.a
        a.sock = socket.create_connection((a.host, a.port), timeout=10)
        a.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        a.leser = aufnehmen.Leser(a.sock)

    def schliessen(self):
        try:
            self.a.sock.close()
        except Exception:
            pass

    def paket(self, frist=5.0):
        """Naechsten eingebetteten Rahmen holen: (Kanal, Rohdaten)."""
        self.a.sock.settimeout(frist)
        if self.a.leser.erstes_byte() != 0x24:
            self.a.leser.zeile()
            return None, b""
        kopf = self.a.leser.genau(4)
        return kopf[1], self.a.leser.genau(struct.unpack(">H", kopf[2:4])[0])

    def anfrage_im_strom(self, methode, frist=6.0):
        """Eine Anfrage stellen, waehrend der Strom laeuft.

        Sobald PLAY laeuft, liegen RTP-Rahmen und RTSP-Antworten in
        derselben Verbindung. Wer die Antwort einfach als Text liest,
        liest Audiodaten -- der Fehler faellt dann dem Geraet zur Last,
        obwohl der Client schuld ist. Also: Rahmen ueberspringen, bis die
        Statuszeile kommt.
        """
        a = self.a
        a._senden(methode, a.basis)
        beginn = time.time()
        while time.time() - beginn < frist:
            a.sock.settimeout(frist)
            if a.leser.erstes_byte() == 0x24:
                kopf = a.leser.genau(4)
                a.leser.genau(struct.unpack(">H", kopf[2:4])[0])
                continue
            statuszeile = a.leser.zeile()
            kopfzeilen = {}
            while True:
                zeile = a.leser.zeile()
                if not zeile:
                    break
                schluessel, _, wert = zeile.partition(":")
                kopfzeilen[schluessel.strip().lower()] = wert.strip()
            laenge = int(kopfzeilen.get("content-length", "0") or 0)
            if laenge:
                a.leser.genau(laenge)
            return statuszeile, kopfzeilen
        raise AssertionError(f"keine Antwort auf {methode} binnen {frist:.0f} s")


def pruefe_geraet(url, name, e):
    """Alle Pruefungen fuer ein Geraet. Gibt True zurueck, wenn alles ging."""
    print(f"\n{name}  ({url})")

    def messen(bezeichnung, funktion):
        start = time.time()
        try:
            text = funktion() or ""
            e.dazu(name, bezeichnung, time.time() - start, text=text)
            return True
        except AssertionError as f:
            e.dazu(name, bezeichnung, time.time() - start, fehler=str(f))
            return False
        except Exception as f:
            e.dazu(name, bezeichnung, time.time() - start,
                   fehler=f"{type(f).__name__}: {f}")
            return False

    s = Sitzung(url)
    zustand = {}
    try:
        if not messen("verbindet sich", s.verbinden):
            return False

        def options():
            kopf, _ = s.a.anfrage("OPTIONS", s.a.basis)
            oeffentlich = kopf.get("public", "")
            fehlend = [m for m in ("OPTIONS", "DESCRIBE", "SETUP", "PLAY", "PAUSE",
                                   "TEARDOWN", "GET_PARAMETER") if m not in oeffentlich]
            assert not fehlend, f"OPTIONS nennt {fehlend} nicht"
            return oeffentlich
        messen("OPTIONS nennt alle Methoden", options)

        def describe():
            _, sdp = s.a.anfrage("DESCRIBE", s.a.basis, {"Accept": "application/sdp"})
            assert "m=audio" in sdp, "keine Audiospur in der Beschreibung"
            zeile = [z for z in sdp.splitlines() if z.startswith("a=rtpmap:")]
            assert zeile, "kein rtpmap in der Beschreibung"
            teile = zeile[0].split(" ", 1)[1].split("/")
            assert teile[0].upper() == "L16", f"nicht L16, sondern {teile[0]}"
            zustand["rate"] = int(teile[1])
            zustand["kanaele"] = int(teile[2]) if len(teile) > 2 else 1
            assert "a=control:" in sdp, "keine Steuerangabe (a=control) fuer die Spur"
            return f"L16/{zustand['rate']}/{zustand['kanaele']}"
        if not messen("DESCRIBE liefert L16", describe):
            return False

        def setup():
            kopf, _ = s.a.anfrage("SETUP", s.a.basis + "/trackID=0",
                                  {"Transport": "RTP/AVP/TCP;unicast;interleaved=0-1"})
            transport = kopf.get("transport", "")
            assert "interleaved=0-1" in transport, f"Transport ohne interleaved: {transport}"
            sitzung = kopf.get("session", "")
            assert sitzung, "SETUP ohne Session"
            assert "timeout=" in sitzung, "Session ohne timeout -- Clients koennen nicht wachhalten"
            return sitzung
        if not messen("SETUP beantwortet TCP-interleaved", setup):
            return False

        def play():
            kopf, _ = s.a.anfrage("PLAY", s.a.basis, {"Range": "npt=now-"})
            info = kopf.get("rtp-info", "")
            assert "seq=" in info and "rtptime=" in info, f"RTP-Info unvollstaendig: {info!r}"
            for teil in info.replace(";", ",").split(","):
                k, _, v = teil.partition("=")
                if k.strip() == "seq":
                    zustand["seq"] = int(v)
                elif k.strip() == "rtptime":
                    zustand["rtptime"] = int(v)
            return info
        if not messen("PLAY nennt seq und rtptime", play):
            return False

        def erstes_paket():
            beginn = time.time()
            while time.time() - beginn < 3.0:
                kanal, roh = s.paket(3.0)
                if kanal == 0:
                    assert len(roh) >= 12, "RTP-Paket kuerzer als sein Kopf"
                    assert roh[0] >> 6 == 2, f"RTP-Version {roh[0] >> 6}, erwartet 2"
                    assert roh[1] & 0x7F == 96, f"Payload-Typ {roh[1] & 0x7F}, erwartet 96"
                    zustand["erste_seq"] = struct.unpack(">H", roh[2:4])[0]
                    zustand["erster_ts"] = struct.unpack(">I", roh[4:8])[0]
                    zustand["ssrc"] = struct.unpack(">I", roh[8:12])[0]
                    return f"seq={zustand['erste_seq']} ts={zustand['erster_ts']}"
            raise AssertionError("binnen drei Sekunden kein RTP-Paket nach PLAY")
        if not messen("erstes RTP-Paket kommt an", erstes_paket):
            return False

        def info_stimmt():
            # RtspServer.kt setzt startSequence und startTimestamp in den
            # RTP-Info-Header. Weichen sie vom ersten Paket ab, springen
            # Clients beim Start -- ein Fehler, den man sonst nur hoert.
            assert zustand["erste_seq"] == zustand["seq"], (
                f"RTP-Info sagt seq={zustand['seq']}, "
                f"erstes Paket hat {zustand['erste_seq']}")
            assert zustand["erster_ts"] == zustand["rtptime"], (
                f"RTP-Info sagt rtptime={zustand['rtptime']}, "
                f"erstes Paket hat {zustand['erster_ts']}")
            return "seq und rtptime passen zum ersten Paket"
        messen("RTP-Info passt zum Strom", info_stimmt)

        def strom():
            seq = zustand["erste_seq"]
            ts = zustand["erster_ts"]
            gesehen = 0
            summe_quadrate = 0
            werte = 0
            spitze = 0
            while gesehen < PAKETE:
                kanal, roh = s.paket(6.0)
                if kanal != 0:
                    if kanal == 1:
                        zustand.setdefault("sr", roh)
                    continue
                gesehen += 1
                neue_seq = struct.unpack(">H", roh[2:4])[0]
                neuer_ts = struct.unpack(">I", roh[4:8])[0]
                ssrc = struct.unpack(">I", roh[8:12])[0]
                assert ssrc == zustand["ssrc"], "SSRC wechselt mitten im Strom"
                erwartet = (seq + 1) & 0xFFFF
                if gesehen > 1:
                    assert neue_seq == erwartet, (
                        f"Sequenzsprung: {erwartet} erwartet, {neue_seq} bekommen "
                        f"(nach {gesehen} Paketen)")
                    frames = (len(roh) - 12) // (2 * zustand["kanaele"])
                    soll = (ts + frames) & 0xFFFFFFFF
                    assert neuer_ts == soll, (
                        f"Zeitstempel springt: {soll} erwartet, {neuer_ts} bekommen")
                seq, ts = neue_seq, neuer_ts
                for i in range(12, len(roh) - 1, 2):
                    wert = struct.unpack(">h", roh[i:i + 2])[0]
                    summe_quadrate += wert * wert
                    werte += 1
                    spitze = max(spitze, abs(wert))
            zustand["rms"] = (summe_quadrate / werte) ** 0.5 if werte else 0.0
            zustand["spitze"] = spitze
            sekunden = werte / (zustand["rate"] * zustand["kanaele"])
            return f"{PAKETE} Pakete, {sekunden:.1f} s, keine Luecke"
        messen(f"{PAKETE} Pakete ohne Sprung", strom)

        def ton_da():
            # Ein stummes Mikrofon liefert exakt Null. Das passiert, wenn
            # Android die Aufnahme im Hintergrund abwuergt -- der Stream
            # laeuft dann weiter und traegt nichts.
            assert zustand.get("spitze", 0) > 0, (
                "der Ton ist exakt still -- nimmt die App wirklich auf?")
            return f"RMS {zustand['rms']:.0f}, Spitze {zustand['spitze']}"
        messen("es ist Ton im Strom", ton_da)

        def sender_report():
            roh = zustand.get("sr")
            beginn = time.time()
            while roh is None and time.time() - beginn < SR_FRIST:
                kanal, daten = s.paket(SR_FRIST)
                if kanal == 1:
                    roh = daten
            assert roh is not None, f"binnen {SR_FRIST:.0f} s kein RTCP Sender Report"
            assert roh[1] == 200, f"RTCP-Typ {roh[1]}, erwartet 200 (Sender Report)"
            assert len(roh) >= 28, "Sender Report zu kurz"
            ntp_s, ntp_f = struct.unpack(">II", roh[8:16])
            zustand["uhr"] = ntp_s - aufnehmen.NTP_EPOCH + ntp_f / 2**32
            return f"Uhr des Geraets {time.strftime('%H:%M:%S', time.localtime(zustand['uhr']))}"
        if messen("RTCP Sender Report kommt", sender_report):
            def uhr():
                abweichung = zustand["uhr"] - time.time()
                assert abs(abweichung) <= UHR_TOLERANZ, (
                    f"Uhr des Geraets weicht um {abweichung:+.1f} s ab. Aufnahmen "
                    "mehrerer Geraete lassen sich damit nicht zuverlaessig "
                    "uebereinanderlegen -- automatische Zeit einschalten.")
                return f"Abweichung {abweichung:+.2f} s"
            messen("Uhr des Geraets stimmt", uhr)

        def zweiter_empfaenger():
            # Die README sagt: mehrere Empfaenger gleichzeitig, jeder mit
            # eigener Session. Das ist die Zusage, auf der der ganze
            # Mitschnitt aufbaut -- BirdNET-Pi haengt ja schon dran.
            zweite = Sitzung(url)
            try:
                zweite.verbinden()
                zweite.a.anfrage("OPTIONS", zweite.a.basis)
                zweite.a.anfrage("DESCRIBE", zweite.a.basis, {"Accept": "application/sdp"})
                zweite.a.anfrage("SETUP", zweite.a.basis + "/trackID=0",
                                 {"Transport": "RTP/AVP/TCP;unicast;interleaved=0-1"})
                zweite.a.anfrage("PLAY", zweite.a.basis, {"Range": "npt=now-"})
                beginn = time.time()
                while time.time() - beginn < 3.0:
                    kanal, roh = zweite.paket(3.0)
                    if kanal == 0:
                        andere = struct.unpack(">I", roh[8:12])[0]
                        assert andere != zustand["ssrc"], (
                            "zweite Sitzung bekommt dieselbe SSRC -- keine eigene Session")
                        # Die erste Sitzung muss weiterlaufen.
                        kanal1, roh1 = s.paket(3.0)
                        assert kanal1 in (0, 1), "erste Sitzung liefert nichts mehr"
                        return "beide Sitzungen bekommen Daten, eigene SSRC je Sitzung"
                raise AssertionError("zweite Sitzung bekommt binnen drei Sekunden nichts")
            finally:
                zweite.schliessen()
        messen("zwei Empfaenger gleichzeitig", zweiter_empfaenger)

        def keepalive():
            statuszeile, kopf = s.anfrage_im_strom("GET_PARAMETER")
            assert " 200 " in statuszeile, f"GET_PARAMETER beantwortet mit {statuszeile!r}"
            assert kopf.get("session"), "GET_PARAMETER antwortet ohne Session"
            return "Sitzung bleibt wach"
        messen("GET_PARAMETER haelt wach", keepalive)

        def teardown():
            statuszeile, _ = s.anfrage_im_strom("TEARDOWN")
            assert " 200 " in statuszeile, f"TEARDOWN beantwortet mit {statuszeile!r}"
            return statuszeile
        messen("TEARDOWN beendet sauber", teardown)
    finally:
        s.schliessen()
    return True


def pruefe_weboberflaeche(ip, name, e):
    """Die optionale Weboberflaeche, falls sie an ist."""
    def messen(bezeichnung, funktion):
        start = time.time()
        try:
            text = funktion() or ""
            e.dazu(name, bezeichnung, time.time() - start, text=text)
        except AssertionError as f:
            e.dazu(name, bezeichnung, time.time() - start, fehler=str(f))
        except Exception as f:
            e.dazu(name, bezeichnung, time.time() - start,
                   uebersprungen=f"nicht erreichbar ({type(f).__name__})")

    def status():
        with urllib.request.urlopen(f"http://{ip}:8080/api/status", timeout=5) as r:
            d = json.load(r)
        fehlend = [k for k in ("app", "device", "running", "url", "format", "restarts")
                   if k not in d]
        assert not fehlend, f"Statusfelder fehlen: {fehlend}"
        assert d.get("running"), "die App meldet, dass sie nicht laeuft"
        assert not d.get("error"), f"die App meldet einen Fehler: {d.get('status')}"
        return (f"{d['device']}, Android {d.get('android')}, App {d['app']}, "
                f"{d['format']}, {d['restarts']} Neustarts, {d.get('battery')}")
    messen("Weboberflaeche meldet gesunden Zustand", status)


def main():
    p = argparse.ArgumentParser(description="Laufende BirdMic-Geraete aktiv pruefen")
    p.add_argument("adressen", nargs="*", help="rtsp://... (ohne Angabe: suchen)")
    p.add_argument("--netz", default=None, help="Netz fuer die Suche, z.B. 192.168.1.0/24")
    p.add_argument("--port", type=int, default=8554)
    p.add_argument("--bericht", default=None, help="JUnit-XML hierhin schreiben")
    p.add_argument("--mindestens", type=int, default=1,
                   help="So viele Geraete muessen gefunden werden (Vorgabe 1)")
    args = p.parse_args()

    quellen = [(f"geraet{i + 1}", u) for i, u in enumerate(args.adressen)]
    if not quellen:
        netz = args.netz
        if not netz:
            gefunden = aufnehmen.mdns_suchen()
            quellen = list(gefunden)
        if not quellen:
            netz = netz or (aufnehmen.eigene_ip().rsplit(".", 1)[0] + ".0/24")
            print(f"Suche BirdMic in {netz} auf Port {args.port} ...")
            quellen = aufnehmen.netz_scannen(netz, args.port)

    if len(quellen) < args.mindestens:
        print(f"Nur {len(quellen)} Geraet(e) gefunden, {args.mindestens} verlangt.")
        return 1

    e = Ergebnis()
    for name, url in quellen:
        host = aufnehmen.zerlege_url(url)[0]
        # Der Name aus dem Statusdienst ist aussagekraeftiger als eine IP.
        try:
            with urllib.request.urlopen(f"http://{host}:8080/api/status", timeout=3) as r:
                name = json.load(r).get("device", name)
        except Exception:
            pass
        pruefe_geraet(url, name, e)
        pruefe_weboberflaeche(host, name, e)

    print(f"\n{len(e.faelle)} Pruefungen, {e.fehlerzahl} fehlgeschlagen, "
          f"{len(quellen)} Geraet(e)")
    if args.bericht:
        e.xml(args.bericht)
        print("Bericht:", args.bericht)
    return 1 if e.fehlerzahl else 0


if __name__ == "__main__":
    sys.exit(main())
