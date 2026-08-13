package net.birdmic.rtsp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Prueft die Digest-Authentifizierung nach RFC 2617: gueltige Anmeldung geht
 * durch, alles andere nicht.
 */
class DigestAuthTest {

    private val user = "birdnet"
    private val password = "geheim"
    private val uri = "rtsp://192.168.1.42:8554/audio.wav"

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Baut den Authorization-Kopf so, wie ein Client es tun wuerde. */
    private fun authHeader(
        auth: DigestAuth,
        method: String = "DESCRIBE",
        user: String = this.user,
        password: String = this.password,
        nonce: String? = null,
    ): String {
        val actualNonce = nonce ?: Regex("nonce=\"([^\"]+)\"")
            .find(auth.challenge())!!.groupValues[1]
        val ha1 = md5("$user:${auth.realm}:$password")
        val ha2 = md5("$method:$uri")
        val response = md5("$ha1:$actualNonce:$ha2")
        return "Digest username=\"$user\", realm=\"${auth.realm}\", " +
            "nonce=\"$actualNonce\", uri=\"$uri\", response=\"$response\""
    }

    @Test
    fun `akzeptiert gueltige Zugangsdaten`() {
        val auth = DigestAuth(user, password)
        assertTrue(auth.verify(authHeader(auth), "DESCRIBE"))
    }

    @Test
    fun `lehnt ein falsches Passwort ab`() {
        val auth = DigestAuth(user, password)
        assertFalse(auth.verify(authHeader(auth, password = "falsch"), "DESCRIBE"))
    }

    @Test
    fun `lehnt einen falschen Benutzer ab`() {
        val auth = DigestAuth(user, password)
        assertFalse(auth.verify(authHeader(auth, user = "jemand"), "DESCRIBE"))
    }

    @Test
    fun `lehnt eine fremde Nonce ab`() {
        val auth = DigestAuth(user, password)
        assertFalse(auth.verify(authHeader(auth, nonce = "00112233445566778899aabbccddeeff"), "DESCRIBE"))
    }

    @Test
    fun `bindet die Antwort an die Methode`() {
        // Ein fuer DESCRIBE erzeugter Hash darf bei PLAY nicht durchgehen.
        val auth = DigestAuth(user, password)
        val header = authHeader(auth, method = "DESCRIBE")
        assertFalse(auth.verify(header, "PLAY"))
    }

    @Test
    fun `lehnt fehlende und kaputte Koepfe ab`() {
        val auth = DigestAuth(user, password)
        assertFalse(auth.verify(null, "DESCRIBE"))
        assertFalse(auth.verify("", "DESCRIBE"))
        assertFalse(auth.verify("Basic aGFsbG86d2VsdA==", "DESCRIBE"))
        assertFalse(auth.verify("Digest ohne=felder", "DESCRIBE"))
    }

    @Test
    fun `laesst ohne konfigurierte Zugangsdaten alles durch`() {
        val auth = DigestAuth("", "")
        assertFalse("Ohne Zugangsdaten ist der Schutz aus", auth.enabled)
        assertTrue(auth.verify(null, "DESCRIBE"))
    }

    @Test
    fun `jede Instanz bekommt eine eigene Nonce`() {
        val nonces = (1..50).map {
            Regex("nonce=\"([^\"]+)\"").find(DigestAuth(user, password).challenge())!!.groupValues[1]
        }
        assertTrue("Nonces muessen verschieden sein", nonces.toSet().size == nonces.size)
        assertTrue("Nonce sollte 128 Bit haben", nonces.all { it.length == 32 })
    }
}
