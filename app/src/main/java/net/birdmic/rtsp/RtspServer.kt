package net.birdmic.rtsp

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Minimaler RTSP-1.0-Server, der genau einen Track anbietet: das Mikrofon als
 * unkomprimiertes L16-Audio. Unterstuetzt OPTIONS, DESCRIBE, SETUP, PLAY,
 * PAUSE, TEARDOWN, GET_PARAMETER -- das ist alles, was ffmpeg, VLC und
 * BirdNET-Pi anfassen.
 */
class RtspServer(
    private val port: Int,
    private val audio: AudioEngine,
    private val auth: DigestAuth? = null,
    /** Sekunden ohne Lebenszeichen, nach denen eine UDP-Session verworfen wird. 0 = nie. */
    private val udpTimeoutSeconds: Int = 0,
    /** Obergrenze gleichzeitiger Empfaenger insgesamt, 0 = unbegrenzt. */
    private val maxClients: Int = 0,
    /** Obergrenze je Absenderadresse, 0 = unbegrenzt. */
    private val maxClientsPerIp: Int = 0,
    private val onEvent: ((EventLog.Kind, String) -> Unit)? = null,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var reaperThread: Thread? = null
    @Volatile private var running = false

    private val connections = CopyOnWriteArrayList<Connection>()
    private val bytesSent = AtomicLong(0)

    /**
     * Anzahl der Clients, die gerade tatsaechlich Audio empfangen.
     *
     * Bewusst gezaehlt statt mitgefuehrt. Ein eigener Zaehler muss bei jedem
     * Zustandswechsel exakt einmal erhoeht und exakt einmal gesenkt werden --
     * und genau das ist in 2.3 schiefgegangen: unter Last stand hier -219.
     * Eine Zaehlung ueber die Liste kann per Konstruktion nicht driften und
     * niemals negativ werden. Bei einer Handvoll Verbindungen kostet sie nichts.
     */
    val playingClients: Int get() = connections.count { it.isPlaying }

    /** Summe der versendeten RTP-Nutzlast in Byte. */
    val totalBytesSent: Long get() = bytesSent.get()

    /** Kurzbeschreibung der verbundenen Clients fuer die Anzeige. */
    fun clientList(): List<String> = connections.mapNotNull { it.describeIfPlaying() }

    @Volatile var lastError: String? = null
        private set

    fun start(): Boolean {
        if (running) return true
        serverSocket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
                soTimeout = 1000
            }
        } catch (e: IOException) {
            lastError = "Port $port belegt: ${e.message}"
            Log.e(TAG, "bind fehlgeschlagen", e)
            return false
        }
        running = true
        lastError = null
        acceptThread = Thread(::acceptLoop, "birdmic-accept").apply { start() }
        if (udpTimeoutSeconds > 0) {
            reaperThread = Thread(::reapLoop, "birdmic-reaper").apply { start() }
        }
        return true
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        acceptThread?.join(2000)
        reaperThread?.join(2000)
        acceptThread = null
        reaperThread = null
        // Beim Abbau nicht auf fremde Sender-Threads warten -- die beenden sich
        // selbst, sobald ihre Warteschlange zu ist.
        connections.forEach { it.close(joinSender = false) }
        connections.clear()
    }

    private fun acceptLoop() {
        val server = serverSocket ?: return
        while (running) {
            val socket = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept fehlgeschlagen", e)
                continue
            }
            val connection = Connection(socket)
            connections.add(connection)
            enforceConnectionLimits(connection)
            Thread(connection::run, "birdmic-client-${socket.port}").start()
        }
    }

    /**
     * Begrenzt die Zahl offener Verbindungen, noch bevor daraus Sitzungen
     * werden.
     *
     * Die Grenze in 2.3 griff erst bei PLAY. Bis dahin hatte jeder Client
     * bereits Verbindung, RTSP-Handschlag und SETUP hinter sich -- und SETUP
     * belegt zwei UDP-Ports. Bei 250 Verbindungen sind das 500 Sockets, lange
     * bevor irgendeine Obergrenze auch nur nachgesehen hat.
     *
     * Der Spielraum ([CONNECTION_HEADROOM]) ist Absicht: ein Client, der eine
     * Steuerverbindung offen haelt und daneben eine zweite aufbaut, soll nicht
     * sofort abgeraeumt werden.
     */
    private fun enforceConnectionLimits(fresh: Connection) {
        if (maxClientsPerIp > 0) {
            val sameIp = connections.filter { it !== fresh && it.address() == fresh.address() }
            dropOldest(sameIp, maxClientsPerIp * CONNECTION_HEADROOM - 1, "Verbindungen je Adresse")
        }
        if (maxClients > 0) {
            val others = connections.filter { it !== fresh }
            dropOldest(others, maxClients * CONNECTION_HEADROOM - 1, "Verbindungen insgesamt")
        }
    }

    /**
     * Trennt die aeltesten Verbindungen, bis nur noch [keep] uebrig sind.
     *
     * **Ohne auf deren Threads zu warten.** Genau das war der zweite Fehler in
     * 2.3: Das Abraeumen lief mit `join(1000)` je Opfer auf dem Thread des
     * neuen Clients. Bei hundert Opfern stand der neue Client bis zu hundert
     * Sekunden still -- und galt in dieser Zeit selbst schon als spielend, also
     * als Opfer fuer den naechsten. Unter Last hat sich die Grenze damit selbst
     * ausgehebelt. Ein geschlossener Socket und eine geschlossene Warteschlange
     * genuegen; die betroffenen Threads beenden sich von allein.
     */
    private fun dropOldest(candidates: List<Connection>, keep: Int, reason: String) {
        if (keep < 0 || candidates.size <= keep) return
        val doomed = candidates.sortedBy { it.startedAt }.take(candidates.size - keep)
        for (connection in doomed) {
            Log.w(TAG, "Grenze $reason erreicht, trenne ${connection.address()}")
            onEvent?.invoke(
                EventLog.Kind.WARNING,
                "Grenze $reason erreicht – älteren Empfänger ${connection.address()} getrennt",
            )
            connection.close(joinSender = false)
        }
    }

    /**
     * Raeumt UDP-Sessions ab, von denen seit Laengerem nichts mehr kam.
     *
     * Bei TCP-interleaved meldet ein fehlgeschlagener Versand den toten Client;
     * bei UDP gibt es dieses Signal nicht, und ein Empfaenger, der ohne
     * TEARDOWN verschwindet, wuerde sonst ewig bedient. Clients halten die
     * Session ueber GET_PARAMETER wach -- der Timeout steht im SETUP.
     */
    private fun reapLoop() {
        val timeoutMs = udpTimeoutSeconds * 1000L
        while (running) {
            // Kurz genug, damit stop() nicht auf den Schlaf warten muss.
            Thread.sleep(1000)
            val now = System.currentTimeMillis()
            for (connection in connections) {
                if (connection.isStaleUdp(now, timeoutMs)) {
                    Log.w(TAG, "UDP-Session ohne Lebenszeichen wird verworfen")
                    onEvent?.invoke(
                        EventLog.Kind.WARNING,
                        "UDP-Empfänger ${connection.address()} ohne Lebenszeichen verworfen",
                    )
                    // Fremder Thread: nicht warten.
                    connection.close(joinSender = false)
                }
            }
        }
    }

    /** Eine TCP-Verbindung eines Clients, inklusive der daran haengenden RTP-Session. */
    private inner class Connection(private val socket: Socket) : AudioEngine.Sink {
        private val input = BufferedInputStream(socket.getInputStream())
        private val output = BufferedOutputStream(socket.getOutputStream())
        private val sessionId = Random.nextInt(100_000, 999_999).toString()

        @Volatile private var sender: RtpSender? = null
        private var transportHeader: String = ""
        private var usesUdp = false

        // Atomar, weil close() aus mehreren Threads kommen kann: Verbindungs-
        // Thread, Sender-Thread, Reaper und server.stop(). Ein einfaches
        // Boolean wuerde den Client-Zaehler doppelt herunterzaehlen.
        private val playingFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val isPlaying: Boolean get() = playingFlag.get()

        /** Fuer die Frage, welche Session bei Ueberschreitung des Limits weichen muss. */
        val startedAt: Long = System.currentTimeMillis()

        @Volatile private var lastRequestAt = System.currentTimeMillis()

        // Der Versand laeuft in einem eigenen Thread, damit ein langsamer
        // Empfaenger niemals den Aufnahme-Thread anhaelt.
        private val packetBytes = audio.framesPerPacket * audio.channels * 2
        @Volatile private var queue = PacketQueue(QUEUE_CAPACITY, packetBytes)
        @Volatile private var senderThread: Thread? = null

        /** Wie viele Pakete dieser Client verpasst hat, weil er zu langsam war. */
        val droppedPackets: Long get() = queue.dropped

        fun address(): String = socket.inetAddress.hostAddress ?: "?"

        fun describeIfPlaying(): String? =
            if (isPlaying) "${address()} (${if (usesUdp) "UDP" else "TCP"})" else null

        fun isStaleUdp(now: Long, timeoutMs: Long): Boolean =
            isPlaying && usesUdp && now - lastRequestAt > timeoutMs

        fun run() {
            socket.soTimeout = 0
            socket.tcpNoDelay = true
            // Betriebssystem-Keepalive: erkennt Gegenstellen, die ohne FIN
            // verschwinden (Stromausfall, WLAN weg).
            runCatching { socket.keepAlive = true }
            // Kleiner Sendepuffer mit Absicht. Der Ringpuffer oben kann nur
            // verwerfen, was er selbst haelt -- was schon im Kernelpuffer liegt,
            // wird auf jeden Fall zugestellt. Ein grosser Puffer wuerde bei
            // einem haengenden Client sekundenlang altes Audio ansammeln und
            // den Empfaenger dauerhaft hinter die Gegenwart fallen lassen.
            runCatching { socket.sendBufferSize = SEND_BUFFER_BYTES }
            try {
                while (running && !socket.isClosed) {
                    val request = readRequest() ?: break
                    lastRequestAt = System.currentTimeMillis()
                    handle(request)
                }
            } catch (e: IOException) {
                Log.d(TAG, "Verbindung beendet: ${e.message}")
            } finally {
                val wasPlaying = isPlaying
                close()
                connections.remove(this)
                if (wasPlaying) {
                    onEvent?.invoke(EventLog.Kind.CLIENT_OUT, "Empfänger ${address()} getrennt")
                }
            }
        }

        /**
         * Liest die naechste RTSP-Nachricht.
         *
         * Bei RTSP ueber TCP teilen sich Steuerung und Medien dieselbe
         * Verbindung: ein Byte '$' leitet einen eingebetteten RTP/RTCP-Rahmen
         * ein (RFC 2326, 10.12). Clients schicken darueber ihre RTCP Receiver
         * Reports zurueck. Diese Rahmen muessen sauber ueberlesen werden --
         * sonst landet Binaerdaten im Textparser und die Verbindung stirbt.
         */
        private fun readRequest(): Request? {
            while (true) {
                input.mark(1)
                val first = input.read()
                if (first == -1) return null
                if (first == INTERLEAVED_MARKER) {
                    if (!skipInterleavedFrame()) return null
                    continue
                }
                input.reset()
                return readRtspRequest()
            }
        }

        /** Kanal, 16-Bit-Laenge, Nutzlast -- wird gelesen und verworfen. */
        private fun skipInterleavedFrame(): Boolean {
            if (input.read() == -1) return false // Kanalnummer
            val high = input.read()
            val low = input.read()
            if (high == -1 || low == -1) return false
            var remaining = (high shl 8) or low // maximal 65535, keine Obergrenze noetig
            while (remaining > 0) {
                val skipped = input.skip(remaining.toLong())
                if (skipped <= 0) {
                    // skip() liefert 0, wenn noch nichts gepuffert ist -- dann einzeln lesen.
                    if (input.read() == -1) return false
                    remaining--
                } else {
                    remaining -= skipped.toInt()
                }
            }
            return true
        }

        private fun readRtspRequest(): Request? {
            val lines = ArrayList<String>()
            while (true) {
                val line = readLine() ?: return null
                if (line.isEmpty()) {
                    if (lines.isEmpty()) continue // Leerzeilen zwischen Requests ignorieren
                    break
                }
                lines.add(line)
                if (lines.size > MAX_HEADERS) throw IOException("Zu viele Kopfzeilen")
            }
            val requestLine = lines[0].split(' ')
            if (requestLine.size < 2) return null

            val headers = HashMap<String, String>()
            for (i in 1 until lines.size) {
                val colon = lines[i].indexOf(':')
                if (colon > 0) {
                    headers[lines[i].substring(0, colon).trim().lowercase()] =
                        lines[i].substring(colon + 1).trim()
                }
            }
            skipBody(headers["content-length"]?.toIntOrNull() ?: 0)
            return Request(requestLine[0].uppercase(), requestLine[1], headers)
        }

        /** Body ueberlesen (z.B. bei ANNOUNCE/SET_PARAMETER), mit harter Obergrenze. */
        private fun skipBody(length: Int) {
            if (length <= 0) return
            if (length > MAX_BODY_BYTES) throw IOException("Body zu gross: $length")
            var remaining = length
            while (remaining > 0) {
                val skipped = input.skip(remaining.toLong())
                if (skipped <= 0) {
                    if (input.read() == -1) return
                    remaining--
                } else {
                    remaining -= skipped.toInt()
                }
            }
        }

        /**
         * Liest eine Zeile mit Laengenbegrenzung. Ohne die koennte ein kaputter
         * oder boesartiger Client den Speicher mit einer endlosen Zeile fluten.
         */
        private fun readLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c == -1) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(c.toChar())
                if (sb.length > MAX_LINE_LENGTH) throw IOException("Zeile zu lang")
            }
        }

        private fun handle(request: Request) {
            val cseq = request.headers["cseq"] ?: "0"

            // OPTIONS bleibt frei: Clients ermitteln damit erst, ob der Server lebt.
            val guard = auth
            if (guard != null && guard.enabled && request.method != "OPTIONS") {
                if (!guard.verify(request.headers["authorization"], request.method)) {
                    respond(
                        cseq, 401, "Unauthorized",
                        mapOf("WWW-Authenticate" to guard.challenge()),
                    )
                    return
                }
            }

            when (request.method) {
                "OPTIONS" -> respond(
                    cseq, 200, "OK",
                    mapOf("Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER"),
                )

                "DESCRIBE" -> {
                    val sdp = buildSdp(socket.localAddress.hostAddress ?: "0.0.0.0")
                    respond(
                        cseq, 200, "OK",
                        mapOf(
                            "Content-Base" to "${request.uri.trimEnd('/')}/",
                            "Content-Type" to "application/sdp",
                        ),
                        sdp,
                    )
                }

                "SETUP" -> handleSetup(request, cseq)
                "PLAY" -> handlePlay(cseq, request.uri)
                "PAUSE" -> {
                    stopStreaming(joinSender = true)
                    respond(cseq, 200, "OK", mapOf("Session" to sessionId))
                }
                "TEARDOWN" -> {
                    respond(cseq, 200, "OK", mapOf("Session" to sessionId))
                    close()
                }
                "GET_PARAMETER" -> respond(cseq, 200, "OK", mapOf("Session" to sessionId))
                else -> respond(cseq, 501, "Not Implemented", emptyMap())
            }
        }

        private fun handleSetup(request: Request, cseq: String) {
            val transport = request.headers["transport"].orEmpty()

            // A repeated SETUP replaces the transport. Stop an active sender and
            // release its sockets before creating a new RTP/RTCP transport.
            if (isPlaying) stopStreaming(joinSender = true)
            sender?.close()
            sender = null
            queue = PacketQueue(QUEUE_CAPACITY, packetBytes)

            try {
                if (transport.contains("/TCP", ignoreCase = true)) {
                    val channels = Regex("interleaved=(\\d+)-(\\d+)").find(transport)
                    val rtpChannel = channels?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val rtcpChannel = channels?.groupValues?.get(2)?.toIntOrNull() ?: (rtpChannel + 1)
                    sender = RtpSender(TcpInterleavedTransport(output, rtpChannel, rtcpChannel))
                    usesUdp = false
                    transportHeader =
                        "RTP/AVP/TCP;unicast;interleaved=$rtpChannel-$rtcpChannel;ssrc=${ssrcHex()}"
                } else {
                    val ports = Regex("client_port=(\\d+)-(\\d+)").find(transport)
                    if (ports == null) {
                        respond(cseq, 461, "Unsupported Transport", emptyMap())
                        return
                    }
                    val clientRtp = ports.groupValues[1].toInt()
                    val clientRtcp = ports.groupValues[2].toInt()
                    val udp = UdpTransport(socket.inetAddress, clientRtp, clientRtcp)
                    sender = RtpSender(udp)
                    usesUdp = true
                    transportHeader = "RTP/AVP/UDP;unicast;client_port=$clientRtp-$clientRtcp;" +
                        "server_port=${udp.serverRtpPort}-${udp.serverRtcpPort};ssrc=${ssrcHex()}"
                }
            } catch (e: Exception) {
                Log.w(TAG, "SETUP fehlgeschlagen", e)
                respond(cseq, 500, "Internal Server Error", emptyMap())
                return
            }
            val timeout = if (udpTimeoutSeconds > 0) udpTimeoutSeconds else 60
            respond(
                cseq, 200, "OK",
                mapOf("Session" to "$sessionId;timeout=$timeout", "Transport" to transportHeader),
            )
        }

        private fun handlePlay(cseq: String, uri: String) {
            val active = sender
            if (active == null) {
                respond(cseq, 455, "Method Not Valid In This State", emptyMap())
                return
            }
            respond(
                cseq, 200, "OK",
                mapOf(
                    "Session" to sessionId,
                    "Range" to "npt=now-",
                    "RTP-Info" to "url=$uri;seq=${active.startSequence};rtptime=${active.startTimestamp}",
                ),
            )
            if (playingFlag.compareAndSet(false, true)) {
                // PAUSE closes its queue to unblock the sender thread. A resumed
                // PLAY therefore needs a fresh queue or it would return 200 OK
                // while silently discarding every subsequent audio packet.
                queue = PacketQueue(QUEUE_CAPACITY, packetBytes)
                // Reihenfolge ist hier wesentlich: erst vollstaendig lebendig
                // werden, dann aufraeumen. In 2.3 stand das Abraeumen davor --
                // dadurch galt diese Verbindung bereits als spielend, waehrend
                // sie noch gar keinen Sender hatte. Sie tauchte in der Liste
                // auf, lieferte aber keinen Ton, und ein anderer Thread konnte
                // sie in diesem Zwischenzustand abraeumen.
                senderThread = Thread(::sendLoop, "birdmic-send-${address()}").apply { start() }
                audio.addSink(this)
                Log.i(TAG, "Client ${address()} spielt")
                onEvent?.invoke(
                    EventLog.Kind.CLIENT_IN,
                    "Empfänger ${address()} verbunden (${if (usesUdp) "UDP" else "TCP"})",
                )
                enforceSessionLimits()
            }
        }

        /**
         * Setzt die Obergrenzen fuer *spielende* Sitzungen durch. Der Neue
         * gewinnt, die aeltesten weichen.
         *
         * Bewusst so herum: laeuft auf der Gegenseite ein Reconnect-Fehler und
         * startet dutzende Prozesse, sind die alten die haengenden. Wuerde man
         * stattdessen den Neuen abweisen, blockierte eine tote Session den
         * Platz dauerhaft und der Stream kaeme nie zurueck.
         */
        private fun enforceSessionLimits() {
            if (maxClientsPerIp > 0) {
                val sameIp = connections.filter {
                    it !== this && it.isPlaying && it.address() == address()
                }
                dropOldest(sameIp, maxClientsPerIp - 1, "je Adresse")
            }
            if (maxClients > 0) {
                val others = connections.filter { it !== this && it.isPlaying }
                dropOldest(others, maxClients - 1, "insgesamt")
            }
        }

        /** Laeuft auf dem Aufnahme-Thread: nur einreihen, niemals senden. */
        override fun onAudio(bigEndianPcm: ByteArray, frames: Int) {
            queue.offer(bigEndianPcm, bigEndianPcm.size)
        }

        /** Eigener Thread je Client: hier darf blockiert werden. */
        private fun sendLoop() {
            val buffer = ByteArray(packetBytes)
            val frames = audio.framesPerPacket
            while (isPlaying && !socket.isClosed) {
                val length = queue.take(buffer, 500)
                if (length <= 0) continue

                // Ein Client, der dauerhaft nicht mitkommt, ist nicht zu retten.
                // Java kennt keinen Schreib-Timeout, also endet ein haengendes
                // write() sonst erst nach dem TCP-Timeout -- Minuten spaeter.
                // Anhaltendes Verwerfen ist das verlaessliche Symptom.
                if (queue.dropped > DROP_LIMIT) {
                    Log.w(TAG, "Client ${address()} kommt dauerhaft nicht mit, wird getrennt")
                    onEvent?.invoke(
                        EventLog.Kind.WARNING,
                        "Empfänger ${address()} zu langsam, getrennt (${queue.dropped} Pakete verworfen)",
                    )
                    close()
                    return
                }

                val active = sender ?: break
                active.send(buffer, frames)
                bytesSent.addAndGet(length.toLong())
                if (active.failed) {
                    // Der Client ist weg (ICMP port unreachable o.ae.) -- abraeumen,
                    // sonst laeuft der Sink bis zum naechsten TCP-Timeout weiter.
                    Log.w(TAG, "Versand an ${address()} fehlgeschlagen")
                    close()
                    return
                }
            }
        }

        private fun ssrcHex() = sender?.let { String.format("%08X", it.ssrc) } ?: "00000000"

        /**
         * [joinSender] nur aus dem eigenen Verbindungs-Thread setzen. Aus einem
         * fremden Thread darf hier niemals gewartet werden -- siehe [dropOldest].
         */
        private fun stopStreaming(joinSender: Boolean) {
            // compareAndSet stellt sicher, dass genau ein Aufrufer aufraeumt,
            // egal wie viele Threads gleichzeitig close() rufen.
            if (!playingFlag.compareAndSet(true, false)) return
            // Erst abmelden, damit nichts mehr nachlaeuft, dann die
            // Warteschlange schliessen -- daran endet der Sender-Thread.
            audio.removeSink(this)
            queue.close()
            if (joinSender) {
                senderThread?.let { if (it !== Thread.currentThread()) it.join(1000) }
                senderThread = null
            }
        }

        fun close(joinSender: Boolean = true) {
            stopStreaming(joinSender)
            sender?.close()
            sender = null
            runCatching { socket.close() }
        }

        private fun respond(
            cseq: String,
            code: Int,
            reason: String,
            headers: Map<String, String>,
            body: String? = null,
        ) {
            val sb = StringBuilder()
            sb.append("RTSP/1.0 $code $reason\r\n")
            sb.append("CSeq: $cseq\r\n")
            sb.append("Server: BirdMic\r\n")
            for ((k, v) in headers) sb.append("$k: $v\r\n")
            if (body != null) sb.append("Content-Length: ${body.toByteArray().size}\r\n")
            sb.append("\r\n")
            if (body != null) sb.append(body)
            synchronized(output) {
                output.write(sb.toString().toByteArray())
                output.flush()
            }
        }
    }

    private fun buildSdp(serverIp: String): String = buildString {
        append("v=0\r\n")
        append("o=- 0 0 IN IP4 $serverIp\r\n")
        append("s=BirdMic\r\n")
        append("i=Android microphone, uncompressed L16\r\n")
        append("c=IN IP4 0.0.0.0\r\n")
        append("t=0 0\r\n")
        append("a=tool:BirdMic\r\n")
        append("a=type:broadcast\r\n")
        append("a=range:npt=now-\r\n")
        append("m=audio 0 RTP/AVP 96\r\n")
        append("a=rtpmap:96 L16/${audio.sampleRate}/${audio.channels}\r\n")
        append("a=control:trackID=0\r\n")
    }

    private data class Request(
        val method: String,
        val uri: String,
        val headers: Map<String, String>,
    )

    companion object {
        private const val TAG = "BirdMic/Rtsp"

        /** '$' leitet einen eingebetteten RTP/RTCP-Rahmen ein. */
        private const val INTERLEAVED_MARKER = 0x24

        // Schutz gegen kaputte oder boesartige Clients im selben Netz.
        private const val MAX_LINE_LENGTH = 4096
        private const val MAX_HEADERS = 64
        private const val MAX_BODY_BYTES = 65536

        /**
         * Pakete Vorlauf je Client, entspricht 100 ms bei 20-ms-Bloecken.
         * Genug fuer normale Netzschwankungen, wenig genug, dass ein haengender
         * Client nicht sekundenlang veraltetes Audio nachliefert.
         */
        private const val QUEUE_CAPACITY = 5

        /**
         * Etwa 170 ms Audio. Klein genug, dass ein stockender Empfaenger schnell
         * in der Warteschlange auflaeuft (wo verworfen werden kann), gross genug
         * fuer normalen Durchsatz im WLAN.
         */
        private const val SEND_BUFFER_BYTES = 16384

        /** Rund 5 s durchgehendes Verwerfen -- danach ist der Client hoffnungslos. */
        private const val DROP_LIMIT = 250L

        /**
         * Wie viele offene Verbindungen je erlaubtem Stream zugelassen werden,
         * bevor die aelteste weicht. Zwei, damit ein Client eine Steuer-
         * verbindung halten und daneben eine neue aufbauen kann.
         */
        private const val CONNECTION_HEADROOM = 2
    }
}
