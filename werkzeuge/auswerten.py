#!/usr/bin/env python3
"""Wertet einen Aufnahmelauf von `aufnehmen.py` aus.

Die Frage, um die es geht, ist nicht "wie klingt das", sondern: **welches
der drei Telefone liefert BirdNET das bessere Signal?** Das entscheidet
sich an vier Dingen, und alle vier stehen hinterher im Bericht:

  Grundrauschen   Wie leise ist die Stille? Ein Geraet mit 10 dB weniger
                  Rauschen hoert einen Ruf, den das andere im Rauschen
                  begraebt. Das ist die wichtigste Zahl ueberhaupt.

  Bandverteilung  Vogelrufe liegen ueberwiegend zwischen 2 und 8 kHz. Ein
                  Mikrofon, das dort abfaellt, ist fuer diesen Zweck
                  schlechter, auch wenn es "lauter" wirkt -- Lautheit
                  kommt meist aus den Bassanteilen, die BirdNET nichts
                  nuetzen.

  Aussetzer       Verlorene Pakete und eingefuegte Stille. Ein Geraet, das
                  alle paar Minuten eine Luecke hat, verliert genau die
                  Ereignisse, wegen derer es dasteht.

  Taktdrift       Weicht der Audiotakt von der Uhr ab, laufen zwei
                  Aufnahmen ueber eine Nacht sekundenweise auseinander.

Zusaetzlich prueft das Programm die Zeitachse gegen: Es vergleicht den
Versatz, den die Uhren der Telefone behaupten, mit dem Versatz, den der
gemessene Pegelverlauf zeigt. Stimmen beide ueberein, sind die Uhren
verlaesslich und die Aufnahmen wirklich synchron. Weichen sie ab, steht
das ausdruecklich im Bericht -- statt dass alles Weitere stillschweigend
auf einer falschen Annahme aufbaut.

Aufruf:

    python auswerten.py
    python auswerten.py C:\\Users\\Chris\\gitlab\\birdmic-rtsp\\werkzeuge\\aufnahmen\\2026-08-20_143000

Ohne Argument wird der neueste Ordner unter `aufnahmen/` genommen.
Gebraucht wird nur die Standardbibliothek.
"""

import argparse
import cmath
import datetime
import glob
import json
import math
import os
import sys
import wave

# Fenstergroesse fuer die Frequenzanalyse. 4096 Punkte sind bei 48 kHz gut
# 12 Hz Aufloesung -- fein genug, und noch schnell genug ohne numpy.
FFT_N = 4096
# So viele Fenster werden ueber die Datei verteilt ausgewertet.
FENSTER = 48
# Bandgrenzen in Hz. Die Grenze bei 2000 und 8000 ist keine Willkuer:
# dazwischen liegt der Bereich, in dem BirdNET Arten unterscheidet.
BAENDER = [
    ("unter 250 Hz", 0, 250),
    ("250 Hz - 1 kHz", 250, 1000),
    ("1 - 2 kHz", 1000, 2000),
    ("2 - 4 kHz", 2000, 4000),
    ("4 - 8 kHz", 4000, 8000),
    ("ueber 8 kHz", 8000, 999999),
]


def db(wert, bezug=32768.0):
    """Linearer Wert -> dBFS. Null wird zu einer Zahl, die nicht luegt."""
    if wert <= 0:
        return -120.0
    return round(20 * math.log10(wert / bezug), 1)


