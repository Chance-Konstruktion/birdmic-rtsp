package net.birdmic.rtsp

import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Winziger HTTP-Server fuer die Ferndiagnose: Status, Logbuch und Spektrum im
 * Browser, ohne dass man das Telefon in die Hand nehmen muss.
 *
 * Bewusste Beschraenkungen -- das hier soll klein und harmlos bleiben:
 *  - **Nur GET und HEAD.** Es gibt keinen Weg, ueber diesen Server irgendetwas
 *    zu aendern; er liest ausschliesslich vor.
 *  - Feste Routenliste, keine Datei wird vom Datentraeger geliefert. Damit
 *    existiert die ganze Klasse von Pfad-Angriffen hier nicht.
 *  - Kein Keep-Alive. Jede Antwort schliesst die Verbindung. Kostet ein paar
 *    Bytes Handshake je Aktualisierung und erspart uns die Zustandshaltung.
 *  - Hoechstens [MAX_WORKERS] Verbindungen gleichzeitig, 5 s Lesezeitlimit.
 *
 * Der Ton laeuft weiter ueber RTSP. Faellt der Webserver aus oder wird er
 * abgeschaltet, merkt der Stream davon nichts -- die beiden teilen sich nur die
 * Datenquelle, nicht den Lebenslauf.
 */
class WebServer(
    private val port: Int,
    private val provider: Provider,
    private val auth: DigestAuth? = null,
    private val onEvent: ((EventLog.Kind, String) -> Unit)? = null,
) {

    /**
     * Alles Geraetenahe steckt hinter dieser Schnittstelle, damit der Server
     * selbst reines Java bleibt und ohne Android getestet werden kann.
     */
    interface Provider {
        fun statusJson(): String
        fun logJson(): String
        fun logText(): String
        fun spectrumJson(): String
    }

    @Volatile private var socket: ServerSocket? = null
    @Volatile private var running = false
    var lastError: String? = null
        private set

    /** Der tatsaechlich belegte Port; bei [port] = 0 vergibt ihn das System. */
    val boundPort: Int get() = socket?.localPort ?: 0

    // Track accepted sockets as well as running tasks: queued clients must also
    // be closed on shutdown, not just tasks that have started.
    private val openClients = ConcurrentHashMap.newKeySet<Socket>()

    private val workers = ThreadPoolExecutor(
        MAX_WORKERS,
        MAX_WORKERS,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_QUEUED_CONNECTIONS),
        { r -> Thread(r, "birdmic-web").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun start(): Boolean {
        return try {
            socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
            running = true
            Thread({ acceptLoop() }, "birdmic-web-accept").apply { isDaemon = true }.start()
            onEvent?.invoke(EventLog.Kind.START, "Webserver auf Port $boundPort")
            true
        } catch (e: IOException) {
            lastError = "Webserver-Port $port belegt (${e.message})"
            onEvent?.invoke(EventLog.Kind.ERROR, lastError ?: "")
            false
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        // Also unblock workers currently reading and release sockets belonging
        // to tasks still waiting in the bounded queue.
        openClients.toList().forEach { client -> runCatching { client.close() } }
        openClients.clear()
        workers.shutdownNow()
        runCatching { workers.awaitTermination(1, TimeUnit.SECONDS) }
    }

    private fun acceptLoop() {
        while (running) {
            val client = try {
                socket?.accept() ?: break
            } catch (e: IOException) {
                if (running) onEvent?.invoke(EventLog.Kind.WARNING, "Webserver: ${e.message}")
                break
            }
            openClients.add(client)
            // The worker queue is bounded. When both workers and queue are full,
            // reject the new socket immediately rather than retaining it forever.
            try {
                workers.execute { handle(client) }
            } catch (_: RejectedExecutionException) {
                openClients.remove(client)
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = READ_TIMEOUT_MS
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()

            val request = readRequest(input) ?: return
            if (request.method != "GET" && request.method != "HEAD") {
                respond(output, 405, "text/plain; charset=utf-8", "Nur GET".toByteArray(), false)
                return
            }
            if (auth?.enabled == true && !auth.verify(request.authorization, request.method)) {
                respondUnauthorized(output)
                return
            }
            route(output, request)
        } catch (_: SocketTimeoutException) {
            // Verbindung geoeffnet, nichts geschickt. Kommt bei Portscannern vor.
        } catch (_: IOException) {
            // Browser weggeklickt. Kein Grund fuer einen Logbucheintrag.
        } finally {
            runCatching { client.close() }
            openClients.remove(client)
        }
    }

    private fun route(output: OutputStream, request: Request) {
        val body = request.method != "HEAD"
        when (request.path) {
            "/", "/index.html" ->
                respond(output, 200, "text/html; charset=utf-8", WebPage.HTML.toByteArray(), body)

            "/api/status" -> respondJson(output, provider.statusJson(), body)
            "/api/log" -> respondJson(output, provider.logJson(), body)
            "/api/spectrum" -> respondJson(output, provider.spectrumJson(), body)

            "/log.txt" -> respond(
                output, 200, "text/plain; charset=utf-8",
                provider.logText().toByteArray(), body,
                extra = "Content-Disposition: attachment; filename=\"birdmic-log.txt\"\r\n",
            )

            else -> respond(output, 404, "text/plain; charset=utf-8", "Nicht da".toByteArray(), body)
        }
    }

    private fun respondJson(output: OutputStream, json: String, withBody: Boolean) =
        respond(output, 200, "application/json; charset=utf-8", json.toByteArray(), withBody)

    private fun respondUnauthorized(output: OutputStream) {
        val challenge = auth?.challenge() ?: "Digest realm=\"BirdMic\""
        respond(
            output, 401, "text/plain; charset=utf-8", "Anmeldung noetig".toByteArray(), true,
            extra = "WWW-Authenticate: $challenge\r\n",
        )
    }

    private fun respond(
        output: OutputStream,
        code: Int,
        contentType: String,
        payload: ByteArray,
        withBody: Boolean,
        extra: String = "",
    ) {
        val header = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(payload.size).append("\r\n")
            // Statuswerte sind je Abruf andere; nichts davon darf zwischengelagert werden.
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append(extra)
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        if (withBody) output.write(payload)
        output.flush()
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "OK"
    }

    private data class Request(val method: String, val path: String, val authorization: String?)

    /** null, wenn die Anfrage unbrauchbar ist oder die Grenzen sprengt. */
    private fun readRequest(input: BufferedInputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        // Der Anfrageteil hinter '?' interessiert uns nicht -- der Browser
        // haengt dort nur seinen Zwischenspeicher-Brecher an.
        val path = parts[1].substringBefore('?').ifBlank { "/" }

        var authorization: String? = null
        var count = 0
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            if (++count > MAX_HEADERS) return null
            if (line.startsWith("Authorization:", ignoreCase = true)) {
                authorization = line.substringAfter(':').trim()
            }
        }
        return Request(method, path, authorization)
    }

    /** Zeile ohne CRLF; null bei Dateiende oder Ueberlaenge. */
    private fun readLine(input: BufferedInputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return null
            if (c == '\n'.code) return buffer.toString().trimEnd('\r')
            if (buffer.length >= MAX_LINE) return null
            buffer.append(c.toChar())
        }
    }

    companion object {
        const val DEFAULT_PORT = 8080
        private const val MAX_WORKERS = 4
        private const val MAX_QUEUED_CONNECTIONS = 8
        private const val MAX_HEADERS = 32
        private const val MAX_LINE = 2048
        private const val READ_TIMEOUT_MS = 5000
    }
}
