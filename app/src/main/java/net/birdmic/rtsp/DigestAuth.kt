package net.birdmic.rtsp

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * RTSP-Digest-Authentifizierung nach RFC 2617.
 *
 * Basic Auth waere einfacher, uebertraegt das Passwort aber im Klartext -- und
 * RTSP hat kein TLS. Digest schickt nur einen Hash ueber die Leitung.
 *
 * Grenzen, damit hier niemand mehr hineinliest, als drinsteckt:
 *  - Der Audiostrom bleibt vollstaendig unverschluesselt. Wer mitschneidet, hoert mit.
 *  - Die Nonce gilt fuer die Lebensdauer des Servers. Ein aufgezeichneter
 *    gueltiger Authorization-Header laesst sich innerhalb dieser Zeit
 *    wiederverwenden; qop/nc/cnonce sind nicht umgesetzt.
 *  - MD5 ist als Hash laengst ueberholt, aber von RFC 2617 so vorgegeben und
 *    das, was RTSP-Clients koennen.
 *
 * Das Verfahren haelt also Unbefugte aus dem Stream heraus. Es ist kein Schutz
 * gegen jemanden, der den Netzverkehr mitschneiden kann.
 */
class DigestAuth(private val username: String, private val password: String) {

    val realm = "BirdMic"
    private val nonce = randomHex()

    val enabled: Boolean get() = username.isNotBlank() && password.isNotBlank()

    /** Inhalt des WWW-Authenticate-Headers fuer eine 401-Antwort. */
    fun challenge(): String =
        "Digest realm=\"$realm\", nonce=\"$nonce\", stale=\"FALSE\""

    /**
     * Prueft den Authorization-Header eines Clients und bindet ihn an den
     * tatsaechlichen Request-Target. Ohne diesen Vergleich koennte ein gueltiger
     * Header auf einen anderen Pfad derselben Methode wiederverwendet werden.
     */
    fun verify(header: String?, method: String, requestUri: String): Boolean {
        if (!enabled) return true
        if (header == null || !header.trimStart().startsWith("Digest", ignoreCase = true)) return false

        val fields = parseFields(header)
        val user = fields["username"] ?: return false
        val uri = fields["uri"] ?: return false
        val response = fields["response"] ?: return false
        val clientNonce = fields["nonce"] ?: return false

        if (user != username) return false
        if (uri != requestUri) return false
        // Nur die selbst ausgegebene Nonce akzeptieren -- damit fallen
        // Mitschnitte aus frueheren Serverlaeufen heraus.
        if (clientNonce != nonce) return false

        val ha1 = md5("$username:$realm:$password")
        val ha2 = md5("$method:$uri")
        val expected = md5("$ha1:$nonce:$ha2")
        return constantTimeEquals(expected, response.lowercase())
    }

    private fun parseFields(header: String): Map<String, String> {
        val result = HashMap<String, String>()
        // key="value" oder key=value, getrennt durch Kommas.
        val pattern = Regex("(\\w+)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))")
        for (match in pattern.findAll(header)) {
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            result[key] = value
        }
        return result
    }

    /** Vergleich ohne fruehen Abbruch, damit die Laufzeit nichts verraet. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** SecureRandom, nicht kotlin.random: die Nonce ist ein Sicherheitsmerkmal. */
    private fun randomHex(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