def fft(werte):
    """Iterative Radix-2-FFT. Reicht fuer 4096 Punkte ohne Fremdbibliothek."""
    n = len(werte)
    ergebnis = list(werte)
    # Bit-Umkehr-Sortierung
    j = 0
    for i in range(1, n):
        bit = n >> 1
        while j & bit:
            j ^= bit
            bit >>= 1
        j |= bit
        if i < j:
            ergebnis[i], ergebnis[j] = ergebnis[j], ergebnis[i]
    laenge = 2
    while laenge <= n:
        winkel = -2j * cmath.pi / laenge
        schritt = cmath.exp(winkel)
        for start in range(0, n, laenge):
            w = 1 + 0j
            for k in range(start, start + laenge // 2):
                u = ergebnis[k]
                v = ergebnis[k + laenge // 2] * w
                ergebnis[k] = u + v
                ergebnis[k + laenge // 2] = u - v
                w *= schritt
        laenge <<= 1
    return ergebnis


def hann(n):
    return [0.5 - 0.5 * math.cos(2 * math.pi * i / (n - 1)) for i in range(n)]


def spektrum(pfad):
    """Mittleres Spektrum einer WAV-Datei, ueber die ganze Laenge verteilt."""
    with wave.open(pfad, "rb") as w:
        kanaele = w.getnchannels()
        rate = w.getframerate()
        gesamt = w.getnframes()
        if gesamt < FFT_N * 2:
            return None, rate
        fenster = hann(FFT_N)
        summe = [0.0] * (FFT_N // 2)
        genommen = 0
        abstand = max(FFT_N, (gesamt - FFT_N) // FENSTER)
        pos = 0
        while pos + FFT_N <= gesamt and genommen < FENSTER:
            w.setpos(pos)
            roh = w.readframes(FFT_N)
            import array as _a
            proben = _a.array("h")
            proben.frombytes(roh)
            if kanaele > 1:
                proben = proben[::kanaele]  # nur der linke Kanal
            if len(proben) < FFT_N:
                break
            block = [complex(proben[i] * fenster[i], 0.0) for i in range(FFT_N)]
            for i, wert in enumerate(fft(block)[: FFT_N // 2]):
                summe[i] += abs(wert)
            genommen += 1
            pos += abstand
        if not genommen:
            return None, rate
        return [s / genommen for s in summe], rate


def baender(spek, rate):
    """Spektrum in die Baender zusammenfassen, jeweils als Mittelwert in dBFS."""
    if not spek:
        return {}
    n = len(spek) * 2
    ergebnis = {}
    for name, unten, oben in BAENDER:
        i0 = max(1, int(unten * n / rate))
        i1 = min(len(spek), int(oben * n / rate) + 1)
        if i1 <= i0:
            ergebnis[name] = -120.0
            continue
        mittel = sum(spek[i0:i1]) / (i1 - i0)
        # Normierung auf Vollaussteuerung: FFT-Laenge und Fenstergewinn raus.
        ergebnis[name] = db(mittel / (FFT_N / 4))
    return ergebnis


def perzentil(werte, anteil):
    if not werte:
        return 0.0
    sortiert = sorted(werte)
    i = min(len(sortiert) - 1, max(0, int(anteil * (len(sortiert) - 1))))
    return sortiert[i]


def versatz_aus_verlauf(a, b, max_bloecke=100):
    """Bester Versatz zwischen zwei Pegelverlaeufen, in Bloecken.

    Kreuzkorrelation auf der Huellkurve, nicht auf dem Signal: bei 10 Hz
    Blockrate reicht das nicht fuer eine Richtungsbestimmung, aber es
    beantwortet die Frage, um die es hier geht -- stimmen die Uhren?
    """
    if len(a) < 20 or len(b) < 20:
        return None, 0.0, False
    mindest = int(0.5 * min(len(a), len(b)))
    werte = []
    for versatz in range(-max_bloecke, max_bloecke + 1):
        i0 = max(0, -versatz)
        i1 = min(len(a), len(b) - versatz)
        if i1 - i0 < mindest:
            continue
        x = a[i0:i1]
        y = b[i0 + versatz:i1 + versatz]
        n = len(x)
        mx = sum(x) / n
        my = sum(y) / n
        zaehler = sum((x[i] - mx) * (y[i] - my) for i in range(n))
        nx = math.sqrt(sum((v - mx) ** 2 for v in x))
        ny = math.sqrt(sum((v - my) ** 2 for v in y))
        if nx == 0 or ny == 0:
            continue
        werte.append((versatz, zaehler / (nx * ny)))
    if not werte:
        return None, 0.0, False
    bester, bestwert = max(werte, key=lambda e: e[1])
    # Ein periodisches Geraeusch -- Pumpe, Verkehr, Netzbrummen -- erzeugt
    # mehrere gleich gute Uebereinstimmungen. Dann ist der beste Wert kein
    # Ergebnis, sondern ein Zufall unter mehreren. Das muss in den Bericht.
    mehrdeutig = any(
        w >= bestwert * 0.95 and abs(v - bester) > 3
        for v, w in werte
        if bestwert > 0
    )
    return bester, round(bestwert, 3), mehrdeutig


def zeit(ts):
    if not ts:
        return "unbekannt"
    return datetime.datetime.fromtimestamp(ts).strftime("%H:%M:%S.%f")[:-3]


def ausschneiden(quelle, ziel, von_sek, bis_sek):
    """Ein Stueck aus einer WAV-Datei in eine neue schreiben."""
    with wave.open(quelle, "rb") as w:
        rate = w.getframerate()
        anfang = max(0, int(von_sek * rate))
        laenge = min(w.getnframes() - anfang, int((bis_sek - von_sek) * rate))
        if laenge <= 0:
            return False
        w.setpos(anfang)
        daten = w.readframes(laenge)
        with wave.open(ziel, "wb") as z:
            z.setnchannels(w.getnchannels())
            z.setsampwidth(w.getsampwidth())
            z.setframerate(rate)
            z.writeframes(daten)
    return True


def main():
    p = argparse.ArgumentParser(description="Einen Aufnahmelauf auswerten")
    p.add_argument("ordner", nargs="?", default=None,
                   help="Aufnahmeordner (Vorgabe: der neueste)")
    p.add_argument("--ausschnitte", type=int, default=0,
                   help="So viele der lautesten Stellen je Geraet als eigene "
                        "WAV-Datei herausschneiden (Vorgabe 0 = keine)")
    p.add_argument("--rand", type=float, default=3.0,
                   help="Sekunden Vorlauf und Nachlauf je Ausschnitt (Vorgabe 3)")
    args = p.parse_args()

    hier = os.path.dirname(os.path.abspath(__file__))
    if args.ordner:
        ordner = args.ordner
    else:
        kandidaten = sorted(glob.glob(os.path.join(hier, "aufnahmen", "*")))
        if not kandidaten:
            print("Keine Aufnahmen gefunden. Erst aufnehmen.py laufen lassen.")
            return 1
        ordner = kandidaten[-1]
    if not os.path.isdir(ordner):
        print("Kein Ordner:", ordner)
        return 1

    dateien = sorted(
        p for p in glob.glob(os.path.join(ordner, "*.json"))
        if os.path.basename(p) != "lauf.json"
    )
    if not dateien:
        print("Keine Geraetedaten in", ordner)
        return 1

    geraete = []
    for p in dateien:
        with open(p, encoding="utf-8") as f:
            geraete.append(json.load(f))

    zeilen = []
    zeilen.append(f"# Auswertung {os.path.basename(ordner)}\n")
    zeilen.append(
        "Erzeugt von `auswerten.py`. Alle Pegel in dBFS, also relativ zur "
        "Vollaussteuerung -- 0 dB waere Uebersteuerung, -60 dB ist sehr leise.\n"
    )

    # -- Was ueberhaupt angekommen ist ------------------------------------
    zeilen.append("## Was angekommen ist\n")
    zeilen.append("| Geraet | Dauer | Rate | Pakete | verloren | Stille eingefuegt | uebersteuert | Taktdrift |")
    zeilen.append("| --- | --- | --- | --- | --- | --- | --- | --- |")
    abbrueche = []
    for g in geraete:
        # Ein Abbruch mittendrin macht das, was bis dahin ankam, nicht
        # wertlos. Frueher fiel ein solches Geraet aus der ganzen
        # Auswertung heraus -- damit verschwand mit dem Fehler auch die
        # Messung. Der Fehler gehoert daneben, nicht anstelle.
        if g.get("fehler"):
            abbrueche.append((g["name"], g["fehler"]))
        if not g.get("verlauf"):
            zeilen.append(
                f"| {g['name']} | -- | | | | | | |" if not g.get("fehler")
                else f"| {g['name']} | **nichts empfangen** | | | | | | |"
            )
            continue
        drift = f"{g['taktdrift_ppm']} ppm" if g.get("taktdrift_ppm") is not None else "zu kurz"
        marke = " ⚠" if g.get("fehler") else ""
        zeilen.append(
            f"| {g['name']}{marke} | {g['sekunden']:.1f} s | {g['rate']} Hz | {g['pakete']} | "
            f"{g['verlorene_pakete']} | {g['eingefuegte_stille_sekunden']:.2f} s | "
            f"{g['uebersteuerte_samples']} | {drift} |"
        )
    zeilen.append("")
    if abbrueche:
        zeilen.append("Abgebrochen waehrend der Aufnahme:\n")
        for name, fehler in abbrueche:
            zeilen.append(f"- **{name}**: {fehler}")
        zeilen.append("")
        zeilen.append(
            "Was bis zum Abbruch ankam, ist unten trotzdem ausgewertet -- die "
            "Dauer in der Tabelle sagt, wie viel das war. Eine kuerzere "
            "Aufnahme ist weniger belastbar, aber sie ist nicht nichts.\n"
        )

    lebend = [g for g in geraete if g.get("verlauf")]

    # -- Zeitachse ---------------------------------------------------------
    zeilen.append("## Zeitachse\n")
    mit_uhr = [g for g in lebend if g.get("erstes_sample_ntp")]
    if len(mit_uhr) < 2:
        zeilen.append(
            "Weniger als zwei Geraete haben einen RTCP Sender Report geliefert. "
            "Ohne den gibt es keine gemeinsame Zeitreferenz -- die Aufnahmen "
            "lassen sich nur noch ungefaehr nebeneinanderlegen. Bei kurzen "
            "Laeufen (unter 5 s) ist das normal, sonst ein Hinweis.\n"
        )
    else:
        bezug = mit_uhr[0]
        zeilen.append(f"Bezug ist **{bezug['name']}**, erstes Sample {zeit(bezug['erstes_sample_ntp'])}.\n")
        zeilen.append("| Geraet | erstes Sample | laut Uhr | laut Pegelverlauf | Abweichung |")
        zeilen.append("| --- | --- | --- | --- | --- |")
        block_s = bezug.get("block_sekunden", 0.1)
        for g in mit_uhr:
            uhr = (g["erstes_sample_ntp"] - bezug["erstes_sample_ntp"]) * 1000.0
            if g is bezug:
                zeilen.append(f"| {g['name']} | {zeit(g['erstes_sample_ntp'])} | 0 ms | 0 ms | -- |")
                continue
            versatz, guete, mehrdeutig = versatz_aus_verlauf(
                [b[0] for b in bezug["verlauf"]], [b[0] for b in g["verlauf"]]
            )
            gemessen = -versatz * block_s * 1000.0 if versatz is not None else None
            if gemessen is None or guete < 0.3:
                zeilen.append(
                    f"| {g['name']} | {zeit(g['erstes_sample_ntp'])} | {uhr:+.0f} ms | "
                    f"kein Zusammenhang (Guete {guete}) | -- |"
                )
            elif mehrdeutig:
                zeilen.append(
                    f"| {g['name']} | {zeit(g['erstes_sample_ntp'])} | {uhr:+.0f} ms | "
                    f"mehrdeutig, {gemessen:+.0f} ms unter mehreren | nicht pruefbar |"
                )
            else:
                zeilen.append(
                    f"| {g['name']} | {zeit(g['erstes_sample_ntp'])} | {uhr:+.0f} ms | "
                    f"{gemessen:+.0f} ms (Guete {guete}) | {abs(uhr - gemessen):.0f} ms |"
                )
        zeilen.append("")
        zeilen.append(
            "Die Spalte *laut Uhr* kommt aus den Wanduhrzeiten der Telefone, "
            "*laut Pegelverlauf* aus der Kreuzkorrelation der gemessenen "
            "Lautstaerke. Liegen beide nah beieinander, sind die Uhren "
            "verlaesslich und die Aufnahmen wirklich synchron. Weichen sie um "
            "mehr als ein paar hundert Millisekunden ab, gehen die Telefonuhren "
            "auseinander -- dann auf allen dreien die automatische Zeit "
            "einschalten und den Lauf wiederholen.\n"
        )
        zeilen.append(
            "Steht dort **mehrdeutig**, war das Geraeusch waehrend der Aufnahme "
            "periodisch -- eine Pumpe, Verkehr, ein Brummen. Dann passt der "
            "Pegelverlauf an mehreren Stellen gleich gut, und der Vergleich "
            "kann die Uhren weder bestaetigen noch widerlegen. Das ist kein "
            "Fehler der Aufnahme, sondern eine Grenze der Methode: aufgehoben "
            "wird sie durch einen Lauf mit unregelmaessigem Geraeusch.\n"
        )
        zeilen.append(
            "Die Aufloesung des Pegelvergleichs betraegt "
            f"{block_s * 1000:.0f} ms. Fuer die Frage, ob die Uhren stimmen, "
            "reicht das. Fuer eine Richtungsbestimmung aus Laufzeitunterschieden "
            "reicht es **nicht** -- dafuer waere ein Vergleich auf Sample-Ebene "
            "noetig, und die drei Geraete stehen fuer diesen Test ohnehin an "
            "derselben Position.\n"
        )

    # -- Pegel -------------------------------------------------------------
    zeilen.append("## Pegel\n")
    zeilen.append("| Geraet | Grundrauschen | Median | lauteste Sekunde | Spitze | Abstand Rausch/Spitze |")
    zeilen.append("| --- | --- | --- | --- | --- | --- |")
    for g in lebend:
        rms = [b[0] for b in g["verlauf"]]
        spitzen = [b[1] for b in g["verlauf"]]
        rausch = perzentil(rms, 0.10)
        median = perzentil(rms, 0.50)
        laut = perzentil(rms, 0.99)
        spitze = max(spitzen) if spitzen else 0
        zeilen.append(
            f"| {g['name']} | {db(rausch)} dB | {db(median)} dB | {db(laut)} dB | "
            f"{db(spitze)} dB | {db(spitze) - db(rausch):.1f} dB |"
        )
    zeilen.append("")
    zeilen.append(
        "*Grundrauschen* ist das 10-Prozent-Perzentil des Pegelverlaufs, also "
        "das, was in den leisesten Momenten uebrigbleibt. Es haengt von der "
        "eingestellten Verstaerkung ab -- ein Vergleich zwischen Geraeten ist "
        "nur bei gleicher Einstellung aussagekraeftig. Die letzte Spalte ist "
        "davon unabhaengig: sie sagt, wie viel Abstand zwischen Rauschen und "
        "lautestem Ereignis liegt, und das ist der eigentliche Nutzwert.\n"
    )

    # -- Frequenzen --------------------------------------------------------
    zeilen.append("## Frequenzverteilung\n")
    spektren = {}
    for g in lebend:
        wavpfad = os.path.join(ordner, g["name"] + ".wav")
        if not os.path.exists(wavpfad):
            continue
        print(f"Rechne Spektrum fuer {g['name']} ...", flush=True)
        spek, rate = spektrum(wavpfad)
        if spek:
            spektren[g["name"]] = baender(spek, rate)
    if spektren:
        namen = list(spektren)
        zeilen.append("| Band | " + " | ".join(namen) + " |")
        zeilen.append("| --- |" + " --- |" * len(namen))
        for bandname, _, _ in BAENDER:
            werte = [spektren[n].get(bandname, -120.0) for n in namen]
            beste = max(werte)
            spalten = []
            for w in werte:
                spalten.append(f"**{w}**" if w == beste else f"{w}")
            zeilen.append(f"| {bandname} | " + " | ".join(spalten) + " |")
        zeilen.append("")
        zeilen.append(
            "Fett ist jeweils der hoechste Wert im Band. Entscheidend sind die "
            "Zeilen **2 - 4 kHz** und **4 - 8 kHz**: dort liegt der Bereich, in "
            "dem BirdNET Arten unterscheidet. Ein Geraet, das nur unten fuehrt, "
            "ist lauter, aber nicht besser.\n"
        )
    else:
        zeilen.append("Keine WAV-Dateien zum Rechnen gefunden.\n")

    # -- Ereignisse --------------------------------------------------------
    zeilen.append("## Lauteste Stellen\n")
    zeilen.append(
        "Bloecke, die mehr als 12 dB ueber dem Grundrauschen liegen, "
        "zusammengefasst. Die Zeit ist der Abstand vom Dateianfang.\n"
    )
    for g in lebend:
        rms = [b[0] for b in g["verlauf"]]
        block_s = g.get("block_sekunden", 0.1)
        rausch = perzentil(rms, 0.10)
        schwelle = rausch * (10 ** (12 / 20.0))
        ereignisse = []
        i = 0
        while i < len(rms):
            if rms[i] > schwelle:
                start = i
                while i < len(rms) and rms[i] > schwelle:
                    i += 1
                ereignisse.append((start, i, max(rms[start:i])))
            else:
                i += 1
        ereignisse.sort(key=lambda e: -e[2])
        zeilen.append(f"**{g['name']}** -- {len(ereignisse)} Stellen")
        if not ereignisse:
            zeilen.append("")
            continue
        zeilen.append("")
        kopf = "| ab | Dauer | Spitzenpegel |"
        trenn = "| --- | --- | --- |"
        if args.ausschnitte:
            kopf += " Ausschnitt |"
            trenn += " --- |"
        zeilen.append(kopf)
        zeilen.append(trenn)
        wavpfad = os.path.join(ordner, g["name"] + ".wav")
        schnittordner = os.path.join(ordner, "ausschnitte")
        for nr, (start, stop, wert) in enumerate(ereignisse[:10], 1):
            zeile = (f"| {start * block_s:.1f} s | {(stop - start) * block_s:.1f} s | "
                     f"{db(wert)} dB |")
            if args.ausschnitte:
                schnitt = ""
                if nr <= args.ausschnitte and os.path.exists(wavpfad):
                    os.makedirs(schnittordner, exist_ok=True)
                    name = f"{g['name']}_{start * block_s:07.1f}s.wav"
                    if ausschneiden(
                        wavpfad, os.path.join(schnittordner, name),
                        start * block_s - args.rand, stop * block_s + args.rand,
                    ):
                        schnitt = f"`{name}`"
                zeile += f" {schnitt} |"
            zeilen.append(zeile)
        zeilen.append("")

    bericht = "\n".join(zeilen)
    ziel = os.path.join(ordner, "bericht.md")
    with open(ziel, "w", encoding="utf-8") as f:
        f.write(bericht)
    print("\n" + bericht)
    print("\nGeschrieben nach:", ziel)
    return 0


if __name__ == "__main__":
    sys.exit(main())
