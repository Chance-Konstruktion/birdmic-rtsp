"""Eine Attrappe, die sich wie BirdMic verhaelt -- zum Pruefen ohne Telefone.

Startet drei RTSP-Server auf 127.0.0.1, die dasselbe Protokoll sprechen wie
`RtspServer.kt`: L16 ueber RTP, TCP-interleaved, RTCP Sender Reports. Das
Geraeusch ist an die Wanduhr gekoppelt, alle drei "hoeren" also dasselbe
zur selben Zeit -- unterschiedlich laut, mit unterschiedlichem Startversatz,
und einer verliert absichtlich jedes 50. Paket.

Damit laesst sich pruefen, ob `aufnehmen.py` und `auswerten.py` tun, was sie
behaupten: ob Luecken erkannt und gefuellt werden, ob der Zeitversatz mit
richtigem Vorzeichen herauskommt, und ob der Bericht ein mehrdeutiges
Ergebnis auch als mehrdeutig ausweist.

    python attrappe.py

    python aufnehmen.py --dauer 25 rtsp://127.0.0.1:8554/audio.wav         rtsp://127.0.0.1:8555/audio.wav rtsp://127.0.0.1:8556/audio.wav
    python auswerten.py

Erwartet: Geraet 2 verliert rund jedes 50. Paket und startet 300 ms spaeter,
und genau diese 300 ms muessen im Bericht in beiden Spalten der Zeitachse
stehen -- einmal aus der Uhr, einmal aus dem Pegelverlauf.
"""
import math, random, socket, struct, sys, threading, time

NTP = 2_208_988_800
RATE = 48000
FRAMES = 960  # 20 ms


def sende(sock, kanal, daten):
    sock.sendall(b"$" + bytes([kanal]) + struct.pack(">H", len(daten)) + daten)


def bediene(conn, name, verlust, verzoegerung, pegel):
    f = conn.makefile("rb")
    seq = random.randint(0, 0xFFFF)
    ts = random.randint(0, 2**31 // 2)
    ssrc = random.randint(0, 2**31)
    sitzung = str(random.randint(10**7, 10**8))
    spielt = False

    def kopf(code, txt, cseq, extra=b"", rumpf=b""):
        z = [f"RTSP/1.0 {code} {txt}", f"CSeq: {cseq}"]
        s = ("\r\n".join(z) + "\r\n").encode() + extra
        if rumpf:
            s += b"Content-Type: application/sdp\r\nContent-Length: %d\r\n\r\n" % len(rumpf) + rumpf
        else:
            s += b"\r\n"
        conn.sendall(s)

    while not spielt:
        zeilen = []
        while True:
            z = f.readline()
            if not z or z in (b"\r\n", b"\n"):
                break
            zeilen.append(z.decode().strip())
        if not zeilen:
            return
        methode = zeilen[0].split()[0]
        cseq = "0"
        for z in zeilen[1:]:
            if z.lower().startswith("cseq:"):
                cseq = z.split(":", 1)[1].strip()
        if methode == "OPTIONS":
            kopf(200, "OK", cseq, b"Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n")
        elif methode == "DESCRIBE":
            sdp = (
                "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=BirdMic\r\nc=IN IP4 0.0.0.0\r\n"
                "t=0 0\r\nm=audio 0 RTP/AVP 96\r\n"
                f"a=rtpmap:96 L16/{RATE}/1\r\na=control:trackID=0\r\n"
            ).encode()
            kopf(200, "OK", cseq, b"", sdp)
        elif methode == "SETUP":
            kopf(200, "OK", cseq,
                 f"Session: {sitzung};timeout=60\r\n"
                 "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n".encode())
        elif methode == "PLAY":
            kopf(200, "OK", cseq,
                 f"Session: {sitzung}\r\nRange: npt=now-\r\n"
                 f"RTP-Info: url=x;seq={seq};rtptime={ts}\r\n".encode())
            spielt = True
        else:
            kopf(200, "OK", cseq, f"Session: {sitzung}\r\n".encode())

    time.sleep(verzoegerung)
    start = time.time()
    n = 0
    letztes_rtcp = 0.0
    try:
        while time.time() - start < 60:
            soll = start + n * FRAMES / RATE
            warten = soll - time.time()
            if warten > 0:
                time.sleep(warten)
            t0 = n * FRAMES
            werte = []
            # alle zwei Sekunden ein Tonstoss, sonst Rauschen
            # unregelmaessig, aber auf allen Attrappen gleich -- so wie ein
            # echtes Geraeusch, das alle drei Telefone zugleich hoeren
            # An die Wanduhr gekoppelt: alle Attrappen hoeren dasselbe
            # Geraeusch zur selben absoluten Zeit, so wie drei Telefone
            # nebeneinander. Nur damit laesst sich pruefen, ob der
            # Zeitversatz mit richtigem Vorzeichen herauskommt.
            burst = random.Random(int((start + t0 / RATE) * 2)).random() < 0.35
            for i in range(FRAMES):
                t = (t0 + i) / RATE
                v = random.gauss(0, 200)
                if burst:
                    v += pegel * math.sin(2 * math.pi * 3000 * t)
                werte.append(max(-32768, min(32767, int(v))))
            nutz = struct.pack(">%dh" % FRAMES, *werte)
            if not (verlust and n % verlust == 0 and n > 0):
                paket = struct.pack(">BBHII", 0x80, 96, seq & 0xFFFF, ts & 0xFFFFFFFF, ssrc) + nutz
                sende(conn, 0, paket)
            seq = (seq + 1) & 0xFFFF
            ts = (ts + FRAMES) & 0xFFFFFFFF
            n += 1
            if time.time() - letztes_rtcp > 2.0:
                letztes_rtcp = time.time()
                ms = int(time.time() * 1000)
                sr = struct.pack(">BBH", 0x80, 200, 6) + struct.pack(
                    ">IIIIII", ssrc, ms // 1000 + NTP,
                    int((ms % 1000) * 2**32 / 1000), ts & 0xFFFFFFFF, n, n * FRAMES * 2)
                sende(conn, 1, sr)
    except (BrokenPipeError, ConnectionResetError, OSError):
        pass


def server(port, name, verlust, verzoegerung, pegel):
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", port))
    s.listen(5)
    while True:
        conn, _ = s.accept()
        threading.Thread(target=bediene, args=(conn, name, verlust, verzoegerung, pegel),
                         daemon=True).start()


if __name__ == "__main__":
    # port, Verlust (jedes n-te Paket faellt aus, 0=keins), Startverzoegerung, Tonpegel
    aufbau = [(8554, "a", 0, 0.0, 8000), (8555, "b", 50, 0.30, 3000), (8556, "c", 0, 0.0, 12000)]
    for port, name, verlust, verz, pegel in aufbau:
        threading.Thread(target=server, args=(port, name, verlust, verz, pegel), daemon=True).start()
    print("Attrappen laufen auf 8554, 8555, 8556")
    time.sleep(120)
