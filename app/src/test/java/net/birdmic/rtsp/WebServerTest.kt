package net.birdmic.rtsp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket
import java.security.MessageDigest

/**
 * Der Webserver wird gegen echte Sockets getestet, nicht gegen Attrappen --
 * die Fehler, die hier weh tun, stecken im Zusammenspiel von Anfragezeile,
 * Kopfzeilen und Antwortlaenge, nicht in der Routentabelle.
 */
class WebServerTest {

    private class FakeProvider : WebServer.Provider {
        var statusCalls = 0
        override fun statusJson(): String {
            statusCalls++
            return """{"running":true}"""
        }

        override fun logJson() = """{"entries":[]}"""
        override fun logText() = "BirdMic Bericht\nZeile zwei\n"
        override fun spectrumJson() = """{"bands":[0.5,0.25]}"""
    }

    private val provider = FakeProvider()
    private var server: WebServer? = null

    @Before
    fun setUp() {
        // Port 0: das Betriebssystem sucht einen freien aus. Sonst kollidieren
        // parallel laufende Tests auf dem Runner miteinander.
        server = WebServer(port = 0, provider = provider).also { assertTrue(it.start()) }
    }

    @After
    fun tearDown() {
        server?.stop()
    }

    private fun request(raw: String): String {
        Socket("127.0.0.1", server!!.boundPort).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write(raw.toByteArray())
            socket.getOutputStream().flush()
            return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun get(path: String, extraHeaders: String = "") =
        request("GET $path HTTP/1.1\r\nHost: test\r\n$extraHeaders\r\n")

    @Test
    fun `liefert die Seite`() {
        val response = get("/")
        assertTrue(response.startsWith("HTTP/1.1 200 OK"))
        assertTrue(response.contains("text/html"))
        assertTrue(response.contains("<title>BirdMic</title>"))
    }

    @Test
    fun `liefert Status als JSON`() {
        val response = get("/api/status")
        assertTrue(response.contains("application/json"))
        assertTrue(response.endsWith("""{"running":true}"""))
        assertEquals(1, provider.statusCalls)
    }

    @Test
    fun `ignoriert den Anfrageteil hinter dem Fragezeichen`() {
        // Der Browser haengt dort seinen Zwischenspeicher-Brecher an.
        assertTrue(get("/api/status?t=1699999999").startsWith("HTTP/1.1 200"))
    }

    @Test
    fun `Logdatei kommt als Anhang`() {
        val response = get("/log.txt")
        assertTrue(response.contains("Content-Disposition: attachment"))
        assertTrue(response.contains("BirdMic Bericht"))
    }

    @Test
    fun `unbekannter Pfad ergibt 404`() {
        assertTrue(get("/etc/passwd").startsWith("HTTP/1.1 404"))
    }

    @Test
    fun `Dashboard setzt dynamische Werte nicht als HTML ein`() {
        // Device names, client labels and mDNS names may be configurable.
        // Guard against reintroducing raw HTML insertion in the bundled page.
        assertFalse(WebPage.HTML.contains(".innerHTML"))
        assertTrue(WebPage.HTML.contains("createElement"))
        assertTrue(WebPage.HTML.contains("textContent"))
        assertTrue(WebPage.HTML.contains("document.addEventListener('visibilitychange'"))
        assertTrue(WebPage.HTML.contains("clearInterval"))
    }

    @Test
    fun `ueberlastete Webserver-Warteschlange weist Verbindungen ab`() {
        val sockets = mutableListOf<Socket>()
        try {
            // Four workers plus a bounded queue cannot retain all these idle clients.
            repeat(40) {
                sockets += Socket("127.0.0.1", server!!.boundPort).apply {
                    soTimeout = 150
                }
            }

            var rejected = 0
            for (socket in sockets) {
                try {
                    if (socket.getInputStream().read() == -1) rejected++
                } catch (_: java.net.SocketTimeoutException) {
                    // An accepted socket is waiting for the request line.
                } catch (_: java.net.SocketException) {
                    // Immediate close is also a valid rejection.
                    rejected++
                }
            }
            assertTrue("Expected overload sockets to be rejected", rejected > 0)
        } finally {
            sockets.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun `schreibende Methoden werden abgewiesen`() {
        val response = request("POST /api/status HTTP/1.1\r\nHost: test\r\nContent-Length: 0\r\n\r\n")
        assertTrue(response.startsWith("HTTP/1.1 405"))
    }

    @Test
    fun `HEAD liefert Kopfzeilen ohne Rumpf`() {
        val response = request("HEAD /api/status HTTP/1.1\r\nHost: test\r\n\r\n")
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertTrue(response.contains("Content-Length: 16"))
        assertFalse(response.contains("running"))
    }

    @Test
    fun `Antwort verbietet Zwischenspeichern`() {
        // Sonst zeigt der Browser nach einem Neustart des Streams alte Werte.
        assertTrue(get("/api/status").contains("Cache-Control: no-store"))
    }

    @Test
    fun `ueberlange Anfragezeile wird verworfen`() {
        val response = get("/" + "a".repeat(4000))
        assertTrue(response.isEmpty())
    }

    @Test
    fun `mit Zugangsschutz zuerst 401 und dann Zugang`() {
        server?.stop()
        val auth = DigestAuth("chris", "geheim")
        server = WebServer(port = 0, provider = provider, auth = auth)
            .also { assertTrue(it.start()) }

        val denied = get("/api/status")
        assertTrue(denied.startsWith("HTTP/1.1 401"))
        assertTrue(denied.contains("WWW-Authenticate: Digest"))

        val nonce = Regex("nonce=\"([^\"]+)\"").find(denied)!!.groupValues[1]
        val ha1 = md5("chris:BirdMic:geheim")
        val ha2 = md5("GET:/api/status")
        val header = "Authorization: Digest username=\"chris\", realm=\"BirdMic\", " +
            "nonce=\"$nonce\", uri=\"/api/status\", response=\"${md5("$ha1:$nonce:$ha2")}\"\r\n"

        assertTrue(get("/api/status", header).startsWith("HTTP/1.1 200"))
        // A valid digest header for one target must not authorize another path.
        assertTrue(get("/log.txt", header).startsWith("HTTP/1.1 401"))
    }

    @Test
    fun `nach dem Stoppen nimmt niemand mehr ab`() {
        val port = server!!.boundPort
        server?.stop()
        val refused = runCatching { Socket("127.0.0.1", port).close() }
        assertTrue("Port $port nimmt noch Verbindungen an", refused.isFailure)
    }

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
